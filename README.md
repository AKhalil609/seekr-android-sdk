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
val bitmap = track?.thumbnailAt(scrubPositionMs)   // draw above your scrubber
```

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
    implementation("tv.seekr:seekr-android:0.1.3")
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

// Eagerly download all sprite sheets so every thumbnailAt() call is instant.
track?.prefetchSheets()
```

Pass whichever ids you have. `Movie` and `Episode` are distinct types so you can't send
an ambiguous request — the API's identifier precedence is encoded in the model.

### 3. Show the thumbnail while scrubbing

**Jetpack Compose** — add `tv.seekr:seekr-compose` and drop in one composable:

```kotlin
SeekrThumbnail(track = track, positionMs = scrubPositionMs)
```

It fetches and crops off the UI thread and renders nothing until a tile is ready. If you'd
rather not pull in the compose artifact, the same thing by hand:

```kotlin
@Composable
fun SeekThumbnail(track: SeekrTrack?, positionMs: Long) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(track, positionMs) {
        bmp = track?.thumbnailAt(positionMs)
    }
    bmp?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null) }
}
```

**Classic Views / ExoPlayer time-bar listener:**

```kotlin
timeBar.addListener(object : TimeBar.OnScrubListener {
    override fun onScrubMove(timeBar: TimeBar, position: Long) {
        scope.launch { thumbnailView.setImageBitmap(track?.thumbnailAt(position)) }
    }
    // onScrubStart / onScrubStop omitted
})
```

`thumbnailAt` is a `suspend` function and is safe to call on the main thread — it hops to
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
| sprite download + `Bitmap.createBitmap` crop | gone — `thumbnailAt()` |
| sheet caching | gone — built in |
| cue lookup by position | gone — `tileAt()` / `thumbnailAt()` |
| **your settings screen storing the key** | **keep** |
| **where you get tmdb/imdb id + s/e** | **keep** |
| **drawing the bitmap in your scrubber** | **keep** |

## Error handling

The SDK never throws on a normal "no previews available" outcome — lookups, network
failures and missing previews all surface as `null`. So `track?.thumbnailAt(pos)` is safe
to call optimistically for every title; if Seekr has nothing, nothing renders.

## Preview accuracy

Previews are an approximation of the video, not a frame-accurate copy of it. Understanding
where the approximation comes from will save you a debugging session the first time a
thumbnail doesn't line up with the frame you land on after a seek.

### 1. Cue quantisation

Thumbnails exist at a fixed interval (commonly every 10s), not for every millisecond of the
title. `thumbnailAt(positionMs)` (and `tileAt`/`cueAt`) return the cue whose start is at or
before `positionMs` — so the image you get back can be up to one interval *behind* the
position you actually asked for.

```kotlin
// Cues exist at 0, 10_000, 20_000, ...
track.thumbnailAt(18_500) // returns the cue that starts at 10_000, not 18_500
```

If you need to know exactly which timestamp the thumbnail you're showing represents, use
`thumbnailFor(positionMs)` (or `PreviewTrack.cueAt` in `seekr-core`) instead of `thumbnailAt` —
it returns a `SeekrThumbnail` with `cueStartMs`/`cueEndMs` alongside the bitmap.

```kotlin
val thumb = track.thumbnailFor(18_500) // -> cueStartMs = 10_000, cueEndMs = 20_000
```

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
different intro/logo/credits length — every preview will be offset from the correct
playback position by the same **constant** amount across the whole title, because the
backend never rescales the preview timeline to match the caller's duration (except for
genuine PAL/NTSC framerate conversions, reported via `PreviewTrack.scale` / `SeekrTrack.scale`).

You can detect this before the user ever notices a drifted thumbnail by comparing
`sourceDurationMs` (the duration the sprites were generated from) against the `durationMs`
you passed to `loadTrack`:

```kotlin
val driftMs = track.sourceDurationMs - durationMsPassedToLoadTrack
if (driftMs != 0L) {
    track.offsetMs = driftMs // correct every lookup up front
}
```

`offsetMs` is exactly the fix — it's a signed correction added to the requested position
before every cue lookup, conceptually identical to a subtitle sync offset. Because the lookup
is `position + offsetMs`, a **negative** value is what you want in the common case, where the
played release has *extra* content at the head (a logo or black frames the sprite source
lacked); a **positive** value handles the reverse, where the played release is *missing* head
content. `sourceDurationMs - durationMsPassedToLoadTrack` gives the right sign automatically,
so prefer it over reasoning it out by hand. It's a mutable `var`, safe to nudge live
from a "sync +/-" UI control while lookups keep happening on every scrub event — you don't
need to re-create the track.

### Diagnosing a mismatch

Use the shape of the error to tell the three causes above apart:

| Symptom | Cause |
|---------|-------|
| Sawtooth error that resets every interval (drifts, then snaps back) | Cue quantisation (§1) |
| Small random error, a few seconds, no pattern | Keyframe approximation (§2) |
| Constant error across the whole title | Source version mismatch — set `offsetMs` (§4) |

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
