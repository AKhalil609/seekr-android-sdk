package tv.seekr.previews.core.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `/sprites` response: a signed VTT URL plus the timebase scale factor. */
@Serializable
internal data class SpriteLookup(
    @SerialName("vtt_url") val vttUrl: String,
    @SerialName("scale") val scale: Double = 1.0,
    // Defaults to 0 so an older backend that omits the field can't break parsing; callers
    // must treat 0 as "unknown" rather than a real duration.
    @SerialName("source_duration_ms") val sourceDurationMs: Long = 0,
)

/** `/v1/keys/validate` response. */
@Serializable
internal data class KeyValidation(
    @SerialName("valid") val valid: Boolean = false,
)
