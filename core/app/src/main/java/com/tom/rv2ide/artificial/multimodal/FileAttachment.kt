/*
 * Holds the user's pending file attachments for the next AI request. Mirrors
 * [ImageAttachment] but is for non-image attachments — text/code files, JSON,
 * XML, YAML, Markdown, log dumps, and ZIP archives whose entries get
 * recursively expanded into a single textual block.
 *
 * Unlike images, file attachments are inlined into the prompt as plain text
 * (wrapped in fenced code blocks with the file path as a header), so EVERY
 * provider — including text-only models — can read them without needing
 * vision support.
 *
 * The list is cleared automatically after the next request so a leftover
 * attachment doesn't leak into the next, unrelated prompt.
 */
package com.tom.rv2ide.artificial.multimodal

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

object FileAttachment {

    /** Hard cap on number of files attached per message. */
    private const val MAX_FILES = 8

    /**
     * Maximum size in bytes we'll inline into the prompt for a single
     * file (text or extracted ZIP entry). Files larger than this are
     * truncated with a "[truncated]" marker so we don't blow past
     * provider context windows.
     */
    private const val MAX_INLINE_BYTES = 256 * 1024

    /** Hard cap on total bytes of inlined content across all attachments. */
    private const val MAX_TOTAL_BYTES = 1 * 1024 * 1024

    /** Hard cap on number of zip entries we'll list / inline from a single archive. */
    private const val MAX_ZIP_ENTRIES = 200

    /** Common text-ish extensions we attempt to inline as text. */
    private val TEXT_EXTS = setOf(
        // Programming languages
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "c", "cc", "cpp",
        "h", "hpp", "rs", "go", "swift", "m", "mm", "rb", "php", "scala",
        "groovy", "gradle", "dart", "lua", "pl", "sh", "bash", "zsh", "fish",
        "ps1", "psm1", "bat", "cmd", "vb", "fs", "fsx", "ml", "hs", "lhs",
        "elm", "ex", "exs", "erl", "hrl", "clj", "cljc", "cljs", "cs", "vue",
        "svelte", "asm", "s", "r", "jl", "nim", "zig", "v", "tf", "tfvars",
        // Markup / config
        "txt", "md", "markdown", "rst", "html", "htm", "xml", "yml", "yaml",
        "json", "json5", "jsonc", "toml", "ini", "cfg", "conf", "properties",
        "env", "dockerfile", "csv", "tsv", "log", "sql", "graphql", "gql",
        "proto", "thrift", "ipynb", "lock",
        // Android-specific
        "smali", "aidl", "rules", "pro", "mk",
    )

    /** Files this size or larger are not even attempted. */
    private const val MAX_RAW_BYTES = 5 * 1024 * 1024

    data class Item(
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long,
        /** Plain-text body to inline into the prompt. Truncation is applied. */
        val body: String,
        /** True when this entry is a ZIP archive that has been expanded. */
        val isZip: Boolean = false,
    )

    private val items = mutableListOf<Item>()

    @Synchronized fun all(): List<Item> = items.toList()
    @Synchronized fun count(): Int = items.size
    @Synchronized fun hasPending(): Boolean = items.isNotEmpty()
    @Synchronized fun isFull(): Boolean = items.size >= MAX_FILES
    @Synchronized fun maxFiles(): Int = MAX_FILES

    @Synchronized
    fun add(item: Item): Boolean {
        if (items.size >= MAX_FILES) return false
        items.add(item)
        return true
    }

    @Synchronized
    fun removeAt(index: Int) {
        if (index in items.indices) items.removeAt(index)
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    /**
     * Build a single textual block of the form
     *
     *   --- ATTACHED FILE: foo.kt (1.2 KB) ---
     *   ```kt
     *   ...content...
     *   ```
     *
     *   --- ATTACHED ARCHIVE: bundle.zip (3 entries) ---
     *   path/to/inner.kt:
     *   ```kt
     *   ...
     *   ```
     *
     * suitable for prepending to the user's prompt. Returns an empty string
     * when no files are attached.
     */
    @Synchronized
    fun renderForPrompt(): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("The user has attached the following file(s) for context. " +
                "Read them carefully before answering.\n\n")
        for (it in items) {
            if (it.isZip) {
                sb.append("--- ATTACHED ARCHIVE: ${it.displayName} (${formatBytes(it.sizeBytes)}) ---\n")
            } else {
                sb.append("--- ATTACHED FILE: ${it.displayName} (${formatBytes(it.sizeBytes)}) ---\n")
            }
            sb.append(it.body.trimEnd()).append("\n\n")
        }
        return sb.toString()
    }

