/*
 * Tracks total estimated AI spend per UTC calendar day and enforces an
 * optional user-configured cap. Pre-request the agent calls
 * [DailyCostGuard.assertWithinBudget]; post-request it calls [recordCost].
 *
 * Persistence is in SharedPreferences so the cap survives process death.
 * The day-rollover key resets the running total automatically.
 */
package com.tom.rv2ide.artificial.usage

import android.content.Context
import android.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object DailyCostGuard {

  private const val KEY_LIMIT_USD = "ai_daily_cost_limit_usd"
  private const val KEY_TODAY_USD = "ai_daily_cost_today_usd"
  private const val KEY_TODAY_DAY = "ai_daily_cost_today_day"

  private fun today(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date())
  }

  /** Returns 0 if no cap is set. */
  fun limitUsd(context: Context): Double {
    val sp = PreferenceManager.getDefaultSharedPreferences(context)
    val v = sp.getString(KEY_LIMIT_USD, "0") ?: "0"
    return v.toDoubleOrNull() ?: 0.0
  }

  fun setLimitUsd(context: Context, usd: Double) {
    PreferenceManager.getDefaultSharedPreferences(context).edit()
      .putString(KEY_LIMIT_USD, String.format(Locale.US, "%.4f", usd))
      .apply()
  }

  fun spentTodayUsd(context: Context): Double {
    val sp = PreferenceManager.getDefaultSharedPreferences(context)
    val day = sp.getString(KEY_TODAY_DAY, null)
    if (day != today()) return 0.0
    return sp.getString(KEY_TODAY_USD, "0")?.toDoubleOrNull() ?: 0.0
  }

  fun recordCost(context: Context, usd: Double) {
    if (usd <= 0.0) return
    val sp = PreferenceManager.getDefaultSharedPreferences(context)
    val day = today()
    val prev = if (sp.getString(KEY_TODAY_DAY, null) == day) {
      sp.getString(KEY_TODAY_USD, "0")?.toDoubleOrNull() ?: 0.0
    } else 0.0
    val next = prev + usd
    sp.edit()
      .putString(KEY_TODAY_DAY, day)
      .putString(KEY_TODAY_USD, String.format(Locale.US, "%.6f", next))
      .apply()
  }

  /**
   * Returns null if the request may proceed; or a user-facing reason string
   * if the cap has already been reached for today. We intentionally skip the
   * pre-flight check when the cap is 0 (= unlimited).
   */
  fun preflightBlockReason(context: Context): String? {
    val cap = limitUsd(context)
    if (cap <= 0.0) return null
    val spent = spentTodayUsd(context)
    if (spent >= cap) {
      return "Daily AI cost cap reached: spent ${CostEstimator.fmtUsd(spent)} of " +
        "${CostEstimator.fmtUsd(cap)} today. Increase the cap in Preferences → AI " +
        "or wait until tomorrow."
    }
    return null
  }

  fun resetTodayForTesting(context: Context) {
    PreferenceManager.getDefaultSharedPreferences(context).edit()
      .remove(KEY_TODAY_USD).remove(KEY_TODAY_DAY).apply()
  }
}
