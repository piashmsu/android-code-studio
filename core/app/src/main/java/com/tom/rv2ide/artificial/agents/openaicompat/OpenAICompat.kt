/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Generic OpenAI-compatible provider. The user supplies three values via the
 *  AI preferences:
 *
 *    - base URL (e.g. https://api.together.xyz/v1, http://192.168.0.10:11434/v1
 *      for a local Ollama server, https://api.deepinfra.com/v1/openai, ...)
 *    - API key (sent as Bearer)
 *    - model id (free-form, e.g. mistralai/Mixtral-8x7B-Instruct-v0.1)
 *
 *  Any service that speaks the OpenAI /chat/completions schema works without a
 *  code change. The base URL may end in /v1 or include the full /chat/completions
 *  path — we normalise it.
 */

package com.tom.rv2ide.artificial.agents.openaicompat

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.ModificationAttempt
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

class OpenAICompat : AIAgent {

  private var apiKey: String? = null
  private val writingRules = WritingRules.Instructions()
  private var projectTreeResult: ProjectTreeResult? = null
  private var fileWriter: AIFileWriter? = null
  private val conversationHistory = mutableListOf<ConversationMessage>()
  private val modificationHistory = mutableListOf<ModificationAttempt>()
  private var currentAttemptCount = 0
  private val maxRetryAttempts = 3
  private var resolvedModel: String = DEFAULT_MODEL
  private var resolvedBaseUrl: String = DEFAULT_BASE_URL

  override val providerId = "openaicompat"
  override val providerName = "OpenAI-compatible"

  companion object {
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    fun registerAgent() {
      AIAgentRegistry.register(
        "openaicompat",
        object : AIAgentRegistry.AgentFactory {
          override fun create(context: Context): AIAgent = OpenAICompat()
          override fun hasValidApiKey(): Boolean =
            ApiKey.getOpenAICompatApiKey().isNotBlank()
          override fun getApiKey(): String? =
            ApiKey.getOpenAICompatApiKey().ifBlank { null }
        },
      )
    }

    /**
     * Normalise a user-entered base URL to one ending in `/chat/completions`.
     * Accepts:
     *   `https://host/v1`               -> `https://host/v1/chat/completions`
     *   `https://host/v1/`              -> `https://host/v1/chat/completions`
     *   `https://host/v1/chat/completions` (left untouched)
     *   `https://host` (rare)           -> `https://host/v1/chat/completions`
     */
    fun normaliseEndpoint(rawBase: String): String {
      val trimmed = rawBase.trim().trimEnd('/')
      if (trimmed.isEmpty()) return DEFAULT_BASE_URL + "/chat/completions"
      if (trimmed.endsWith("/chat/completions")) return trimmed
      if (trimmed.endsWith("/v1")) return "$trimmed/chat/completions"
      // Best-effort: assume an OpenAI-style root and append /v1/chat/completions.
      return "$trimmed/v1/chat/completions"
    }
  }

  override fun initialize(apiKey: String, context: Context) {
    this.apiKey = apiKey
    resolvedModel = ApiKey.getOpenAICompatModel().ifBlank { DEFAULT_MODEL }
    resolvedBaseUrl = ApiKey.getOpenAICompatBaseUrl().ifBlank { DEFAULT_BASE_URL }
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
    success: Boolean,
  ) {
    modificationHistory.add(
      ModificationAttempt(
        timestamp = System.currentTimeMillis(),
        filePath = filePath,
        previousContent = oldContent,
        newContent = newContent,
        attemptNumber = currentAttemptCount,
        success = success,
      ),
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
    projectStructure: String?,
  ): Result<String> = withContext(Dispatchers.IO) {
    try {
      val key = apiKey ?: return@withContext Result.failure(
        IllegalStateException("OpenAI-compatible service not initialized"),
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

        if (currentAttemptCount > 0) {
          append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n\n")
        }

        append("=== USER REQUEST ===\n")
        append(prompt)
      }

      val response = callApi(key, fullPrompt)
      if (response.isBlank()) {
        return@withContext Result.failure(Exception("Empty response from $providerName"))
      }

      conversationHistory.add(ConversationMessage("user", prompt))
      conversationHistory.add(ConversationMessage("assistant", response))
      while (conversationHistory.size > 20) {
        conversationHistory.removeAt(0)
      }
      Result.success(response)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  private fun callApi(apiKey: String, prompt: String): String {
    val endpoint = normaliseEndpoint(resolvedBaseUrl)
    val url = URL(endpoint)
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("Authorization", "Bearer $apiKey")
      connection.doOutput = true
      connection.connectTimeout = 30000
      connection.readTimeout = 60000

      val messages = JSONArray()
      messages.put(JSONObject().put("role", "system").put("content", writingRules.useThis()))
      val pendingImages = com.tom.rv2ide.artificial.multimodal.ImageAttachment.all()
      if (pendingImages.isNotEmpty()) {
        // Multimodal user message — works on any OpenAI-compat endpoint that
        // supports vision (OpenAI gpt-4o, Together, Groq llama-3.2 vision,
        // Fireworks, vLLM, Ollama llava etc.). Endpoints that don't support
        // vision will return a clear 400 — much better than silently dropping.
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
        .put("model", resolvedModel)
        .put("messages", messages)
        .put("temperature", 0.7)
        .put("max_tokens", 4096)

      connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

      val responseCode = connection.responseCode
      if (responseCode != HttpURLConnection.HTTP_OK) {
        val errorStream =
          connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
        android.util.Log.e("OpenAICompat", "Error ($responseCode) from $endpoint: $errorStream")
        val message = try {
          JSONObject(errorStream).optJSONObject("error")?.optString("message") ?: errorStream
        } catch (_: Exception) { errorStream }
        // Context-window overflow can be reported as 400 *or* 429 with a
        // "tokens"/"context length" message; surface as ContextTooLongException
        // so the manager keeps the user on this provider instead of switching.
        if (com.tom.rv2ide.artificial.agents.openrouter.OpenRouter.isContextTooLong(message)) {
          throw com.tom.rv2ide.artificial.exceptions.ContextTooLongException(
            "$endpoint context overflow: $message"
          )
        }
        when (responseCode) {
          401 -> throw InvalidApiKeyException("Invalid API key for $endpoint: $message")
          402 -> throw QuotaExceededException("Quota exhausted at $endpoint: $message")
          429 -> throw RateLimitException("Rate limit at $endpoint: $message")
          else -> throw Exception("API error from $endpoint ($responseCode): $message")
        }
      }

      val responseBody = connection.inputStream.bufferedReader().readText()
      val json = JSONObject(responseBody)

      json.optJSONObject("usage")?.let { usage ->
        UsageTracker.record(
          promptTokens = usage.optInt("prompt_tokens", 0),
          completionTokens = usage.optInt("completion_tokens", 0),
          model = resolvedModel,
        )
      }

      val choices = json.optJSONArray("choices") ?: throw Exception("No choices in response")
      if (choices.length() > 0) {
        val first = choices.getJSONObject(0)
        // Standard OpenAI shape — message.content. Some servers (Ollama in
        // older versions) return text under `text` instead.
        first.optJSONObject("message")?.optString("content")?.let { if (it.isNotBlank()) return it }
        first.optString("text").let { if (it.isNotBlank()) return it }
      }
      throw Exception("Empty response from $endpoint")
    } catch (e: java.net.SocketTimeoutException) {
      throw Exception("Request timeout to $endpoint: ${e.message}")
    } catch (e: java.net.UnknownHostException) {
      throw Exception("Cannot reach $endpoint: ${e.message}")
    } finally {
      connection.disconnect()
    }
  }

  private fun readRelevantFiles(): Map<String, String> {
    val filesContent = mutableMapOf<String, String>()
    val tree = projectTreeResult?.tree ?: return filesContent
    val filePaths = tree.lines().filter { it.isNotBlank() }
    var loaded = 0
    val maxFiles = 40
    val maxBytesPerFile = 64 * 1024
    for (filePath in filePaths) {
      if (loaded >= maxFiles) break
      val trimmedPath = filePath.trim()
      val file = File(trimmedPath)
      if (
        file.isFile &&
        (
          trimmedPath.endsWith(".kt") ||
            trimmedPath.endsWith(".java") ||
            trimmedPath.endsWith(".xml") ||
            trimmedPath.endsWith(".gradle") ||
            trimmedPath.endsWith(".gradle.kts")
          ) &&
        !trimmedPath.contains("/build/") && !trimmedPath.contains("/.gradle/")
      ) {
        try {
          val len = file.length()
          val content = if (len > maxBytesPerFile) file.readText().take(maxBytesPerFile)
            else file.readText()
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
        IllegalStateException("OpenAI-compatible service not initialized"),
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

      val endpoint = normaliseEndpoint(resolvedBaseUrl)
      val full = SseChatStream.call(
        endpoint = endpoint,
        apiKey = key,
        model = resolvedModel,
        systemPrompt = writingRules.useThis(),
        userPrompt = fullPrompt,
        onChunk = onChunk,
      )

      if (full.isBlank()) {
        return@withContext Result.failure(Exception("Empty stream from $providerName"))
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
