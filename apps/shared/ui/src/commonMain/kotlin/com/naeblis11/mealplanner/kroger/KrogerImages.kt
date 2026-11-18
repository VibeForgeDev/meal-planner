package com.naeblis11.mealplanner.kroger

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.decodeImageBitmap
import com.naeblis11.mealplanner.ui.theme.MealColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kroger's product pictures, held in memory only while the app runs (Kroger's acceptable use: nothing it returns is
 * stored), the most recent [MAX] of them, so scrolling back and forth fetches each once. A picture that couldn't be had
 * is remembered as missing for the session, so a broken one isn't asked for again and again.
 */
object KrogerImages {
    const val MAX = 150

    private val missing = Any()
    private val cache = object : LinkedHashMap<String, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > MAX
    }

    /** The picture for [url] already in memory, or null. */
    fun cached(url: String): ImageBitmap? = synchronized(cache) { cache[url] as? ImageBitmap }

    /** Whether [url] was already tried this session (found or not). */
    fun tried(url: String): Boolean = synchronized(cache) { cache.containsKey(url) }

    /** Fetches and decodes [url] through [fetch] (blocking), remembering the outcome in memory. */
    fun load(url: String, fetch: (String) -> ByteArray?): ImageBitmap? {
        synchronized(cache) { cache[url] }?.let { return it as? ImageBitmap }
        val bitmap = fetch(url)?.let(::decodeImageBitmap)
        synchronized(cache) { cache[url] = bitmap ?: missing }
        return bitmap
    }

    fun clear() = synchronized(cache) { cache.clear() }
}

/** A product's picture in the review: big enough to tell products apart on a phone (owner, 2026-11-17). */
val PRODUCT_IMAGE_SIZE: Dp = 96.dp

/** A product's picture in a list of picks (the summary, Done, Remembered products). */
val PICK_IMAGE_SIZE: Dp = 72.dp

/**
 * A product's picture in a [size] square, fetched off the main thread through [LocalKroger]; a plain square while it
 * loads, or when Kroger has none, so the list never waits on pictures.
 */
@Composable
fun KrogerProductImage(url: String?, size: Dp = PRODUCT_IMAGE_SIZE, modifier: Modifier = Modifier) {
    val controls = LocalKroger.current
    val bitmap by produceState(url?.let(KrogerImages::cached), url, controls) {
        if (url == null || controls == null || value != null || KrogerImages.tried(url)) return@produceState
        value = withContext(Dispatchers.IO) { KrogerImages.load(url) { controls.image(it) } }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.size(size).clip(shape).background(MealColors.Paper).border(1.dp, MealColors.LineSoft, shape),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp)) }
    }
}
