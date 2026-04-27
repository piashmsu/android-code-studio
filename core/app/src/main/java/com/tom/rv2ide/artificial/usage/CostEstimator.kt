/*
 * Estimates dollar cost from token counts using a curated table of per-million-
 * token rates. Coverage is best-effort — for unrecognised models we fall back
 * to a low default. Numbers are approximate (provider list-prices) and meant
 * for in-app awareness, not invoicing.
 */
package com.tom.rv2ide.artificial.usage

import kotlin.math.max

object CostEstimator {

  /** $/M-token (input, output) for known models. Lower-case match. */
  private val TABLE: Map<String, Pair<Double, Double>> = mapOf(
    // OpenAI
    "gpt-4o" to (2.50 to 10.00),
    "gpt-4o-mini" to (0.15 to 0.60),
    "gpt-4-turbo" to (10.00 to 30.00),
    "gpt-4" to (30.00 to 60.00),
    "gpt-3.5-turbo" to (0.50 to 1.50),
    "o1-preview" to (15.00 to 60.00),
    "o1-mini" to (3.00 to 12.00),
    // Anthropic
    "claude-3-5-sonnet" to (3.00 to 15.00),
    "claude-3-5-haiku" to (0.80 to 4.00),
    "claude-3-opus" to (15.00 to 75.00),
    "claude-3-sonnet" to (3.00 to 15.00),
    "claude-3-haiku" to (0.25 to 1.25),
    // Google
    "gemini-1.5-pro" to (1.25 to 5.00),
    "gemini-1.5-flash" to (0.075 to 0.30),
    "gemini-2.0-flash" to (0.10 to 0.40),
    "gemini-pro" to (0.50 to 1.50),
    // DeepSeek
    "deepseek-chat" to (0.14 to 0.28),
    "deepseek-coder" to (0.14 to 0.28),
    "deepseek-v3" to (0.14 to 0.28),
    "deepseek-v3.1" to (0.14 to 0.28),
    "deepseek-r1" to (0.55 to 2.19),
    // Llama / Together / Groq
    "llama-3.3-70b" to (0.59 to 0.79),
    "llama-3.1-70b" to (0.59 to 0.79),
    "llama-3.1-8b" to (0.18 to 0.18),
    "llama-3.2-90b" to (0.90 to 0.90),
    "qwen-2.5-coder-32b" to (0.20 to 0.20),
    "qwen-2.5-72b" to (0.59 to 0.79),
    // xAI Grok
    "grok-beta" to (5.00 to 15.00),
    "grok-2" to (2.00 to 10.00),
  )

  fun rateFor(model: String): Pair<Double, Double> {
    val key = model.lowercase()
    // Free OpenRouter models are exactly free.
    if (key.endsWith(":free")) return 0.0 to 0.0
    // Local Ollama / vLLM: zero by definition (self-hosted).
    if (key.startsWith("ollama/") || key.contains("local")) return 0.0 to 0.0
    // Try direct match, then strip vendor prefix (vendor/model), then suffixes.
    TABLE[key]?.let { return it }
    val noVendor = key.substringAfter('/', missingDelimiterValue = key)
    TABLE[noVendor]?.let { return it }
    val coreSlug = noVendor.substringBefore(':').substringBefore('@')
    TABLE[coreSlug]?.let { return it }
    // Substring match — pick the longest matching key.
    val match = TABLE.entries
      .filter { coreSlug.contains(it.key) || it.key.contains(coreSlug) }
      .maxByOrNull { it.key.length }
    if (match != null) return match.value
    // Unknown — assume cheap to avoid scaring the user.
    return 0.05 to 0.10
  }

  /** Approximate USD cost for the given usage. */
  fun costFor(promptTokens: Int, completionTokens: Int, model: String): Double {
    val (inRate, outRate) = rateFor(model)
    if (inRate == 0.0 && outRate == 0.0) return 0.0
    val inCost = promptTokens.toDouble() / 1_000_000.0 * inRate
    val outCost = completionTokens.toDouble() / 1_000_000.0 * outRate
    return max(0.0, inCost + outCost)
  }

  /** Pretty-printed dollar amount, never showing sub-cent noise. */
  fun fmtUsd(amount: Double): String {
    return when {
      amount == 0.0 -> "$0.00 (free)"
      amount < 0.0001 -> "<$0.0001"
      amount < 0.01 -> String.format("$%.4f", amount)
      else -> String.format("$%.3f", amount)
    }
  }
}
