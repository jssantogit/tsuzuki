package tachiyomi.domain.tsuzuki.provider.torrent

import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterVolume
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderPage
import java.net.URI

data class TorrentCandidateFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long? = null,
    val languages: Set<String> = emptySet(),
) {
    init {
        require(index >= 0) { "Torrent file index must not be negative" }
        require(path.isNotBlank() && path.length <= MAX_PATH_CHARS) {
            "Torrent file path is invalid"
        }
        require('\\' !in path && '\u0000' !in path && !path.startsWith('/')) {
            "Torrent file path must be relative"
        }
        val segments = path.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Torrent file path contains unsafe segments"
        }
        require(sizeBytes == null || sizeBytes >= 0L) {
            "Torrent file size must not be negative"
        }
        require(languages.size <= MAX_LANGUAGES && languages.all(::validLanguage)) {
            "Torrent file languages are invalid"
        }
    }

    private companion object {
        const val MAX_PATH_CHARS = 4096
        const val MAX_LANGUAGES = 32
    }
}

data class TorrentCandidate(
    val infoHash: String?,
    val magnetUri: String?,
    val torrentUrl: String?,
    val displayName: String,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val languages: Set<String> = emptySet(),
    val files: List<TorrentCandidateFile>? = null,
) {
    init {
        require(infoHash != null || magnetUri != null || torrentUrl != null) {
            "Torrent candidate requires a resolvable identity"
        }
        infoHash?.let {
            require(INFO_HASH.matches(it)) { "Torrent info hash is invalid" }
        }
        magnetUri?.let {
            require(it.length <= MAX_URI_CHARS && runCatching { URI(it).scheme == "magnet" }.getOrDefault(false)) {
                "Torrent magnet URI is invalid"
            }
        }
        torrentUrl?.let(::validateHttpUrl)
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_CHARS) {
            "Torrent display name is invalid"
        }
        require(sizeBytes == null || sizeBytes >= 0L) { "Torrent size must not be negative" }
        require(seeders == null || seeders >= 0) { "Torrent seeders must not be negative" }
        require(peers == null || peers >= 0) { "Torrent peers must not be negative" }
        require(languages.size <= MAX_LANGUAGES && languages.all(::validLanguage)) {
            "Torrent languages are invalid"
        }
        files?.let { values ->
            require(values.size <= MAX_FILES) { "Torrent candidate contains too many files" }
            require(values.map(TorrentCandidateFile::index).distinct().size == values.size) {
                "Torrent candidate contains duplicate file indices"
            }
        }
    }

    private companion object {
        const val MAX_URI_CHARS = 8192
        const val MAX_DISPLAY_NAME_CHARS = 2048
        const val MAX_LANGUAGES = 32
        const val MAX_FILES = 20_000
        val INFO_HASH = Regex("(?i)(?:[0-9a-f]{40}|[0-9a-f]{64})")
    }
}

data class TorrentSearchRequest(
    val titles: List<String>,
    val preferredLanguages: Set<String> = emptySet(),
    val chapterNumber: String? = null,
    val volume: Int? = null,
    val cursor: ProviderCursor? = null,
) {
    init {
        require(titles.isNotEmpty() && titles.size <= MAX_TITLES) {
            "Torrent search requires a bounded title set"
        }
        require(titles.all { it.isNotBlank() && it.length <= MAX_TITLE_CHARS }) {
            "Torrent search title is invalid"
        }
        require(
            preferredLanguages.size <= MAX_LANGUAGES &&
                preferredLanguages.all(::validLanguage),
        ) {
            "Torrent search preferred languages are invalid"
        }
        require(
            chapterNumber == null ||
                (chapterNumber.isNotBlank() && chapterNumber.length <= MAX_CHAPTER_NUMBER_CHARS),
        ) {
            "Torrent search chapter number is invalid"
        }
        require(volume == null || volume >= 0) {
            "Torrent search volume must not be negative"
        }
    }

    private companion object {
        const val MAX_TITLES = 16
        const val MAX_TITLE_CHARS = 1024
        const val MAX_LANGUAGES = 32
        const val MAX_CHAPTER_NUMBER_CHARS = 64
    }
}

fun interface TorrentSearchGateway {
    suspend fun search(
        providerId: ProviderId,
        request: TorrentSearchRequest,
    ): ProviderCallResult<ProviderPage<TorrentCandidate>>
}

