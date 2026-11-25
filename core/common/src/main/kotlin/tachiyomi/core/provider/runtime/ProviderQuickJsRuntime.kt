package tachiyomi.core.provider.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.define
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

data class ProviderRuntimeLimits(
    val wallClockTimeoutMs: Long = 5_000L,
    val jsExecutionTimeoutMs: Long = 3_000L,
    val memoryLimitBytes: Long = 32L * 1024L * 1024L,
    val stackLimitBytes: Long = 512L * 1024L,
) {
    init {
        require(wallClockTimeoutMs > 0L) { "Provider wall-clock timeout must be positive" }
        require(jsExecutionTimeoutMs > 0L) { "Provider JavaScript timeout must be positive" }
        require(memoryLimitBytes > 0L) { "Provider memory limit must be positive" }
        require(stackLimitBytes > 0L) { "Provider stack limit must be positive" }
    }
}

enum class ProviderScriptFailure {
    TIMEOUT,
    SCRIPT_ERROR,
    HOST_ERROR,
}

sealed interface ProviderScriptExecution {
    data class Success(val value: String?) : ProviderScriptExecution

    data class Failure(val reason: ProviderScriptFailure) : ProviderScriptExecution
}

class ProviderQuickJsRuntime(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val limits: ProviderRuntimeLimits = ProviderRuntimeLimits(),
) {

    suspend fun evaluate(
        source: String,
        fileName: String = "provider.js",
        hostServices: ProviderHostServices = ProviderHostServices(),
    ): ProviderScriptExecution {
        val runtime = QuickJs.create(dispatcher)
        val hostFailures = ProviderHostFailureTracker()
        return try {
            runtime.memoryLimit = limits.memoryLimitBytes
            runtime.maxStackSize = limits.stackLimitBytes
            runtime.evaluationTimeoutMillis = limits.jsExecutionTimeoutMs
            // This raw evaluate() path has NO verified Provider package or
            // manifest to authorize the future MangaALL snapshot privilege.
            // Only ProviderScriptPackageRuntime.invoke may expose it after
            // independently checking the exact code11 manifest grant.
            // Never infer authorization from an injected native Host object.
            runtime.installHostServices(
                hostServices.copy(mangaAllChapterSnapshot = null),
                hostFailures,
            )

            val value = withTimeout(limits.wallClockTimeoutMs) {
                runtime.evaluate<Any?>(
                    code = source,
                    filename = fileName,
                    asModule = false,
                )
            }
            ProviderScriptExecution.Success(value?.toString())
        } catch (_: QuickJsInterruptedException) {
            ProviderScriptExecution.Failure(ProviderScriptFailure.TIMEOUT)
        } catch (_: TimeoutCancellationException) {
            runtime.interruptEvaluation()
            ProviderScriptExecution.Failure(ProviderScriptFailure.TIMEOUT)
        } catch (_: QuickJsException) {
            ProviderScriptExecution.Failure(
                if (hostFailures.failed) ProviderScriptFailure.HOST_ERROR else ProviderScriptFailure.SCRIPT_ERROR,
            )
        } catch (error: CancellationException) {
            runtime.interruptEvaluation()
            throw error
        } catch (_: Throwable) {
            ProviderScriptExecution.Failure(ProviderScriptFailure.HOST_ERROR)
        } finally {
            runtime.close()
        }
    }
}

