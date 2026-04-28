/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Tracks per-session token usage for AI providers that report it (OpenRouter,
 *  OpenAI, etc.). Lightweight, in-memory only — counts reset when the process
 *  is recreated. UI surfaces read [last] and [totalTokens] for badges/footers.
 */

package com.tom.rv2ide.artificial.usage

import java.util.concurrent.atomic.AtomicLong

object UsageTracker {

  data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val model: String,
    val timestamp: Long = System.currentTimeMillis(),
  )

  @Volatile var last: Usage? = null
    private set

  private val totalPrompt = AtomicLong(0)
  private val totalCompletion = AtomicLong(0)
  private val requestCount = AtomicLong(0)

  fun record(promptTokens: Int, completionTokens: Int, model: String) {
    val total = promptTokens + completionTokens
    last = Usage(promptTokens, completionTokens, total, model)
    totalPrompt.addAndGet(promptTokens.toLong())
    totalCompletion.addAndGet(completionTokens.toLong())
    requestCount.incrementAndGet()
    // Best-effort: feed the daily cost guard if the IDEApplication singleton
    // is available. Wrapped in a try/catch so unit tests / non-app contexts
    // that don't have the singleton don't crash here.
    try {
      val cls = Class.forName("com.tom.rv2ide.app.IDEApplication")
      val instance = cls.getField("instance").get(null) as android.content.Context
      val cost = CostEstimator.costFor(promptTokens, completionTokens, model)
      DailyCostGuard.recordCost(instance, cost)
    } catch (_: Throwable) { /* ignore */ }
  }

  fun totalTokens(): Long = totalPrompt.get() + totalCompletion.get()
  fun totalRequests(): Long = requestCount.get()

  fun reset() {
    last = null
    totalPrompt.set(0)
    totalCompletion.set(0)
    requestCount.set(0)
  }
}
