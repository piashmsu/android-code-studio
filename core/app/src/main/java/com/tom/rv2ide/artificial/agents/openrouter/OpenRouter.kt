/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agents.openrouter

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.exceptions.ContextTooLongException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.artificial.usage.UsageTracker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenRouter provider. OpenRouter exposes an OpenAI-compatible chat completions API at
 * https://openrouter.ai/api/v1 and forwards requests to a wide range of upstream models
 * (OpenAI, Anthropic, Google, Mistral, Meta, DeepSeek, ...). Using one OpenRouter API key
 * the user can pick any supported model id (e.g. `openai/gpt-4o-mini`,
 * `anthropic/claude-3.5-sonnet`, `meta-llama/llama-3.1-70b-instruct:free`).
 *
 * The model id is read in this order:
 *   1. The user-overridden custom model id (`ai_agent_openrouter_custom_model` pref).
 *   2. The currently selected model from [Agents] when its provider is `openrouter`.
 *   3. A safe default (`openai/gpt-4o-mini`).
 */
class OpenRouter : AIAgent {

  private var apiKey: String? = null
  private val writingRules = WritingRules.Instructions()
  private var projectTreeResult: ProjectTreeResult? = null
  private var fileWriter: AIFileWriter? = null
  private val conversationHistory = mutableListOf<ConversationMessage>()
  private val modificationHistory = mutableListOf<ModificationAttempt>()
  private var currentAttemptCount = 0
  private val maxRetryAttempts = 3
  private var agents: Agents? = null
  private var selectedModel: String = DEFAULT_MODEL
  override val providerId = "openrouter"
  override val providerName = "OpenRouter"

  companion object {
    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val DEFAULT_MODEL = "openai/gpt-4o-mini"
    private const val HTTP_REFERER = "https://github.com/AndroidCSOfficial/android-code-studio"
    private const val X_TITLE = "Android Code Studio"

    /**
     * Tried in order whenever the primary model returns a transient/server error
     * or rate limit. Mostly free models so a fallback never costs the user money.
     */
    private val FALLBACK_MODELS = listOf(
      "deepseek/deepseek-chat:free",
      "google/gemini-2.0-flash-exp:free",
      "meta-llama/llama-3.1-70b-instruct:free",
      "mistralai/mistral-7b-instruct:free",
    )

    /**
     * Models with the largest context windows (free tier). Tried — in this
     * order — when the upstream rejects the request because the prompt
     * exceeds the active model's context window. The first entry has ~1M
     * input tokens, the rest are 128k+ so a 50k-token prompt easily fits.
     */
    private val LARGE_CONTEXT_FALLBACK_MODELS = listOf(
      "google/gemini-2.0-flash-exp:free",                 // ~1M tokens
      "google/gemini-flash-1.5:free",                     // ~1M tokens
      "meta-llama/llama-3.3-70b-instruct:free",           // 128k
      "meta-llama/llama-3.1-70b-instruct:free",           // 128k
      "qwen/qwen-2.5-coder-32b-instruct:free",            // 128k, code-tuned
      "deepseek/deepseek-chat-v3.1:free",                 // 64k+
    )

    /** Heuristic — does this upstream error message describe a context window overflow? */
    fun isContextTooLong(message: String): Boolean {
      val m = message.lowercase()
      return m.contains("context length") ||
        m.contains("context window") ||
        m.contains("maximum context") ||
        (m.contains("tokens") && (m.contains("exceed") || m.contains("too many") || m.contains(">"))) ||
        m.contains("prompt is too long") ||
        m.contains("input is too long")
    }

    fun registerAgent() {
      AIAgentRegistry.register(
        "openrouter",
        object : AIAgentRegistry.AgentFactory {
          override fun create(context: Context): AIAgent {
            return OpenRouter()
          }

          override fun hasValidApiKey(): Boolean {
            val key = ApiKey.getOpenRouterApiKey()
            return key.isNotEmpty()
          }

          override fun getApiKey(): String? {
            val key = ApiKey.getOpenRouterApiKey()
            return key.ifEmpty { null }
          }
        }
      )
    }
  }

  override fun initialize(apiKey: String, context: Context) {
    this.apiKey = apiKey
    agents = Agents(context)
    selectedModel = resolveModel()
  }

  override fun reinitializeWithNewModel(apiKey: String, context: Context) {
    initialize(apiKey, context)
  }

  override fun setContext(context: Context) {
    fileWriter = AIFileWriter(context)
  }

  override fun setProjectData(projectTreeResult: ProjectTreeResult) {
    this.projectTreeResult = projectTreeResult
  }

