package tv.seekr.previews.core.contrib

import kotlin.test.Test
import kotlin.test.assertEquals
import tv.seekr.previews.core.contrib.ContributionError as E

/** SDK-API §2 policy table (PACKAGE-SPEC §7.3.8). */
class ErrorPolicyTest {
    @Test
    fun `policy per error`() {
        val table: List<Pair<E, ErrorPolicy>> = listOf(
            E.RateLimited(5) to ErrorPolicy.RetryAfter,
            E.ServerBusy(null) to ErrorPolicy.RetryAfter,
            E.InstallationTooNew to ErrorPolicy.RetryAfter,
            E.Network to ErrorPolicy.RetryAfter,
            E.Server(500) to ErrorPolicy.RetryAfter,
            E.Rejected(408, "upload_too_slow", null) to ErrorPolicy.RetryAfter,
            E.NotObserved to ErrorPolicy.RetryNeedsLater,
            E.JobClosed("closed") to ErrorPolicy.DropFileCache,
            E.JobCap("tiles") to ErrorPolicy.StopFile,
            E.JobMismatch(1, 1) to ErrorPolicy.StopFile,
            E.UnsupportedContent to ErrorPolicy.StopFile,
            E.Rejected(422, "bad_format", null) to ErrorPolicy.StopFile,
            E.Rejected(400, "invalid_envelope", null) to ErrorPolicy.StopFile,
            E.Rejected(413, "payload_too_large", null) to ErrorPolicy.StopFile,
            E.Rejected(415, "unsupported_media_type", null) to ErrorPolicy.StopFile,
            E.Rejected(403, "other", null) to ErrorPolicy.StopUntilRestart,
            E.NotEnabled to ErrorPolicy.StopUntilRestart,
            E.NotAllowed to ErrorPolicy.StopUntilRestart,
            E.Suspended to ErrorPolicy.StopUntilRestart,
            E.Disabled to ErrorPolicy.StopUntilRestart,
            E.Unauthorized to ErrorPolicy.StopUntilSignInChanges,
            E.SignedOut to ErrorPolicy.StopUntilSignInChanges,
        )
        for ((error, policy) in table) assertEquals(policy, error.policy, error.toString())
    }
}
