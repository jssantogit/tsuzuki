package tachiyomi.core.provider.packageformat

import tachiyomi.core.provider.supplychain.ProviderArtifactStore
import tachiyomi.core.provider.supplychain.VerifiedProviderArtifact

class ProviderPackageActivator(
    private val hostApiVersion: Int,
    private val parser: ProviderPackageParser,
    private val artifactStore: ProviderArtifactStore,
) {
    init {
        require(hostApiVersion > 0) { "Host API version must be positive" }
    }

    fun activate(artifact: VerifiedProviderArtifact): ParsedProviderPackage {
        val parsed = parser.parse(artifact.bytes)
        val manifest = parsed.manifest
        val descriptor = artifact.descriptor

        if (manifest.id != descriptor.providerId) {
            fail("Provider manifest identity does not match the signed repository descriptor")
        }
        if (
            manifest.version.name != descriptor.versionName ||
            manifest.version.code != descriptor.versionCode
        ) {
            fail("Provider manifest version does not match the signed repository descriptor")
        }
        if (manifest.minHostApi != descriptor.minHostApi) {
            fail("Provider manifest Host API requirement does not match the signed repository descriptor")
        }
        if (manifest.minHostApi > hostApiVersion) {
            fail("Provider package requires a newer Host API")
        }

        artifactStore.activate(artifact)
        return parsed
    }

    private fun fail(message: String): Nothing =
        throw ProviderPackageException(message)
}