  override fun clearConversation() {
    conversationHistory.clear()
    modificationHistory.clear()
    currentAttemptCount = 0
  }

  override fun recordModification(
    filePath: String,
    oldContent: String?,
    newContent: String,
    success: Boolean
  ) {
    modificationHistory.add(
      ModificationAttempt(
        timestamp = System.currentTimeMillis(),
        filePath = filePath,
        previousContent = oldContent,
        newContent = newContent,
        attemptNumber = currentAttemptCount,
        success = success
      )
    )
  }

  override fun undoLastModification(): Boolean {
    if (modificationHistory.isEmpty()) return false
    val lastMod = modificationHistory.lastOrNull { it.success } ?: return false

    return if (lastMod.previousContent != null) {
      val result = writeFile(lastMod.filePath, lastMod.previousContent)
      if (result is FileWriteResult.Success) {
        modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
        true
      } else false
    } else {
      try {
        File(lastMod.filePath).delete()
        modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
        true
      } catch (e: Exception) {
        false
      }
    }
  }

  override fun getModificationHistory(): List<ModificationAttempt> = modificationHistory.toList()
  override fun resetAttemptCount() { currentAttemptCount = 0 }
  override fun incrementAttemptCount() { currentAttemptCount++ }
  override fun getCurrentAttemptCount(): Int = currentAttemptCount
  override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

