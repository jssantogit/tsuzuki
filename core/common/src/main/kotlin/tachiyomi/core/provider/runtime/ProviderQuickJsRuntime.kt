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
        return try {
            runtime.memoryLimit = limits.memoryLimitBytes
            runtime.maxStackSize = limits.stackLimitBytes
            runtime.evaluationTimeoutMillis = limits.jsExecutionTimeoutMs
            runtime.installHostServices(hostServices)

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
            ProviderScriptExecution.Failure(ProviderScriptFailure.SCRIPT_ERROR)
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

private fun QuickJs.installHostServices(services: ProviderHostServices) {
    define("tsuzuki") {
        services.http?.let { http ->
            define("http") {
                asyncFunction("get") { args ->
                    http.getText(args.stringArgument(0))
                }
            }
        }
        services.dom?.let { dom ->
            define("dom") {
                asyncFunction("selectText") { args ->
                    dom.selectText(
                        resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                        cssSelector = args.stringArgument(1),
                    )
                }
            }
        }
        services.browser?.let { browser ->
            define("browser") {
                asyncFunction("readText") { args ->
                    browser.readText(
                        url = args.stringArgument(0),
                        cssSelector = args.stringArgument(1),
                    )
                }
            }
        }
        services.storage?.let { storage ->
            define("storage") {
                asyncFunction("get") { args ->
                    storage.get(args.stringArgument(0))
                }
                asyncFunction("set") { args ->
                    storage.set(
                        key = args.stringArgument(0),
                        value = args.stringArgument(1),
                    )
                    true
                }
                asyncFunction("remove") { args ->
                    storage.remove(args.stringArgument(0))
                    true
                }
            }
        }
        services.secrets?.let { secrets ->
            define("secrets") {
                asyncFunction("get") { args ->
                    secrets.get(args.stringArgument(0))
                }
            }
        }
        services.binary?.let { binary ->
            define("binary") {
                asyncFunction("fetch") { args ->
                    binary.fetch(args.stringArgument(0)).value
                }
                asyncFunction("zipEntry") { args ->
                    binary.zipEntry(
                        resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                        entryName = args.stringArgument(1),
                    ).value
                }
            }
        }
        services.crypto?.let { crypto ->
            define("crypto") {
                asyncFunction("aesCbcDecrypt") { args ->
                    crypto.aesCbcDecrypt(
                        resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                        keyHex = args.stringArgument(1),
                        ivHex = args.stringArgument(2),
                    ).value
                }
            }
        }
        services.image?.let { image ->
            define("image") {
                asyncFunction("crop") { args ->
                    image.crop(
                        resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                        x = args.intArgument(1),
                        y = args.intArgument(2),
                        width = args.intArgument(3),
                        height = args.intArgument(4),
                    ).value
                }
                asyncFunction("pixel") { args ->
                    image.pixel(
                        resourceHandle = ProviderResourceHandle(args.stringArgument(0)),
                        x = args.intArgument(1),
                        y = args.intArgument(2),
                    )
                }
            }
        }
        services.log?.let { log ->
            define("log") {
                asyncFunction("info") { args ->
                    log.info(args.stringArgument(0))
                    true
                }
            }
        }
    }
}

private fun Array<Any?>.stringArgument(index: Int): String =
    getOrNull(index)?.toString()
        ?: throw IllegalArgumentException("Provider host argument $index is required")

private fun Array<Any?>.intArgument(index: Int): Int =
    (getOrNull(index) as? Number)?.toInt()
        ?: getOrNull(index)?.toString()?.toIntOrNull()
        ?: throw IllegalArgumentException("Provider host argument $index must be an integer")
