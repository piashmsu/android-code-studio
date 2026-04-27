package com.tom.rv2ide.handlers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
import com.tom.rv2ide.adapters.FileModificationAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.text.MarkdownRenderer
import com.tom.rv2ide.artificial.usage.SessionLog
import com.tom.rv2ide.artificial.usage.UsageTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AIRequestHandler(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val aiAgent: AIAgentManager,
    private val statusText: MaterialTextView,
    private val summaryText: MaterialTextView,
    private val progressIndicator: CircularProgressIndicator,
    private val executeBtn: MaterialButton,
    private val fileModificationList: RecyclerView,
    private val fileModificationAdapter: FileModificationAdapter,
    private val summaryCard: LinearLayout,
    private val onFileOpen: (String) -> Unit,
    private val onTypeText: (String, Long) -> Unit,
    private val getCurrentFile: () -> File?,
    private val refreshEditor: () -> Unit
) {
    
    private var executionJob: Job? = null
    
    fun execute(userRequest: String) {
        executionJob?.cancel()
        executionJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    executeBtn.isEnabled = false
                    progressIndicator.visibility = View.VISIBLE
                    summaryCard.visibility = View.GONE
                    fileModificationAdapter.clear()
                    fileModificationList.visibility = View.GONE
                    // Clear any leftover code-block cards from a previous reply.
                    statusText.rootView.findViewById<LinearLayout>(
                        com.tom.rv2ide.R.id.replySegments
                    )?.let { it.removeAllViews(); it.visibility = View.GONE }
                }

                SessionLog.add(SessionLog.Entry("user", userRequest))

                // Surface obvious offline state up front so the user doesn't sit
                // through a 30 s watchdog when they're on airplane mode.
                if (!com.tom.rv2ide.artificial.safety.PromptSafety.isOnline(executeBtn.context)) {
                    withContext(Dispatchers.Main) {
                        executeBtn.isEnabled = true
                        progressIndicator.visibility = View.GONE
                        statusText.text = "🌐 No internet connection. Check Wi-Fi / mobile data and try again."
                    }
                    return@launch
                }

                // Warn the user once if their prompt visibly contains a secret —
                // they may have pasted a key or token by mistake. The request is
                // still sent (we can't fully redact reliably), but the warning
                // gives them a chance to abort.
                val secrets = com.tom.rv2ide.artificial.safety.PromptSafety.findSecrets(userRequest)
                if (secrets.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "⚠️ Possible secret detected in prompt (${secrets.size}). Sending anyway."
                    }
                }

                // Expand any `@filename` mentions into attached file context so the
                // model sees the actual contents of the files the user is asking
                // about, rather than relying on its own guess.
                val expanded = com.tom.rv2ide.artificial.text.MentionResolver.resolve(
                    userRequest,
                    aiAgent.getProjectRoot(),
                )
                if (expanded.resolved.isNotEmpty() || expanded.unresolved.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val pieces = mutableListOf<String>()
                        if (expanded.resolved.isNotEmpty()) {
                            pieces += "Attached: " + expanded.resolved.joinToString(", ")
                        }
                        if (expanded.unresolved.isNotEmpty()) {
                            pieces += "Could not find: " + expanded.unresolved.joinToString(", ")
                        }
                        statusText.text = pieces.joinToString("\n")
                    }
                }

                executeAIRequest(expanded.expandedPrompt)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    executeBtn.isEnabled = true
                    progressIndicator.visibility = View.GONE
                    statusText.text = "❌ Error: ${e.message}"
                }
            }
        }
    }
    
    private suspend fun executeAIRequest(userRequest: String) {
        aiAgent.executeRequest(userRequest, object : AIAgentManager.AIAgentCallback {
            override fun onProcessing(message: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    statusText.text = message
                }
            }

            override fun onFileModifying(filePath: String, fileName: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    if (fileModificationList.visibility == View.GONE) {
                        fileModificationList.visibility = View.VISIBLE
                    }
                    fileModificationAdapter.addItem(fileName)
                }
            }

            override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
                lifecycleScope.launch(Dispatchers.Main) {
                    fileModificationAdapter.updateItemStatus(fileName, success)
                    
                    if (getCurrentFile()?.name == fileName && success) {
                        refreshEditor()
                    }
                }
            }

            override fun onSuccess(
                response: String,
                modifications: List<AIAgentManager.ModificationResult>,
                summary: AIAgentManager.ModificationSummary
            ) {
                lifecycleScope.launch(Dispatchers.Main) {
                    handleSuccess(response, modifications, summary)
                }
            }

            override fun onTextResponse(
                response: String,
                summary: AIAgentManager.ModificationSummary
            ) {
                lifecycleScope.launch(Dispatchers.Main) {
                    handleTextResponse(response)
                }
            }

            override fun onError(message: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    handleError(message)
                }
            }

            override fun onRetry(attemptNumber: Int, message: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    statusText.text = "🔄 Retry #$attemptNumber: $message"
                }
            }

            override fun onStreamChunk(delta: String, fullSoFar: String) {
                // Coalesce updates on the main thread; the SSE callback fires
                // dozens of times per second, so we just push the latest text
                // and let the rendering cost stay sublinear.
                lifecycleScope.launch(Dispatchers.Main) {
                    summaryCard.visibility = View.VISIBLE
                    val truncated = if (fullSoFar.length > 8000)
                        "…" + fullSoFar.takeLast(8000) else fullSoFar
                    summaryText.text = truncated
                    statusText.text = "💬 Streaming…"
                }
            }

            override suspend fun confirmFileChange(
                filePath: String,
                previousContent: String?,
                newContent: String,
            ): Boolean {
                val ctx = statusText.context
                val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                val enabled = sp.getBoolean("ai_agent_diff_preview_enabled", false)
                if (!enabled) return true
                return try {
                    com.tom.rv2ide.artificial.diff.DiffPreviewDialog.confirm(
                        ctx, filePath, previousContent, newContent,
                    )
                } catch (_: Exception) {
                    // If the dialog can't show (e.g., context not an Activity),
                    // fall back to writing.
                    true
                }
            }
        })
    }
    
    private fun handleSuccess(
        response: String,
        modifications: List<AIAgentManager.ModificationResult>,
        summary: AIAgentManager.ModificationSummary
    ) {
        progressIndicator.visibility = View.GONE
        statusText.text = "✅ Operation completed"
        SessionLog.add(SessionLog.Entry("assistant", response, model = UsageTracker.last?.model))
        summaryText.text = buildSummaryText(summary)
        summaryCard.visibility = View.VISIBLE
        
        if (modifications.isNotEmpty()) {
            val firstMod = modifications.first()
            val file = File(firstMod.filePath)
            if (file.exists()) {
                onFileOpen(file.name)
            }
        }
        
        executeBtn.isEnabled = true
    }

    private fun handleTextResponse(response: String) {
        progressIndicator.visibility = View.GONE
        executeBtn.isEnabled = true
        SessionLog.add(SessionLog.Entry("assistant", response, model = UsageTracker.last?.model))

        // If the reply contains fenced code blocks, render each block as its
        // own card with a Copy button so the user can grab snippets without
        // having to long-press-select. Otherwise fall back to the single
        // selectable status text.
        val segmentsContainer = statusText.rootView.findViewById<LinearLayout>(
            com.tom.rv2ide.R.id.replySegments
        )
        val showRich = segmentsContainer != null &&
            com.tom.rv2ide.artificial.text.RichReplyRenderer.render(segmentsContainer, response)
        if (showRich) {
            statusText.text = "✅ Response received  •  ${response.length} chars"
            segmentsContainer?.visibility = View.VISIBLE
        } else {
            segmentsContainer?.visibility = View.GONE
            segmentsContainer?.removeAllViews()
            statusText.text = MarkdownRenderer.render(response)
        }
        statusText.setOnLongClickListener {
            val ctx = statusText.context
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AI response", response))
            Toast.makeText(ctx, "Copied AI response", Toast.LENGTH_SHORT).show()
            true
        }

        // Show usage chip below if the provider reported tokens — useful for
        // gauging cost on text-only replies (Q&A, code review, explain).
        val usage = UsageTracker.last
        if (usage != null) {
            summaryCard.visibility = View.VISIBLE
            val cost = com.tom.rv2ide.artificial.usage.CostEstimator
                .costFor(usage.promptTokens, usage.completionTokens, usage.model)
            summaryText.text = buildString {
                append("🔢 Tokens: ${usage.promptTokens} in / ${usage.completionTokens} out")
                append("  •  this request: ${com.tom.rv2ide.artificial.usage.CostEstimator.fmtUsd(cost)}")
                try {
                    val ctx = statusText.context.applicationContext
                    val today = com.tom.rv2ide.artificial.usage.DailyCostGuard.spentTodayUsd(ctx)
                    val limit = com.tom.rv2ide.artificial.usage.DailyCostGuard.limitUsd(ctx)
                    append("\n💰 Today: ${com.tom.rv2ide.artificial.usage.CostEstimator.fmtUsd(today)}")
                    if (limit > 0.0) append(" of ${com.tom.rv2ide.artificial.usage.CostEstimator.fmtUsd(limit)}")
                } catch (_: Throwable) { }
            }
        } else {
            summaryCard.visibility = View.GONE
        }
        fileModificationList.visibility = View.GONE
    }
    
    private fun handleError(message: String) {
        progressIndicator.visibility = View.GONE
        executeBtn.isEnabled = true
        
        statusText.text = """
❌ ERROR OCCURRED

$message

Please check the error message and try again.
        """.trimIndent()
    }
    
    private fun buildSummaryText(summary: AIAgentManager.ModificationSummary): String {
        val builder = StringBuilder()
        builder.append("📊 Total Files: ${summary.totalFiles}\n")
        builder.append("✅ Successful: ${summary.successfulFiles}\n")
        if (summary.failedFiles > 0) {
            builder.append("❌ Failed: ${summary.failedFiles}\n")
        }
        builder.append("🆕 New Files: ${summary.newFiles}\n")
        builder.append("✏️ Modified Files: ${summary.modifiedFiles}\n")

        UsageTracker.last?.let { usage ->
            val cost = com.tom.rv2ide.artificial.usage.CostEstimator
                .costFor(usage.promptTokens, usage.completionTokens, usage.model)
            val costStr = com.tom.rv2ide.artificial.usage.CostEstimator.fmtUsd(cost)
            builder.append("🔢 Tokens: ${usage.promptTokens} in / ${usage.completionTokens} out")
                .append("  •  this request: $costStr")
                .append("  •  session total: ${UsageTracker.totalTokens()}\n")
            // Show today's running spend so the user is never surprised by
            // their bill at end of month.
            try {
                val ctx = statusText.context.applicationContext
                val today = com.tom.rv2ide.artificial.usage.DailyCostGuard.spentTodayUsd(ctx)
                val limit = com.tom.rv2ide.artificial.usage.DailyCostGuard.limitUsd(ctx)
                builder.append("💰 Today: ${com.tom.rv2ide.artificial.usage.CostEstimator.fmtUsd(today)}")
                if (limit > 0.0) {
                    builder.append(" of ${com.tom.rv2ide.artificial.usage.CostEstimator.fmtUsd(limit)}")
                }
                builder.append('\n')
            } catch (_: Throwable) { }
        }
        builder.append('\n')
        
        builder.append("Files:\n")
        summary.fileDetails.forEach { detail ->
            val icon = if (detail.status == AIAgentManager.FileStatus.SUCCESS) "✅" else "❌"
            val type = if (detail.changeType == AIAgentManager.ChangeType.CREATED) "Created" else "Modified"
            builder.append("$icon $type: ${detail.fileName}\n")
        }
        
        return builder.toString()
    }
    
    fun cancel() {
        executionJob?.cancel()
    }
}