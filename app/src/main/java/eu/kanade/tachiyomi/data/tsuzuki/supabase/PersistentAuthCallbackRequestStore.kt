package eu.kanade.tachiyomi.data.tsuzuki.supabase

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class AuthCallbackPkceRequest(
    val nonce: String,
    val codeChallenge: String,
)

/**
 * Creates a PKCE verifier on the initiating device. Only the challenge is
 * transmitted. The private verifier is consumed once and never enters a URL.
 */
interface AuthCallbackRequestStore {
    fun start(type: String): AuthCallbackPkceRequest
    fun consume(nonce: String, typeHint: String? = null): String?
    fun cancel(type: String, nonce: String)
}

class PersistentAuthCallbackRequestStore(
    private val directory: File,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val nonceSource: () -> String = ::newNonce,
    private val verifierSource: () -> String = ::newVerifier,
) : AuthCallbackRequestStore {

    override fun start(type: String): AuthCallbackPkceRequest = synchronized(lock) {
        require(type in SUPPORTED_TYPES) { "Unsupported callback request type" }
        val nonce = nonceSource()
        require(nonce.matches(NONCE_PATTERN)) { "Invalid callback state" }
        val verifier = verifierSource()
        require(verifier.matches(PKCE_VERIFIER_PATTERN)) { "Invalid PKCE verifier" }
        val issuedAt = nowEpochMillis()
        val loaded = load()
        check(loaded != null) { "Cannot replace a damaged auth request ledger" }
        val current = loaded.filter { it.isValidAt(issuedAt) }
        val fingerprint = hash(nonce)
        check(current.none { it.nonceHash == fingerprint }) { "Callback nonce collision" }
        val record = PendingRequest(
            type = type,
            nonceHash = fingerprint,
            verifier = verifier,
            expiresAtEpochMillis = issuedAt + LIFETIME_MILLIS,
        )
        persist((current + record).takeLast(MAX_PENDING_REQUESTS))
        AuthCallbackPkceRequest(
            nonce = nonce,
            codeChallenge = challenge(verifier),
        )
    }

    override fun consume(nonce: String, typeHint: String?): String? = synchronized(lock) {
        if (!nonce.matches(NONCE_PATTERN)) return@synchronized null
        if (typeHint != null && typeHint !in SUPPORTED_TYPES) return@synchronized null
        val entries = load() ?: return@synchronized null
        val now = nowEpochMillis()
        val fingerprint = hash(nonce)
        val index = entries.indexOfFirst {
            it.isValidAt(now) && it.verifier?.matches(PKCE_VERIFIER_PATTERN) == true &&
                (typeHint == null || it.type == typeHint) &&
                MessageDigest.isEqual(
                    it.nonceHash.toByteArray(Charsets.UTF_8),
                    fingerprint.toByteArray(Charsets.UTF_8),
                )
        }
        if (index < 0) return@synchronized null
        try {
            persist(
                entries.filterIndexed { offset, entry ->
                    offset != index && entry.isValidAt(now)
                },
            )
            entries[index].verifier
        } catch (_: Exception) {
            // No exchange is allowed without durable single-use consumption.
            null
        }
    }

    override fun cancel(type: String, nonce: String) {
        synchronized(lock) {
            val entries = load() ?: return@synchronized
            val fingerprint = hash(nonce)
            persist(entries.filterNot { it.type == type && it.nonceHash == fingerprint })
        }
    }

    private fun load(): List<PendingRequest>? {
        val file = File(directory, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val records = json.decodeFromString(
                ListSerializer(PendingRequest.serializer()),
                file.readText(),
            )
            records.takeIf { entries ->
                entries.size <= MAX_PENDING_REQUESTS &&
                    entries.all {
                        it.type in SUPPORTED_TYPES &&
                            it.nonceHash.matches(Regex("[0-9a-f]{64}")) &&
                            it.expiresAtEpochMillis >= 0 &&
                            (it.verifier == null || it.verifier.matches(PKCE_VERIFIER_PATTERN))
                    }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun persist(entries: List<PendingRequest>) {
        check(directory.isDirectory || directory.mkdirs()) {
            "Cannot store a pending auth request"
        }
        val file = File(directory, FILE_NAME)
        val temporary = File(directory, "$FILE_NAME.tmp")
        try {
            temporary.writeText(
                json.encodeToString(ListSerializer(PendingRequest.serializer()), entries),
            )
            check(temporary.renameTo(file)) { "Could not persist auth request" }
        } finally {
            temporary.delete()
        }
    }

    private fun PendingRequest.isValidAt(now: Long): Boolean =
        now >= 0 && now <= expiresAtEpochMillis

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII)),
        )

    @Serializable
    private data class PendingRequest(
        val type: String,
        val nonceHash: String,
        val expiresAtEpochMillis: Long,
        // Null supports reading old nonce-only ledgers; they can NEVER exchange.
        val verifier: String? = null,
    )

    companion object {
        const val FILE_NAME = "tsuzuki_auth_pending_callbacks_v1.json"
        const val LIFETIME_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private const val MAX_PENDING_REQUESTS = 8
        private val SUPPORTED_TYPES = setOf("signup", "recovery")
        private val NONCE_PATTERN = Regex("[A-Za-z0-9_-]{16,128}")
        private val PKCE_VERIFIER_PATTERN = Regex("[A-Za-z0-9_-]{43,128}")
        private val lock = Any()
        private val json = Json { ignoreUnknownKeys = false }

        private fun newNonce(): String = randomBase64Url(24)
        private fun newVerifier(): String = randomBase64Url(32)

        private fun randomBase64Url(length: Int): String {
            val bytes = ByteArray(length)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