internal fun QuickJs.installHostServices(
    services: ProviderHostServices,
    hostFailures: ProviderHostFailureTracker,
) {
    define("tsuzuki") {
        // Pure bounded extraction operates only on data already held by this VM.
        // It does not grant network, filesystem, browser or resource-handle access.
        define("protocol") {
            asyncFunction("jsonPointer") { args ->
                hostFailures.call {
                    ProviderAdvancedExtraction.jsonPointer(args.stringArgument(0), args.stringArgument(1))
                }
            }
            asyncFunction("protobufFields") { args ->
                hostFailures.call { ProviderAdvancedExtraction.protobufFields(args.stringArgument(0)) }
            }
        }
        define("document") {
            asyncFunction("query") { args ->
                hostFailures.call {
                    ProviderAdvancedExtraction.queryDocument(
                        args.stringArgument(0),
                        args.stringArgument(1),
                        args.stringArgument(2),
                        args.optionalStringArgument(3),
                    )
                }
            }
        }
        services.http?.let { http ->
            define("http") {
                asyncFunction("get") { args ->
                    hostFailures.call { http.getText(args.stringArgument(0)) }
                }
                asyncFunction("getResource") { args ->
                    hostFailures.call { http.getResource(args.stringArgument(0)).value }
                }
                asyncFunction("request") { args ->
                    hostFailures.call {
                        val method = try {
                            ProviderHttpMethod.valueOf(args.stringArgument(0).uppercase())
                        } catch (error: IllegalArgumentException) {
                            throw ProviderHostServiceException(
                                "Provider HTTP method is unsupported",
                                error,
                            )
                        }
                        val headers = decodeHttpHeaders(args.stringArgument(2))
                        ProviderHttpProtocol.encodeResponse(
                            http.request(
                                ProviderHttpRequest(
                                    method = method,
                                    url = args.stringArgument(1),
                                    headers = headers,
                                    body = args.optionalStringArgument(3),
                                ),
                            ),
                        )
                    }
                }
            }
        }
        services.p2p?.let { p2p ->
            define("p2p") {
                asyncFunction("acquire") { args ->
                    hostFailures.call {
                        ProviderP2pProtocol.encodeResponse(
                            p2p.acquire(
                                ProviderP2pProtocol.decodeRequest(
                                    args.stringArgument(0),
                                ),
                            ),
                        )
                    }
                }
            }
        }
        services.dom?.let { dom ->
            define("dom") {
                asyncFunction("selectText") { args ->
                    hostFailures.call {
                        dom.selectText(
                            resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                            cssSelector = args.stringArgument(1),
                        )
                    }
                }
            }
        }
        services.browser?.let { browser ->
            define("browser") {
                asyncFunction("readText") { args ->
                    hostFailures.call {
                        browser.readText(
                            url = args.stringArgument(0),
                            cssSelector = args.stringArgument(1),
                        )
                    }
                }
            }
        }
        services.mangaAllChapterSnapshot?.let { snapshot ->
            define("mangaAllChapterSnapshot") {
                asyncFunction("start") { args ->
                    hostFailures.call {
                        boundedMangaAllChapterSnapshotResult(
                            snapshot.start(args.stringArgument(0), args.stringArgument(1)),
                        )
                    }
                }
                asyncFunction("next") { args ->
                    hostFailures.call {
                        boundedMangaAllChapterSnapshotResult(
                            snapshot.next(
                                args.stringArgument(0),
                                args.stringArgument(1),
                                args.stringArgument(2),
                            ),
                        )
                    }
                }
            }
        }
        services.storage?.let { storage ->
            define("storage") {
                asyncFunction("get") { args ->
                    hostFailures.call { storage.get(args.stringArgument(0)) }
                }
                asyncFunction("set") { args ->
                    hostFailures.call {
                        storage.set(
                            key = args.stringArgument(0),
                            value = args.stringArgument(1),
                        )
                    }
                    true
                }
                asyncFunction("remove") { args ->
                    hostFailures.call { storage.remove(args.stringArgument(0)) }
                    true
                }
            }
        }
        services.secrets?.let { secrets ->
            define("secrets") {
                asyncFunction("get") { args ->
                    hostFailures.call { secrets.get(args.stringArgument(0)) }
                }
            }
        }
        services.binary?.let { binary ->
            define("binary") {
                asyncFunction("inspect") { args ->
                    hostFailures.call {
                        binary.inspect(
                            ProviderResourceHandle(args.stringArgument(0)),
                            args.stringArgument(1),
                            args.stringArgument(2),
                        )
                    }
                }
                asyncFunction("transform") { args ->
                    hostFailures.call {
                        binary.transform(ProviderResourceHandle(args.stringArgument(0)), args.stringArgument(1)).value
                    }
                }
                asyncFunction("fetch") { args ->
                    hostFailures.call { binary.fetch(args.stringArgument(0)).value }
                }
                asyncFunction("zipEntry") { args ->
                    hostFailures.call {
                        binary.zipEntry(
                            resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                            entryName = args.stringArgument(1),
                        ).value
                    }
                }
                asyncFunction("promote") { args ->
                    hostFailures.call {
                        binary.promote(
                            resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                            format = ProviderManagedResourceFormat.valueOf(
                                args.stringArgument(1).uppercase(),
                            ),
                        )
                    }
                }
            }
        }
        services.crypto?.let { crypto ->
            define("crypto") {
                asyncFunction("aesCbcDecrypt") { args ->
                    hostFailures.call {
                        crypto.aesCbcDecrypt(
                            resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                            keyHex = args.stringArgument(1),
                            ivHex = args.stringArgument(2),
                        ).value
                    }
                }
            }
        }
        services.image?.let { image ->
            define("image") {
                asyncFunction("compose") { args ->
                    hostFailures.call {
                        image.compose(ProviderResourceHandle(args.stringArgument(0)), args.stringArgument(1)).value
                    }
                }
                asyncFunction("crop") { args ->
                    hostFailures.call {
                        image.crop(
                            resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                            x = args.intArgument(1),
                            y = args.intArgument(2),
                            width = args.intArgument(3),
                            height = args.intArgument(4),
                        ).value
                    }
                }
                asyncFunction("pixel") { args ->
                    hostFailures.call {
                        image.pixel(
                            resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                            x = args.intArgument(1),
                            y = args.intArgument(2),
                        )
                    }
                }
            }
        }
        services.log?.let { log ->
            define("log") {
                asyncFunction("info") { args ->
                    hostFailures.call { log.info(args.stringArgument(0)) }
                    true
                }
            }
        }
    }
}

