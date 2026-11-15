# Full-title capture (0.6.0)

Opt-in mode that contributes every cue the server still needs while the player is open (playing or paused), instead of only the cues the
viewer plays through. The SDK stays free of media3: the app decodes the frames and hands them over through a `CueFrameSource`.

## API

```kotlin
enum class ContributionMode { WhileWatching, FullTitle }

interface CueFrameSource {
    suspend fun frameAt(cueIndex: Int, targetMs: Long): CueFrameResult   // targetMs = cueIndex * 10_000
    fun close()
}
sealed interface CueFrameResult {
    data class Frame(val frame: CapturedVideoFrame, val ptsMs: Long) : CueFrameResult  // any size; TileEncoder letterboxes to 320x180
    data object Skip : CueFrameResult                                                  // HDR without tone-mapping, undecodable cue
    data class Fatal(val reason: String) : CueFrameResult                              // decoder unavailable: fall back
}

suspend fun SeekrContributor.startSession(
    content, durationMs, fp, frameSource, playback, lookup = LookupOutcome.Unknown,
    mode: ContributionMode = ContributionMode.WhileWatching,   // new
    cueFrames: CueFrameSource? = null,                         // new; required for FullTitle
)

data class ContributionState.Capturing(
    val capturedCues: Int, val targetCues: Int,
    val mode: ContributionMode = WhileWatching,                // new: the mode in effect (FullTitle only while the background worker works)
    val fellBack: Boolean = false,                             // new: FullTitle was requested but gave up for this session
)
```

`FullTitle` without a `cueFrames` behaves exactly like `WhileWatching`. The SDK owns `cueFrames` once passed: it calls `close()` exactly once,
when the session ends, when full capture falls back, when the session is replaced or refused, or immediately when the mode is `WhileWatching`.
`close()` never runs while a `frameAt` is in flight.

`frameAt` requirements: cancellable (use `suspendCancellableCoroutine` / `runInterruptible` around blocking decode), no main-thread work, decode
small (320x180 is enough), never throw for an ordinary bad cue (return `Skip`). Exceptions and calls over 8 s count as errors.

Compatibility: `Capturing(Int, Int)` keeps its 0.5 constructor; `copy`/`componentN` of `Capturing` and `startSession`'s JVM signature changed
(new trailing parameters), so recompile against 0.6.0.

## What runs

* The while-watching PixelCopy capture is unchanged and keeps running in `FullTitle`; a cue is stored and counted once, whichever path gets it
  first (claimed under a lock; the loser's frame is dropped). Uploads, cache, network gating, `ErrorPolicy` and stats are unchanged and count both paths.
* One worker coroutine on a dedicated single thread (`THREAD_PRIORITY_BACKGROUND`, created lazily), at most one `frameAt` in flight.
* Work list: `needed ∪ overlap` (from `/needs`, refreshed after uploads) minus cues already cached. Order: from the cue just ahead of the
  playhead to the end, then wrapping from cue 0. A cue the source returned `Skip`/a rejected frame for is not asked again this session.

## Pacing (from `PlaybackSnapshot`, re-read before every call)

| Condition | Behaviour |
|---|---|
| paused | one call per 400 ms (measured from the end of the previous call) |
| playing | one call per 3 s |
| `msSinceLastStall < 5 s` (buffering/stalled) | hold (state `Waiting(Buffering)`) |
| `msSinceLastSeek < 5 s` | hold (`Waiting(Seeking)`) |
| `bufferedAheadMs < 30 s` (near the end of the file: less than what is left) | hold (`Waiting(Buffering)`) |
| `droppedFramesRecent` rose since the last look | hold one 500 ms cycle at a time while it keeps rising (`Waiting(Buffering)`) |
| `thermalThrottled` / device thermal / HDR / secure | hold (the session loop also stops the session for these, as before) |

Holds lift by themselves; the worker re-checks every 500 ms. While full capture works, `Waiting(Paused)` / `Waiting(NotNormalSpeed)` of the
viewer path are not shown (the worker does not care); `Capturing(mode = FullTitle)` is.

## Frame acceptance (drift)

`|ptsMs − cue·10 000| <= 3 000` is stored. Further away is not stored: the server silently drops tiles with drift in (3 s, 10 s] and a tile over
10 s makes the package `bad_format` (PACKAGE-SPEC §3/§7.3.5), so both are discarded and the cue is not retried. An unknown pts (< 0) is discarded too.

## Fallback

Full capture ends for the session (state `Capturing(mode = WhileWatching, fellBack = true)`, source closed, while-watching continues) on:

* `CueFrameResult.Fatal`;
* 3 consecutive errors or timeouts (exception, > 8 s, tile could not be stored; `Frame`/`Skip` reset the count);
* 2 pacing violations: while playing, `droppedFramesRecent` increased across a `frameAt` call (cumulative for the session).

## Threading and cost

The worker, the JPEG encode and the cache write of full-capture tiles run on the dedicated thread; the contributor's own thread keeps its role.
Nothing is created for `WhileWatching` sessions. Stopping a session cancels the worker without waiting for it.
