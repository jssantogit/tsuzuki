package eu.kanade.presentation.tsuzuki

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.manga.components.MangaCoverLoadEvent
import eu.kanade.presentation.more.settings.components.SettingsCanvas
import eu.kanade.presentation.more.settings.components.SettingsGroup
import eu.kanade.presentation.more.settings.screen.IntegrationSettingRow
import eu.kanade.presentation.more.settings.screen.IntegrationToggleRow
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceScreen
import eu.kanade.presentation.more.settings.screen.SettingsMainScreen
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.tsuzuki.detail.CanonicalDetailsHero
import eu.kanade.presentation.tsuzuki.search.SearchCompactCard
import eu.kanade.tachiyomi.DefaultSkinValidationActivity
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationAuthState
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationConfigState
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationSettingsItem
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import mihon.app.di.appGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCategory
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.i18n.MR
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import android.graphics.Color as AndroidColor

/** Actual production composables rendered in a disposable, network-free host. */
@RunWith(AndroidJUnit4::class)
class DefaultSkinRenderedValidationInstrumentedTest {
    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    companion object {
        @JvmStatic
        @BeforeClass
        fun clearOwnedEvidenceDirectoryOnce() {
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            File(requireNotNull(targetContext.getExternalFilesDir(null)), "default-skin-rendered")
                .deleteRecursively()
        }
    }

    @Test
    fun searchArtworkFixturesKeepTheForegroundInsideOneStableMeasuredFrame() = withHost { activity ->
        val fixtures = listOf(
            ArtworkFixture("two-thirds", 300, 450, whitespace = false),
            ArtworkFixture("wide", 600, 200, whitespace = false),
            ArtworkFixture("tall", 200, 600, whitespace = false),
            ArtworkFixture("intrinsic-whitespace", 300, 450, whitespace = true),
            ArtworkFixture("failed", 0, 0, whitespace = false),
            ArtworkFixture("missing", 0, 0, whitespace = false),
        )
        val artifactDirectory = artifactDirectory()
        val measurements = mutableListOf<String>()

        fixtures.forEach { fixture ->
            val data = if (fixture.name == "failed") {
                File(activity.cacheDir, "does-not-exist-${System.nanoTime()}.png").toURI().toString()
            } else if (fixture.name == "missing") {
                null
            } else {
                createFixture(activity.cacheDir, fixture, markerBands = fixture.name == "wide").toURI().toString()
            }
            val foregroundEvents = Collections.synchronizedList(mutableListOf<MangaCoverLoadEvent>())
            val atmosphereEvents = Collections.synchronizedList(mutableListOf<MangaCoverLoadEvent>())
            val terminalLoadEvents = CountDownLatch(if (data == null) 0 else 2)
            val cardClicks = AtomicInteger()
            instrumentation.runOnMainSync {
                activity.setValidationContent {
                    Column(Modifier.fillMaxWidth()) {
                        SearchCompactCard(
                            item = CatalogItem(
                                provider = "render-fixture",
                                providerId = fixture.name,
                                title = "fixture:${fixture.name}",
                                coverUrl = data,
                            ),
                            onClick = { cardClicks.incrementAndGet() },
                            onForegroundLoadEvent = { event ->
                                foregroundEvents += event
                                if (event is MangaCoverLoadEvent.Succeeded || event is MangaCoverLoadEvent.Failed) {
                                    terminalLoadEvents.countDown()
                                }
                            },
                            onAtmosphereLoadEvent = { event ->
                                atmosphereEvents += event
                                if (event is MangaCoverLoadEvent.Succeeded || event is MangaCoverLoadEvent.Failed) {
                                    terminalLoadEvents.countDown()
                                }
                            },
                        )
                    }
                }
            }
            instrumentation.waitForIdleSync()
            if (data != null) {
                assertTrue(
                    "Coil did not finish fixture ${fixture.name}",
                    terminalLoadEvents.await(10, TimeUnit.SECONDS),
                )
            }
            val nodes = awaitSemantics(activity) { snapshot ->
                val missingArtworkAttempted =
                    fixture.name != "missing" ||
                        foregroundEvents.any {
                            it is MangaCoverLoadEvent.Attempt && !it.requestDataPresent
                        }
                snapshot.any { it.tag == "search_compact_card" } &&
                    snapshot.any { it.tag == "search_compact_artwork_frame" } &&
                    snapshot.any { "fixture:${fixture.name}" in it.text } &&
                    missingArtworkAttempted
            }
            if (fixture.name == "failed") {
                assertTrue(foregroundEvents.any { it is MangaCoverLoadEvent.Failed && !it.willFallback })
                assertTrue(atmosphereEvents.any { it is MangaCoverLoadEvent.Failed && !it.willFallback })
            } else if (fixture.name == "missing") {
                assertTrue(foregroundEvents.any { it is MangaCoverLoadEvent.Attempt && !it.requestDataPresent })
            } else {
                assertTrue(foregroundEvents.any { it is MangaCoverLoadEvent.Succeeded })
                assertTrue(atmosphereEvents.any { it is MangaCoverLoadEvent.Succeeded })
            }

            val card = nodes.singleNode("search_compact_card")
            val frame = nodes.singleNode("search_compact_artwork_frame")
            val foreground = nodes.singleNode("search_compact_artwork_foreground")
            val atmosphere = nodes.singleNodeOrNull("search_compact_artwork_atmosphere")
            assertTrue("Compact card must be the sole click owner", card.hasClickAction && card.role == Role.Button)
            assertEquals(
                "Artwork must not create another clickable target",
                1,
                nodes.count { it.hasClickAction && rectsOverlap(it.bounds, frame.bounds) },
            )
            assertRectApproximately(frame.layoutBounds, foreground.layoutBounds)
            val semanticArtwork = nodes.filter {
                it.contentDescription == listOf("fixture:${fixture.name}") && rectsOverlap(it.bounds, frame.bounds)
            }
            assertEquals("Only the foreground artwork should describe this fixture", 1, semanticArtwork.size)
            atmosphere?.let {
                assertRectApproximately(frame.layoutBounds, it.layoutBounds)
                assertTrue("Decorative artwork must remain semantics-free", it.contentDescription.isEmpty())
            }
            assertRatio(frame.layoutBounds, 2f / 3f)
            if (fixture.name == "wide") {
                val location = screenLocation(activity)
                val centerY = location[1] + frame.layoutBounds.center.y.toInt()
                val leftMarker = AndroidColor.rgb(246, 49, 166)
                val rightMarker = AndroidColor.rgb(25, 221, 242)
                val leftX = location[0] + (frame.layoutBounds.left + frame.layoutBounds.width * 0.12f).toInt()
                val rightX = location[0] + (frame.layoutBounds.left + frame.layoutBounds.width * 0.88f).toInt()
                val markerPixels = awaitScreenPixels(activity, listOf(leftX to centerY, rightX to centerY)) { pixels ->
                    pixels == listOf(leftMarker, rightMarker)
                }
                assertEquals(
                    "Fit must preserve the complete source's left and right markers",
                    listOf(leftMarker, rightMarker),
                    markerPixels,
                )
                measurements += listOf(
                    "wide-markers",
                    Integer.toHexString(markerPixels[0]),
                    Integer.toHexString(markerPixels[1]),
                ).joinToString(",")
            }

            val screenshot = File(artifactDirectory, "search-${fixture.name}.png")
            saveScreenshot(screenshot)
            assertTrue("Expected a non-empty screenshot for ${fixture.name}", screenshot.length() > 0L)
            measurements += listOf(
                fixture.name,
                frame.bounds.toCsv(),
                frame.layoutBounds.toCsv(),
                foreground.layoutBounds.toCsv(),
            ).joinToString(",")

            val location = screenLocation(activity)
            UiDevice.getInstance(instrumentation).click(
                location[0] + card.bounds.center.x.toInt(),
                location[1] + card.bounds.center.y.toInt(),
            )
            instrumentation.waitForIdleSync()
            assertEquals("Only the compact card tap opens the result", 1, cardClicks.get())
        }

        File(artifactDirectory, "search-measurements.csv").writeText(
            "fixture,visible-frame,raw-frame,raw-foreground\n${measurements.joinToString("\n")}\n",
        )
        assertTrue(File(artifactDirectory, "search-measurements.csv").length() > 0L)
    }