internal class ProviderHostFailureTracker {
    private val didFail = AtomicBoolean(false)

    val failed: Boolean
        get() = didFail.get()

    suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            didFail.set(true)
            throw error
        }
}

private fun Array<Any?>.stringArgument(index: Int): String =
    getOrNull(index)?.toString()
        ?: throw IllegalArgumentException("Provider host argument $index is required")

private fun Array<Any?>.intArgument(index: Int): Int =
    (getOrNull(index) as? Number)?.toInt()
        ?: getOrNull(index)?.toString()?.toIntOrNull()
        ?: throw IllegalArgumentException("Provider host argument $index must be an integer")

private fun Array<Any?>.optionalStringArgument(index: Int): String? =
    getOrNull(index)?.toString()

private fun boundedMangaAllChapterSnapshotResult(value: String): String {
    if (value.length > 48 * 1024 ||
        value.encodeToByteArray().size > 48 * 1024 ||
        !MangaAllFutureStrictJson.accepts(value)
    ) {
        throw ProviderHostServiceException("Future MangaALL chapter output violates the Host boundary")
    }
    // The service returns a chapter page, never an arbitrary JSON primitive.
    // Validate both the strict lexical grammar and the exact outer envelope.
    val page = runCatching { HOST_JSON.parseToJsonElement(value) }.getOrNull() as? JsonObject
    val next = page?.get("nextCursor")
    if (page?.keys != setOf("items", "nextCursor") ||
        page?.get("items") !is JsonArray ||
        (next != JsonNull && (next !is JsonPrimitive || !next.isString))
    ) {
        throw ProviderHostServiceException("Future MangaALL chapter page is malformed")
    }
    return value
}

private fun decodeHttpHeaders(value: String): Map<String, String> {
    if (value.length > ProviderHttpProtocol.MAX_REQUEST_JSON_CHARS) {
        throw ProviderHostServiceException("Provider HTTP headers exceed the size limit")
    }
    return try {
        HOST_JSON.decodeFromString(value)
    } catch (error: Exception) {
        throw ProviderHostServiceException("Provider HTTP headers are malformed", error)
    }
}

private val HOST_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}