data class TorrentAcquisitionRequest(
    val operationId: String,
    val candidate: TorrentCandidate,
    val selectedFile: TorrentCandidateFile,
) {
    init {
        require(OPERATION_ID.matches(operationId)) {
            "Torrent acquisition operation ID is invalid"
        }
        require(candidate.files.orEmpty().any { it == selectedFile }) {
            "Selected torrent file does not belong to the candidate"
        }
    }
}

sealed interface DebridResolveState {
    data class Ready(
        val resource: TorrentReadableResource.HttpFile,
    ) : DebridResolveState

    data class Pending(
        val jobId: String,
    ) : DebridResolveState {
        init {
            require(validJobId(jobId)) { "Debrid job ID is invalid" }
        }
    }
}

fun interface DebridResolveGateway {
    suspend fun resolve(
        providerId: ProviderId,
        request: TorrentAcquisitionRequest,
    ): ProviderCallResult<DebridResolveState>
}

sealed interface P2pAcquireState {
    data class Ready(
        val resource: TorrentReadableResource.LocalArchive,
    ) : P2pAcquireState

    data class Pending(
        val jobId: String,
    ) : P2pAcquireState {
        init {
            require(validJobId(jobId)) { "P2P job ID is invalid" }
        }
    }
}

fun interface P2pAcquireGateway {
    suspend fun acquire(
        providerId: ProviderId,
        request: TorrentAcquisitionRequest,
    ): ProviderCallResult<P2pAcquireState>
}

data class TorrentChapterRequest(
    val identity: CanonicalChapterIdentity,
    val volume: Int?,
    val preferredLanguages: Set<String> = emptySet(),
) {
    init {
        require(identity.isSpecific && identity.isNumbered) {
            "Torrent chapter mapping requires a specific numbered canonical identity"
        }
        require(volume == null || volume >= 0) { "Torrent chapter volume must not be negative" }
        require(preferredLanguages.size <= MAX_LANGUAGES && preferredLanguages.all(::validLanguage)) {
            "Torrent preferred languages are invalid"
        }
    }

    private companion object {
        const val MAX_LANGUAGES = 32
    }
}

sealed interface TorrentChapterFileMatch {
    data object None : TorrentChapterFileMatch

    data class Exact(
        val file: TorrentCandidateFile,
    ) : TorrentChapterFileMatch

    data class Ambiguous(
        val files: List<TorrentCandidateFile>,
    ) : TorrentChapterFileMatch {
        init {
            require(files.size > 1) { "Ambiguous torrent match requires multiple files" }
        }
    }
}

class TorrentChapterMapper(
    private val parseLabel: ParseCanonicalChapterLabel = ParseCanonicalChapterLabel(),
    private val parseVolume: ParseCanonicalChapterVolume = ParseCanonicalChapterVolume(),
) {

    fun map(
        request: TorrentChapterRequest,
        candidate: TorrentCandidate,
    ): TorrentChapterFileMatch {
        val files = candidate.files.orEmpty()
        if (files.isEmpty()) return TorrentChapterFileMatch.None

        var plausible = files
            .asSequence()
            .filter(::isSupportedReadableFile)
            .mapNotNull { file ->
                val label = file.chapterLabel()
                val parsed = parseLabel(label)
                file.takeIf { parsed.identity == request.identity }
                    ?.let { ParsedTorrentFile(file, parseVolume(label)) }
            }
            .toList()

        if (request.volume != null) {
            plausible = plausible.filter { it.volume == request.volume }
        }

        if (request.preferredLanguages.isNotEmpty()) {
            val languageMatches = plausible.filter { parsed ->
                parsed.file.languages.any { it in request.preferredLanguages }
            }
            if (languageMatches.isNotEmpty()) {
                plausible = languageMatches
            }
        }

        val matchingFiles = plausible
            .map(ParsedTorrentFile::file)
            .sortedBy(TorrentCandidateFile::index)

        return when (matchingFiles.size) {
            0 -> TorrentChapterFileMatch.None
            1 -> TorrentChapterFileMatch.Exact(matchingFiles.single())
            else -> TorrentChapterFileMatch.Ambiguous(matchingFiles)
        }
    }

    private fun isSupportedReadableFile(file: TorrentCandidateFile): Boolean {
        val lower = file.path.lowercase()
        return lower.endsWith(".cbz") || lower.endsWith(".zip")
    }

    private fun TorrentCandidateFile.chapterLabel(): String =
        path.substringAfterLast('/')
            .substringBeforeLast('.', missingDelimiterValue = path.substringAfterLast('/'))
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()

    private data class ParsedTorrentFile(
        val file: TorrentCandidateFile,
        val volume: Int?,
    )
}