  override suspend fun generateCode(
    prompt: String,
    context: String?,
    language: String,
    projectStructure: String?
  ): Result<String> = withContext(Dispatchers.IO) {
    try {
      val key = apiKey
        ?: return@withContext Result.failure(
          IllegalStateException("OpenRouter service not initialized")
        )

      val fileContents = readRelevantFiles()
      val needsCorrection = isUserRequestingCorrection(prompt)

      val fullPrompt = buildString {
        append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
        projectTreeResult?.let {
          append(it.tree)
          append("\n\nCRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths.\n\n")
        }

        if (fileContents.isNotEmpty()) {
          append("=== CURRENT FILES CONTENT ===\n")
          fileContents.forEach { (path, content) ->
            append("FILE: $path\nCONTENT:\n$content\n\n")
          }
        }

        if (context != null) {
          append("=== ADDITIONAL CONTEXT ===\n$context\n\n")
        }

        if (conversationHistory.isNotEmpty()) {
          append("=== CONVERSATION HISTORY ===\n")
          conversationHistory.forEach { msg ->
            append("${msg.role.uppercase()}: ${msg.content}\n\n")
          }
        }

        if (needsCorrection && modificationHistory.isNotEmpty()) {
          append("=== CORRECTION REQUIRED ===\n")
          append("The user indicated the previous modification was WRONG. Try a different approach.\n\n")
        }

        if (currentAttemptCount > 0) {
          append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n\n")
        }

        append("=== USER REQUEST ===\n")
        append(prompt)
      }

      val response = callApi(key, fullPrompt)
      if (response.isBlank()) {
        return@withContext Result.failure(Exception("Empty response from OpenRouter"))
      }

      conversationHistory.add(ConversationMessage("user", prompt))
      conversationHistory.add(ConversationMessage("assistant", response))

      // Cap conversation history to prevent unbounded RAM growth.
      while (conversationHistory.size > 20) {
        conversationHistory.removeAt(0)
      }

      Result.success(response)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  private fun callApi(apiKey: String, prompt: String): String {
    val attempts = buildList {
      add(selectedModel)
      // Add automatic fallbacks for retryable failures. We only fall back on
      // transient/upstream errors, never on auth/quota errors. The fallbacks
      // are widely-available models that any OpenRouter account can hit.
      addAll(FALLBACK_MODELS)
    }.distinct()

    var lastError: Throwable? = null
    for ((index, model) in attempts.withIndex()) {
      try {
        return callOnce(apiKey, prompt, model)
      } catch (e: InvalidApiKeyException) {
        throw e // never fall back if the key is bad
      } catch (e: ContextTooLongException) {
        // Same provider, larger context window — try the big-context chain
        // instead of failing up to AIAgentManager (which would otherwise
        // switch to a different provider, which the user does not want).
        lastError = e
        android.util.Log.w(
          "OpenRouter",
          "Prompt exceeds context window on $model. Trying large-context models on the same provider.",
        )
        val bigChain = (LARGE_CONTEXT_FALLBACK_MODELS - model).distinct()
        for (bigModel in bigChain) {
          try {
            return callOnce(apiKey, prompt, bigModel)
          } catch (inner: ContextTooLongException) {
            lastError = inner
            android.util.Log.w("OpenRouter", "Still too long on $bigModel.")
          } catch (inner: Throwable) {
            lastError = inner
            android.util.Log.w("OpenRouter", "Big-context fallback $bigModel failed: ${inner.message}")
          }
        }
        // None of the large-context models worked — give up but stay on this
        // provider; the manager treats ContextTooLongException as
        // sticky-to-provider so it will not jump to another provider.
        throw lastError as ContextTooLongException
      } catch (e: QuotaExceededException) {
        throw e // user must add credits — switching model won't help
      } catch (e: RateLimitException) {
        lastError = e
        android.util.Log.w("OpenRouter", "Rate limited on $model, falling back…", e)
      } catch (e: Throwable) {
        lastError = e
        android.util.Log.w(
          "OpenRouter",
          "Transient failure on $model (attempt ${index + 1}/${attempts.size}): ${e.message}",
        )
      }
    }
    throw lastError ?: Exception("OpenRouter exhausted all fallback models")
  }

  private fun callOnce(apiKey: String, prompt: String, model: String): String {
    val url = URL(ENDPOINT)
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("Authorization", "Bearer $apiKey")
      connection.setRequestProperty("HTTP-Referer", HTTP_REFERER)
      connection.setRequestProperty("X-Title", X_TITLE)
      connection.doOutput = true
      connection.connectTimeout = 30000
      connection.readTimeout = 60000

      val messages = JSONArray()
      messages.put(JSONObject().put("role", "system").put("content", writingRules.useThis()))
      val pendingImages = com.tom.rv2ide.artificial.multimodal.ImageAttachment.all()
      if (pendingImages.isNotEmpty()) {
        // Multimodal user message — every modern OpenRouter vision model
        // (gpt-4o*, gemini-*, claude-*, llama-3.2-*-vision, qwen-2-vl, etc.)
        // accepts this exact shape. Multiple images are simply repeated parts.
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        for (img in pendingImages) {
          parts.put(
            JSONObject().put("type", "image_url").put(
              "image_url", JSONObject().put("url", img.dataUrl),
            ),
          )
        }
        messages.put(JSONObject().put("role", "user").put("content", parts))
      } else {
        messages.put(JSONObject().put("role", "user").put("content", prompt))
      }

      val requestBody = JSONObject()
        .put("model", model)
        .put("messages", messages)
        .put("temperature", 0.7)
        .put("max_tokens", 4096)

      connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

      val responseCode = connection.responseCode
      if (responseCode != HttpURLConnection.HTTP_OK) {
        val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
        android.util.Log.e("OpenRouter", "Error response ($responseCode): $errorStream")
        val message = try {
          JSONObject(errorStream).optJSONObject("error")?.optString("message") ?: errorStream
        } catch (_: Exception) { errorStream }

        // Some upstream providers report context-window overflow as 400 with
        // a "tokens"/"context length" message — surface that as
        // ContextTooLongException so callApi can switch to a bigger model
        // *on the same provider* instead of failing the request up.
        if (isContextTooLong(message)) {
          throw ContextTooLongException("OpenRouter context overflow: $message")
        }
        when {
          responseCode == 401 -> throw InvalidApiKeyException("Invalid OpenRouter API key: $message")
          responseCode == 402 -> throw QuotaExceededException("OpenRouter credit exhausted: $message")
          responseCode == 429 -> {
            // 429 with an explicit token-limit message is *also* a context
            // overflow on free models — same fix as 400 above.
            if (isContextTooLong(message)) {
              throw ContextTooLongException("OpenRouter token limit: $message")
            }
            throw RateLimitException("OpenRouter rate limit: $message")
          }
          else -> throw Exception("OpenRouter API error ($responseCode): $message")
        }
      }

      val responseBody = connection.inputStream.bufferedReader().readText()
      val json = JSONObject(responseBody)

      // Record token usage when the upstream surfaces it.
      json.optJSONObject("usage")?.let { usage ->
        UsageTracker.record(
          promptTokens = usage.optInt("prompt_tokens", 0),
          completionTokens = usage.optInt("completion_tokens", 0),
          model = model,
        )
      }

      val choices = json.getJSONArray("choices")
      if (choices.length() > 0) {
        return choices.getJSONObject(0).getJSONObject("message").getString("content")
      }
      throw Exception("No response from OpenRouter API")
    } catch (e: java.net.SocketTimeoutException) {
      throw Exception("OpenRouter request timeout: ${e.message}")
    } catch (e: java.net.UnknownHostException) {
      throw Exception("Network error - cannot reach OpenRouter: ${e.message}")
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Resolve the model id to use. The OpenRouter custom-model preference takes precedence
   * because OpenRouter supports hundreds of models that aren't in the static list.
   */
  private fun resolveModel(): String {
    val custom = ApiKey.getOpenRouterCustomModel().trim()
    if (custom.isNotBlank()) return custom

    val current = agents?.getAgent()
    if (!current.isNullOrBlank() && agents?.getProvider() == "openrouter") {
      return current
    }
    return DEFAULT_MODEL
  }

  private fun isUserRequestingCorrection(message: String): Boolean {
    val keywords = listOf(
      "wrong", "not what", "mistake", "error", "incorrect",
      "that's not", "not right", "fix", "undo", "revert",
      "different", "try again", "not working"
    )
    return keywords.any { message.lowercase().contains(it) }
  }

  private fun readRelevantFiles(): Map<String, String> {
    val filesContent = mutableMapOf<String, String>()
    val tree = projectTreeResult?.tree ?: return filesContent
    val filePaths = tree.lines().filter { it.isNotBlank() }

    // Cap the total number of files we read to avoid OOM on huge projects.
    var loaded = 0
    val maxFiles = 40
    val maxBytesPerFile = 64 * 1024 // 64KB per file is plenty for code

    for (filePath in filePaths) {
      if (loaded >= maxFiles) break
      val trimmedPath = filePath.trim()
      val file = File(trimmedPath)
      if (file.isFile &&
        (trimmedPath.endsWith(".kt") ||
          trimmedPath.endsWith(".java") ||
          trimmedPath.endsWith(".xml") ||
          trimmedPath.endsWith(".gradle") ||
          trimmedPath.endsWith(".gradle.kts")) &&
        !trimmedPath.contains("/build/") && !trimmedPath.contains("/.gradle/")
      ) {
        try {
          val len = file.length()
          val content = if (len > maxBytesPerFile) {
            file.readText().take(maxBytesPerFile)
          } else file.readText()
          filesContent[trimmedPath] = content
          loaded++
        } catch (_: Exception) {
          // Skip unreadable files.
        }
      }
    }
    return filesContent
  }

  override fun writeFile(filePath: String, content: String): FileWriteResult {
    val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
    return writer.writeFile(filePath, content, createBackup = true)
  }

  override fun isInitialized(): Boolean = apiKey != null

  override fun supportsStreaming(): Boolean = true

  override suspend fun generateCodeStreaming(
    prompt: String,
    context: String?,
    language: String,
    projectStructure: String?,
    onChunk: (delta: String, full: String) -> Unit,
  ): Result<String> = withContext(Dispatchers.IO) {
    try {
      val key = apiKey ?: return@withContext Result.failure(
        IllegalStateException("OpenRouter service not initialized"),
      )
      val fileContents = readRelevantFiles()
      val fullPrompt = buildString {
        append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
        projectTreeResult?.let {
          append(it.tree)
          append("\n\nCRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths.\n\n")
        }
        if (fileContents.isNotEmpty()) {
          append("=== CURRENT FILES CONTENT ===\n")
          fileContents.forEach { (path, c) -> append("FILE: $path\nCONTENT:\n$c\n\n") }
        }
        if (context != null) append("=== ADDITIONAL CONTEXT ===\n$context\n\n")
        if (conversationHistory.isNotEmpty()) {
          append("=== CONVERSATION HISTORY ===\n")
          conversationHistory.forEach { append("${it.role.uppercase()}: ${it.content}\n\n") }
        }
        append("=== USER REQUEST ===\n")
        append(prompt)
      }
      val full = com.tom.rv2ide.artificial.agents.openaicompat.SseChatStream.call(
        endpoint = ENDPOINT,
        apiKey = key,
        model = selectedModel,
        systemPrompt = writingRules.useThis(),
        userPrompt = fullPrompt,
        extraHeaders = mapOf(
          "HTTP-Referer" to HTTP_REFERER,
          "X-Title" to X_TITLE,
        ),
        onChunk = onChunk,
      )
      if (full.isBlank()) {
        return@withContext Result.failure(Exception("Empty stream from OpenRouter"))
      }
      conversationHistory.add(ConversationMessage("user", prompt))
      conversationHistory.add(ConversationMessage("assistant", full))
      while (conversationHistory.size > 20) conversationHistory.removeAt(0)
      Result.success(full)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  private data class ConversationMessage(val role: String, val content: String)
}
