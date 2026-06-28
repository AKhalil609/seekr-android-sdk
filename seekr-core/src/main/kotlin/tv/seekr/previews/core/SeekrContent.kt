package tv.seekr.previews.core

/**
 * Identifies the title (or specific episode) to fetch previews for.
 *
 * Construct exactly one well-formed variant. The Seekr API resolves identifiers in
 * priority order `show_tmdb_id → show_imdb_id → imdb_id → tmdb_id`, so an [Episode]
 * always takes precedence over any movie ids — modelling the two as distinct types
 * keeps you from accidentally sending a mixed, ambiguous request.
 */
sealed interface SeekrContent {

    /** A film. Provide at least one of [tmdbId] / [imdbId]. */
    data class Movie(
        val tmdbId: Int? = null,
        val imdbId: String? = null,
    ) : SeekrContent {
        init {
            require(tmdbId != null || imdbId != null) {
                "SeekrContent.Movie requires a tmdbId or imdbId"
            }
        }
    }

    /**
     * A single episode of a series. [season] and [episode] are mandatory — the API
     * returns 400 for a `show_*` id without them. Provide at least one show id.
     */
    data class Episode(
        val showTmdbId: Int? = null,
        val showImdbId: String? = null,
        val season: Int,
        val episode: Int,
    ) : SeekrContent {
        init {
            require(showTmdbId != null || showImdbId != null) {
                "SeekrContent.Episode requires a showTmdbId or showImdbId"
            }
        }
    }
}
