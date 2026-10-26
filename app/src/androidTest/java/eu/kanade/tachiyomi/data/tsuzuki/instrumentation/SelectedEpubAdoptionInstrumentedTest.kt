package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.sqldelight.async.coroutines.awaitAsList
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.CanonicalStandaloneArchive
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalCore
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSection
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSnapshot
import eu.kanade.tachiyomi.data.backup.models.BackupCoreChapter
import eu.kanade.tachiyomi.data.backup.models.BackupCoreTitle
import eu.kanade.tachiyomi.data.backup.models.BackupCoreUserAdoptionReceipt
import eu.kanade.tachiyomi.data.backup.models.CanonicalCoreWireJson
import eu.kanade.tachiyomi.data.backup.restore.CanonicalCoreBackupImporter
import eu.kanade.tachiyomi.data.tsuzuki.inventory.SelectedEpub3ArchiveImport
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
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
            // The canonical number is normalized; the original source label lives in the claim.
            assertEquals(setOf("1", "2"), adopted.map { it.displayNumber }.toSet())
            assertEquals(
                setOf("Chapter 1", "Chapter 2"),
                ledger.byTitle(titleId).mapNotNull { it.claim.label }.filter { it.startsWith("Chapter") }.toSet(),
            )
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
            // Exercise the real .tachibk gzip/Protobuf decoder before the
            // deliberately gated canonical restore. The production backup
            // creator/restorer must not be treated as activated by this test.
            val archiveBytes = ByteArrayOutputStream().use { bytes ->
                GZIPOutputStream(bytes).use { gzip ->
                    gzip.write(
                        ProtoBuf.encodeToByteArray(
                            Backup.serializer(),
                            Backup(
                                backupManga = emptyList(),
                                canonicalSnapshot = BackupCanonicalSnapshot(sections = listOf(section)),
                            ),
                        ),
                    )
                }
                bytes.toByteArray()
            }
            val archiveUri = createDocument(
                archiveBytes,
                "ice-canonical-${UUID.randomUUID()}.tachibk",
                "application/octet-stream",
            )
            val decodedSection = try {
                val decoded = BackupDecoder(context, ProtoBuf).decode(archiveUri)
                assertEquals(1, decoded.canonicalSnapshot?.sections?.size)
                requireNotNull(decoded.canonicalSnapshot).sections.single()
            } finally {
                context.contentResolver.delete(archiveUri, null, null)
            }
            assertEquals(section.sha256, decodedSection.sha256)

            // Restore into a second disposable SQLite database. Never overwrite the live fixture.
            val isolatedName = "ice-restore-${UUID.randomUUID()}.db"
            val driver = AndroidxSqliteDriver(
                driver = BundledSQLiteDriver(),
                databaseType = AndroidxSqliteDatabaseType.FileProvider(context, isolatedName),
                schema = Database.Schema,
                configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
            )
            try {
                val restoredDb = AppBindings.providesDatabase(driver)
                val importer = CanonicalCoreBackupImporter(restoredDb, Json)
                importer.restoreCore(decodedSection)
                importer.restoreCore(decodedSection) // Restore receipt prevents double replay.
                val restoredReceipts = restoredDb.tsuzuki_user_adoption_receiptsQueries
                    .getTsuzukiUserAdoptionReceiptsByTitle(titleId).awaitAsList()
                assertEquals(
                    receipts.map { it.review_fingerprint }.sorted(),
                    restoredReceipts.map { it.review_fingerprint }.sorted(),
                )
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
            report("BINARY_ARCHIVE_DECODE", "protobuf=VALID|gzip=VALID|canonical=RESTORED_ISOLATED")
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

    @Test(timeout = 180_000L)
    fun safPickerCancellationAndFileSelectionAreUserDrivenAndShadowOnly() = runBlocking {
        val context = fixtureContext()
        val titleId = "ice-saf-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val database = (context.applicationContext as App).graph.database
        CanonicalTitleRepositoryImpl(database).insert(
            CanonicalTitle(titleId, "SAF UI EPUB fixture", CanonicalIdentityState.SOURCE_ONLY, now, now),
        )
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val intent = requireNotNull(CanonicalTitleActivity.newIntent(context, titleId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A background Context.startActivity() can be ignored by Android's
        // background activity-start policy on a cold, cleared test process.
        // Instrumentation launches explicitly and synchronously instead.
        val launched = InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
        assertTrue(launched is CanonicalTitleActivity)
        try {
            // Wait for the real title screen to be RESUMED and populated before
            // scrolling its lazy list. Otherwise early swipes may leave the
            // import action outside the viewport when loading completes.
            awaitResumedActivity(CanonicalTitleActivity::class.java)
            val titleShown = device.wait(
                Until.hasObject(By.text("SAF UI EPUB fixture")),
                30_000,
            )
            val visibleText = if (titleShown) {
                ""
            } else {
                device.findObjects(By.text(java.util.regex.Pattern.compile(".+")))
                    .mapNotNull { it.text }
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(15)
                    .joinToString(" | ")
            }
            assertTrue(
                "Canonical title not rendered; foreground=${device.currentPackageName}; visible=$visibleText",
                titleShown,
            )
            val action = awaitUiAction(device, "Import EPUB3 index", "Importar índice EPUB3")
            assertTrue("Import action must appear on actual title details", action != null)
            requireNotNull(action).click()
            assertTrue(
                "Android DocumentsUI must own the picker window",
                device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 12_000) ||
                    device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 4_000),
            )
            device.pressBack()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(IndependentInventoryRepositoryImpl(database).byTitle(titleId).isEmpty())
            assertTrue(CanonicalChapterRepositoryImpl(database).getByCanonicalTitleId(titleId).isEmpty())
            report("SAF_UI_CANCEL", "picker=OPENED|candidates=EMPTY|chapters=EMPTY")

            // Now choose a real file through DocumentsUI, not by injecting a
            // URI into the ActivityResult callback. Only shadow evidence may
            // exist after the visual picker returns to the title screen.
            val displayName = "ice-picker-${UUID.randomUUID()}.epub"
            val fileUri = createDocument(epubBytes(), displayName)
            try {
                requireNotNull(awaitUiAction(device, "Import EPUB3 index", "Importar índice EPUB3")).click()
                val pickerIsOpen =
                    device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 8_000) ||
                        device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 4_000)
                assertTrue("System picker must reopen", pickerIsOpen)
                var fileNode = device.wait(Until.findObject(By.text(displayName)), 5_000)
                if (fileNode == null) {
                    // Downloads can be hidden on Recent. Navigate using the
                    // public system UI, never use a private provider grant.
                    val roots = device.findObject(By.descContains("Show roots"))
                        ?: device.findObject(By.descContains("Show navigation drawer"))
                    roots?.click()
                    device.wait(Until.findObject(By.text("Downloads")), 7_000)?.click()
                    fileNode = device.wait(Until.findObject(By.text(displayName)), 8_000)
                }
                assertTrue(
                    "Selected EPUB must be visible in system DocumentsUI, package=${device.currentPackageName}",
                    fileNode != null,
                )
                requireNotNull(fileNode).click()
                val ledger = IndependentInventoryRepositoryImpl(database)
                var found = false
                repeat(80) {
                    if (ledger.byTitle(titleId).size == 3) {
                        found = true
                        return@repeat
                    }
                    SystemClock.sleep(200)
                }
                assertTrue("SAF picker result must import candidate evidence", found)
                assertEquals(3, ledger.byTitle(titleId).size)
                assertTrue(CanonicalChapterRepositoryImpl(database).getByCanonicalTitleId(titleId).isEmpty())
                report("SAF_UI_SELECTED", "picker=RETURNED|candidates=3|chapters=EMPTY")
            } finally {
                context.contentResolver.delete(fileUri, null, null)
            }
        } finally {
            device.pressBack()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { launched.finish() }
        }
    }

    @Test(timeout = 120_000L)
    fun adoptedChapterOpensReaderWithoutInventingPagesOrReadingProgress() = runBlocking {
        val context = fixtureContext()
        val titleId = "ice-reader-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val database = (context.applicationContext as App).graph.database
        CanonicalTitleRepositoryImpl(database).insert(
            CanonicalTitle(titleId, "Reader without content", CanonicalIdentityState.SOURCE_ONLY, now, now),
        )
        val ledger = IndependentInventoryRepositoryImpl(database)
        val chapters = CanonicalChapterRepositoryImpl(database)
        val adoption = UserAdoptedInventoryRepositoryImpl(database, ledger, chapters, ChapterMutationGate())
        val imported = SelectedEpub3ArchiveImport.read(
            epubBytes().inputStream(),
            context.cacheDir,
            titleId,
            now,
        ).getOrThrow()
        ledger.saveBatch(imported)
        val request = UserAdoptionReviewRequest(imported.scope, imported.claims.take(1).map { it.sourceRecordId })
        val preview = adoption.preview(request) as UserAdoptionPreview.Ready
        assertTrue(
            adoption.adopt(UserAdoptionConfirmation(request, preview.fingerprint, now + 1, true)) is
                UserAdoptionCommitResult.Adopted,
        )
        val chapter = chapters.getByCanonicalTitleId(titleId).single()
        context.startActivity(
            ReaderActivity.newCanonicalIntent(context, chapter.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val reader = awaitResumedActivity(ReaderActivity::class.java)
        try {
            var noSourcePresented = false
            repeat(50) {
                val state = reader.viewModel.state.value
                if (state.dialog is ReaderViewModel.Dialog.ContentSelector) {
                    noSourcePresented = true
                    return@repeat
                }
                SystemClock.sleep(150)
            }
            val state = reader.viewModel.state.value
            assertTrue("Reader must request a source instead of inventing EPUB pages", noSourcePresented)
            assertTrue(state.currentChapter?.pages.isNullOrEmpty())
            assertTrue(state.viewerChapters == null)
            assertEquals(-1, state.totalPages)
            assertTrue(chapters.getVariantsByCanonicalChapterId(chapter.id).isEmpty())
            assertNull(CanonicalReadingRepositoryImpl(database).getProgress(chapter.id))
            report("READER_UI_NO_PAGES", "source=REQUIRED|viewer=EMPTY|progress=EMPTY")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { reader.finish() }
        }
    }

    private fun awaitUiAction(device: UiDevice, vararg labels: String): androidx.test.uiautomator.UiObject2? {
        repeat(12) {
            for (label in labels) {
                device.wait(Until.findObject(By.text(label)), 1500)?.let { return it }
            }
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                22,
            )
        }
        return null
    }

    private fun <T : Activity> awaitResumedActivity(type: Class<T>): T {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        repeat(80) {
            var activity: T? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity = monitor.getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance(type)
                    .firstOrNull()
            }
            if (activity != null) return requireNotNull(activity)
            SystemClock.sleep(150)
        }
        error("Expected ${type.simpleName} to be resumed")
    }

    @Test(timeout = 240_000L)
    fun productionBackupWorkerWritesStandaloneTachibkWithAdoptedReceipts() = runBlocking {
        val context = fixtureContext()
        val database = (context.applicationContext as App).graph.database
        val titleId = "ice-backup-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        CanonicalTitleRepositoryImpl(database).insert(
            CanonicalTitle(titleId, "Real writer adopted edition", CanonicalIdentityState.SOURCE_ONLY, now, now),
        )
        val ledger = IndependentInventoryRepositoryImpl(database)
        val chapters = CanonicalChapterRepositoryImpl(database)
        val adoption = UserAdoptedInventoryRepositoryImpl(database, ledger, chapters, ChapterMutationGate())
        val batch = SelectedEpub3ArchiveImport.read(
            epubBytes().inputStream(),
            context.cacheDir,
            titleId,
            now,
        ).getOrThrow()
        ledger.saveBatch(batch)
        val request = UserAdoptionReviewRequest(batch.scope, listOf(batch.claims.first().sourceRecordId))
        val ready = adoption.preview(request) as UserAdoptionPreview.Ready
        assertTrue(
            adoption.adopt(UserAdoptionConfirmation(request, ready.fingerprint, now + 1, true)) is
                UserAdoptionCommitResult.Adopted,
        )
        val file = File.createTempFile("ice-production-", ".tachibk", context.cacheDir)
        try {
            val uri = Uri.fromFile(file)
            // Do not construct a synthetic Backup message here: run the same
            // foreground WorkManager job used by CreateBackupScreen.
            BackupCreateJob.startNow(context, uri, BackupOptions(canonicalLibraryOnly = true))
            val work = WorkManager.getInstance(context)
            var terminal: WorkInfo.State? = null
            for (attempt in 0 until 170) {
                val jobs = work.getWorkInfosForUniqueWork("BackupCreator:manual")
                    .get(3, TimeUnit.SECONDS)
                val state = jobs.lastOrNull()?.state
                if (state != null && state.isFinished) {
                    terminal = state
                    break
                }
                SystemClock.sleep(500)
            }
            assertEquals(
                "Production backup worker must successfully verify its own output",
                WorkInfo.State.SUCCEEDED,
                terminal,
            )
            val actual = BackupDecoder(context, ProtoBuf).decode(uri)
            val section = CanonicalStandaloneArchive.requireRestorable(actual, true)
            val core = CanonicalCoreWireJson.codec.decodeFromString(
                BackupCanonicalCore.serializer(),
                section.payloadJson,
            )
            assertTrue(core.titles.any { it.id == titleId })
            assertEquals(1, core.userAdoptionReceipts.count { it.canonicalTitleId == titleId })
            assertEquals(1, core.chapters.count { it.canonicalTitleId == titleId })
            assertTrue(actual.backupManga.isEmpty())
            report("PRODUCTION_ARCHIVE", "worker=SUCCESS|gzip=VALID|receipts=1|chapters=1")
        } finally {
            file.delete()
        }
    }

    private fun fixtureContext() = InstrumentationRegistry.getInstrumentation().targetContext.also { context ->
        assertEquals(
            "This test must run only via the disposable emulator harness",
            "true",
            InstrumentationRegistry.getArguments().getString("iceEpubE2eOptIn"),
        )
        assertEquals("app.tsuzuki.dev", context.packageName)
        assertTrue(
            "Do not run against a physical user's device",
            android.os.Build.HARDWARE.contains("ranchu") ||
                android.os.Build.HARDWARE.contains("goldfish"),
        )
    }

    private fun report(stage: String, facts: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply { putString("stream", "ICE_EPUB_E2E|stage=$stage|$facts") },
        )
    }

    private fun createDocument(
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/epub+zip",
    ): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
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
