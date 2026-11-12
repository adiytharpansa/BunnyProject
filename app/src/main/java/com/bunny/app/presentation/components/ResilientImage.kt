package com.bunny.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Session memory of image URLs that failed through every retry.
 * A URL in here is not requested again until it loads successfully
 * somewhere (which removes it) or the process restarts — the
 * design's "stop asking for what keeps failing" rule (Fase 7-A).
 */
object FailedImageUrls {
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun isFailed(url: String): Boolean = url in failed

    fun markFailed(url: String) {
        failed += url
    }

    fun markOk(url: String) {
        failed -= url
    }
}

/**
 * Fase 7-A — a photo that fights for itself.
 *
 * The rule (2026-11-11) is real photos only, no letter stand-ins:
 * the surface stays plain until the channel's actual photo lands.
 * What was missing is persistence — one network hiccup used to
 * leave the plain surface forever. This loader retries a failed
 * photo twice with a pause (bypassing the caches on retry, so the
 * retry is a real second chance), then remembers the URL as failed
 * for the rest of the session instead of hammering it.
 *
 * [requestBuilder] lets a call site keep its own request shaping
 * (the card thumbnail's fixed decode size, say) while gaining the
 * retry behaviour.
 */
@Composable
fun ResilientImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    requestBuilder: ImageRequest.Builder.() -> Unit = {},
) {
    // All state is declared unconditionally at the top: whether the
    // plain surface or the photo is drawn is a leaf decision below,
    // so a URL turning "failed" mid-session can never reshuffle
    // this composable's slot table.
    var attempt by remember(url) { mutableIntStateOf(0) }
    var painterState by remember(url) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    LaunchedEffect(url, painterState, attempt) {
        val target = url ?: return@LaunchedEffect
        when (painterState) {
            is AsyncImagePainter.State.Success -> FailedImageUrls.markOk(target)
            is AsyncImagePainter.State.Error -> {
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(if (attempt == 0) FIRST_RETRY_DELAY_MS else SECOND_RETRY_DELAY_MS)
                    attempt++
                } else {
                    FailedImageUrls.markFailed(target)
                }
            }
            else -> Unit
        }
    }
    val plain = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
    if (url.isNullOrBlank() || FailedImageUrls.isFailed(url)) {
        Box(modifier.then(plain))
        return
    }
    val context = LocalContext.current
    val request = remember(url, attempt) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .apply {
                if (attempt > 0) {
                    // A retry that reads the cache is not a retry.
                    memoryCachePolicy(CachePolicy.DISABLED)
                    diskCachePolicy(CachePolicy.DISABLED)
                }
            }
            .apply(requestBuilder)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        onState = { painterState = it },
        modifier = modifier.then(plain),
    )
}

private const val MAX_ATTEMPTS = 3
private const val FIRST_RETRY_DELAY_MS = 800L
private const val SECOND_RETRY_DELAY_MS = 2_000L