    /**
     * Read the picked URI as a text-ish file and produce an [Item]. For ZIPs,
     * extract entries and inline them. Returns null on any failure (e.g.
     * binary, oversized).
     */
    fun loadFromUri(resolver: ContentResolver, uri: Uri): Item? {
        return try {
            val (name, size) = queryNameAndSize(resolver, uri)
            val displayName = name.ifBlank { uri.lastPathSegment ?: "file" }
            val ext = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            val bytes = resolver.openInputStream(uri).use { it?.readBytes() } ?: return null
            if (bytes.size > MAX_RAW_BYTES) return null

            if (ext == "zip" || isZipMagic(bytes)) {
                val body = expandZip(bytes)
                Item(
                    displayName = displayName,
                    mimeType = "application/zip",
                    sizeBytes = bytes.size.toLong().coerceAtLeast(size),
                    body = body,
                    isZip = true,
                )
            } else {
                val text = decodeTextOrNull(bytes) ?: return null
                val truncated = truncateText(text, MAX_INLINE_BYTES)
                val lang = ext.takeIf { it.isNotBlank() && it in TEXT_EXTS } ?: ""
                val body = "```$lang\n$truncated\n```"
                Item(
                    displayName = displayName,
                    mimeType = "text/$ext",
                    sizeBytes = bytes.size.toLong().coerceAtLeast(size),
                    body = body,
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = ""
        var size = 0L
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nIdx >= 0) name = c.getString(nIdx).orEmpty()
                    if (sIdx >= 0) size = c.getLong(sIdx)
                }
            }
        } catch (_: Throwable) { /* best-effort */ }
        return name to size
    }

    private fun isZipMagic(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
                bytes[0] == 0x50.toByte() && // 'P'
                bytes[1] == 0x4B.toByte() && // 'K'
                (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
    }

    private fun expandZip(bytes: ByteArray): String {
        val sb = StringBuilder()
        var totalInlined = 0
        var entries = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > MAX_ZIP_ENTRIES) {
                    sb.append("\n[truncated: more than $MAX_ZIP_ENTRIES entries in archive]\n")
                    break
                }
                val name = entry.name
                if (entry.isDirectory) {
                    sb.append("📁 $name/\n")
                    continue
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var n: Int
                while (zip.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                val entryBytes = out.toByteArray()

                if (ext in TEXT_EXTS && totalInlined < MAX_TOTAL_BYTES) {
                    val text = decodeTextOrNull(entryBytes) ?: run {
                        sb.append("📄 $name (${formatBytes(entryBytes.size.toLong())}, binary, skipped)\n")
                        return@run null
                    } ?: continue
                    val budget = (MAX_INLINE_BYTES.toLong()
                        .coerceAtMost((MAX_TOTAL_BYTES - totalInlined).toLong())).toInt()
                    val truncated = truncateText(text, budget)
                    totalInlined += truncated.toByteArray(Charsets.UTF_8).size
                    sb.append("\n📄 $name (${formatBytes(entryBytes.size.toLong())}):\n")
                    sb.append("```$ext\n").append(truncated).append("\n```\n")
                } else {
                    sb.append("📄 $name (${formatBytes(entryBytes.size.toLong())}, binary or oversized, listed only)\n")
                }
            }
        }
        if (sb.isEmpty()) sb.append("[archive is empty]")
        return sb.toString()
    }

    private fun decodeTextOrNull(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        // Heuristic: if more than 1% of the first 4KB is non-printable
        // (excluding common whitespace), treat as binary.
        val sample = bytes.copyOf(minOf(bytes.size, 4096))
        var bad = 0
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v == 0) return null  // null byte → almost certainly binary
            val printable = v in 0x20..0x7E ||
                    v == 0x09 || v == 0x0A || v == 0x0D ||
                    v >= 0x80  // non-ASCII printable / UTF-8 continuation
            if (!printable) bad += 1
        }
        if (bad * 100 / sample.size > 1) return null
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun truncateText(text: String, maxBytes: Int): String {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return text
        val truncated = String(raw, 0, maxBytes, Charsets.UTF_8)
        return truncated + "\n\n[…truncated, ${formatBytes(raw.size.toLong() - maxBytes)} more]"
    }

    private fun formatBytes(n: Long): String {
        if (n < 1024) return "$n B"
        val kb = n / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        return String.format("%.1f MB", kb / 1024.0)
    }
}
