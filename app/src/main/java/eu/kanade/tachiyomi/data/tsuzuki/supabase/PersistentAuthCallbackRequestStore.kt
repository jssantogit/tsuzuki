package eu.kanade.tachiyomi.data.tsuzuki.supabase

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A callback may only establish a session after a local user action initiated
 * the matching email flow. Entries survive process death, expire and are
 * consumed at most once before contacting Supabase.
 */
interface AuthCallbackRequestStore {
    fun start(type: String): String
    fun consume(type: String, nonce: String): Boolean
    fun cancel(type: String, nonce: String)
}

class PersistentAuthCallbackRequestStore(
    private val directory: File,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val nonceSource: () -> String = ::newNonce,
) : AuthCallbackRequestStore {

    override fun start(type: String): String = synchronized(lock) {
        require(type in SUPPORTED_TYPES) { "Unsupported callback request type" }

        val nonce = nonceSource()
        require(nonce.matches(Regex("[A-Za-z0-9_-]{16,128}"))) {
            "Callback nonce must be a URL-safe high-entropy value"
        }
        val issuedAt = nowEpochMillis()
        val loaded = load()
        // A corrupted ledger may NOT be overwritten with a newly trusted one.
        check(loaded != null) {
            "Cannot establish a callback request over a damaged ledger"
        }
        val current = loaded.filter { it.isValidAt(issuedAt) }
        val fingerprint = hash(nonce)
        check(current.none { it.nonceHash == fingerprint }) {
            "Auth callback nonce collision"
        }
        val record = PendingRequest(
            type = type,
            nonceHash = fingerprint,
            expiresAtEpochMillis = issuedAt + LIFETIME_MILLIS,
        )
        persist((current + record).takeLast(MAX_PENDING_REQUESTS))
        nonce
    }

    override fun consume(type: String, nonce: String): Boolean = synchronized(lock) {
        if (type !in SUPPORTED_TYPES || nonce.isBlank()) return@synchronized false
        val entries = load() ?: return@synchronized false
        val now = nowEpochMillis()
        val nonceHash = hash(nonce)
        val matchingIndex = entries.indexOfFirst { record ->
            record.isValidAt(now) && record.type == type &&
                MessageDigest.isEqual(
                    record.nonceHash.toByteArray(Charsets.UTF_8),
                    nonceHash.toByteArray(Charsets.UTF_8),
                )
        }
        if (matchingIndex < 0) return@synchronized false
        try {
            persist(
                entries.filterIndexed { index, record ->
                    index != matchingIndex && record.isValidAt(now)
                },
            )
            true
        } catch (_: Exception) {
            // A callback is not authorized unless consumption is durable.
            false
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
            records.takeIf { items ->
                items.size <= MAX_PENDING_REQUESTS &&
                    items.all {
                        it.type in SUPPORTED_TYPES &&
                            it.nonceHash.matches(Regex("[0-9a-f]{64}")) &&
                            it.expiresAtEpochMillis >= 0
                    }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun persist(entries: List<PendingRequest>) {
        check(directory.isDirectory || directory.mkdirs()) {
            "Cannot store a pending authentication callback"
        }
        val file = File(directory, FILE_NAME)
        val temp = File(directory, "$FILE_NAME.tmp")
        try {
            temp.writeText(
                json.encodeToString(ListSerializer(PendingRequest.serializer()), entries),
            )
            check(temp.renameTo(file)) { "Could not persist callback request ledger" }
        } finally {
            temp.delete()
        }
    }

    private fun PendingRequest.isValidAt(now: Long): Boolean =
        now >= 0 && now <= expiresAtEpochMillis

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    @Serializable
    private data class PendingRequest(
        val type: String,
        val nonceHash: String,
        val expiresAtEpochMillis: Long,
    )

    companion object {
        const val FILE_NAME = "tsuzuki_auth_pending_callbacks_v1.json"
        const val LIFETIME_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private const val MAX_PENDING_REQUESTS = 8
        private val SUPPORTED_TYPES = setOf("signup", "recovery")
        private val lock = Any()
        private val json = Json { ignoreUnknownKeys = false }

        private fun newNonce(): String {
            val random = ByteArray(24).also(SecureRandom()::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        }
    }
}
