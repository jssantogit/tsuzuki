package tachiyomi.core.provider.supplychain

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ProviderLocalConfiguration(
    val providerId: String,
    val enabled: Boolean = true,
    val enabledContentLanguages: Set<String>? = null,
)

interface ProviderLocalConfigurationStore {
    fun list(): List<ProviderLocalConfiguration>

    fun get(providerId: String): ProviderLocalConfiguration?

    fun save(configuration: ProviderLocalConfiguration)

    fun remove(providerId: String): Boolean
}

class FileProviderLocalConfigurationStore(
    private val root: File,
) : ProviderLocalConfigurationStore {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    init {
        ensureDirectory(root, "Provider local configuration store")
    }

    override fun list(): List<ProviderLocalConfiguration> =
        root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file -> file.isFile && file.extension == "json" }
            .map(::readConfiguration)
            .sortedBy(ProviderLocalConfiguration::providerId)
            .toList()

    override fun get(providerId: String): ProviderLocalConfiguration? {
        validateIdentifier(providerId, "Provider ID")
        val file = configurationFile(providerId)
        if (!file.exists()) return null
        return readConfiguration(file)
    }

    @Synchronized
    override fun save(configuration: ProviderLocalConfiguration) {
        validateConfiguration(configuration)
        atomicWrite(
            configurationFile(configuration.providerId),
            json.encodeToString(configuration).encodeToByteArray(),
            "Provider local configuration",
        )
    }

    @Synchronized
    override fun remove(providerId: String): Boolean {
        validateIdentifier(providerId, "Provider ID")
        val file = configurationFile(providerId)
        if (!file.exists()) return false
        if (!file.delete()) {
            throw ProviderSupplyChainException("Provider local configuration could not be removed")
        }
        return true
    }

    private fun readConfiguration(file: File): ProviderLocalConfiguration {
        val configuration = try {
            json.decodeFromString<ProviderLocalConfiguration>(file.readText())
        } catch (error: Exception) {
            throw ProviderSupplyChainException("Provider local configuration is malformed", error)
        }
        validateConfiguration(configuration)
        if (file.nameWithoutExtension != configuration.providerId) {
            throw ProviderSupplyChainException(
                "Provider local configuration does not match its file identity",
            )
        }
        return configuration
    }

    private fun validateConfiguration(configuration: ProviderLocalConfiguration) {
        validateIdentifier(configuration.providerId, "Provider ID")
        configuration.enabledContentLanguages?.forEach { language ->
            if (language.isBlank() || language.length > 32) {
                throw ProviderSupplyChainException("Provider content language is invalid")
            }
        }
    }

    private fun configurationFile(providerId: String): File =
        File(root, "$providerId.json")
}
