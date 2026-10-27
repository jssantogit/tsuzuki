package eu.kanade.tachiyomi.data.tsuzuki.inventory

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaimKind
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryDerivation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SelectedEpub3ArchiveImportTest {
    @Test
    fun `a selected EPUB3 archive yields only local edition candidates and never canonical chapters`() {
        val epub = packageArchive()
        val result = SelectedEpub3ArchiveImport.read(
            ByteArrayInputStream(epub),
            tempDir(),
            "canonical-book",
            42L,
        ).getOrThrow()

        result.scope.canonicalTitleId shouldBe "canonical-book"
        result.scope.producerId shouldBe "epub3-user-selected"
        result.scope.language shouldBe "ja"
        result.scope.workIdentityVerified shouldBe false
        result.scope.editionIdentityVerified shouldBe false
        result.claims.map { it.label } shouldBe listOf("Chapter 1", "Chapter 2", "Preface")
        result.claims.map { it.kind } shouldBe listOf(
            InventoryClaimKind.INDIVIDUAL,
            InventoryClaimKind.INDIVIDUAL,
            InventoryClaimKind.AMBIGUOUS,
        )
        result.claims.map { it.derivation }.toSet() shouldBe setOf(InventoryDerivation.USER_SUPPLIED_EDITION_TOC)
        result.claims.all { it.provenance?.originReference?.startsWith("user-selected:") == true } shouldBe true
        result.claims.all { it.provenance?.originReference?.contains("/") == false } shouldBe true
        val identical = SelectedEpub3ArchiveImport.read(
            ByteArrayInputStream(epub),
            tempDir(),
            "canonical-book",
            42L,
        ).getOrThrow()
        result.scope.editionKey shouldBe identical.scope.editionKey
        result.claims.map { it.sourceRecordId } shouldBe identical.claims.map { it.sourceRecordId }
    }

    @Test
    fun `refuse unsafe package paths before opening referenced entries`() {
        for (unsafe in listOf("../outside.opf", "/absolute.opf", "https://invalid/x.opf", "OPS/../bad.opf")) {
            SelectedEpub3ArchiveImport.read(
                ByteArrayInputStream(packageArchive(rootfile = unsafe)),
                tempDir(),
                "canonical-book",
                42L,
            ).isFailure shouldBe true
        }
    }

    @Test
    fun `reject files without an EPUB3 nav and XML with external entities`() {
        val unsafeOpf = """<!DOCTYPE package [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:language>ja</dc:language></metadata>
              <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest>
            </package>
        """.trimIndent()
        SelectedEpub3ArchiveImport.read(
            ByteArrayInputStream(packageArchive(opf = unsafeOpf)),
            tempDir(),
            "canonical-book",
            42L,
        ).isFailure shouldBe true
        SelectedEpub3ArchiveImport.read(
            ByteArrayInputStream(packageArchive(navPath = "missing.xhtml")),
            tempDir(),
            "canonical-book",
            42L,
        ).isFailure shouldBe true
    }

    @Test
    fun `reject XInclude markup rather than relying on unsupported Android parser flags`() {
        val xinclude = """
            <package xmlns="http://www.idpf.org/2007/opf"
                xmlns:xi="http://www.w3.org/2001/XInclude" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:language>ja</dc:language></metadata>
              <xi:include href="file:///etc/passwd" parse="text"/>
              <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest>
            </package>
        """.trimIndent()
        SelectedEpub3ArchiveImport.read(
            ByteArrayInputStream(packageArchive(opf = xinclude)),
            tempDir(),
            "canonical-book",
            42L,
        ).isFailure shouldBe true
    }

    @Test
    fun `reject oversized nav even when its zip member compresses efficiently`() {
        val nav = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body><nav epub:type="toc"><ol><li><a href="ch.xhtml">Chapter 1</a></li></ol></nav>
          <!-- ${"x".repeat(600_000)} --></body></html>"""
        SelectedEpub3ArchiveImport.read(
            ByteArrayInputStream(packageArchive(nav = nav)),
            tempDir(),
            "canonical-book",
            42L,
        ).isFailure shouldBe true
    }

    @Test
    fun `EPUB2 NCX with chapter anchors yields review-only candidates and preserves source format`() {
        val source = epub2Archive()
        val result = SelectedEpub3ArchiveImport.read(
            ByteArrayInputStream(source),
            tempDir(),
            "canonical-book",
            42L,
        ).getOrThrow()

        result.scope.producerId shouldBe "epub2-user-selected"
        result.scope.language shouldBe "en"
        result.scope.workIdentityVerified shouldBe false
        result.scope.editionIdentityVerified shouldBe false
        result.claims.map { it.label } shouldBe listOf("Chapter 0", "Chapter 1")
        result.claims.map { it.kind } shouldBe listOf(InventoryClaimKind.INDIVIDUAL, InventoryClaimKind.INDIVIDUAL)
        result.claims.map { it.provenance?.sourceKind?.name } shouldBe
            listOf("USER_SELECTED_EPUB2_NCX", "USER_SELECTED_EPUB2_NCX")
        result.claims.all { it.derivation == InventoryDerivation.USER_SUPPLIED_EDITION_TOC } shouldBe true
        result.claims.all { it.provenance?.sourceUnitId?.startsWith("index.html#") == true } shouldBe true
    }

    @Test
    fun `EPUB2 NCX rejects external references DTD ambiguity and missing NCX`() {
        val unsafe = listOf(
            epub2Archive(ncx = ncx2("file:///etc/passwd")),
            epub2Archive(ncx = ncx2("../escaped.xhtml")),
            epub2Archive(ncx = ncx2("index.html#p2", "index.html#p2")),
            epub2Archive(ncx = """<!DOCTYPE ncx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                ${ncx2("index.html#p2")}"""),
            epub2Archive(opfNcxId = "absent"),
        )
        unsafe.forEach { archive ->
            SelectedEpub3ArchiveImport.read(
                ByteArrayInputStream(archive),
                tempDir(),
                "canonical-book",
                42L,
            ).isFailure shouldBe true
        }
    }

    private fun ncx2(first: String, second: String = "index.html#p56") = """
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
          <navMap>
            <navPoint id="one" playOrder="1"><navLabel><text>Example Cap. 00</text></navLabel>
              <content src="$first"/></navPoint>
            <navPoint id="two" playOrder="2"><navLabel><text>Example Cap. 01</text></navLabel>
              <content src="$second"/></navPoint>
          </navMap>
        </ncx>
    """.trimIndent()

    private fun epub2Archive(
        ncx: String = ncx2("index.html#p2"),
        opfNcxId: String = "ncx",
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun item(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            item("mimetype", "application/epub+zip")
            item(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                   <rootfiles><rootfile full-path="content.opf"
                    media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            item(
                "content.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:language>en</dc:language><dc:title>Untitled</dc:title></metadata>
                  <manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest>
                  <spine toc="$opfNcxId"/></package>""",
            )
            item("toc.ncx", ncx)
        }
        return bytes.toByteArray()
    }

    private fun tempDir() = java.nio.file.Files.createTempDirectory("tsuzuki-selected-epub-test").toFile()
        .apply { deleteOnExit() }

    private fun packageArchive(
        rootfile: String = "OPS/package.opf",
        navPath: String = "nav.xhtml",
        opf: String = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:language>ja</dc:language></metadata>
          <manifest><item id="nav" href="$navPath" media-type="application/xhtml+xml" properties="nav"/></manifest>
        </package>""",
        nav: String = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body><nav epub:type="toc"><ol>
          <li><a href="Text/ch01.xhtml">Chapter 1</a></li>
          <li><a href="Text/ch02.xhtml">Chapter 2</a></li>
          <li><a href="Text/preface.xhtml">Preface</a></li>
          </ol></nav></body></html>""",
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun item(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            item("mimetype", "application/epub+zip")
            item(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="$rootfile" media-type="application/oebps-package+xml"/></rootfiles>
                  </container>""",
            )
            item("OPS/package.opf", opf)
            item("OPS/nav.xhtml", nav)
        }
        return bytes.toByteArray()
    }
}
