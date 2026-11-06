package tv.seekr.previews.core.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/sprites` response: a signed VTT URL, the timebase scale factor, and the optional
 * perceptual-hash sidecar that [tv.seekr.previews.core.PreviewTrack.matchOffset] uses.
 *
 * Every field except `vtt_url` is defaulted. The backend omits the hash fields entirely for
 * a version that has no fingerprints (it never sends `null`), and older backends omit them
 * unconditionally — either way parsing must survive, because a lookup that fails to parse
 * takes previews down completely rather than just disabling offset matching.
 */
@Serializable
internal data class SpriteLookup(
    @SerialName("vtt_url") val vttUrl: String,
    @SerialName("scale") val scale: Double = 1.0,
    // Defaults to 0 so an older backend that omits the field can't break parsing; callers
    // must treat 0 as "unknown" rather than a real duration.
    @SerialName("source_duration_ms") val sourceDurationMs: Long = 0,
    // Signed URL of this version's `hashes.bin`. Defaults to "" — same contract as
    // sourceDurationMs: empty means "unknown", i.e. this version publishes no hashes and
    // offset matching is unavailable for it. It carries the same `exp`/`sig` as vttUrl
    // because the backend signs the whole version directory, so no extra auth is involved.
    @SerialName("hashes_url") val hashesUrl: String = "",
    // Identifier for the reduction the hashes were produced with. Only "dhash8" is
    // understood; anything else (including the "" default) must decline rather than compare
    // hashes produced by a different reduction against ours.
    @SerialName("hash_algo") val hashAlgo: String = "",
    // How many hashes `hashes.bin` holds, so a client can size its buffer and cross-check
    // the download. Defaults to 0, meaning "unknown" — not "empty".
    @SerialName("hash_count") val hashCount: Int = 0,
)

/** `/v1/keys/validate` response. */
@Serializable
internal data class KeyValidation(
    @SerialName("valid") val valid: Boolean = false,
)

/** The backend's error envelope: `{"error": "..."}`. Only the message is read, to tell refusals apart. */
@Serializable
internal data class ErrorBody(
    @SerialName("error") val error: String = "",
)
