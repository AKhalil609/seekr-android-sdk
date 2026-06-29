package tv.seekr.previews.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import tv.seekr.previews.android.SeekrTrack

/**
 * Drop-in seek-preview thumbnail for Jetpack Compose.
 *
 * Place it above your scrubber and feed it the current scrub position; it fetches and
 * crops the right tile off the UI thread and renders nothing until one is available, so
 * a missing preview simply shows empty space.
 *
 * ```
 * val track by produceState<SeekrTrack?>(null, mediaId) {
 *     value = seekr.loadTrack(content, durationMs)
 * }
 * SeekrThumbnail(track = track, positionMs = scrubPositionMs)
 * ```
 *
 * @param track the track from [tv.seekr.previews.android.Seekr.loadTrack]; null renders nothing.
 * @param positionMs the scrub position to preview.
 */
@Composable
fun SeekrThumbnail(
    track: SeekrTrack?,
    positionMs: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var bitmap by remember(track) { mutableStateOf<Bitmap?>(null) }
    // Conflate rapid position changes so only the latest position triggers a crop.
    val posFlow = remember { MutableStateFlow(positionMs) }
    LaunchedEffect(positionMs) { posFlow.value = positionMs }
    LaunchedEffect(track) {
        posFlow.collectLatest { pos ->
            // Only overwrite bitmap on success; keeps last good frame visible during crop.
            track?.thumbnailAt(pos)?.let { bitmap = it }
        }
    }
    bitmap?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}
