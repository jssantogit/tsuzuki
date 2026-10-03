package tachiyomi.core.provider.packageformat

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

@Serializable
data class ProviderManifestVersion(
    val name: String,
    val code: Long,
)

@Serializable
data class ProviderManifestCapability(
    val id: String,
    val version: Int,
)

@Serializable
data class ProviderManifestNetworkPermission(
    val origins: Set<String> = emptySet(),
    val localNetwork: Boolean = false,
)

@Serializable
data class ProviderManifestBrowserPermission(
    val origins: Set<String> = emptySet(),
)

@Serializable
data class ProviderManifestStoragePermission(
    val enabled: Boolean = false,
)

@Serializable
data class ProviderManifestPermissions(
    val network: ProviderManifestNetworkPermission? = null,
    val browser: ProviderManifestBrowserPermission? = null,
    val storage: ProviderManifestStoragePermission = ProviderManifestStoragePermission(),
    val secrets: Set<String> = emptySet(),
)

@Serializable
data class ProviderManifestSetting(
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
)

@Serializable
data class ProviderScriptManifest(
    val manifestVersion: Int,
    val id: String,
    val name: String,
    val version: ProviderManifestVersion,
    val minHostApi: Int,
    val entrypoint: String,
    val capabilities: List<ProviderManifestCapability>,
    val permissions: ProviderManifestPermissions = ProviderManifestPermissions(),
    val contentLanguages: Set<String> = emptySet(),
    val settings: List<ProviderManifestSetting> = emptyList(),
)

data class ProviderPackageLimits(
    val maxEntries: Int = 256,
    val maxEntryBytes: Long = 2L * 1024 * 1024,
    val maxTotalUncompressedBytes: Long = 16L * 1024 * 1024,
) {
    init {
        require(maxEntries > 0) { "Provider package entry limit must be positive" }
        require(maxEntryBytes > 0L) { "Provider package entry byte limit must be positive" }
        require(maxTotalUncompressedBytes >= maxEntryBytes) {
            "Provider package total byte limit must cover at least one entry"
        }
    }
}

data class ParsedProviderPackage(
    val manifest: ProviderScriptManifest,
    val entries: Map<String, ByteArray>,
)

