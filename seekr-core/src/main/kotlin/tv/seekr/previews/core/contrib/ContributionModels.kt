package tv.seekr.previews.core.contrib

/** `reason` of `GET /v1/contrib/needs`. [Unknown] is a value from a newer server: treated as "do not capture". */
enum class NeedsReason { Ok, HaveExact, NotObserved, Ignored, BlockedTakedown, JobClosed, JobCap, Unknown }

data class Needs(
    val generate: Boolean,
    val reason: NeedsReason,
    val gridMs: Int,
    val nSlots: Int,
    val jobDurationMs: Long,
    /** Empty sets when [reason] is not [NeedsReason.Ok]. */
    val needed: CueSet,
    val overlap: CueSet,
    val uploadAfterCues: Int,
) {
    /** What to capture. The server's secret overlap sample is indistinguishable from `needed` by design. */
    val capture: CueSet get() = needed union overlap
}

sealed interface NeedsResult {
    data class Success(val needs: Needs) : NeedsResult
    data class Failure(val error: ContributionError) : NeedsResult
}

enum class JobStatus { Collecting, AwaitingApproval, Approved, Promoted, Rejected, Expired, TakenDown, Unknown }

data class Submission(
    val id: String,
    /** The v1 status string (`pending`, `promoted`, ...), passed through. */
    val status: String,
    /** `null` for legacy rows. */
    val jobStatus: JobStatus?,
    val duplicate: Boolean,
    /** PUT only: cues of the job still uncovered after this insert. `null` when absent (GET) or not decodable. */
    val needed: CueSet?,
    val rejectCode: String?,
)

data class DryRunInfo(val observed: Boolean, val gridMs: Int, val nSlots: Int, val tileCount: Int)

sealed interface SubmitResult {
    /** 201, or 200 for a duplicate ([Submission.duplicate]). */
    data class Accepted(val submission: Submission) : SubmitResult
    data class DryRun(val info: DryRunInfo) : SubmitResult
    data class Failure(val error: ContributionError) : SubmitResult
}

sealed interface StatusResult {
    data class Success(val submission: Submission) : StatusResult
    data class Failure(val error: ContributionError) : StatusResult
}

sealed interface InstallationResult {
    data class Success(val token: String, val usableAfterEpochMs: Long) : InstallationResult
    data class Failure(val error: ContributionError) : InstallationResult
}

sealed interface ContributionError {
    /** A [tv.seekr.previews.core.SeekrAuth.Bearer] returned no token. */
    data object SignedOut : ContributionError

    /** 401: the key or session is unknown or revoked. */
    data object Unauthorized : ContributionError

    /** 403 `contrib_not_enabled`. */
    data object NotEnabled : ContributionError

    /** 403 `contrib_not_allowed` (TV contributions off), or a bearer asked to mint an installation. */
    data object NotAllowed : ContributionError

    /** 403 `installation_too_new`. */
    data object InstallationTooNew : ContributionError

    /** 403 `contrib_blocked` or an explicitly suspended account. */
    data object Suspended : ContributionError

    /** 503 `contrib_disabled`. */
    data object Disabled : ContributionError

    /** 429 (`rate_limited` | `quota_exceeded`). */
    data class RateLimited(val retryAfterSec: Int?) : ContributionError

    /** 503 `server_busy`. */
    data class ServerBusy(val retryAfterSec: Int?) : ContributionError

    /** 409 `not_observed`. */
    data object NotObserved : ContributionError

    /** 409 `job_closed`; [detail] is `closed | have_exact | ignored | blocked_takedown`. */
    data class JobClosed(val detail: String?) : ContributionError

    /** 409 `job_cap`; [detail] is `user_jobs | title_jobs | tiles`. */
    data class JobCap(val detail: String?) : ContributionError

    /** 409 `job_mismatch`. */
    data class JobMismatch(val nSlots: Int, val jobDurationMs: Long) : ContributionError

    /** Any other 4xx (400, 408, 411, 413, 415, 422, 404 ...). [code] is the body's `error`, `""` when there is none. */
    data class Rejected(val httpStatus: Int, val code: String, val detail: String?) : ContributionError

    /** The content has no id the envelope can be built from; nothing was sent. */
    data object UnsupportedContent : ContributionError

    /** Offline, timeout, or a response that could not be parsed. */
    data object Network : ContributionError

    /** 5xx other than the ones above. */
    data class Server(val httpStatus: Int) : ContributionError
}

/** What a caller should do about an error (PACKAGE-SPEC §7.3.8). */
enum class ErrorPolicy { RetryAfter, RetryNeedsLater, DropFileCache, StopFile, StopUntilRestart, StopUntilSignInChanges, StopSession }

val ContributionError.policy: ErrorPolicy
    get() = when (this) {
        is ContributionError.RateLimited,
        is ContributionError.ServerBusy,
        ContributionError.InstallationTooNew,
        ContributionError.Network,
        is ContributionError.Server,
        -> ErrorPolicy.RetryAfter
        ContributionError.NotObserved -> ErrorPolicy.RetryNeedsLater
        is ContributionError.JobClosed -> ErrorPolicy.DropFileCache
        is ContributionError.JobCap,
        is ContributionError.JobMismatch,
        ContributionError.UnsupportedContent,
        -> ErrorPolicy.StopFile
        // 408 upload_too_slow is the one 4xx worth retrying (PACKAGE-SPEC §5); an unrecognised 403 is a refusal of the account.
        is ContributionError.Rejected -> when (httpStatus) {
            408 -> ErrorPolicy.RetryAfter
            403 -> ErrorPolicy.StopUntilRestart
            else -> ErrorPolicy.StopFile
        }
        ContributionError.NotEnabled,
        ContributionError.NotAllowed,
        ContributionError.Suspended,
        ContributionError.Disabled,
        -> ErrorPolicy.StopUntilRestart
        ContributionError.Unauthorized,
        ContributionError.SignedOut,
        -> ErrorPolicy.StopUntilSignInChanges
    }

/** Where the installation token (API-key auth only) is persisted. The default is in memory; the Android contributor supplies a file-backed one. */
interface InstallationStore {
    suspend fun load(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

class InMemoryInstallationStore : InstallationStore {
    @Volatile
    private var token: String? = null

    override suspend fun load(): String? = token
    override suspend fun save(token: String) {
        this.token = token
    }
    override suspend fun clear() {
        token = null
    }
}
