/*
 * Renders an AI reply that may contain fenced code blocks into a vertical
 * LinearLayout. Each prose segment becomes a styled MaterialTextView (using
 * [MarkdownRenderer]); each code block becomes a card with a header that
 * shows the language tag and a "Copy" button. This is what gives users the
 * Stack-Overflow-style per-block copy affordance they expect from a modern
 * chat app.
 *
 * Falls back to a single text view when the reply contains no fenced code.
 */
package com.tom.rv2ide.artificial.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView

object RichReplyRenderer {

  /**
   * Replace the contents of [container] with a sequence of segments parsed
   * from [markdown]. Returns true if the markdown contained at least one
   * fenced code block (so the caller can show this container instead of the
   * plain status TextView).
   */
  fun render(container: LinearLayout, markdown: String): Boolean {
    container.removeAllViews()
    val ctx = container.context
    val segments = parse(markdown)
    val hasCode = segments.any { it is Segment.Code }
    if (!hasCode) return false
    for (seg in segments) {
      when (seg) {
        is Segment.Text -> if (seg.body.isNotBlank()) container.addView(textView(ctx, seg.body))
        is Segment.Code -> container.addView(codeCard(ctx, seg))
      }
    }
    return true
  }

  private fun textView(ctx: Context, md: String): MaterialTextView {
    return MaterialTextView(ctx).apply {
      text = MarkdownRenderer.render(md)
      setTextIsSelectable(true)
      setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
      val padding = (resources.displayMetrics.density * 4).toInt()
      setPadding(0, padding, 0, padding)
    }
  }

  private fun codeCard(ctx: Context, seg: Segment.Code): View {
    val density = ctx.resources.displayMetrics.density
    val card = MaterialCardView(ctx).apply {
      radius = density * 8
      cardElevation = density * 1
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
      ).apply {
        topMargin = (density * 6).toInt()
        bottomMargin = (density * 6).toInt()
      }
    }
    val column = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
    }

    // Header row: language label on the left, Copy button on the right.
    val header = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER_VERTICAL
      val pH = (density * 10).toInt()
      val pV = (density * 4).toInt()
      setPadding(pH, pV, pH, pV)
      setBackgroundColor(0x14000000) // subtle tint
    }
    val langLabel = MaterialTextView(ctx).apply {
      text = if (seg.language.isNotBlank()) seg.language else "code"
      typeface = Typeface.MONOSPACE
      textSize = 12f
      alpha = 0.75f
      layoutParams = LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
      )
    }
    val copyBtn = MaterialButton(
      ctx, null,
      com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
      text = "Copy"
      textSize = 11f
      val pH = (density * 10).toInt()
      val pV = (density * 2).toInt()
      setPadding(pH, pV, pH, pV)
      minWidth = 0
      minimumWidth = 0
      insetTop = 0
      insetBottom = 0
      setOnClickListener {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AI code block", seg.body))
        Toast.makeText(ctx, "Copied ${seg.body.length} chars", Toast.LENGTH_SHORT).show()
      }
    }
    header.addView(langLabel); header.addView(copyBtn)
    column.addView(header)

    // Body: monospace selectable code text.
    val body = MaterialTextView(ctx).apply {
      text = seg.body
      typeface = Typeface.MONOSPACE
      textSize = 13f
      setTextIsSelectable(true)
      val pH = (density * 12).toInt()
      val pV = (density * 8).toInt()
      setPadding(pH, pV, pH, pV)
    }
    column.addView(body)
    card.addView(column)
    return card
  }

  // ---------- parser ----------

  sealed class Segment {
    data class Text(val body: String) : Segment()
    data class Code(val language: String, val body: String) : Segment()
  }

  fun parse(markdown: String): List<Segment> {
    val src = markdown.replace("\r\n", "\n")
    val out = mutableListOf<Segment>()
    var i = 0
    val pending = StringBuilder()
    while (i < src.length) {
      if (src.startsWith("```", i)) {
        val end = src.indexOf("```", i + 3)
        if (end > 0) {
          if (pending.isNotEmpty()) {
            out.add(Segment.Text(pending.toString())); pending.setLength(0)
          }
          val raw = src.substring(i + 3, end).trimStart('\n')
          val firstNl = raw.indexOf('\n')
          val (lang, body) = if (firstNl >= 0 &&
            raw.substring(0, firstNl).trim().matches(Regex("[a-zA-Z0-9+_.-]+"))) {
            raw.substring(0, firstNl).trim() to raw.substring(firstNl + 1)
          } else { "" to raw }
          out.add(Segment.Code(lang, body.trimEnd()))
          i = end + 3
          if (i < src.length && src[i] == '\n') i++
          continue
        }
      }
      pending.append(src[i]); i++
    }
    if (pending.isNotEmpty()) out.add(Segment.Text(pending.toString()))
    return out
  }
}
