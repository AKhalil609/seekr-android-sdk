# Seekr Previews — Android SDK

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
    implementation("tv.seekr:seekr-android:0.1.0")
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
background dispatchers for the network fetch and the pixel crop. Throttle calls to your
frame rate if the user drags quickly.

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

## Publishing (maintainers)

Coordinates come from `gradle.properties` (`GROUP=tv.seekr`, `VERSION_NAME`). The modules
declare `maven-publish` publications (`seekr-core`, `seekr-android`) with sources jars and
POM metadata.

Maven Central additionally requires GPG signing and a verified `tv.seekr` namespace on
Sonatype. The least-friction path is the
[`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin)
plugin — add it, set the signing/Sonatype secrets, and run `publishToMavenCentral`. Until
then, `./gradlew publishToMavenLocal` works for local testing.

## License

[Apache-2.0](LICENSE).

## Building

The Gradle wrapper is committed, so no local Gradle install is needed:

```bash
./gradlew :seekr-android:assembleRelease
./gradlew :seekr-compose:assembleRelease
```
