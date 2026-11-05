package tv.seekr.previews.core

/**
 * The outcome of a track lookup, for callers that must tell "this title has no previews"
 * from "you may not have previews" — a settings screen, say, or deciding whether to ask
 * again later. [SeekrPreviews.loadTrack] collapses all of this to `track or null`.
 */
sealed interface TrackResult {
    class Found(val track: PreviewTrack) : TrackResult

    /** The title (or this release of it) has no sprites. Asking again will not help. */
    data object NoPreviews : TrackResult

    /** The server refused the caller. See [RejectReason]; most are not worth retrying soon. */
    data class Rejected(val reason: RejectReason) : TrackResult

    /** Over quota or rate limit. [retryAfterSec] is the server's `Retry-After`, when it sent one. */
    data class RateLimited(val retryAfterSec: Int?) : TrackResult

    /** Offline, a server error, or an answer that could not be parsed. Worth another try later. */
    data object Unavailable : TrackResult
}

/** Why the server refused a lookup. */
enum class RejectReason {
    /** [SeekrAuth.Bearer] had no token. */
    SignedOut,

    /** The key or device session is unknown or revoked. */
    Unauthorized,

    /** The account is suspended. */
    AccountSuspended,

    /** The operator switched sprites off (for everyone, or for non-admins). */
    Disabled,

    /** The playback-attestation token was missing or invalid (server in `enforce` mode). */
    Attestation,

    /** Any other 4xx the SDK does not know. */
    Other,
}
