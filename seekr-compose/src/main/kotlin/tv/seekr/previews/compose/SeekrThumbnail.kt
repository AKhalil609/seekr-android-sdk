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
 * @param offsetMs optional signed sync correction in milliseconds, forwarded to
 * [tv.seekr.previews.android.SeekrTrack.offsetMs] every recomposition. Wire this to a "sync
 * +/-" control next to your scrubber when the preview release doesn't match the playback
 * release; see [tv.seekr.previews.core.PreviewTrack.offsetMs] for the sign convention.
 * Defaults to `null`, which leaves the track's existing `offsetMs` untouched — pass a value
 * only if this composable owns the offset, otherwise it would overwrite an offset you set
 * on the track yourself (e.g. seeded from `sourceDurationMs`). Changing it alone (without
 * [positionMs] changing) re-fetches the thumbnail so the nudge is visible immediately, even
 * while paused.
 */
@Composable
fun SeekrThumbnail(
    track: SeekrTrack?,
    positionMs: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    offsetMs: Long? = null,
) {
    var bitmap by remember(track) { mutableStateOf<Bitmap?>(null) }
    // Conflate rapid position/offset changes so only the latest pair triggers a crop.
    val posFlow = remember { MutableStateFlow(positionMs to offsetMs) }
    LaunchedEffect(positionMs, offsetMs) { posFlow.value = positionMs to offsetMs }
    LaunchedEffect(track) {
        posFlow.collectLatest { (pos, offset) ->
            // Null means "this composable doesn't own the offset" — don't clobber a value the
            // caller set on the track itself.
            if (offset != null) track?.offsetMs = offset
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
