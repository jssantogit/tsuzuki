package eu.kanade.tachiyomi.data.tsuzuki.inventory

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.chapter.inventory.SelectedEpub3Navigation
import tachiyomi.domain.tsuzuki.chapter.inventory.SelectedEpub3TocAdapter
import tachiyomi.domain.tsuzuki.chapter.inventory.SelectedEpubXmlPreflight
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Host-owned, user-selected EPUB3 metadata import. No DRM handling, no page
 * extraction, no image reads, and no Provider/publisher authority.
 *
 * SAF streams are staged to a bounded temporary file for random access to
 * three small XML members. The temporary EPUB is deleted even on failure.
 */
internal object SelectedEpub3ArchiveImport {
    private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 8192
    private const val MAX_CONTAINER_BYTES = 64 * 1024
    private const val MAX_OPF_BYTES = 512 * 1024
    private const val OPF_NS = "http://www.idpf.org/2007/opf"
    private const val CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private val languagePattern = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}$")

    fun read(
        stream: InputStream,
        cacheDirectory: File,
        canonicalTitleId: String,
        observedAt: Long,
    ): Result<InventoryEvidenceBatch> = runCatching {
        require(canonicalTitleId.isNotBlank() && observedAt >= 0L)
        require(cacheDirectory.isDirectory)
        val staged = File.createTempFile("tsuzuki-edition-", ".epub", cacheDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            stream.use { input ->
                staged.outputStream().buffered().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_ARCHIVE_BYTES) { "Selected EPUB exceeds size limit" }
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            require(total > 0)
            val editionFingerprint = digest.digest().toHex()
            ZipFile(staged).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size in 1..MAX_ZIP_ENTRIES) { "EPUB archive has too many entries" }
                require(entries.map { it.name }.distinct().size == entries.size) {
                    "Duplicate EPUB member names are ambiguous"
                }
                require(entries.all { safeEntryPath(it.name, allowDirectory = it.isDirectory) }) {
                    "EPUB contains an unsafe archive path"
                }
                val mimetype = readMember(zip, "mimetype", 64).decodeStrict()
                require(mimetype == "application/epub+zip") { "Selected archive is not an EPUB" }
                val container = parseXml(readMember(zip, "META-INF/container.xml", MAX_CONTAINER_BYTES))
                val rootfiles = container.getElementsByTagNameNS(CONTAINER_NS, "rootfile")
                require(rootfiles.length == 1) { "EPUB must identify a single OPF" }
                val root = rootfiles.item(0) as Element
                require(root.getAttribute("media-type") == "application/oebps-package+xml")
                val opfPath = root.getAttribute("full-path")
                require(safeEntryPath(opfPath))
                val opf = parseXml(readMember(zip, opfPath, MAX_OPF_BYTES))
                val pkg = opf.documentElement
                require(pkg.namespaceURI == OPF_NS && pkg.localName == "package")
                require(pkg.getAttribute("version").startsWith("3.")) { "Only EPUB3 editions are supported" }
                val languages = opf.getElementsByTagNameNS(DC_NS, "language")
                require(languages.length == 1) { "EPUB language is unknown or ambiguous" }
                val language = languages.item(0).textContent.trim()
                require(languagePattern.matches(language))
                val items = opf.getElementsByTagNameNS(OPF_NS, "item")
                val navItems = (0 until items.length).map { items.item(it) as Element }.filter {
                    it.getAttribute("properties").split(Regex("\\s+")).contains("nav")
                }
                require(navItems.size == 1) { "An EPUB3 navigation manifest item is required" }
                val navItem = navItems.single()
                require(navItem.getAttribute("media-type") == "application/xhtml+xml")
                val navPath = resolveMemberPath(opfPath, navItem.getAttribute("href"))
                val navBytes = readMember(zip, navPath, SelectedEpub3TocAdapter.MAX_DOCUMENT_BYTES)
                val nav = navBytes.decodeStrict()
                val navDigest = "sha256:" + MessageDigest.getInstance("SHA-256").digest(navBytes).toHex()
                val editionKey = "epub3:" + editionFingerprint
                val selection = SelectedEpub3Navigation(
                    scope = InventoryScope(
                        canonicalTitleId = canonicalTitleId,
                        producerId = "epub3-user-selected",
                        externalWorkId = editionKey,
                        editionKey = editionKey,
                        language = language,
                        workIdentityVerified = false,
                        editionIdentityVerified = false,
                    ),
                    editionReference = "user-selected:epub-" + editionFingerprint,
                    snapshotRevision = editionFingerprint,
                    snapshotDigest = navDigest,
                    navXhtml = nav,
                    observedAt = observedAt,
                )
                SelectedEpub3TocAdapter.adapt(selection).getOrThrow()
            }
        } finally {
            staged.delete()
        }
    }

    private fun safeEntryPath(path: String, allowDirectory: Boolean = false): Boolean {
        if (path.isBlank() || path.length > 1024 || path.startsWith('/') || path.contains('\\')) return false
        if (path.contains('?') || path.contains('#') || path.contains(':') || path.any { it.code < 32 }) return false
        val pieces = path.removeSuffix("/").split("/")
        if (pieces.any { it.isEmpty() || it == "." || it == ".." }) return false
        return allowDirectory || !path.endsWith('/')
    }

    private fun resolveMemberPath(opfPath: String, href: String): String {
        require(safeEntryPath(href)) { "Unsafe EPUB navigation href" }
        val prefix = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val path = prefix + href
        require(safeEntryPath(path))
        return path
    }

    private fun readMember(zip: ZipFile, path: String, limit: Int): ByteArray {
        require(safeEntryPath(path))
        val entry: ZipEntry = zip.getEntry(path) ?: error("Required EPUB member is absent")
        require(!entry.isDirectory && entry.size <= limit && entry.size >= -1)
        zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(out.size() + count <= limit) { "EPUB member exceeds XML bound" }
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }

    private fun parseXml(bytes: ByteArray): Document {
        // Android's DocumentBuilderFactory does not implement the desktop JAXP
        // secure-processing or XInclude setters. Validate the exact bounded
        // UTF-8 text *before* the DOM parser receives it: markup entity
        // declarations require a DOCTYPE and are never allowed.
        val xml = bytes.decodeStrict()
        SelectedEpubXmlPreflight.rejectExternalDeclarations(xml)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parsed = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> error("External XML entities are not allowed") }
        }.parse(InputSource(StringReader(xml)))
        require(parsed.doctype == null) { "EPUB package XML cannot contain a DTD" }
        require(parsed.getElementsByTagNameNS("http://www.w3.org/2001/XInclude", "*").length == 0) {
            "EPUB package XML cannot include external XML content"
        }
        return parsed
    }

    private fun ByteArray.decodeStrict(): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this)).toString()

    private fun ByteArray.toHex() = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
