# Seekr Previews — Android SDK

[![CI](https://github.com/AKhalil609/seekr-android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/AKhalil609/seekr-android-sdk/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/tv.seekr/seekr-android)](https://central.sonatype.com/artifact/tv.seekr/seekr-android)

Drop-in seek-preview thumbnails for Android TV and mobile players, powered by the
[Seekr](https://seekr.tv) API. Add one dependency, pass your key, and get a cropped
`Bitmap` for any scrub position — the SDK handles the `/sprites` lookup, WebVTT parsing,
sprite-sheet download, cropping and caching for you.

```kotlin
val seekr = Seekr.create(apiKey = "sk_live_…")
val track = seekr.loadTrack(SeekrContent.Movie(tmdbId = 603), durationMs = player.duration)
val thumb = track?.thumbnailFor(scrubPositionMs)   // draw thumb.bitmap above your scrubber
```

`thumbnailFor` also tells you *which moment* the image you got back actually shows
(`thumbnailFor(…)?.cueStartMs`). You will need that — thumbnails exist roughly once every
10 seconds, not per frame, so the bitmap is almost never the exact position you asked for.
Read **[Preview accuracy](#preview-accuracy)** before you ship; it is short, and it is the
difference between a scrubber that feels precise and one that quietly lies to your users.

## Modules

| Artifact | What it gives you | Depends on |
|----------|-------------------|------------|
| `tv.seekr:seekr-core` | Framework-agnostic Kotlin/JVM client. Resolves a title to a `PreviewTrack` of tile **coordinates** (`SeekrTile`). No Android, no image loading. | OkHttp, coroutines, kotlinx-serialization |
| `tv.seekr:seekr-android` | Everything in core **plus** sprite-sheet download, cropping and caching — returns ready-to-draw `Bitmap`s. | `seekr-core` |
| `tv.seekr:seekr-compose` | A single `@Composable SeekrThumbnail(track, positionMs)` drop-in. | `seekr-android` |

Most apps want **seekr-android** (or **seekr-compose** if your player UI is Compose).
Reach for **seekr-core** directly only if you render tiles yourself (e.g. a custom GPU
path) or run on non-Android JVM.

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("tv.seekr:seekr-android:0.2.0")
}
```

Requires `minSdk` 21+. The library declares the `INTERNET` permission for you.

## Usage

### 1. Create one client and reuse it

```kotlin
// Share your app's OkHttpClient so previews reuse its connection pool & timeouts.
val seekr = Seekr.create(apiKey = userKey, httpClient = appOkHttp)
```

### 2. Load a track when playback starts

```kotlin
val content = if (isSeries) {
    SeekrContent.Episode(showTmdbId = showId, season = s, episode = e)
} else {
    SeekrContent.Movie(tmdbId = movieId, imdbId = imdbId)
}

val track = seekr.loadTrack(content, durationMs = player.duration)
// track == null  → no previews for this title; just don't show a thumbnail.

// Eagerly download all sprite sheets so every lookup is instant.
track?.prefetchSheets()
```

Pass whichever ids you have. `Movie` and `Episode` are distinct types so you can't send
an ambiguous request — the API's identifier precedence is encoded in the model.

### 3. Show the thumbnail while scrubbing

There are two lookups, and which one you pick matters more than it looks:

| Call | Returns | Use it when |
|------|---------|-------------|
| `thumbnailFor(positionMs)` | `SeekrThumbnail(bitmap, cueStartMs, cueEndMs)` | **Default choice.** You get the moment the image actually shows, so you can label it, snap your scrubber to it, or seek to it. |
| `thumbnailAt(positionMs)` | `Bitmap?` | You genuinely only need pixels and will never reason about *when* the frame is from. |

```kotlin
@Composable
fun SeekThumbnail(track: SeekrTrack?, positionMs: Long) {
    var thumb by remember(track) { mutableStateOf<SeekrThumbnail?>(null) }
    LaunchedEffect(track, positionMs) {
        track?.thumbnailFor(positionMs)?.let { thumb = it }
    }
    thumb?.let {
        Column {
            Image(bitmap = it.bitmap.asImageBitmap(), contentDescription = null)
            // Tell the user which moment they're looking at — see "Preview accuracy".
            Text(formatTime(it.cueStartMs))
        }
    }
}
```

**Jetpack Compose, quick start** — add `tv.seekr:seekr-compose` and drop in one composable:

```kotlin
SeekrThumbnail(track = track, positionMs = scrubPositionMs)
```

It fetches and crops off the UI thread and renders nothing until a tile is ready. It is
deliberately minimal: it draws a bare bitmap and gives you no access to the resolved cue,
so it cannot label the frame, snap to the cue grid, or drive a seek that matches the
preview. Use it to get running in an afternoon; once you care about any of that, own the
loop yourself with `thumbnailFor` as above. Note also that its optional `offsetMs`
parameter **writes through to `track.offsetMs` on every recomposition** — pass it only if
this composable is the single owner of the sync offset, otherwise leave it `null` and set
the offset on the track directly.

**Classic Views / ExoPlayer time-bar listener:**

```kotlin
timeBar.addListener(object : TimeBar.OnScrubListener {
    override fun onScrubMove(timeBar: TimeBar, position: Long) {
        scope.launch {
            val thumb = track?.thumbnailFor(position) ?: return@launch
            thumbnailView.setImageBitmap(thumb.bitmap)
            thumbnailLabel.text = formatTime(thumb.cueStartMs)
        }
    }
    // onScrubStart / onScrubStop omitted
})
```

Both lookups are `suspend` functions and are safe to call on the main thread — they hop to
a background dispatcher for the network fetch only; the in-memory crop runs inline and is
sub-millisecond for typical thumbnail sizes.

### Optional: validate a key in settings

```kotlin
if (!seekr.validateKey()) showInvalidKeyMessage()
```

Not required before `loadTrack` — use it only to give users a clear message when they
paste a bad key.

## How it maps onto a hand-rolled integration

If you've integrated Seekr by hand (as the reference NuvioTV player did), the SDK replaces
the reusable plumbing and leaves only the parts that are genuinely yours:

| Hand-rolled piece | With the SDK |
|-------------------|--------------|
| Retrofit `SeekriApi` (`/sprites`, VTT) | gone — internal to the SDK |
| `parseVtt` / `parseVttTime` / `scale` handling | gone — internal |
| sprite download + `Bitmap.createBitmap` crop | gone — `thumbnailFor()` |
| sheet caching | gone — built in |
| cue lookup by position | gone — `cueAt()` / `thumbnailFor()` |
| **your settings screen storing the key** | **keep** |
| **where you get tmdb/imdb id + s/e** | **keep** |
| **drawing the bitmap in your scrubber** | **keep** |

## Error handling

The SDK never throws on a normal "no previews available" outcome — lookups, network
failures and missing previews all surface as `null`. So `track?.thumbnailFor(pos)` is safe
to call optimistically for every title; if Seekr has nothing, nothing renders.

## Preview accuracy

Previews are an approximation of the video, not a frame-accurate copy of it. Understanding
where the approximation comes from will save you a debugging session the first time a
thumbnail doesn't line up with the frame you land on after a seek.

### 1. Cue quantisation

Thumbnails exist at a fixed interval (commonly every 10s), not for every millisecond of the
title. `thumbnailFor(positionMs)` / `thumbnailAt(positionMs)` (and `tileAt`/`cueAt`) return
the cue whose start is at or before `positionMs` — so the image you get back can be up to
one interval *behind* the position you actually asked for.

```kotlin
// Cues exist at 0, 10_000, 20_000, ...
track.thumbnailAt(18_500) // returns the cue that starts at 10_000, not 18_500
```

`thumbnailFor(positionMs)` (or `PreviewTrack.cueAt` in `seekr-core`) tells you exactly which
window the bitmap came from — it returns a `SeekrThumbnail` carrying `cueStartMs`/`cueEndMs`
alongside the pixels. Prefer it over `thumbnailAt` in anything but a throwaway integration.

```kotlin
val thumb = track.thumbnailFor(18_500) // -> cueStartMs = 10_000, cueEndMs = 20_000
```

#### The lookup is a *floor*, not a *nearest* — and that matters

This trips up nearly every integration, so it's worth being explicit. Both lookups return
the cue that **contains** `positionMs`, and each cue's frame was captured at its **start**.
So the containing cue is only the closest available frame for the first half of its window:

```
position 18_500 → cue 10_000..20_000 → frame at 10_000, which is 8.5s stale
                                       (the frame at 20_000 is only 1.5s away)
```

If you show the containing cue's frame verbatim, a user parked at 18:29 sees the 18:20
frame even though an 18:30 frame exists one second away. Past the midpoint of a cue you
almost always want its **successor**:

```kotlin
val cue = track.thumbnailFor(positionMs) ?: return
val preferSuccessor = (positionMs - cue.cueStartMs) > (cue.cueEndMs - positionMs)
val shown = if (preferSuccessor) track.thumbnailFor(cue.cueEndMs) ?: cue else cue
```

Note the comparison is written without dividing by two, so it stays exact on a non-uniform
cue grid (cue lengths are not guaranteed to be equal, and will be less uniform once cue
starts carry real keyframe times — see §2).

Whichever you pick, **show the user `cueStartMs`, not their raw scrub position.** A label
reading "18:30" under an 18:30 frame is honest and feels precise; the same frame under a
"18:29" label is a small lie the user's eye will eventually catch. The rule of thumb for
the whole feature: *don't approximate the frame you're missing — narrow what you claim.*

### 2. Keyframe approximation

Decoding every frame of a title to find the exact one at each cue time would make sprite
generation impractically slow, so the generator instead extracts the nearest container
keyframe within +/-3s of the cue's grid time. That means the frame actually shown in the
sprite can be up to 3 seconds away from `cueStartMs` — the cue's grid time, not necessarily
the true frame timestamp.

Newly generated titles record the *true* keyframe time as the cue start, so `cueStartMs` is
exact for them. Older titles still carry the grid time. In other words, `cueStartMs`
precision varies by title today, and improves over time as media is regenerated — treat it
as accurate to within a few seconds, not to the millisecond, unless you've confirmed
otherwise for a given title.

### 3. Seeking from a preview

**This is the one most integrations get wrong.** Sprite frames are keyframe-aligned (see
above), but ExoPlayer/media3's default seek is an *exact* seek that deliberately lands
between keyframes and decodes forward to hit the requested position precisely. Seeking to
the position the user actually dragged to after showing them a keyframe-aligned preview
means the frame they land on won't match the thumbnail they were just looking at.

If you want the played frame to visually match the preview, tell ExoPlayer to snap to the
nearest sync sample instead of seeking exactly:

```kotlin
import androidx.media3.exoplayer.SeekParameters

player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
player.seekTo(target)
```

The alternative is to seek to `cueStartMs` (from `thumbnailFor`/`cueAt`) with the default
`SeekParameters.DEFAULT`. Be honest with yourself about the trade-off either way:

- `SeekParameters.CLOSEST_SYNC` + seek to the user's actual position: the played frame
  matches a keyframe near where the user dragged, but not necessarily the exact preview
  they saw.
- Default seek to `cueStartMs`: the played frame matches the preview exactly, but the user
  can land up to one cue interval away from where they actually aimed.

There's no universally correct choice — pick per integration.

### 4. Source version mismatch and the sync offset

Sprites are generated once from one specific release of a title. If a user is playing a
*different* release of the same title — a different regional cut, an extended edition, a
different intro/logo/credits length — every preview may be offset from the correct
playback position by the same **constant** amount across the whole title, because the
backend never rescales the preview timeline to match the caller's duration (except for
genuine PAL/NTSC framerate conversions, reported via `PreviewTrack.scale` / `SeekrTrack.scale`).

`offsetMs` is the fix — a signed correction added to the requested position before every
cue lookup, conceptually identical to a subtitle sync offset. Because the lookup is
`position + offsetMs`:

- **Negative** — the played release has *extra* content at the **head** (a distributor logo
  or black frames the sprite source lacked). A scene at 00:10:00 in the sprite source sits
  at 00:10:30 in playback, so `offsetMs = -30_000`.
- **Positive** — the played release is *missing* head content the sprite source had.

It's a mutable `var`, safe to nudge live from a "sync +/-" UI control while lookups keep
happening on every scrub event — you don't need to re-create the track.

#### Do not auto-apply `sourceDurationMs - durationMs`

`sourceDurationMs` tells you the duration the sprites were generated from, and comparing it
to the `durationMs` you passed to `loadTrack` looks like it hands you the offset for free:

```kotlin
// ❌ Do NOT do this.
track.offsetMs = track.sourceDurationMs - durationMsPassedToLoadTrack
```

**This is wrong more often than it is right, and it fails silently.** A duration delta tells
you the two releases differ in *length*; it tells you nothing about *where* the difference
sits. The formula is only correct in the one case where the entire difference is at the head:

| Where the releases differ | Duration delta | Correct `offsetMs` | What the formula does |
|---------------------------|----------------|--------------------|------------------------|
| Extra 30s logo at the **head** | −30 000 | −30 000 | ✅ correct |
| Extra 30s of **credits** / post-roll at the tail | −30 000 | **0** | ❌ shifts every preview by 30s |
| Trimmed 30s from the **middle** | −30 000 | not constant | ❌ no single value is right |
| Different container padding, rounding, VFR reporting | a few hundred ms | ~0 | ❌ adds jitter for nothing |

Tail differences are at least as common as head differences (regional credit rolls, retail
vs. streaming masters), so seeding the offset from the duration delta will routinely make
previews **worse** for users whose thumbnails were correct to begin with — and because the
error is uniform, it looks exactly like a *correct* preview of the wrong moment. Nobody
files a bug for that; they just stop trusting the scrubber.

**What to do instead.** Treat the delta as a *signal that drift is possible*, never as a
value:

```kotlin
val driftMs = track.sourceDurationMs - durationMsPassedToLoadTrack

// Leave the offset alone. Previews are correct for the great majority of titles.
track.offsetMs = 0

// Use the drift only to decide whether to surface the manual sync control more
// prominently, and to seed its starting suggestion if the user opens it.
val syncLikelyNeeded = abs(driftMs) > 2_000
```

Give the user a "Preview sync +/-" control (the same affordance as subtitle delay), let them
nudge it while watching the thumbnail update live, and persist their choice per title. A
human comparing a thumbnail against the frame they know they're on solves this in three
button presses and is right every time; a duration subtraction is a guess that is confidently
wrong on a large fraction of titles. If you do offer `driftMs` as a one-tap suggestion inside
that control, label it as a suggestion and keep it undoable.

`sourceDurationMs` defaults to `0` when the backend omits `source_duration_ms` (older
backends), so guard for that before computing any drift at all.

Better still, don't guess at all — see §5.

### 5. Automatic sync by matching frames

The offset in §4 is a property of *where* two releases differ, which no arithmetic on their
durations can recover. So don't infer it — **measure** it. The backend publishes a 64-bit
perceptual hash of every sprite thumbnail; hand the SDK a few frames decoded from the video
you are actually playing, and it finds them in that list and reads the offset straight out of
the match.

```kotlin
// Four frames spread across one cue interval, taken at a quiet moment in playback.
val burst = BitmapSample(
    frames = captures.map { BitmapFrame(it.bitmap, it.playbackMs) },
)

val match = track.matchOffset(listOf(burst1, burst2, burst3, burst4, burst5))
if (match != null) {
    track.offsetMs = match.offsetMs      // apply silently
}                                        // null → leave the manual control in charge
```

If your player can't seek — a P2P stream, where seeking ahead lands on pieces nobody has
downloaded — collect bursts as playback reaches them instead:

```kotlin
val matcher = track.offsetMatcher()

// ... every 30-60s of playback, once a burst has been captured:
val match = matcher.offer(burst)
when {
    match != null -> track.offsetMs = match.offsetMs
    matcher.isTerminal -> stopCapturingFrames()   // see MatchStatus for why
    else -> Unit                                   // keep feeding
}
```

#### What to capture

A `BitmapSample` is a **burst**: several frames taken close together, spread evenly across
one cue interval (`track.cueIntervalMs`). The burst, not the frame, is the unit of evidence.

| Rule | Why |
|------|-----|
| **4 frames per burst**, `cueIntervalMs / 4` apart | Thumbnails exist only at cue positions, so a single frame grabbed at an arbitrary moment is usually mid-shot and resembles nothing in the list. Measured against a real second release: 1 frame resolves **24.4%** of the time, 2 resolves **53.0%**, 4 resolves **86.0%** — against an 85.0% ceiling. One frame per burst isn't four times cheaper, it's mostly broken. |
| **5 bursts**, spread across playback | Three must agree; five gives the consensus rule something to reject. |
| **Skip the first ~60 seconds** | Logos, black frames and title cards are low-entropy and hash-degenerate — the worst possible material to fingerprint. |
| **Small bitmaps are fine** | Everything reduces to 9×8 anyway. Handing over 256×144 instead of a full-resolution frame costs a mean of **0.09 bits** of hash error and never more than 1, against the ~6 bits a real re-encode costs. Grab the cheapest thing your player will give you; ~256×144 is a comfortable default. |

#### `null` means keep manual control

Matching declines rather than guessing when fewer than three bursts agree. That is normal —
expect it on roughly 14% of attempts even with good frames — and it is the safe outcome,
because the manual control from §4 is still there. Leave `offsetMs` alone and say nothing.

The asymmetry is the whole design. A decline costs the user nothing they didn't already have.
A *wrong* automatic sync moves every thumbnail uniformly, which looks like a correct preview
of the wrong moment rather than a bug, so nobody reports it — they just stop trusting the
scrubber. Consensus is therefore deliberately strict: three of the last five bursts must
agree within ±(interval/2 + 3s), and estimates that fall outside are discarded rather than
averaged in.

For the same reason **nothing is ever applied for you**, and there is no confidence threshold
to tune: receiving a non-`null` result *is* the confidence gate. `OffsetMatch.confidence` is
there for your logs, not for a second `if`.

#### When it can't work at all

`OffsetMatcher.status` explains every `null`, and `isTerminal` says whether to give up:

| Status | Terminal | Meaning |
|--------|----------|---------|
| `NEED_MORE_BURSTS` | no | Fewer than 3 bursts so far. Keep feeding. |
| `NO_CONSENSUS` | no | Bursts disagree. Keep feeding — the window rolls. |
| `NO_HASHES_PUBLISHED` | yes | This version has no hashes, or its sidecar isn't uploaded. |
| `UNSUPPORTED_ALGO` | yes | Hashes use a reduction this SDK version doesn't implement. |
| `HASH_COUNT_MISMATCH` | yes | Hash list and cue list disagree on length, so index N is no longer cue N. Declining beats mis-indexing. |
| `HASH_FETCH_FAILED` | after 3 tries | Download failed. One blip shouldn't disable the feature; a dead URL shouldn't be re-fetched on every burst. |

Stop capturing frames when `isTerminal` is `true`. Frame capture costs a readback per frame,
and repeating it for a title that structurally cannot match is pure battery loss.

#### It never blocks a thumbnail

The hash list is downloaded on the **first** `matchOffset` call — never during `loadTrack`,
and never on the path that draws a thumbnail. Matching runs on background dispatchers and
costs microseconds of CPU per frame. Draw previews immediately with whatever `offsetMs` you
already have and swap in a matched value only if one arrives; a failed or slow match should be
indistinguishable from today's behaviour, at today's speed.

#### Precision

`OffsetMatch.offsetMs` is derived from the matched cue's start time, so it is quantised to
the cue grid — expect the true offset rounded to the nearest cue, not to the millisecond.
Versions generated before 2026-09-07 additionally carry up to ±3s of cue jitter (§2), which
the matched offset inherits. Both are well inside the tolerance the consensus window already
allows for, and both are far better than a duration guess that can be tens of seconds out.

### 6. Signed-in apps: no key, and scrubbing cue to cue

An app that already signs the user in to a Seekr account (the Seekr TV app) passes the
account's bearer token instead of a key:

```kotlin
val seekr = Seekr.create(auth = SeekrAuth.Bearer { tokenStore.current() })

when (val r = seekr.lookupTrack(content, durationMs, positionMs = player.currentPosition)) {
    is SeekrTrackResult.Found -> track = r.track
    SeekrTrackResult.NoPreviews -> Unit                    // this title has no sprites
    is SeekrTrackResult.Rejected -> showStatus(r.reason)   // SignedOut, Unauthorized, AccountSuspended, …
    is SeekrTrackResult.RateLimited -> retryLater(r.retryAfterSec)
    SeekrTrackResult.Unavailable -> retryLater(null)       // offline / server error
}
```

`loadTrack` is the same call collapsed to "track or `null`".

To scrub so the frame you show is the frame a seek lands on, step by cue and seek to the cue's
start — `stepCue` works on the preview timeline and `toPlaybackMs` undoes `offsetMs`:

```kotlin
val cue = track.stepCue(positionMs = scrubMs, steps = +1) ?: return   // -1 for backwards
val seekTo = track.toPlaybackMs(cue.startMs)
```

Stepping back from the middle of a cue first returns that cue's own start; from a cue's exact
start, `-1` is the previous cue. Both clamp at the ends of the track.

The playback-attestation token (`X-Playback-Attestation`) is minted for you from the secret the
release was built with; `positionMs` is bound into it. A device clock that is far off is
corrected from the server's `Date` header and the call retried once.

### Diagnosing a mismatch

Use the shape of the error to tell the causes above apart:

| Symptom | Cause |
|---------|-------|
| Preview is always *behind* the position, worst just before it jumps | Floor lookup — prefer the successor cue past the midpoint (§1) |
| Sawtooth error that resets every interval (drifts, then snaps back) | Cue quantisation (§1) |
| Small random error, a few seconds, no pattern | Keyframe approximation (§2) |
| Preview is right while scrubbing, wrong after the seek lands | Seek parameters — see (§3) |
| Constant error across the whole title, in the same direction everywhere | Source version mismatch — measure it with `matchOffset` (§5), or offer a manual `offsetMs` control (§4) |
| Constant error that appeared after you added a "smart" auto-offset | You're auto-applying `sourceDurationMs - durationMs` (§4) |
| Auto-sync always declines (`matchOffset` returns `null` every time) | Check `OffsetMatcher.status`: usually `NO_HASHES_PUBLISHED`, or bursts of one frame instead of four (§5) |
| Auto-sync returns a confident but wrong offset | Frames timestamped on the wrong clock, or `playbackMs` not matching the position passed to `thumbnailAt` (§5) |

## Designing the scrub UI

The API gives you a frame roughly every 10 seconds. The temptation is to hide that and let
the UI imply per-frame precision; resist it. The integrations that feel best are the ones
that are *honest about their resolution* rather than the ones that interpolate hardest.

- **Quantise coarse scrubbing to the cue grid.** If a press moves the scrubber by one cue,
  every press changes the picture. Free-scrubbing at 1s granularity over a 10s grid means
  nine presses out of ten show an identical frame, which reads as a frozen or broken UI.
- **Label the frame, not the intent.** Show `cueStartMs`. See §1.
- **Consider a filmstrip.** Rendering the previous / current / next cue with their
  timestamps turns the grid from a limitation into context, and makes the granularity
  self-evident without an explanation.
- **Draw tick marks on the scrubber** at cue boundaries when they're far enough apart to be
  legible (below roughly 5dp of pitch they're visual noise — skip them).
- **Don't cross-dissolve between frames.** A 10s jump is a cut, not a motion; a fade makes
  it read as lag.
- **Expose the sync control** rather than guessing at an offset (§4).

## Publishing (maintainers)

Publishing is wired with the [`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin)
plugin and a GitHub Actions `Publish` workflow that runs on every GitHub Release. Coordinates,
POM and signing config live in `gradle.properties`. See **[PUBLISHING.md](PUBLISHING.md)** for
the one-time Central Portal namespace + GPG + secrets setup and the release flow.

## License

[Apache-2.0](LICENSE).

## Building

The Gradle wrapper is committed, so no local Gradle install is needed:

```bash
./gradlew :seekr-android:assembleRelease
./gradlew :seekr-compose:assembleRelease
```
