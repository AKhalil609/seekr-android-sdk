package tv.seekr.previews.android.internal

import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.contrib.ContributionClient
import tv.seekr.previews.core.contrib.NeedsResult
import tv.seekr.previews.core.contrib.SubmitResult

/** The two calls the contributor makes. `ContributionClient` is a final class, so tests substitute this seam instead. */
internal interface ContributionApi {
    suspend fun needs(content: SeekrContent, durationMs: Long, fp: String): NeedsResult
    suspend fun submit(content: SeekrContent, durationMs: Long, fp: String, nvmt: ByteArray): SubmitResult
}

internal class ClientApi(private val client: ContributionClient, private val appVersion: String) : ContributionApi {
    override suspend fun needs(content: SeekrContent, durationMs: Long, fp: String): NeedsResult =
        client.needs(content, durationMs, fp)

    override suspend fun submit(content: SeekrContent, durationMs: Long, fp: String, nvmt: ByteArray): SubmitResult =
        client.submit(content, durationMs, fp, nvmt, appVersion)
}