enum class TorrentAcquisitionPreference {
    DEBRID_ONLY,
    P2P_ONLY,
    DEBRID_THEN_P2P,
}

enum class TorrentAcquisitionRoute {
    DEBRID,
    DIRECT_P2P,
}

sealed interface TorrentAcquisitionDecision {
    data class Routes(
        val ordered: List<TorrentAcquisitionRoute>,
    ) : TorrentAcquisitionDecision {
        init {
            require(ordered.isNotEmpty()) { "Torrent acquisition route list must not be empty" }
            require(ordered.distinct().size == ordered.size) {
                "Torrent acquisition routes must not contain duplicates"
            }
        }
    }

    data object DirectP2pConsentRequired : TorrentAcquisitionDecision

    data object Unavailable : TorrentAcquisitionDecision
}

object TorrentAcquisitionPolicy {

    fun resolve(
        preference: TorrentAcquisitionPreference,
        hasUsableDebrid: Boolean,
        hasUsableP2p: Boolean,
        directP2pAllowed: Boolean,
    ): TorrentAcquisitionDecision = when (preference) {
        TorrentAcquisitionPreference.DEBRID_ONLY -> {
            if (hasUsableDebrid) {
                TorrentAcquisitionDecision.Routes(listOf(TorrentAcquisitionRoute.DEBRID))
            } else {
                TorrentAcquisitionDecision.Unavailable
            }
        }
        TorrentAcquisitionPreference.P2P_ONLY -> when {
            !hasUsableP2p -> TorrentAcquisitionDecision.Unavailable
            directP2pAllowed ->
                TorrentAcquisitionDecision.Routes(listOf(TorrentAcquisitionRoute.DIRECT_P2P))
            else -> TorrentAcquisitionDecision.DirectP2pConsentRequired
        }
        TorrentAcquisitionPreference.DEBRID_THEN_P2P -> when {
            hasUsableDebrid && hasUsableP2p && directP2pAllowed ->
                TorrentAcquisitionDecision.Routes(
                    listOf(
                        TorrentAcquisitionRoute.DEBRID,
                        TorrentAcquisitionRoute.DIRECT_P2P,
                    ),
                )
            hasUsableDebrid ->
                TorrentAcquisitionDecision.Routes(listOf(TorrentAcquisitionRoute.DEBRID))
            hasUsableP2p && directP2pAllowed ->
                TorrentAcquisitionDecision.Routes(listOf(TorrentAcquisitionRoute.DIRECT_P2P))
            hasUsableP2p ->
                TorrentAcquisitionDecision.DirectP2pConsentRequired
            else ->
                TorrentAcquisitionDecision.Unavailable
        }
    }
}

enum class TorrentArchiveFormat {
    CBZ,
    ZIP,
}

sealed interface TorrentReadableResource {
    data class HttpFile(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val allowedOrigins: Set<String>,
        val allowLocalNetwork: Boolean = false,
    ) : TorrentReadableResource {
        init {
            validateHttpUrl(url)
            require(headers.size <= MAX_HEADERS) {
                "Torrent HTTP resource has too many headers"
            }
            require(allowedOrigins.isNotEmpty()) {
                "Torrent HTTP resource requires network authority"
            }
            require(allowedOrigins.size <= MAX_ORIGINS) {
                "Torrent HTTP resource has too many allowed origins"
            }
            require(
                allowedOrigins.all { origin ->
                    origin.isNotBlank() && origin.length <= MAX_ORIGIN_CHARS
                },
            ) {
                "Torrent HTTP resource origin authority is invalid"
            }
            require(
                headers.all { (name, value) ->
                    HEADER_NAME.matches(name) &&
                        name.lowercase() !in FORBIDDEN_REQUEST_HEADERS &&
                        value.length <= MAX_HEADER_VALUE_CHARS &&
                        !value.contains('\r') &&
                        !value.contains('\n')
                },
            ) {
                "Torrent HTTP resource header is invalid"
            }
        }

        private companion object {
            const val MAX_HEADERS = 32
            const val MAX_HEADER_VALUE_CHARS = 8192
            const val MAX_ORIGINS = 64
            const val MAX_ORIGIN_CHARS = 2048
            val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
            val FORBIDDEN_REQUEST_HEADERS = setOf(
                "connection",
                "content-length",
                "host",
                "keep-alive",
                "proxy-authenticate",
                "proxy-authorization",
                "proxy-connection",
                "te",
                "trailer",
                "transfer-encoding",
                "upgrade",
            )
        }
    }

