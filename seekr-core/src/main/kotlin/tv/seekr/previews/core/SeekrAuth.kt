package tv.seekr.previews.core

/**
 * How a client proves who it is to the Seekr API.
 *
 * Most integrations use [ApiKey]. An app that already signs the user in to a Seekr account
 * (the Seekr TV app) uses [Bearer]: the account is the credential, so no key is ever typed,
 * stored or shipped.
 */
sealed interface SeekrAuth {
    /** An `sk_live_…` key, sent as `X-API-Key`. */
    data class ApiKey(val key: String) : SeekrAuth

    /**
     * A signed-in Seekr account's bearer token, sent as `Authorization: Bearer …`.
     *
     * [token] is asked for before every API call (never for the CDN), so it can hand out a
     * freshly stored value. Return `null` when signed out: the call then resolves to
     * [RejectReason.SignedOut] without touching the network.
     */
    class Bearer(val token: suspend () -> String?) : SeekrAuth
}
