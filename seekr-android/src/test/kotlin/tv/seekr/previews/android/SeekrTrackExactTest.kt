package tv.seekr.previews.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tv.seekr.previews.core.SeekrAttestation
import tv.seekr.previews.core.SeekrAuth
import tv.seekr.previews.core.SeekrContent

/** `SeekrTrack.exact` delegates to the core flag read from `/sprites`. */
class SeekrTrackExactTest {
    private val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:10.000\nhttps://sprites.seekr.tv/s0.jpg?sig=1#xywh=0,0,320,180\n"

    private fun exact(lookupJson: String): Boolean {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val body = if (req.url.encodedPath == "/sprites") lookupJson else vtt
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val seekr = Seekr.create(SeekrAuth.ApiKey("k"), httpClient = http, attestation = SeekrAttestation.None)
        val found = runBlocking { seekr.lookupTrack(SeekrContent.Anime(1376, 1), 1_425_000) }
        return assertIs<SeekrTrackResult.Found>(found).track.exact
    }

    @Test
    fun `exact true, false and absent`() {
        assertEquals(true, exact("""{"vtt_url":"https://sprites.seekr.tv/a.vtt?sig=1","exact":true}"""))
        assertEquals(false, exact("""{"vtt_url":"https://sprites.seekr.tv/a.vtt?sig=1","exact":false}"""))
        assertEquals(false, exact("""{"vtt_url":"https://sprites.seekr.tv/a.vtt?sig=1"}"""))
    }
}