    data class LocalArchive(
        val uri: String,
        val format: TorrentArchiveFormat,
    ) : TorrentReadableResource {
        init {
            require(uri.length <= MAX_URI_CHARS) {
                "Torrent local archive URI is too long"
            }
            val parsed = runCatching { URI(uri) }.getOrNull()
            require(
                parsed != null &&
                    parsed.scheme == "content" &&
                    !parsed.host.isNullOrBlank() &&
                    parsed.userInfo == null,
            ) {
                "Torrent local archive must use managed content URI authority"
            }
        }

        private companion object {
            const val MAX_URI_CHARS = 8192
        }
    }
}

enum class TorrentAcquisitionFailure {
    UNAVAILABLE,
    AUTH_REQUIRED,
    PERMISSION_DENIED,
    NETWORK_ERROR,
    ACQUISITION_FAILED,
    P2P_CONSENT_REQUIRED,
}

sealed interface TorrentBackendResult {
    data class Success(
        val resource: TorrentReadableResource,
    ) : TorrentBackendResult

    data class Failure(
        val reason: TorrentAcquisitionFailure,
    ) : TorrentBackendResult
}

fun interface TorrentAcquisitionBackend {
    suspend fun acquire(
        candidate: TorrentCandidate,
        file: TorrentCandidateFile,
    ): TorrentBackendResult
}

sealed interface TorrentAcquisitionResult {
    data class Success(
        val route: TorrentAcquisitionRoute,
        val file: TorrentCandidateFile,
        val resource: TorrentReadableResource,
    ) : TorrentAcquisitionResult

    data class Failure(
        val reason: TorrentAcquisitionFailure,
    ) : TorrentAcquisitionResult
}

class TorrentAcquisitionRouter(
    private val debrid: TorrentAcquisitionBackend,
    private val directP2p: TorrentAcquisitionBackend,
) {

    suspend fun acquire(
        candidate: TorrentCandidate,
        selectedFile: TorrentCandidateFile,
        decision: TorrentAcquisitionDecision,
    ): TorrentAcquisitionResult {
        require(candidate.files.orEmpty().any { it == selectedFile }) {
            "Selected torrent file does not belong to the candidate"
        }

        val routes = when (decision) {
            is TorrentAcquisitionDecision.Routes -> decision.ordered
            TorrentAcquisitionDecision.DirectP2pConsentRequired ->
                return TorrentAcquisitionResult.Failure(
                    TorrentAcquisitionFailure.P2P_CONSENT_REQUIRED,
                )
            TorrentAcquisitionDecision.Unavailable ->
                return TorrentAcquisitionResult.Failure(
                    TorrentAcquisitionFailure.UNAVAILABLE,
                )
        }

        var lastFailure = TorrentAcquisitionFailure.UNAVAILABLE
        for (route in routes) {
            val backend = when (route) {
                TorrentAcquisitionRoute.DEBRID -> debrid
                TorrentAcquisitionRoute.DIRECT_P2P -> directP2p
            }
            when (val result = backend.acquire(candidate, selectedFile)) {
                is TorrentBackendResult.Success ->
                    return TorrentAcquisitionResult.Success(
                        route = route,
                        file = selectedFile,
                        resource = result.resource,
                    )
                is TorrentBackendResult.Failure ->
                    lastFailure = result.reason
            }
        }

        return TorrentAcquisitionResult.Failure(lastFailure)
    }
}

private fun validateHttpUrl(value: String) {
    require(value.length <= 8192) { "Torrent URL is too long" }
    val uri = runCatching { URI(value) }.getOrNull()
    require(
        uri != null &&
            (uri.scheme == "http" || uri.scheme == "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null,
    ) {
        "Torrent URL must be absolute HTTP(S) without credentials"
    }
}

private fun validLanguage(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= 32 &&
        LANGUAGE.matches(value)

private fun validJobId(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= 256 &&
        JOB_ID.matches(value)

private val OPERATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val JOB_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
private val LANGUAGE = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")
