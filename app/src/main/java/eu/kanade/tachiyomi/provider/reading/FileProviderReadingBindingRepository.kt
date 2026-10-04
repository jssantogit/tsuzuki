package eu.kanade.tachiyomi.provider.reading

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBindingRepository
import java.io.File
import java.security.MessageDigest

class FileProviderReadingBindingRepository(
    private val root: File,
) : ProviderReadingBindingRepository {

    private val lock = Any()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    init {
        check(root.exists() || root.mkdirs()) { "Provider reading binding directory could not be created" }
        check(root.isDirectory) { "Provider reading binding root is not a directory" }
    }

    override suspend fun get(
        canonicalTitleId: String,
        providerId: ProviderId,
        facetId: String?,
    ): ProviderReadingBinding? = synchronized(lock) {
        readAll().firstOrNull { binding ->
            binding.canonicalTitleId == canonicalTitleId &&
                binding.ref.providerId == providerId &&
                binding.ref.facetId == facetId
        }
    }

    override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> = synchronized(lock) {
        readAll()
            .filter { it.canonicalTitleId == canonicalTitleId }
            .sortedWith(
                compareBy<ProviderReadingBinding>(
                    { it.ref.providerId.value },
                    { it.ref.facetId.orEmpty() },
                    { it.id },
                ),
            )
    }

    override suspend fun upsert(binding: ProviderReadingBinding) {
        synchronized(lock) {
            val existing = fileFor(binding.id)
                .takeIf(File::exists)
                ?.let(::read)
            if (existing != null) {
                require(existing.id == binding.id) { "Provider reading binding identity changed" }
                require(existing.canonicalTitleId == binding.canonicalTitleId) {
                    "Provider reading binding canonical title changed"
                }
                require(existing.ref == binding.ref) { "Provider reading binding target changed" }
                require(existing.createdAt == binding.createdAt) { "Provider reading binding creation time changed" }
            }
            atomicWrite(fileFor(binding.id), json.encodeToString(binding.toRecord()))
        }
    }

    override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
        synchronized(lock) {
            val file = fileFor(bindingId)
            if (!file.exists()) return
            val existing = read(file)
            require(updatedAt >= existing.updatedAt) { "Provider reading binding update moved backwards" }
            atomicWrite(
                file,
                json.encodeToString(
                    existing.copy(
                        availability = ProviderBindingAvailability.UNAVAILABLE,
                        updatedAt = updatedAt,
                    ).toRecord(),
                ),
            )
        }
    }

    private fun readAll(): List<ProviderReadingBinding> = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension == "json" }
        .map(::read)
        .toList()

    private fun read(file: File): ProviderReadingBinding {
        val record = runCatching { json.decodeFromString<BindingRecord>(file.readText()) }
            .getOrElse { error -> throw IllegalStateException("Provider reading binding is malformed", error) }
        val binding = record.toDomain()
        require(file.nameWithoutExtension == fileKey(binding.id)) {
            "Provider reading binding file identity does not match payload"
        }
        return binding
    }

    private fun fileFor(bindingId: String): File = File(root, "${fileKey(bindingId)}.json")

    private fun fileKey(bindingId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(bindingId.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun atomicWrite(file: File, contents: String) {
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(contents)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IllegalStateException("Provider reading binding could not be persisted")
        }
    }

    @Serializable
    private data class BindingRecord(
        val id: String,
        val canonicalTitleId: String,
        val providerId: String,
        val facetId: String? = null,
        val externalWorkId: String,
        val verification: String,
        val availability: String,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        fun toDomain() = ProviderReadingBinding(
            id = id,
            canonicalTitleId = canonicalTitleId,
            ref = ProviderBindingRef(
                providerId = ProviderId(providerId),
                facetId = facetId,
                externalWorkId = externalWorkId,
            ),
            verification = ProviderBindingVerification.valueOf(verification),
            availability = ProviderBindingAvailability.valueOf(availability),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun ProviderReadingBinding.toRecord() = BindingRecord(
        id = id,
        canonicalTitleId = canonicalTitleId,
        providerId = ref.providerId.value,
        facetId = ref.facetId,
        externalWorkId = ref.externalWorkId,
        verification = verification.name,
        availability = availability.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