    @Test
    fun measuredLayerAssertionRejectsAnIntentionallyMisalignedFixture() {
        val expected = Rect(0f, 0f, 148f, 222f)
        val shifted = Rect(0f, 2f, 148f, 224f)
        val error = runCatching { assertRectApproximately(expected, shifted) }.exceptionOrNull()
        assertTrue("Bounds probe accepted intentionally shifted layer", error is AssertionError)
        val wrongRatio = runCatching { assertRatio(Rect(0f, 0f, 148f, 300f), 2f / 3f) }.exceptionOrNull()
        assertTrue("Ratio probe accepted intentionally malformed frame", wrongRatio is AssertionError)
        val containmentError = runCatching {
            assertRectContains(Rect(0f, 0f, 100f, 100f), Rect(102f, 102f, 104f, 104f))
        }.exceptionOrNull()
        assertTrue("Surface grouping probe accepted a row escaping its surface", containmentError is AssertionError)
        val flowError = runCatching {
            assertVerticalFlow(Rect(0f, 0f, 100f, 50f), Rect(0f, 48f, 100f, 70f), "negative oracle fixture")
        }.exceptionOrNull()
        assertTrue("Content-flow probe accepted overlapping metadata", flowError is AssertionError)
        val expectedMarkers = listOf(AndroidColor.rgb(246, 49, 166), AndroidColor.rgb(25, 221, 242))
        val cropEquivalentMarkers = listOf(AndroidColor.rgb(246, 207, 218), AndroidColor.rgb(246, 207, 218))
        assertTrue(
            "Foreground-fit pixel oracle accepted crop-equivalent edge pixels",
            runCatching { assertEquals(expectedMarkers, cropEquivalentMarkers) }.exceptionOrNull() is AssertionError,
        )
    }

    @Test
    fun settingsRootRendersSixGroupedSurfacesWithCompactRowsAndSectionRhythm() = withHost { activity ->
        val expectedRows = mapOf(
            "account" to "account",
            "tracking" to "account",
            "personalization" to "experience",
            "reading" to "experience",
            "library" to "experience",
            "integrations" to "content",
            "providers" to "content",
            "downloads" to "data",
            "data_storage" to "data",
            "security" to "system",
            "diagnostics" to "system",
            "advanced" to "system",
            "about" to "about",
        )
        val artifactDirectory = artifactDirectory()
        val foundRows = mutableSetOf<String>()
        val foundHeadings = mutableSetOf<String>()
        val foundSurfaces = mutableSetOf<String>()
        val foundDividers = mutableSetOf<String>()
        val measuredInterGroupGaps = mutableListOf<Float>()
        val measuredLabelSurfaceGaps = mutableListOf<Float>()

        listOf(false, true).forEach { dark ->
            instrumentation.runOnMainSync {
                activity.setValidationContent(dark = dark) {
                    Navigator(screen = SettingsMainScreen, onBackPressed = null) { _ ->
                        SettingsMainScreen.Content(twoPane = false, navigateUp = null)
                    }
                }
            }
            awaitSemantics(activity) { nodes -> nodes.any { it.tag == "settings_heading_account" } }
            if (!dark) {
                saveScreenshot(File(artifactDirectory, "settings-light.png"))
            } else {
                saveScreenshot(File(artifactDirectory, "settings-dark.png"))
            }

            repeat(18) {
                val nodes = semanticsNodes(activity)
                val byTag = nodes.filter { it.tag.isNotBlank() }.associateBy { it.tag }
                foundHeadings += byTag.keys.filter { it.startsWith("settings_heading_") }
                foundSurfaces += byTag.keys.filter { it.startsWith("settings_surface_") }
                foundDividers += byTag.keys.filter { it.startsWith("settings_divider_") }
                expectedRows.keys.forEach { row ->
                    val rowNode = byTag["settings_destination_$row"] ?: return@forEach
                    foundRows += row
                    val group = expectedRows.getValue(row)
                    val surface = byTag["settings_surface_$group"]
                    assertNotNull("$row must be inside its $group shared surface", surface)
                    assertRectContains(requireNotNull(surface).layoutBounds, rowNode.layoutBounds)
                }

                val visibleGroups = listOf("account", "experience", "content", "data", "system", "about")
                    .mapNotNull { key ->
                        val heading = byTag["settings_heading_$key"] ?: return@mapNotNull null
                        val surface = byTag["settings_surface_$key"] ?: return@mapNotNull null
                        key to (heading.layoutBounds to surface.layoutBounds)
                    }
                visibleGroups.forEach { (_, bounds) ->
                    measuredLabelSurfaceGaps += bounds.second.top - bounds.first.bottom
                }
                visibleGroups.zipWithNext().forEach { (previous, next) ->
                    measuredInterGroupGaps += next.second.first.top - previous.second.second.bottom
                }
                if (foundRows.containsAll(expectedRows.keys)) return@repeat
                val device = UiDevice.getInstance(instrumentation)
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    device.displayWidth / 2,
                    device.displayHeight / 4,
                    12,
                )
                instrumentation.waitForIdleSync()
            }
        }

