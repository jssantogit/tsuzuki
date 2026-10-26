package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalCore
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSection
import eu.kanade.tachiyomi.data.backup.models.BackupCoreChapter
import eu.kanade.tachiyomi.data.backup.models.BackupCoreTitle
import eu.kanade.tachiyomi.data.backup.models.BackupCoreUserAdoptionReceipt
import eu.kanade.tachiyomi.data.backup.restore.CanonicalCoreBackupImporter
import eu.kanade.tachiyomi.data.tsuzuki.inventory.SelectedEpub3ArchiveImport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.UserAdoptedInventoryRepositoryImpl
import tachiyomi.data.tsuzuki.chapter.IndependentInventoryRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.interactor.ChapterMutationGate
import tachiyomi.domain.tsuzuki.chapter.inventory.GetIndependentInventorySnapshot
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionCommitResult
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionConfirmation
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionPreview
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionReviewRequest
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opt-in offline emulator gate: production parsing + SQLDelight + adopted provenance + portable restore. */
@RunWith(AndroidJUnit4::class)
class SelectedEpubAdoptionInstrumentedTest {
    @Test(timeout = 180_000L)
    fun contentUriImportsShadowThenExplicitAdoptionRoundTripsPortableReceiptWithoutReaderPages() = runBlocking {
        val context = fixtureContext()
        val database = (context.applicationContext as App).graph.database
        val titleId = "ice-e2e-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        CanonicalTitleRepositoryImpl(database).insert(
            CanonicalTitle(
                id = titleId,
                displayTitle = "Offline EPUB adoption fixture",
                identityState = CanonicalIdentityState.SOURCE_ONLY,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val document = createDocument(epubBytes(), "ice-e2e-${UUID.randomUUID()}.epub")
        try {
            val ledger = IndependentInventoryRepositoryImpl(database)
            val chapters = CanonicalChapterRepositoryImpl(database)
            val adoption = UserAdoptedInventoryRepositoryImpl(database, ledger, chapters, ChapterMutationGate())
            val contentResolver = context.contentResolver
            val batch = SelectedEpub3ArchiveImport.read(
                requireNotNull(contentResolver.openInputStream(document)),
                context.cacheDir,
                titleId,
                now,
            ).getOrThrow()

            assertEquals("epub3-user-selected", batch.scope.producerId)
            assertFalse(batch.scope.workIdentityVerified)
            assertFalse(batch.scope.editionIdentityVerified)
            assertEquals(3, batch.claims.size)
            assertTrue(chapters.getByCanonicalTitleId(titleId).isEmpty())
            ledger.saveBatch(batch)
            // Idempotent import must not invent duplicate candidate rows.
            ledger.saveBatch(batch)
            assertEquals(3, ledger.byTitle(titleId).size)
            assertEquals(2, adoption.candidatePage(batch.scope).candidates.size)
            val snapshot = GetIndependentInventorySnapshot(ledger).execute(titleId)
            assertEquals(2, snapshot.sources.single().candidateObservations)

            val ids = batch.claims.take(2).map { it.sourceRecordId }
            val request = UserAdoptionReviewRequest(batch.scope, ids)
            val ready = adoption.preview(request) as UserAdoptionPreview.Ready
            assertEquals(
                UserAdoptionCommitResult.Refused,
                adoption.adopt(UserAdoptionConfirmation(request, ready.fingerprint, now + 1, false)),
            )
            assertTrue(chapters.getByCanonicalTitleId(titleId).isEmpty())
            val created = adoption.adopt(UserAdoptionConfirmation(request, ready.fingerprint, now + 2, true))
            assertTrue(created is UserAdoptionCommitResult.Adopted)
            val adopted = chapters.getByCanonicalTitleId(titleId).sortedBy { it.displayNumber }
            assertEquals(2, adopted.size)
            assertEquals(setOf("Chapter 1", "Chapter 2"), adopted.map { it.displayNumber }.toSet())
            val receipts = database.tsuzuki_user_adoption_receiptsQueries
                .getTsuzukiUserAdoptionReceiptsByTitle(titleId).awaitAsList()
            assertEquals(2, receipts.size)
            assertTrue(receipts.all { it.origin_reference.startsWith("user-selected:") })
            assertTrue(receipts.all { it.review_fingerprint == ready.fingerprint })
            for (chapter in adopted) {
                assertTrue(chapters.getVariantsByCanonicalChapterId(chapter.id).isEmpty())
                assertNull(CanonicalReadingRepositoryImpl(database).getProgress(chapter.id))
            }
            assertEquals(
                UserAdoptionCommitResult.Refused,
                adoption.adopt(UserAdoptionConfirmation(request, ready.fingerprint, now + 3, true)),
            )
            assertEquals(2, chapters.getByCanonicalTitleId(titleId).size)

            val portable = BackupCanonicalCore(
                titles = listOf(
                    BackupCoreTitle(titleId, "Offline EPUB adoption fixture", "SOURCE_ONLY", now, now),
                ),
                chapters = adopted.map { chapter ->
                    BackupCoreChapter(
                        id = chapter.id,
                        canonicalTitleId = titleId,
                        displayNumber = chapter.displayNumber,
                        volume = chapter.volume,
                        title = chapter.title,
                        type = chapter.type.name,
                        baseNumber = chapter.baseNumber,
                        part = chapter.part,
                        alphaSuffix = chapter.alphaSuffix,
                        confidence = chapter.confidence,
                        createdAt = chapter.createdAt,
                        updatedAt = chapter.updatedAt,
                        confirmation = chapter.confirmation.name,
                        numberingKind = chapter.numberingKind?.name,
                        decimalNumerator = chapter.decimalFraction?.numerator,
                        decimalScale = chapter.decimalFraction?.scale,
                    )
                },
                userAdoptionReceipts = receipts.map { row ->
                    BackupCoreUserAdoptionReceipt(
                        canonicalChapterId = row.canonical_chapter_id,
                        receiptId = row.receipt_id,
                        canonicalTitleId = row.canonical_title_id,
                        producerId = row.producer_id,
                        externalWorkId = row.external_work_id,
                        editionKey = row.edition_key,
                        scopeLanguage = row.scope_language,
                        sourceRecordId = row.source_record_id,
                        sourceKind = row.source_kind,
                        derivation = row.derivation,
                        originReference = row.origin_reference,
                        exactRevision = row.exact_revision,
                        attributionReference = row.attribution_reference,
                        licenseReference = row.license_reference,
                        snapshotDigest = row.snapshot_digest,
                        sourceUnitId = row.source_unit_id,
                        structuralCategory = row.structural_category,
                        acceptedLabel = row.accepted_label,
                        identityKey = row.identity_key,
                        volumeKey = row.volume_key,
                        observedAt = row.observed_at,
                        acceptedAt = row.accepted_at,
                        reviewFingerprint = row.review_fingerprint,
                    )
                },
            )
            portable.validate()
            val section = BackupCanonicalSection.fromJson(
                BackupCanonicalCore.SECTION_KEY,
                Json.encodeToString(BackupCanonicalCore.serializer(), portable),
            )

            // Restore into a second disposable SQLite database. Never overwrite the live fixture.
            val isolatedName = "ice-restore-${UUID.randomUUID()}.db"
            val driver = AndroidSqliteDriver(Database.Schema, context, isolatedName)
            try {
                val restoredDb = AppBindings.providesDatabase(driver)
                val importer = CanonicalCoreBackupImporter(restoredDb, Json)
                importer.restoreCore(section)
                importer.restoreCore(section) // Restore receipt prevents double replay.
                val restoredReceipts = restoredDb.tsuzuki_user_adoption_receiptsQueries
                    .getTsuzukiUserAdoptionReceiptsByTitle(titleId).awaitAsList()
                assertEquals(receipts.map { it.review_fingerprint }.sorted(), restoredReceipts.map { it.review_fingerprint }.sorted())
                assertEquals(2, restoredReceipts.size)
                assertEquals(2, CanonicalChapterRepositoryImpl(restoredDb).getByCanonicalTitleId(titleId).size)
                assertTrue(
                    CanonicalChapterRepositoryImpl(restoredDb)
                        .getVariantsByCanonicalChapterId(adopted.first().id).isEmpty(),
                )
                assertNull(CanonicalReadingRepositoryImpl(restoredDb).getProgress(adopted.first().id))
            } finally {
                driver.close()
                context.deleteDatabase(isolatedName)
            }
            report("IMPORT_ADOPT_RESTORE", "candidates=2|adopted=2|backup=RESTORED|reader=NO_PAGES")
        } finally {
            context.contentResolver.delete(document, null, null)
        }
    }

    @Test(timeout = 90_000L)
    fun malformedContentUriAndInterruptedStreamNeverMaterializeChapters() = runBlocking {
        val context = fixtureContext()
        val titleId = "ice-failure-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val database = (context.applicationContext as App).graph.database
        CanonicalTitleRepositoryImpl(database).insert(
            CanonicalTitle(titleId, "Failed EPUB fixture", CanonicalIdentityState.SOURCE_ONLY, now, now),
        )
        val ledger = IndependentInventoryRepositoryImpl(database)
        val chapters = CanonicalChapterRepositoryImpl(database)
        val truncated = createDocument("not an epub".toByteArray(), "ice-bad-${UUID.randomUUID()}.epub")
        try {
            val invalid = SelectedEpub3ArchiveImport.read(
                requireNotNull(context.contentResolver.openInputStream(truncated)),
                context.cacheDir,
                titleId,
                now,
            )
            assertTrue(invalid.isFailure)
            val interrupted = SelectedEpub3ArchiveImport.read(
                object : InputStream() {
                    var index = 0
                    override fun read(): Int {
                        if (index++ >= 40) throw IOException("Selected document stream disconnected")
                        return 65
                    }
                },
                context.cacheDir,
                titleId,
                now,
            )
            assertTrue(interrupted.isFailure)
            assertTrue(ledger.byTitle(titleId).isEmpty())
            assertTrue(chapters.getByCanonicalTitleId(titleId).isEmpty())
            assertTrue(
                context.cacheDir.listFiles().orEmpty().none {
                    it.name.startsWith("tsuzuki-edition-") && it.name.endsWith(".epub")
                },
            )
            report("FAILED_IMPORT", "ledger=EMPTY|chapters=EMPTY|cache=CLEARED")
        } finally {
            context.contentResolver.delete(truncated, null, null)
        }
    }

    private fun fixtureContext() = InstrumentationRegistry.getInstrumentation().targetContext.also { context ->
        assumeTrue("Dedicated disposable-emulator job only", InstrumentationRegistry.getArguments()
            .getString("iceEpubE2eOptIn") == "true")
        assertEquals("app.tsuzuki.dev", context.packageName)
        assertEquals("1", android.os.SystemProperties.get("ro.kernel.qemu", "0"))
    }

    private fun report(stage: String, facts: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply { putString("stream", "ICE_EPUB_E2E|stage=$stage|$facts") },
        )
    }

    private fun createDocument(bytes: ByteArray, displayName: String): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun epubBytes(): ByteArray {
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent(),
            "OPS/package.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:language>ja</dc:language></metadata>
                  <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest>
                </package>
            """.trimIndent(),
            "OPS/nav.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <body><nav epub:type="toc"><ol>
                    <li><a href="Text/001.xhtml">Chapter 1</a></li>
                    <li><a href="Text/002.xhtml">Chapter 2</a></li>
                    <li><a href="Text/preface.xhtml">Preface</a></li>
                  </ol></nav></body>
                </html>
            """.trimIndent(),
        )
        return ByteArrayOutputStream().use { data ->
            ZipOutputStream(data).use { zip ->
                entries.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            data.toByteArray()
        }
    }
}
