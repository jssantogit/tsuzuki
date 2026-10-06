package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.CanonicalReaderTargetPlan
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ProviderHttpPageException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class ProviderHttpPageLoader(
    private val requests: List<PreparedHttpPage>,
    cacheRoot: File,
    client: OkHttpClient = OkHttpClient(),
    private val maxImageBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
) : PageLoader() {

    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val directory = File(
        cacheRoot,
        "provider-reader-${UUID.randomUUID()}",
    ).apply {
        check(mkdirs() || isDirectory) { "Unable to create Provider Reader cache directory" }
    }
    private val pages = requests.mapIndexed { index, request ->
        ReaderPage(
            index = index,
            url = request.url,
            imageUrl = request.url,
        )
    }

    init {
        require(requests.isNotEmpty()) { "Provider Reader requires at least one page" }
        require(maxImageBytes > 0L) { "Provider Reader image limit must be positive" }
    }

    override var isLocal: Boolean = false

    override suspend fun getPages(): List<ReaderPage> = pages

    override suspend fun loadPage(page: ReaderPage) {
        if (isRecycled) {
            throw ProviderHttpPageException("Provider Reader loader has been recycled")
        }
        val request = requests.getOrNull(page.index)
            ?: throw ProviderHttpPageException("Provider Reader page index is invalid")
        val target = pageFile(page.index)

        if (page.status == Page.State.Ready && target.isFile) {
            page.stream = { target.inputStream() }
            return
        }

        page.status = Page.State.DownloadImage
        try {
            download(
                request = request,
                target = target,
            )
            if (isRecycled) {
                target.delete()
                throw ProviderHttpPageException("Provider Reader loader was recycled during download")
            }
            page.stream = { target.inputStream() }
            page.status = Page.State.Ready
        } catch (error: CancellationException) {
            target.delete()
            throw error
        } catch (error: Throwable) {
            target.delete()
            val failure = if (error is ProviderHttpPageException) {
                error
            } else {
                ProviderHttpPageException("Provider Reader page download failed", error)
            }
            page.status = Page.State.Error(failure)
            throw failure
        }
    }

    override fun retryPage(page: ReaderPage) {
        if (isRecycled) return
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        scope.launch {
            runCatching { loadPage(page) }
        }
    }

    override fun recycle() {
        if (isRecycled) return
        super.recycle()
        activeCalls.forEach(Call::cancel)
        activeCalls.clear()
        scope.cancel()
        directory.deleteRecursively()
    }

    private suspend fun download(
        request: PreparedHttpPage,
        target: File,
    ) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val policy = ProviderNetworkPolicy(
                allowedOrigins = request.allowedOrigins,
                allowLocalNetwork = request.allowLocalNetwork,
            )
            policy.validate(request.url, resolveAddress = false)
            val requestClient = client.newBuilder()
                .dns { hostname -> policy.resolvePublicAddresses(hostname) }
                .build()
            val httpRequest = Request.Builder()
                .url(request.url)
                .apply {
                    request.headers.forEach { (name, value) ->
                        header(name, value)
                    }
                }
                .get()
                .build()
            val call = requestClient.newCall(httpRequest)
            activeCalls += call

            continuation.invokeOnCancellation {
                call.cancel()
                target.delete()
                activeCalls.remove(call)
            }

            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        activeCalls.remove(call)
                        target.delete()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                ProviderHttpPageException("Provider Reader HTTP request failed", e),
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        activeCalls.remove(call)
                        try {
                            response.use { value ->
                                if (!value.isSuccessful) {
                                    throw ProviderHttpPageException(
                                        "Provider Reader HTTP response was not successful",
                                    )
                                }
                                val declaredLength = value.body.contentLength()
                                if (declaredLength > maxImageBytes) {
                                    throw ProviderHttpPageException(
                                        "Provider Reader page exceeds the image byte limit",
                                    )
                                }

                                value.body.byteStream().use { input ->
                                    FileOutputStream(target).use { output ->
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                        var total = 0L
                                        while (true) {
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            if (read == 0) continue
                                            total += read
                                            if (total > maxImageBytes) {
                                                throw ProviderHttpPageException(
                                                    "Provider Reader page exceeds the image byte limit",
                                                )
                                            }
                                            output.write(buffer, 0, read)
                                        }
                                        output.flush()
                                        output.fd.sync()
                                    }
                                }
                            }

                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            } else {
                                target.delete()
                            }
                        } catch (error: Throwable) {
                            target.delete()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    if (error is ProviderHttpPageException) {
                                        error
                                    } else {
                                        ProviderHttpPageException(
                                            "Provider Reader page download failed",
                                            error,
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    private fun pageFile(index: Int): File =
        File(directory, "page-$index.bin")

    private companion object {
        const val DEFAULT_MAX_IMAGE_BYTES = 16L * 1024L * 1024L
    }
}

internal class ProviderHttpChapterLoader(
    private val pageLoader: ProviderHttpPageLoader,
) : ReaderChapterLoader {

    override suspend fun loadChapter(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null) return

        chapter.state = ReaderChapter.State.Loading
        try {
            chapter.pageLoader = pageLoader
            val pages = pageLoader.getPages()
                .onEach { it.chapter = chapter }
            check(pages.isNotEmpty()) { "Provider Reader page list is empty" }

            if (!chapter.chapter.read) {
                chapter.requestedPage = chapter.chapter.last_page_read
            }
            chapter.state = ReaderChapter.State.Loaded(pages)
        } catch (error: Throwable) {
            chapter.state = ReaderChapter.State.Error(error)
            throw error
        }
    }

    companion object {
        fun from(
            context: Context,
            plan: CanonicalReaderTargetPlan.HttpPages,
        ): ProviderHttpChapterLoader =
            ProviderHttpChapterLoader(
                ProviderHttpPageLoader(
                    requests = plan.pages,
                    cacheRoot = File(context.cacheDir, "provider-reader"),
                ),
            )
    }
}