        assertEquals(expectedRows.keys, foundRows)
        assertEquals(
            setOf(
                "settings_heading_account",
                "settings_heading_experience",
                "settings_heading_content",
                "settings_heading_data",
                "settings_heading_system",
                "settings_heading_about",
            ),
            foundHeadings,
        )
        assertEquals(
            setOf(
                "settings_surface_account",
                "settings_surface_experience",
                "settings_surface_content",
                "settings_surface_data",
                "settings_surface_system",
                "settings_surface_about",
            ),
            foundSurfaces,
        )
        assertEquals(7, foundDividers.size)
        assertTrue(
            "Need measured adjacent group and label/surface pairs",
            measuredInterGroupGaps.isNotEmpty() && measuredLabelSurfaceGaps.isNotEmpty(),
        )
        assertTrue(
            "Inter-group rhythm should exceed label-to-surface gap " +
                "(inter=$measuredInterGroupGaps intra=$measuredLabelSurfaceGaps)",
            measuredInterGroupGaps.min() > measuredLabelSurfaceGaps.max(),
        )
        writeMeasurements(
            "settings-measurements.csv",
            buildList {
                add("heading,surface,row-count,divider-count,inter-group-gap-px,label-surface-gap-px")
                addAll(
                    foundSurfaces.sorted().map { surface ->
                        val key = surface.removePrefix("settings_surface_")
                        val rows = expectedRows.filterValues { it == key }.keys.joinToString("|")
                        val dividerCount = foundDividers.count { it.startsWith("settings_divider_${key}_") }
                        listOf(
                            "settings_heading_$key",
                            surface,
                            rows,
                            dividerCount,
                            measuredInterGroupGaps.joinToString("|"),
                            measuredLabelSurfaceGaps.joinToString("|"),
                        ).joinToString(",")
                    },
                )
            },
        )
    }

    @Test
    fun defaultSkinCanvasAndGroupSurfaceRenderSemanticLightAndDarkRoles() = withHost { activity ->
        val artifactDirectory = artifactDirectory()
        val paletteMeasurements = mutableListOf<String>()
        listOf(
            PaletteFixture("light", false, 0xFFF7F7F8.toInt(), 0xFFFFFFFF.toInt()),
            PaletteFixture("dark", true, 0xFF0B0C0D.toInt(), 0xFF1C1E1F.toInt()),
        ).forEach { fixture ->
            instrumentation.runOnMainSync {
                activity.setValidationContent(dark = fixture.dark) {
                    SettingsCanvas(modifier = Modifier.testTag("palette_canvas")) {
                        SettingsGroup(surfaceTestTag = "palette_surface") {
                            androidx.compose.foundation.layout.Box(
                                Modifier.fillMaxWidth().height(140.dp).testTag("palette_surface_probe"),
                            )
                        }
                    }
                }
            }
            val nodes = awaitSemantics(activity) { it.any { node -> node.tag == "palette_surface_probe" } }
            val canvas = nodes.singleNode("palette_canvas").bounds
            val surface = nodes.singleNode("palette_surface").bounds
            val composeView = activity.composeView
            val location = IntArray(2)
            instrumentation.runOnMainSync { composeView.getLocationOnScreen(location) }
            val screenshot = File(artifactDirectory, "palette-${fixture.name}.png")
            val canvasX = location[0] + canvas.left.toInt() + 2
            val canvasY = location[1] + canvas.center.y.toInt()
            val surfaceX = location[0] + surface.right.toInt() - 3
            val surfaceY = location[1] + surface.center.y.toInt()
            val pixels = awaitStableScreenshot(
                destination = screenshot,
                samplePoints = listOf(canvasX to canvasY, surfaceX to surfaceY),
            )
            val canvasPixel = pixels[0]
            val surfacePixel = pixels[1]
            paletteMeasurements += listOf(
                fixture.name,
                Integer.toHexString(canvasPixel),
                Integer.toHexString(surfacePixel),
                canvas.toCsv(),
                surface.toCsv(),
                "$canvasX;$canvasY",
                "$surfaceX;$surfaceY",
            ).joinToString(",")
            writeMeasurements(
                "palette-measurements.csv",
                listOf(
                    listOf(
                        "theme",
                        "canvas-pixel",
                        "surface-pixel",
                        "canvas-bounds",
                        "surface-bounds",
                        "canvas-screen-point",
                        "surface-screen-point",
                    ).joinToString(","),
                ) + paletteMeasurements,
            )
            assertEquals("${fixture.name} canvas sample", fixture.canvasColor, canvasPixel)
            assertEquals("${fixture.name} surface sample", fixture.surfaceColor, surfacePixel)
        }
        writeMeasurements(
            "palette-measurements.csv",
            listOf("theme,canvas-pixel,surface-pixel,canvas-bounds,surface-bounds") + paletteMeasurements,
        )
    }

    @Test
    fun appearanceUsesCompactAccessibleThemeSamplesAndKeepsThemeModeSeparate() = withHost { activity ->
        val artifactDirectory = artifactDirectory()
        listOf(false, true).forEach { dark ->
            instrumentation.runOnMainSync {
                activity.setValidationContent(dark = dark) {
                    Navigator(screen = SettingsAppearanceScreen, onBackPressed = null) { _ ->
                        SettingsAppearanceScreen.Content()
                    }
                }
            }
            val nodes = awaitSemantics(activity) { snapshot ->
                snapshot.count { it.tag.startsWith("appearance_theme_preview_") } >= 2
            }
            val previews = nodes.filter { it.tag.startsWith("appearance_theme_preview_") }
            assertTrue("Appearance should offer multiple real themes", previews.size >= 2)
            previews.forEach { preview ->
                val ratio = preview.layoutBounds.width / preview.layoutBounds.height
                assertTrue("Theme sample ${preview.tag} should be compact landscape, ratio=$ratio", ratio > 1f)
                assertTrue(
                    "Theme sample must not dominate the viewport: ${preview.tag}",
                    preview.layoutBounds.height < activity.composeView.height * 0.30f,
                )
                val correspondingChoice = nodes.filter { candidate ->
                    candidate.role == Role.RadioButton && rectsOverlap(candidate.bounds, preview.bounds)
                }
                assertTrue("Theme preview is missing radio semantics: ${preview.tag}", correspondingChoice.isNotEmpty())
                assertTrue(
                    "Theme preview must announce a real theme name",
                    correspondingChoice.any { it.contentDescription.isNotEmpty() },
                )
                assertTrue("Theme preview must remain selectable", correspondingChoice.any { it.hasClickAction })
            }
            val selectedChoices = nodes.filter { it.role == Role.RadioButton && it.selected == true }
            assertEquals("Exactly one real theme should be selected", 1, selectedChoices.size)
            val themeModeTag = nodes.singleNode("appearance_theme_mode")
            assertTrue(
                "Theme Mode must remain a separate single-choice preference",
                nodes.any {
                    it.role == Role.Button && it.hasClickAction &&
                        rectsOverlap(it.layoutBounds, themeModeTag.layoutBounds)
                },
            )
            assertTrue(
                "Appearance must retain its real switch preferences, including AMOLED",
                nodes.any { it.role == Role.Switch },
            )
            saveScreenshot(File(artifactDirectory, if (dark) "appearance-dark.png" else "appearance-light.png"))

            val expectedThemeKeys = AppTheme.entries
                .filterNot { it.titleRes == null || (it == AppTheme.MONET && !DeviceUtil.isDynamicColorAvailable) }
                .map { it.name.lowercase() }
                .toSet()
            val observedThemeKeys = mutableSetOf<String>()
            val device = UiDevice.getInstance(instrumentation)
            var visible = nodes.filter { it.tag.startsWith("appearance_theme_preview_") }
            repeat(10) {
                observedThemeKeys += visible.map { it.tag.removePrefix("appearance_theme_preview_") }
                if (observedThemeKeys.containsAll(expectedThemeKeys)) return@repeat
                val firstPreview = visible.firstOrNull() ?: return@repeat
                val root = activity.composeView
                val location = screenLocation(activity)
                val y = location[1] + firstPreview.bounds.center.y.toInt()
                device.swipe(
                    location[0] + (root.width * 0.88f).toInt(),
                    y,
                    location[0] + (root.width * 0.16f).toInt(),
                    y,
                    14,
                )
                instrumentation.waitForIdleSync()
                visible = semanticsNodes(activity).filter { it.tag.startsWith("appearance_theme_preview_") }
            }
            assertEquals(
                "Every available real theme must remain represented/selectable",
                expectedThemeKeys,
                observedThemeKeys,
            )
        }

        val preferences = activity.appGraph.uiPreferences
        val previousThemeMode = preferences.themeMode.get()
        val previousAmoled = preferences.themeDarkAmoled.get()
        val modeLabels = mapOf(
            ThemeMode.SYSTEM to activity.stringResource(MR.strings.theme_system),
            ThemeMode.LIGHT to activity.stringResource(MR.strings.theme_light),
            ThemeMode.DARK to activity.stringResource(MR.strings.theme_dark),
        )
        val themeModeTitle = activity.stringResource(MR.strings.tsuzuki_theme_mode_title)
        try {
            preferences.themeDarkAmoled.set(true)
            instrumentation.runOnMainSync {
                activity.setValidationContent(dark = false) {
                    Navigator(screen = SettingsAppearanceScreen, onBackPressed = null) { _ ->
                        SettingsAppearanceScreen.Content()
                    }
                }
            }
            ThemeMode.entries.forEach { mode ->
                preferences.themeMode.set(mode)
                val modeNodes = awaitSemantics(activity) { snapshot ->
                    snapshot.any { it.tag == "appearance_theme_mode" } &&
                        snapshot.any { modeLabels.getValue(mode) in it.text }
                }
                val modeTag = modeNodes.singleNode("appearance_theme_mode")
                val modeRow = modeNodes.singleOrNull { it.tag == "appearance_theme_mode" && it.role == Role.Button }
                    ?: modeNodes.single {
                        it.role == Role.Button && rectsOverlap(it.layoutBounds, modeTag.layoutBounds)
                    }
                assertTrue(
                    "Theme Mode is a single-choice setting",
                    modeRow.role == Role.Button && modeRow.hasClickAction,
                )
                val modeTitle = modeNodes.single { themeModeTitle in it.text }
                assertRectContains(modeTag.layoutBounds, modeTitle.layoutBounds)
                val currentValue = modeNodes.single { modeLabels.getValue(mode) in it.text }
                assertRectContains(modeTag.layoutBounds, currentValue.layoutBounds)

                val amoledTag = modeNodes.singleNode("appearance_amoled")
                val amoled = modeNodes.singleOrNull { it.tag == "appearance_amoled" && it.role == Role.Switch }
                    ?: modeNodes.single {
                        it.role == Role.Switch && rectsOverlap(it.layoutBounds, amoledTag.layoutBounds)
                    }
                assertTrue("AMOLED remains an accessible preference switch", amoled.role == Role.Switch)
                assertEquals(
                    "AMOLED's stored value survives disabled state",
                    ToggleableState.On,
                    amoled.toggleableState,
                )
                assertEquals("AMOLED is disabled only for Light mode", mode != ThemeMode.LIGHT, amoled.enabled)
                saveScreenshot(File(artifactDirectory, "appearance-mode-${mode.name.lowercase()}.png"))

                val location = screenLocation(activity)
                UiDevice.getInstance(instrumentation).click(
                    location[0] + modeRow.bounds.center.x.toInt(),
                    location[1] + modeRow.bounds.center.y.toInt(),
                )
                val options = awaitSemantics(activity) { snapshot ->
                    snapshot.count {
                        it.role == Role.RadioButton && snapshot.textWithin(it).any { text -> text in modeLabels.values }
                    } == 3
                }
                val modeOptions = options.filter {
                    it.role == Role.RadioButton && options.textWithin(it).any { text -> text in modeLabels.values }
                }
                assertEquals("Theme Mode offers System, Light, and Dark", 3, modeOptions.size)
                assertEquals("Exactly one Theme Mode is selected", 1, modeOptions.count { it.selected == true })
                val selectedOption = modeOptions.single { it.selected == true }
                assertTrue(
                    "Selected option reflects the displayed Theme Mode",
                    options.textWithin(selectedOption).contains(modeLabels.getValue(mode)),
                )
                assertTrue("Theme Mode options remain selectable", modeOptions.all { it.hasClickAction })
                UiDevice.getInstance(instrumentation).pressBack()
                awaitSemantics(activity) { snapshot -> snapshot.any { it.tag == "appearance_theme_mode" } }
            }
        } finally {
            instrumentation.runOnMainSync {
                preferences.themeMode.set(previousThemeMode)
                preferences.themeDarkAmoled.set(previousAmoled)
            }
        }
        writeMeasurements(
            "appearance-measurements.csv",
            listOf("preview,ratio,bounds,selected") + semanticsNodes(activity)
                .filter { it.tag.startsWith("appearance_theme_preview_") }
                .map { "${it.tag},${it.bounds.width / it.bounds.height},${it.bounds.toCsv()},${it.selected}" },
        )
    }

    @Test
    fun integrationRootIsNavigationOnlyWhileDetailOwnsTheEnablementSwitch() = withHost { activity ->
        val opened = CountDownLatch(1)
        val openCount = AtomicInteger()
        val tracker = activity.appGraph.trackerManager.myAnimeList
        val previousClientId = tracker.getClientId()
        try {
            tracker.setClientId("")
            val malIncomplete = integrationItem("mal", enabled = true)
            val kitsuActive = integrationItem("kitsu", enabled = true)
            val komgaDisabled = integrationItem("komga", enabled = false)
            instrumentation.runOnMainSync {
                activity.setValidationContent {
                    SettingsCanvas {
                        SettingsGroup(title = "Fixture integrations") {
                            listOf(malIncomplete, kitsuActive, komgaDisabled).forEach { item ->
                                IntegrationSettingRow(item, statusRefreshToken = 0) {
                                    openCount.incrementAndGet()
                                    opened.countDown()
                                }
                            }
                        }
                    }
                }
            }

            val rootNodes = awaitSemantics(activity) { nodes ->
                listOf("mal", "kitsu", "komga").all { id ->
                    nodes.any { it.tag == "integration_root_row_$id" } &&
                        nodes.any { it.tag == "integration_status_$id" }
                }
            }
            assertIntegrationStatus(activity, rootNodes, "mal", MR.strings.tsuzuki_integration_status_not_configured)
            assertIntegrationStatus(activity, rootNodes, "kitsu", MR.strings.tsuzuki_integration_status_active)
            assertIntegrationStatus(activity, rootNodes, "komga", MR.strings.tsuzuki_integration_status_disabled)
            assertTrue(
                "Every Integration root row is one navigation action",
                rootNodes.filter { it.tag.startsWith("integration_root_row_") }.all { node ->
                    node.hasClickAction && node.role == Role.Button
                },
            )
            assertTrue(
                "Status is informational text, not an interaction",
                rootNodes.filter { it.tag.startsWith("integration_status_") }.all { node ->
                    !node.hasClickAction && node.text.isNotEmpty()
                },
            )
            assertTrue("No enablement switch may be exposed on root", rootNodes.none { it.role == Role.Switch })
            saveScreenshot(File(artifactDirectory(), "integrations-root.png"))
            val rootRow = rootNodes.singleNode("integration_root_row_kitsu")
            val location = screenLocation(activity)
            UiDevice.getInstance(instrumentation).click(
                location[0] + rootRow.bounds.center.x.toInt(),
                location[1] + rootRow.bounds.center.y.toInt(),
            )
            assertTrue("Tapping Integration root should open detail", opened.await(2, TimeUnit.SECONDS))
            assertEquals(1, openCount.get())

            tracker.setClientId("render-validation-client-id")
            val malConfigured = integrationItem("mal", enabled = true)
            val configuredStatusLabel =
                activity.stringResource(MR.strings.tsuzuki_integration_status_configured)
            instrumentation.runOnMainSync {
                activity.setValidationContent {
                    SettingsCanvas {
                        SettingsGroup(title = "Configured integration") {
                            IntegrationSettingRow(malConfigured, statusRefreshToken = 1) {}
                        }
                    }
                }
            }
            val configuredNodes = awaitSemantics(activity) { nodes ->
                nodes.any { it.tag == "integration_root_row_mal" } &&
                    nodes.any {
                        it.tag == "integration_status_mal" && it.text.singleOrNull() == configuredStatusLabel
                    }
            }
            assertIntegrationStatus(activity, configuredNodes, "mal", MR.strings.tsuzuki_integration_status_configured)
            assertTrue(
                "Configured MAL remains a root navigation row",
                configuredNodes.singleNode("integration_root_row_mal").hasClickAction,
            )
            assertTrue(
                "Configured root still has no enablement switch",
                configuredNodes.none { it.role == Role.Switch },
            )
            saveScreenshot(File(artifactDirectory(), "integrations-mal-configured.png"))

            val changed = CountDownLatch(1)
            val changeValue = AtomicReference<Boolean>()
            instrumentation.runOnMainSync {
                activity.setValidationContent {
                    SettingsCanvas {
                        SettingsGroup {
                            IntegrationToggleRow(
                                title = "Enable fixture integration",
                                checked = false,
                                enabled = true,
                                onCheckedChange = { value ->
                                    changeValue.set(value)
                                    changed.countDown()
                                },
                                testTag = "integration_detail_enablement",
                            )
                        }
                    }
                }
            }
            val detailNodes = awaitSemantics(activity) { nodes ->
                nodes.any { it.tag == "integration_detail_enablement" } && nodes.any { it.role == Role.Switch }
            }
            saveScreenshot(File(artifactDirectory(), "integrations-detail.png"))
            val switchTag = detailNodes.singleNode("integration_detail_enablement")
            val switchNode = detailNodes.single { it.role == Role.Switch && rectsOverlap(it.bounds, switchTag.bounds) }
            assertTrue("Detail enablement must be a toggle action", switchNode.hasClickAction)
            val toggleLocation = screenLocation(activity)
            UiDevice.getInstance(instrumentation).click(
                toggleLocation[0] + switchNode.bounds.center.x.toInt(),
                toggleLocation[1] + switchNode.bounds.center.y.toInt(),
            )
            assertTrue("Detail toggle should invoke its callback", changed.await(2, TimeUnit.SECONDS))
            assertEquals(true, changeValue.get())
            writeMeasurements(
                "integrations-measurements.csv",
                listOf("element,role,click,bounds") + rootNodes
                    .filter { it.tag.startsWith("integration_root_row_") || it.tag.startsWith("integration_status_") }
                    .map { "${it.tag},${it.role},${it.hasClickAction},${it.bounds.toCsv()}" } +
                    "${switchNode.tag},${switchNode.role},${switchNode.hasClickAction},${switchNode.bounds.toCsv()}",
            )
        } finally {
            tracker.setClientId(previousClientId)
        }
    }

    @Test
    fun canonicalDetailsHeroUsesSharedBoundsAndFlowsLongTitle() = withHost { activity ->
        val cases = listOf(
            HeroFixture("short-dark", "Re:Zero", dark = true, artwork = "two-thirds"),
            HeroFixture(
                "long-light",
                "Re:Zero kara Hajimeru Isekai Seikatsu: Dai-1 Shou - Outo no Ichinichi-hen",
                dark = false,
                artwork = "wide",
            ),
            HeroFixture(
                "very-long-title",
                "A very long canonical title that wraps naturally across several lines without truncation " +
                    "and keeps every metadata row below the end of its own title geometry",
                dark = false,
                artwork = "tall",
            ),
            HeroFixture("missing-artwork", "No Artwork", dark = false, artwork = null),
            HeroFixture("failed-artwork", "Failed Artwork", dark = false, artwork = "failed"),
        )
        val artifactDirectory = artifactDirectory()
        val measurements = mutableListOf<String>()

        cases.forEach { fixture ->
            val data = when (fixture.artwork) {
                null -> null
                "failed" -> File(activity.cacheDir, "missing-hero-${System.nanoTime()}.png").toURI().toString()
                else -> {
                    val artwork = requireNotNull(fixture.artwork)
                    val dimensions = when (artwork) {
                        "two-thirds" -> ArtworkFixture(
                            artwork,
                            300,
                            450,
                            whitespace = false,
                            darkArtwork = fixture.dark,
                        )
                        "wide" -> ArtworkFixture(
                            artwork,
                            600,
                            200,
                            whitespace = false,
                            darkArtwork = fixture.dark,
                        )
                        else -> ArtworkFixture(
                            artwork,
                            200,
                            600,
                            whitespace = false,
                            darkArtwork = fixture.dark,
                        )
                    }
                    createFixture(activity.cacheDir, dimensions).toURI().toString()
                }
            }
            val loadEvents = Collections.synchronizedList(mutableListOf<MangaCoverLoadEvent>())
            val artworkLoad = CountDownLatch(if (data == null) 0 else 1)
            val state = CanonicalTitleScreenState.Loaded(
                title = CanonicalTitle(
                    id = "fixture-${fixture.name}",
                    displayTitle = fixture.title,
                    identityState = CanonicalIdentityState.SOURCE_ONLY,
                    createdAt = 0,
                    updatedAt = 0,
                ),
                libraryEntry = null,
                chapters = emptyList(),
                coverUrl = data,
                startDate = "1997",
                editorialFormat = "Manga",
                editorialStatus = "Ongoing",
            )
            instrumentation.runOnMainSync {
                activity.setValidationContent(dark = fixture.dark) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        CanonicalDetailsHero(
                            state = state,
                            background = defaultSkinColors().canvas,
                            onReadTitle = {},
                            onAddToLibrary = {},
                            onRemoveFromLibrary = {},
                            onRefresh = {},
                            onSynopsis = {},
                            onArtworkLoadEvent = { event ->
                                loadEvents += event
                                if (event is MangaCoverLoadEvent.Succeeded || event is MangaCoverLoadEvent.Failed) {
                                    artworkLoad.countDown()
                                }
                            },
                        )
                        Text(
                            "First content after Hero",
                            Modifier.padding(16.dp).then(Modifier.testTag("hero_first_post_content")),
                        )
                    }
                }
            }
            instrumentation.waitForIdleSync()
            if (data != null) {
                assertTrue("Hero artwork did not complete for ${fixture.name}", artworkLoad.await(10, TimeUnit.SECONDS))
            }
            if (fixture.artwork == "failed") {
                assertTrue(loadEvents.any { it is MangaCoverLoadEvent.Failed && !it.willFallback })
            } else if (data != null) {
                assertTrue(loadEvents.any { it is MangaCoverLoadEvent.Succeeded })
            }
            var nodes = awaitSemantics(activity) { snapshot ->
                snapshot.any { it.tag == "canonical_details_hero_title" && fixture.title in it.text } &&
                    (
                        fixture.artwork == null || fixture.artwork == "failed" ||
                            snapshot.any { it.tag == "canonical_details_hero_frame" }
                        )
            }
            if (fixture.artwork == "failed") {
                nodes = awaitSemantics(activity) { snapshot ->
                    snapshot.any { it.tag == "canonical_details_hero_title" && fixture.title in it.text } &&
                        snapshot.none { it.tag == "canonical_details_hero_frame" }
                }
            }
            val hero = nodes.singleNode("canonical_details_hero_content")
            val title = nodes.singleNode("canonical_details_hero_title")
            val metadata = nodes.singleNode("canonical_details_hero_metadata")
            val nextContent = nodes.singleNode("hero_first_post_content")
            assertVerticalFlow(title.layoutBounds, metadata.layoutBounds, "metadata for ${fixture.name}")
            assertVerticalFlow(metadata.layoutBounds, nextContent.layoutBounds, "post-Hero content for ${fixture.name}")
            assertTrue(
                "post-Hero content overlaps Hero for ${fixture.name}",
                nextContent.layoutBounds.top >= hero.layoutBounds.bottom - 1f,
            )

            val frame = nodes.singleNodeOrNull("canonical_details_hero_frame")
            val artwork = nodes.singleNodeOrNull("canonical_details_hero_artwork")
            val fade = nodes.singleNodeOrNull("canonical_details_hero_fade")
            if (fixture.artwork == null || fixture.artwork == "failed") {
                assertTrue("Absent/failed artwork must collapse the frame", frame == null)
            } else {
                assertNotNull("Loaded artwork frame missing for ${fixture.name}", frame)
                assertNotNull("Artwork layer missing for ${fixture.name}", artwork)
                assertNotNull("Fade layer missing for ${fixture.name}", fade)
                assertRectApproximately(requireNotNull(frame).layoutBounds, requireNotNull(artwork).layoutBounds)
                assertRectApproximately(requireNotNull(frame).layoutBounds, requireNotNull(fade).layoutBounds)
                assertTrue(
                    "Decorative Hero image must not expose duplicate semantics",
                    artwork?.contentDescription.isNullOrEmpty(),
                )
                if (fixture.name == "short-dark" || fixture.name == "long-light") {
                    val location = screenLocation(activity)
                    val sampleX = location[0] + (frame.layoutBounds.left + frame.layoutBounds.width / 2f).toInt()
                    val sampleY = location[1] + (frame.layoutBounds.top + frame.layoutBounds.height * 0.12f).toInt()
                    val sample = awaitScreenPixels(activity, listOf(sampleX to sampleY)) { pixels ->
                        val color = pixels.single()
                        if (fixture.name == "short-dark") {
                            AndroidColor.red(color) in 20..100 && AndroidColor.green(color) in 20..100
                        } else {
                            AndroidColor.red(color) > 180 &&
                                AndroidColor.green(color) in 140..235 &&
                                AndroidColor.blue(color) > 150
                        }
                    }.single()
                    measurements += "${fixture.name}-painted-artwork,${Integer.toHexString(sample)}"
                }
            }

            val screenshot = File(artifactDirectory, "details-${fixture.name}.png")
            saveScreenshot(screenshot)
            assertTrue("Expected a non-empty Details render for ${fixture.name}", screenshot.length() > 0L)
            measurements += listOf(
                fixture.name,
                hero.layoutBounds.toCsv(),
                title.layoutBounds.toCsv(),
                metadata.layoutBounds.toCsv(),
                nextContent.layoutBounds.toCsv(),
            ).joinToString(",")
        }

        File(artifactDirectory, "details-measurements.csv").writeText(
            "fixture,hero,title,metadata,next\n${measurements.joinToString("\n")}\n",
        )
    }

    private fun withHost(block: (DefaultSkinValidationActivity) -> Unit) {
        val context = instrumentation.targetContext
        val intent = Intent(context, DefaultSkinValidationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as DefaultSkinValidationActivity
        try {
            block(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun semanticsNodes(activity: DefaultSkinValidationActivity): List<NodeSnapshot> {
        var result = emptyList<NodeSnapshot>()
        instrumentation.runOnMainSync {
            if (
                !activity.composeView.isAttachedToWindow ||
                !activity.composeView.isLaidOut ||
                !activity.composeView.hasWindowFocus()
            ) {
                return@runOnMainSync
            }
            val root = activity.findViewRootForTest()
            root.measureAndLayoutForTest()
            result = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).map { node ->
                val position = node.positionInRoot
                val size = node.size
                NodeSnapshot(
                    tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" },
                    bounds = node.boundsInRoot,
                    layoutBounds = Rect(
                        position.x,
                        position.y,
                        position.x + size.width,
                        position.y + size.height,
                    ),
                    contentDescription = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() },
                    text = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text },
                    role = if (node.config.contains(SemanticsProperties.Role)) {
                        node.config[SemanticsProperties.Role]
                    } else {
                        null
                    },
                    hasClickAction = node.config.contains(SemanticsActions.OnClick),
                    selected = if (node.config.contains(SemanticsProperties.Selected)) {
                        node.config[SemanticsProperties.Selected]
                    } else {
                        null
                    },
                    enabled = !node.config.contains(SemanticsProperties.Disabled),
                    toggleableState = if (node.config.contains(SemanticsProperties.ToggleableState)) {
                        node.config[SemanticsProperties.ToggleableState]
                    } else {
                        null
                    },
                )
            }
        }
        return result
    }

    private fun DefaultSkinValidationActivity.findViewRootForTest(): ViewRootForTest {
        fun find(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    find(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        return requireNotNull(find(window.decorView)) { "Compose ViewRootForTest was not attached" }
    }

    private fun List<NodeSnapshot>.singleNode(tag: String): NodeSnapshot =
        single { it.tag == tag }

    private fun List<NodeSnapshot>.singleNodeOrNull(tag: String): NodeSnapshot? =
        singleOrNull { it.tag == tag }

    private fun List<NodeSnapshot>.textWithin(node: NodeSnapshot): List<String> =
        filter { it.text.isNotEmpty() && rectContains(node.layoutBounds, it.layoutBounds) }
            .flatMap { it.text }

    private fun integrationItem(id: String, enabled: Boolean) = TsuzukiIntegrationSettingsItem(
        id = IntegrationId(id),
        label = when (id) {
            "mal" -> "MyAnimeList"
            "kitsu" -> "Kitsu"
            "komga" -> "Komga"
            else -> id
        },
        category = if (id == "komga") IntegrationCategory.PERSONAL_SERVER else IntegrationCategory.METADATA_SERVICE,
        enabled = enabled,
        legacyTrackerId = when (id) {
            "mal" -> 1L
            "kitsu" -> 3L
            "komga" -> 6L
            else -> null
        },
        supportsTracking = false,
        capabilities = emptyList(),
        configurableCapabilities = emptySet(),
        restrictedCapabilities = emptyMap(),
        policies = emptyMap(),
        configState = TsuzukiIntegrationConfigState.DEFAULT,
        authState = TsuzukiIntegrationAuthState.NOT_REQUIRED,
        capabilityEnabled = emptyMap(),
    )

    private fun assertIntegrationStatus(
        context: android.content.Context,
        nodes: List<NodeSnapshot>,
        id: String,
        expectedResource: dev.icerock.moko.resources.StringResource,
    ) {
        val status = nodes.singleNode("integration_status_$id")
        assertEquals(
            "Unexpected runtime-backed status for $id",
            context.stringResource(expectedResource),
            status.text.singleOrNull(),
        )
        assertTrue("Integration status must not own a separate click action", !status.hasClickAction)
    }

    private fun screenLocation(activity: DefaultSkinValidationActivity): IntArray {
        val location = IntArray(2)
        instrumentation.runOnMainSync { activity.composeView.getLocationOnScreen(location) }
        return location
    }

    private fun rectsOverlap(first: Rect, second: Rect): Boolean =
        first.left < second.right && first.right > second.left &&
            first.top < second.bottom && first.bottom > second.top

    private fun rectContains(outer: Rect, inner: Rect, tolerance: Float = 1.1f): Boolean =
        outer.left <= inner.left + tolerance && outer.top <= inner.top + tolerance &&
            outer.right + tolerance >= inner.right && outer.bottom + tolerance >= inner.bottom

    private fun assertRectContains(outer: Rect, inner: Rect) {
        val tolerance = 1.1f
        assertTrue("Expected $outer to contain $inner", outer.left <= inner.left + tolerance)
        assertTrue("Expected $outer to contain $inner", outer.top <= inner.top + tolerance)
        assertTrue("Expected $outer to contain $inner", outer.right + tolerance >= inner.right)
        assertTrue("Expected $outer to contain $inner", outer.bottom + tolerance >= inner.bottom)
    }

    private fun assertVerticalFlow(previous: Rect, next: Rect, label: String) {
        assertTrue("$label overlaps previous content: previous=$previous next=$next", next.top >= previous.bottom - 1f)
    }

    private fun awaitSemantics(
        activity: DefaultSkinValidationActivity,
        timeoutMs: Long = 5_000,
        predicate: (List<NodeSnapshot>) -> Boolean,
    ): List<NodeSnapshot> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var latest = emptyList<NodeSnapshot>()
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            latest = semanticsNodes(activity)
            if (predicate(latest)) return latest
            SystemClock.sleep(40)
        }
        val diagnostics = writeHostTimeoutDiagnostics(activity, latest)
        throw AssertionError("Timed out waiting for current Compose fixture; $diagnostics")
    }

    private fun writeHostTimeoutDiagnostics(
        activity: DefaultSkinValidationActivity,
        latest: List<NodeSnapshot>,
    ): String {
        var viewState = "unavailable"
        instrumentation.runOnMainSync {
            val composeView = activity.composeView
            val decorView = activity.window.decorView
            viewState = listOf(
                "activityWindowFocus=${activity.hasWindowFocus()}",
                "decorAttached=${decorView.isAttachedToWindow}",
                "decorLaidOut=${decorView.isLaidOut}",
                "decorWindowFocus=${decorView.hasWindowFocus()}",
                "composeAttached=${composeView.isAttachedToWindow}",
                "composeLaidOut=${composeView.isLaidOut}",
                "composeWindowFocus=${composeView.hasWindowFocus()}",
                "composeShown=${composeView.isShown}",
                "composeVisibility=${composeView.visibility}",
                "composeWindowVisibility=${composeView.windowVisibility}",
                "composeSize=${composeView.width}x${composeView.height}",
                "finishing=${activity.isFinishing}",
                "destroyed=${activity.isDestroyed}",
            ).joinToString(" ")
        }
        val context = instrumentation.targetContext
        val power = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val keyguard = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val directory = artifactDirectory()
        val stem = "diagnostic-host-${SystemClock.uptimeMillis()}"
        val screenshot = File(directory, "$stem.png")
        val screenshotResult = runCatching {
            saveScreenshot(screenshot)
            screenshot.absolutePath
        }.getOrElse { "failed:${it.javaClass.simpleName}:${it.message}" }
        val details = listOf(
            viewState,
            "screenInteractive=${power.isInteractive}",
            "keyguardLocked=${keyguard.isKeyguardLocked}",
            "deviceLocked=${keyguard.isDeviceLocked}",
            "lastTags=${latest.map { it.tag }}",
            "screenshot=$screenshotResult",
        ).joinToString("\n")
        File(directory, "$stem.txt").writeText(details)
        return details.replace('\n', ';')
    }

    private fun awaitScreenPixels(
        activity: DefaultSkinValidationActivity,
        points: List<Pair<Int, Int>>,
        predicate: (List<Int>) -> Boolean,
    ): List<Int> {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var latest = emptyList<Int>()
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(UiDevice.getInstance(instrumentation).takeScreenshot())
            try {
                latest = points.map { (x, y) ->
                    assertTrue(
                        "Pixel sample ($x,$y) must be inside screenshot ${bitmap.width}x${bitmap.height}",
                        x in 0 until bitmap.width && y in 0 until bitmap.height,
                    )
                    bitmap.getPixel(x, y)
                }
            } finally {
                bitmap.recycle()
            }
            if (predicate(latest)) return latest
            SystemClock.sleep(40)
        }
        assertTrue("Timed out waiting for expected painted fixture pixels; last=$latest", false)
        return latest
    }

    private fun awaitStableScreenshot(
        destination: File,
        samplePoints: List<Pair<Int, Int>>,
    ): List<Int> {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var previous: List<Int>? = null
        var stableFrames = 0
        var latestPixels = emptyList<Int>()
        var latestBitmap: Bitmap? = null
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(UiDevice.getInstance(instrumentation).takeScreenshot())
            val pixels = samplePoints.map { (x, y) ->
                assertTrue(
                    "Pixel sample ($x,$y) must be inside screenshot ${bitmap.width}x${bitmap.height}",
                    x in 0 until bitmap.width && y in 0 until bitmap.height,
                )
                bitmap.getPixel(x, y)
            }
            latestBitmap?.recycle()
            latestBitmap = bitmap
            latestPixels = pixels
            stableFrames = if (pixels == previous) stableFrames + 1 else 0
            if (stableFrames >= 2) {
                FileOutputStream(destination).use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                bitmap.recycle()
                latestBitmap = null
                assertTrue("Expected a non-empty settled palette screenshot", destination.length() > 0L)
                return pixels
            }
            previous = pixels
            SystemClock.sleep(40)
        }
        latestBitmap?.let { bitmap ->
            FileOutputStream(destination).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
        }
        assertTrue("Palette pixels did not stabilize; last sample=$latestPixels", false)
        return latestPixels
    }

    private fun assertRectApproximately(actual: Rect, expected: Rect) {
        val tolerance = 1.1f
        assertTrue("left differs: $actual vs $expected", kotlin.math.abs(actual.left - expected.left) <= tolerance)
        assertTrue("top differs: $actual vs $expected", kotlin.math.abs(actual.top - expected.top) <= tolerance)
        assertTrue("right differs: $actual vs $expected", kotlin.math.abs(actual.right - expected.right) <= tolerance)
        assertTrue(
            "bottom differs: $actual vs $expected",
            kotlin.math.abs(actual.bottom - expected.bottom) <= tolerance,
        )
    }

    private fun assertRatio(bounds: Rect, expected: Float) {
        val actual = bounds.width / bounds.height
        assertTrue("Expected 2:3 artwork frame, actual=$actual", kotlin.math.abs(actual - expected) < 0.015f)
    }

    private fun Rect.toCsv(): String =
        listOf(left, top, right, bottom).joinToString(";") { "%.1f".format(java.util.Locale.ROOT, it) }

    private fun artifactDirectory(): File = File(
        requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
        "default-skin-rendered",
    ).apply {
        mkdirs()
    }

    private fun saveScreenshot(destination: File) {
        val bitmap = requireNotNull(UiDevice.getInstance(instrumentation).takeScreenshot())
        FileOutputStream(destination).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
        assertTrue("Could not save non-empty render artifact: $destination", destination.length() > 0L)
    }

    private fun writeMeasurements(name: String, lines: List<String>) {
        val output = File(artifactDirectory(), name)
        output.writeText(lines.joinToString("\n", postfix = "\n"))
        assertTrue("Expected measured evidence in $name", output.length() > 0L)
    }

    private fun createFixture(directory: File, fixture: ArtworkFixture, markerBands: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = if (fixture.darkArtwork) AndroidColor.rgb(37, 40, 45) else AndroidColor.rgb(246, 207, 218)
        canvas.drawColor(background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (fixture.darkArtwork) AndroidColor.rgb(235, 221, 184) else AndroidColor.rgb(57, 43, 52)
        }
        if (fixture.whitespace) {
            canvas.drawRect(
                RectF(fixture.width * 0.22f, fixture.height * 0.24f, fixture.width * 0.78f, fixture.height * 0.76f),
                paint,
            )
        } else if (markerBands) {
            paint.color = AndroidColor.rgb(246, 49, 166)
            canvas.drawRect(0f, fixture.height * 0.25f, fixture.width * 0.2f, fixture.height * 0.75f, paint)
            paint.color = AndroidColor.rgb(25, 221, 242)
            canvas.drawRect(
                fixture.width * 0.8f,
                fixture.height * 0.25f,
                fixture.width.toFloat(),
                fixture.height * 0.75f,
                paint,
            )
        }
        val tone = if (fixture.darkArtwork) "dark" else "light"
        val markers = if (markerBands) "-markers" else ""
        val output = File(directory, "default-skin-${fixture.name}-$tone$markers.png")
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return output
    }

    private data class ArtworkFixture(
        val name: String,
        val width: Int,
        val height: Int,
        val whitespace: Boolean,
        val darkArtwork: Boolean = false,
    )

    private data class HeroFixture(
        val name: String,
        val title: String,
        val dark: Boolean,
        val artwork: String?,
    )

    private data class PaletteFixture(
        val name: String,
        val dark: Boolean,
        val canvasColor: Int,
        val surfaceColor: Int,
    )

    private data class NodeSnapshot(
        val tag: String,
        val bounds: Rect,
        val layoutBounds: Rect,
        val contentDescription: List<String>,
        val text: List<String>,
        val role: Role?,
        val hasClickAction: Boolean,
        val selected: Boolean?,
        val enabled: Boolean,
        val toggleableState: ToggleableState?,
    )
}