class ProviderPackageException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class ProviderPackageParser(
    private val limits: ProviderPackageLimits = ProviderPackageLimits(),
) {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun parse(bytes: ByteArray): ParsedProviderPackage {
        if (!hasZipLocalFileHeader(bytes)) {
            fail("Provider package is not a valid ZIP container")
        }

        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        var entryCount = 0

        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = validatePath(entry.name)
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    entryCount += 1
                    if (entryCount > limits.maxEntries) {
                        fail("Provider package contains too many files")
                    }
                    if (path in entries) {
                        fail("Provider package contains a duplicate file path")
                    }
                    rejectExecutablePayload(path)

                    val content = readBoundedEntry(zip)
                    totalBytes += content.size
                    if (totalBytes > limits.maxTotalUncompressedBytes) {
                        fail("Provider package exceeds the total uncompressed byte limit")
                    }
                    entries[path] = content
                    zip.closeEntry()
                }
            }
        } catch (error: ProviderPackageException) {
            throw error
        } catch (error: IOException) {
            throw ProviderPackageException("Provider package ZIP could not be read", error)
        }

        if (entries.isEmpty()) {
            fail("Provider package does not contain files")
        }

        val manifestBytes = entries["manifest.json"]
            ?: fail("Provider package is missing manifest.json")
        val manifest = try {
            json.decodeFromString<ProviderScriptManifest>(manifestBytes.decodeToString())
        } catch (error: Exception) {
            throw ProviderPackageException("Provider manifest is malformed", error)
        }
        validateManifest(manifest, entries)

        return ParsedProviderPackage(
            manifest = manifest,
            entries = entries.mapValues { (_, value) -> value.copyOf() },
        )
    }

    private fun readBoundedEntry(zip: ZipInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            if (read == 0) continue

            total += read
            if (total > limits.maxEntryBytes) {
                fail("Provider package file exceeds the per-entry byte limit")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun validateManifest(
        manifest: ProviderScriptManifest,
        entries: Map<String, ByteArray>,
    ) {
        if (manifest.manifestVersion != SUPPORTED_MANIFEST_VERSION) {
            fail("Unsupported Provider manifest version")
        }
        if (!PROVIDER_ID.matches(manifest.id)) {
            fail("Provider manifest ID is invalid")
        }
        if (manifest.name.isBlank()) {
            fail("Provider manifest name must not be blank")
        }
        if (manifest.version.name.isBlank() || manifest.version.code <= 0L) {
            fail("Provider manifest version is invalid")
        }
        if (manifest.minHostApi <= 0) {
            fail("Provider minimum Host API must be positive")
        }

        val entrypoint = validatePath(manifest.entrypoint)
        if (!entrypoint.endsWith(".js") && !entrypoint.endsWith(".mjs")) {
            fail("Provider entrypoint must be a JavaScript module")
        }
        if (entrypoint !in entries) {
            fail("Provider entrypoint is missing from the package")
        }

        val capabilities = mutableSetOf<Pair<String, Int>>()
        val capabilityIds = mutableSetOf<String>()
        manifest.capabilities.forEach { capability ->
            if (!CAPABILITY_ID.matches(capability.id) || capability.version <= 0) {
                fail("Provider manifest capability is invalid")
            }
            if (!capabilities.add(capability.id to capability.version)) {
                fail("Provider manifest contains duplicate capability declarations")
            }
            if (!capabilityIds.add(capability.id)) {
                fail("Provider manifest declares multiple versions of one capability")
            }
        }

        manifest.permissions.network?.origins?.forEach(::validateOrigin)
        manifest.permissions.browser?.origins?.forEach(::validateOrigin)
        if (manifest.permissions.secrets.any { !SETTING_KEY.matches(it) }) {
            fail("Provider manifest secret identifier is invalid")
        }
        if (manifest.contentLanguages.any { it.isBlank() || it.length > 32 }) {
            fail("Provider manifest content language is invalid")
        }

        val settingKeys = mutableSetOf<String>()
        manifest.settings.forEach { setting ->
            if (!SETTING_KEY.matches(setting.key) || !settingKeys.add(setting.key)) {
                fail("Provider manifest setting key is invalid or duplicated")
            }
            if (setting.label.isBlank()) {
                fail("Provider manifest setting label must not be blank")
            }
            if (setting.type !in SETTING_TYPES) {
                fail("Provider manifest setting type is unsupported")
            }
            if (setting.type == "select" && setting.options.isEmpty()) {
                fail("Provider select setting requires options")
            }
            if (setting.options.any { it.isBlank() }) {
                fail("Provider manifest setting option must not be blank")
            }
        }
    }

    private fun validateOrigin(value: String) {
        val match = ORIGIN.matchEntire(value)
            ?: fail("Provider permission origin is invalid")
        val host = match.groupValues[2]
        if (host.isBlank() || host.contains("..")) {
            fail("Provider permission origin host is invalid")
        }
    }

    private fun validatePath(raw: String): String {
        if (
            raw.isBlank() ||
            raw.startsWith("/") ||
            raw.startsWith("\\") ||
            '\\' in raw ||
            ':' in raw
        ) {
            fail("Provider package path is unsafe")
        }

        val segments = raw.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            fail("Provider package path is unsafe")
        }
        return segments.joinToString("/")
    }

    private fun rejectExecutablePayload(path: String) {
        val lower = path.lowercase()
        if (FORBIDDEN_SUFFIXES.any(lower::endsWith)) {
            fail("Provider package contains a forbidden executable payload")
        }
    }

    private fun hasZipLocalFileHeader(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() &&
            bytes[3] == 4.toByte()

    private fun fail(message: String): Nothing =
        throw ProviderPackageException(message)

    private companion object {
        const val SUPPORTED_MANIFEST_VERSION = 1

        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val CAPABILITY_ID = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
        val SETTING_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val ORIGIN = Regex("https://(\\*\\.)?([A-Za-z0-9.-]+)(?::[0-9]{1,5})?")
        val FORBIDDEN_SUFFIXES = setOf(".apk", ".dex", ".jar", ".so", ".wasm")
        val SETTING_TYPES = setOf("string", "boolean", "select", "secret")
    }
}
