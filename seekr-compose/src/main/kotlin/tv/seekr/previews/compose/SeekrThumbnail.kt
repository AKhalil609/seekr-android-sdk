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
 * ### Scope: this is the quick-start path
 * It draws a bare bitmap and gives you no access to the resolved cue, so it cannot label the
 * frame with the moment it actually shows, snap your scrubber to the cue grid, or drive a
 * seek that agrees with the preview. Thumbnails exist roughly once every 10 seconds, so any
 * player that takes seek accuracy seriously will want all three. When you get there, drop
 * this composable and own the loop yourself with
 * [tv.seekr.previews.android.SeekrTrack.thumbnailFor], which returns `cueStartMs`/`cueEndMs`
 * alongside the pixels — see the "Preview accuracy" section of the README.
 *
 * @param track the track from [tv.seekr.previews.android.Seekr.loadTrack]; null renders nothing.
 * @param positionMs the scrub position to preview.
 * @param offsetMs optional signed sync correction in milliseconds, **written through to**
 * [tv.seekr.previews.android.SeekrTrack.offsetMs] on every recomposition. Wire this to a
 * user-facing "sync +/-" control when the preview release doesn't match the playback release;
 * see [tv.seekr.previews.core.PreviewTrack.offsetMs] for the sign convention, and
 * [tv.seekr.previews.core.PreviewTrack.sourceDurationMs] for why you should not compute this
 * value from a duration difference. Defaults to `null`, which leaves the track's existing
 * `offsetMs` untouched — pass a value only if this composable is the single owner of the
 * offset, otherwise it will clobber one you set on the track yourself. Changing it alone
 * (without [positionMs] changing) re-fetches the thumbnail so the nudge is visible
 * immediately, even while paused.
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
