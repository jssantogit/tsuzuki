package tachiyomi.core.provider.runtime

import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.moduleLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.provider.packageformat.ParsedProviderPackage

enum class ProviderPackageFailure {
    UNDECLARED_CAPABILITY,
    MISSING_CAPABILITY_EXPORT,
    MODULE_ERROR,
    MALFORMED_INPUT,
    MALFORMED_RESULT,
    TIMEOUT,
    HOST_ERROR,
}

sealed interface ProviderPackageExecution {
    data class Success(val json: String) : ProviderPackageExecution

    data class Failure(val reason: ProviderPackageFailure) : ProviderPackageExecution
}

sealed interface ProviderPackageContract {
    data object Valid : ProviderPackageContract

    data class Invalid(val reason: ProviderPackageFailure) : ProviderPackageContract
}

class ProviderScriptPackageRuntime(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val limits: ProviderRuntimeLimits = ProviderRuntimeLimits(),
) {

    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    suspend fun validateContract(
        providerPackage: ParsedProviderPackage,
    ): ProviderPackageContract {
        val declared = providerPackage.manifest.capabilities
        if (declared.isEmpty()) return ProviderPackageContract.Valid

        val source = buildContractValidationModule(
            entrypoint = providerPackage.manifest.entrypoint,
            capabilityIds = declared.map { it.id },
        )

        return when (
            val result = evaluateModule(
                providerPackage = providerPackage,
                source = source,
                resultMode = ResultMode.CONTRACT,
                hostServices = ProviderHostServices(),
            )
        ) {
            is ModuleEvaluation.Success -> ProviderPackageContract.Valid
            is ModuleEvaluation.Failure -> ProviderPackageContract.Invalid(result.reason)
        }
    }

    suspend fun invoke(
        providerPackage: ParsedProviderPackage,
        capabilityId: String,
        capabilityVersion: Int,
        inputJson: String,
        hostServices: ProviderHostServices = ProviderHostServices(),
    ): ProviderPackageExecution {
        val declared = providerPackage.manifest.capabilities.singleOrNull {
            it.id == capabilityId && it.version == capabilityVersion
        } ?: return ProviderPackageExecution.Failure(ProviderPackageFailure.UNDECLARED_CAPABILITY)

        if (!isValidJson(inputJson)) {
            return ProviderPackageExecution.Failure(ProviderPackageFailure.MALFORMED_INPUT)
        }

        val source = buildInvocationModule(
            entrypoint = providerPackage.manifest.entrypoint,
            capabilityId = declared.id,
            inputJson = inputJson,
        )

        return when (
            val result = evaluateModule(
                providerPackage = providerPackage,
                source = source,
                resultMode = ResultMode.INVOCATION,
                hostServices = hostServices,
            )
        ) {
            is ModuleEvaluation.Success -> {
                val value = result.value
                    ?: return ProviderPackageExecution.Failure(ProviderPackageFailure.MALFORMED_RESULT)
                if (!isValidJson(value)) {
                    ProviderPackageExecution.Failure(ProviderPackageFailure.MALFORMED_RESULT)
                } else {
                    ProviderPackageExecution.Success(value)
                }
            }
            is ModuleEvaluation.Failure -> ProviderPackageExecution.Failure(result.reason)
        }
    }

    private suspend fun evaluateModule(
        providerPackage: ParsedProviderPackage,
        source: String,
        resultMode: ResultMode,
        hostServices: ProviderHostServices,
    ): ModuleEvaluation {
        val moduleSources = providerPackage.entries
            .asSequence()
            .filter { (path, _) -> path.endsWith(".js") || path.endsWith(".mjs") }
            .associate { (path, bytes) -> path to bytes.decodeToString() }

        val loader = moduleLoader {
            normalize { baseName, requestedName ->
                normalizePackageModule(
                    baseName = baseName,
                    requestedName = requestedName,
                )
            }
            load { name ->
                moduleSources[name]?.let(ModuleContent::Source)
            }
        }

        val runtime = QuickJs.create(
            jobDispatcher = dispatcher,
            moduleLoader = loader,
        )
        val hostFailures = ProviderHostFailureTracker()
        var captured: String? = null

        return try {
            runtime.memoryLimit = limits.memoryLimitBytes
            runtime.maxStackSize = limits.stackLimitBytes
            runtime.evaluationTimeoutMillis = limits.jsExecutionTimeoutMs
            runtime.installHostServices(hostServices, hostFailures)
            runtime.function("__tsuzukiReturn") { args ->
                captured = args.firstOrNull() as? String
            }

            withTimeout(limits.wallClockTimeoutMs) {
                runtime.evaluate<Any?>(
                    code = source,
                    filename = SYNTHETIC_ENTRYPOINT,
                    asModule = true,
                )
            }

            if (resultMode == ResultMode.CONTRACT) {
                ModuleEvaluation.Success(CONTRACT_OK)
            } else if (captured != null) {
                ModuleEvaluation.Success(captured)
            } else {
                ModuleEvaluation.Failure(ProviderPackageFailure.MALFORMED_RESULT)
            }
        } catch (_: QuickJsInterruptedException) {
            ModuleEvaluation.Failure(ProviderPackageFailure.TIMEOUT)
        } catch (_: TimeoutCancellationException) {
            runtime.interruptEvaluation()
            ModuleEvaluation.Failure(ProviderPackageFailure.TIMEOUT)
        } catch (error: QuickJsException) {
            when {
                hostFailures.failed ->
                    ModuleEvaluation.Failure(ProviderPackageFailure.HOST_ERROR)
                error.message?.contains(MISSING_EXPORT_MARKER) == true ->
                    ModuleEvaluation.Failure(ProviderPackageFailure.MISSING_CAPABILITY_EXPORT)
                error.message?.contains(MALFORMED_RESULT_MARKER) == true ->
                    ModuleEvaluation.Failure(ProviderPackageFailure.MALFORMED_RESULT)
                else ->
                    ModuleEvaluation.Failure(ProviderPackageFailure.MODULE_ERROR)
            }
        } catch (error: CancellationException) {
            runtime.interruptEvaluation()
            throw error
        } catch (_: ProviderModuleResolutionException) {
            ModuleEvaluation.Failure(ProviderPackageFailure.MODULE_ERROR)
        } catch (_: Throwable) {
            ModuleEvaluation.Failure(
                if (hostFailures.failed) {
                    ProviderPackageFailure.HOST_ERROR
                } else {
                    ProviderPackageFailure.MODULE_ERROR
                },
            )
        } finally {
            runtime.close()
        }
    }

    private fun buildContractValidationModule(
        entrypoint: String,
        capabilityIds: List<String>,
    ): String {
        val importPath = json.encodeToString("./$entrypoint")
        val capabilityPaths = json.encodeToString(
            capabilityIds.map { it.split('.') },
        )
        return """
            import provider from $importPath;
            const capabilityPaths = $capabilityPaths;
            for (const path of capabilityPaths) {
              let current = provider;
              for (const segment of path) {
                current = current?.[segment];
              }
              if (typeof current !== "function") {
                throw new Error("$MISSING_EXPORT_MARKER");
              }
            }
            __tsuzukiReturn("$CONTRACT_OK");
        """.trimIndent()
    }

    private fun buildInvocationModule(
        entrypoint: String,
        capabilityId: String,
        inputJson: String,
    ): String {
        val importPath = json.encodeToString("./$entrypoint")
        val capabilityPath = json.encodeToString(capabilityId.split('.'))
        val encodedInput = json.encodeToString(inputJson)
        return """
            import provider from $importPath;
            const path = $capabilityPath;
            let handler = provider;
            for (const segment of path) {
              handler = handler?.[segment];
            }
            if (typeof handler !== "function") {
              throw new Error("$MISSING_EXPORT_MARKER");
            }
            const input = JSON.parse($encodedInput);
            const result = await handler(input);
            const resultJson = JSON.stringify(result);
            if (typeof resultJson !== "string") {
              throw new Error("$MALFORMED_RESULT_MARKER");
            }
            __tsuzukiReturn(resultJson);
        """.trimIndent()
    }

    private fun normalizePackageModule(
        baseName: String,
        requestedName: String,
    ): String {
        if (
            requestedName.isBlank() ||
            requestedName.startsWith("/") ||
            requestedName.startsWith("\\") ||
            '\\' in requestedName ||
            ':' in requestedName
        ) {
            throw ProviderModuleResolutionException()
        }

        val baseDirectory = baseName
            .substringBeforeLast('/', missingDelimiterValue = "")
            .split('/')
            .filter(String::isNotEmpty)
            .toMutableList()

        val requestedSegments = requestedName.split('/')
        val resolved = if (requestedName.startsWith(".")) {
            baseDirectory
        } else {
            mutableListOf()
        }

        requestedSegments.forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (resolved.isEmpty()) {
                        throw ProviderModuleResolutionException()
                    }
                    resolved.removeAt(resolved.lastIndex)
                }
                else -> resolved += segment
            }
        }

        if (resolved.isEmpty()) {
            throw ProviderModuleResolutionException()
        }

        val normalized = resolved.joinToString("/")
        if (!normalized.endsWith(".js") && !normalized.endsWith(".mjs")) {
            throw ProviderModuleResolutionException()
        }
        return normalized
    }

    private fun isValidJson(value: String): Boolean =
        runCatching { json.parseToJsonElement(value) }.isSuccess

    private sealed interface ModuleEvaluation {
        data class Success(val value: String?) : ModuleEvaluation

        data class Failure(val reason: ProviderPackageFailure) : ModuleEvaluation
    }

    private enum class ResultMode {
        CONTRACT,
        INVOCATION,
    }

    private class ProviderModuleResolutionException : IllegalArgumentException()

    private companion object {
        const val SYNTHETIC_ENTRYPOINT = "__tsuzuki_runtime__.js"
        const val CONTRACT_OK = "ok"
        const val MISSING_EXPORT_MARKER = "__TSUZUKI_MISSING_CAPABILITY_EXPORT__"
        const val MALFORMED_RESULT_MARKER = "__TSUZUKI_MALFORMED_RESULT__"
    }
}
