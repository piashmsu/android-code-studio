/*
 * Holds the user's pending image attachments for the next AI request. The
 * Chat tab populates this on image-picker pick and/or camera capture; the
 * OpenAI-compat / OpenRouter agents read it and emit a multimodal user
 * message
 *   { role: "user", content: [
 *      { type:"text", text:"..." },
 *      { type:"image_url", image_url:{url:"data:..."} },
 *      { type:"image_url", image_url:{url:"data:..."} },
 *      ...
 *   ] }
 * which every modern vision-capable model on those providers understands.
 *
 * Multiple images can be attached at once. The list is cleared automatically
 * after the next request so a leftover image doesn't leak into the next,
 * unrelated prompt.
 */
package com.tom.rv2ide.artificial.multimodal

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageAttachment {

    /** Maximum bytes of compressed image we'll send. Keeps prompts under ~6MB. */
    private const val MAX_BYTES = 4 * 1024 * 1024

    /** Hard cap on number of images per message — vision models bill per image. */
    private const val MAX_IMAGES = 6

    data class Item(
        val dataUrl: String,
        val label: String,
        val thumbnail: Bitmap? = null,
    )

    private val items = mutableListOf<Item>()

    @Synchronized
    fun all(): List<Item> = items.toList()

    @Synchronized
    fun count(): Int = items.size

    @Synchronized
    fun hasPending(): Boolean = items.isNotEmpty()

    @Synchronized
    fun add(item: Item): Boolean {
        if (items.size >= MAX_IMAGES) return false
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

    /** Convenience for callers that just want to know whether the cap was hit. */
    @Synchronized
    fun isFull(): Boolean = items.size >= MAX_IMAGES

    @Synchronized
    fun maxImages(): Int = MAX_IMAGES

    // --- Legacy single-image API for backwards compat with older call sites. ---

    /** First pending image as a `data:image/...;base64,...` URL, or null. */
    val pendingDataUrl: String?
        @Synchronized get() = items.firstOrNull()?.dataUrl

    /** First pending image's display label, or null. */
    val pendingLabel: String?
        @Synchronized get() = items.firstOrNull()?.label

    fun set(dataUrl: String, label: String) {
        clear()
        add(Item(dataUrl, label))
    }

    /**
     * Read the user-picked image from the given URI, downscale to at most
     * [maxEdge] pixels on the longest edge (so vision models don't reject huge
     * photos), JPEG-compress at quality 85, and produce a `data:image/jpeg;base64,...`
     * URL. Returns null on any failure.
     */
    fun encodeFromUri(
        resolver: ContentResolver,
        uri: Uri,
        maxEdge: Int = 1600,
    ): EncodedImage? {
        return try {
            val bytes = resolver.openInputStream(uri).use { it?.readBytes() } ?: return null
            encodeFromBytes(bytes, maxEdge)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Same as [encodeFromUri] but for in-memory bytes (e.g. camera capture).
     */
    fun encodeFromBytes(bytes: ByteArray, maxEdge: Int = 1600): EncodedImage? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val sample = computeInSampleSize(opts.outWidth, opts.outHeight, maxEdge)
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) ?: return null
            val scaled = scaleToMaxEdge(bitmap, maxEdge)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            var jpeg = out.toByteArray()
            var quality = 75
            while (jpeg.size > MAX_BYTES && quality >= 35) {
                out.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                jpeg = out.toByteArray()
                quality -= 10
            }
            val dataUrl = "data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}"
            EncodedImage(
                dataUrl = dataUrl,
                sizeBytes = jpeg.size,
                thumbnail = makeThumbnail(scaled),
            )
        } catch (_: Throwable) {
            null
        }
    }

    data class EncodedImage(
        val dataUrl: String,
        val sizeBytes: Int,
        val thumbnail: Bitmap?,
    )

    private fun makeThumbnail(src: Bitmap, edge: Int = 96): Bitmap? {
        return try {
            val w = src.width; val h = src.height
            if (w <= 0 || h <= 0) return null
            val scale = edge.toFloat() / maxOf(w, h)
            val nw = (w * scale).toInt().coerceAtLeast(1)
            val nh = (h * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, nw, nh, true)
        } catch (_: Throwable) { null }
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, maxEdge: Int): Int {
        if (srcW <= 0 || srcH <= 0) return 1
        var sample = 1
        var w = srcW
        var h = srcH
        while (w / 2 >= maxEdge || h / 2 >= maxEdge) {
            w /= 2; h /= 2; sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= maxEdge && h <= maxEdge) return src
        val scale = maxEdge.toFloat() / maxOf(w, h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
