package tv.seekr.previews.core

/**
 * Where the playback-attestation secret comes from. The default, [BuiltIn], is the secret
 * this release was built with; most callers never name this type.
 */
sealed interface SeekrAttestation {
    /** The secret compiled into this release (none if it was built without one). */
    data object BuiltIn : SeekrAttestation

    /** Your own secret, for a self-hosted control plane. */
    class Secret(val bytes: ByteArray) : SeekrAttestation

    /** Send no token. Fine against a server in `log`/`off` mode, refused by one in `enforce` mode. */
    data object None : SeekrAttestation
}
