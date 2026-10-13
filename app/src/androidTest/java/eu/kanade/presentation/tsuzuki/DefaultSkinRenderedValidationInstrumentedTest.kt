package eu.kanade.presentation.tsuzuki

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
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
        private val renderedRunId = UUID.randomUUID().toString()

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
                activity.setValidationContent(sceneId = "search-${fixture.name}") {
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
            val screenshot = File(artifactDirectory, "search-${fixture.name}.png")
            val markerPixels = captureRenderedState(
                activity = activity,
                destination = screenshot,
                expectedTheme = activity.validationThemeForEvidence,
                fixtureId = fixture.name,
                expectedSceneId = "search-${fixture.name}",
                targetTag = "search_compact_card",
                identityTags = listOf(
                    "search_compact_card",
                    "search_compact_artwork_frame",
                    "search_compact_artwork_foreground",
                ),
                requiredSemanticText = "fixture:${fixture.name}",
            ) { bitmap, windowOrigin, _ ->
                if (fixture.name == "wide") {
                    val leftMarker = AndroidColor.rgb(246, 49, 166)
                    val rightMarker = AndroidColor.rgb(25, 221, 242)
                    val centerY = windowOrigin[1] + frame.layoutBounds.center.y.toInt()
                    val leftX = windowOrigin[0] + (frame.layoutBounds.left + frame.layoutBounds.width * 0.12f).toInt()
                    val rightX = windowOrigin[0] + (frame.layoutBounds.left + frame.layoutBounds.width * 0.88f).toInt()
                    val markers = mapOf(
                        "foreground-left-marker" to PixelProbe(leftX, centerY, bitmap.getPixel(leftX, centerY)),
                        "foreground-right-marker" to PixelProbe(rightX, centerY, bitmap.getPixel(rightX, centerY)),
                    )
                    measurements += listOf(
                        "wide-markers",
                        Integer.toHexString(markers.getValue("foreground-left-marker").color),
                        Integer.toHexString(markers.getValue("foreground-right-marker").color),
                    ).joinToString(",")
                    markers
                } else {
                    emptyMap()
                }
            }
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
                activity.setValidationContent(sceneId = "settings-root-${if (dark) "dark" else "light"}", dark = dark) {
                    Navigator(screen = SettingsMainScreen, onBackPressed = null) { _ ->
                        SettingsMainScreen.Content(twoPane = false, navigateUp = null)
                    }
                }
            }
            awaitSemantics(activity) { nodes -> nodes.any { it.tag == "settings_heading_account" } }
            captureRenderedState(
                activity = activity,
                destination = File(artifactDirectory, if (dark) "settings-dark.png" else "settings-light.png"),
                expectedTheme = if (dark) "dark" else "light",
                fixtureId = "settings-root",
                expectedSceneId = "settings-root-${if (dark) "dark" else "light"}",
                targetTag = "settings_surface_account",
                identityTags = listOf(
                    "settings_heading_account",
                    "settings_surface_account",
                    "settings_destination_account",
                    "settings_destination_tracking",
                ),
            )

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
                activity.setValidationContent(sceneId = "palette-${fixture.name}", dark = fixture.dark) {
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
            val screenshot = File(artifactDirectory, "palette-${fixture.name}.png")
            val pixels = captureRenderedState(
                activity = activity,
                destination = screenshot,
                expectedTheme = fixture.name,
                fixtureId = "semantic-palette-roles",
                expectedSceneId = "palette-${fixture.name}",
                targetTag = "palette_canvas",
                identityTags = listOf("palette_canvas", "palette_surface", "palette_surface_probe"),
            ) { bitmap, windowOrigin, _ ->
                val canvasX = windowOrigin[0] + canvas.left.toInt() + 2
                val canvasY = windowOrigin[1] + canvas.center.y.toInt()
                val surfaceX = windowOrigin[0] + surface.right.toInt() - 3
                val surfaceY = windowOrigin[1] + surface.center.y.toInt()
                mapOf(
                    "canvas" to PixelProbe(canvasX, canvasY, bitmap.getPixel(canvasX, canvasY)),
                    "surface" to PixelProbe(surfaceX, surfaceY, bitmap.getPixel(surfaceX, surfaceY)),
                )
            }
            val canvasProbe = pixels.getValue("canvas")
            val surfaceProbe = pixels.getValue("surface")
            val canvasPixel = canvasProbe.color
            val surfacePixel = surfaceProbe.color
            paletteMeasurements += listOf(
                fixture.name,
                Integer.toHexString(canvasPixel),
                Integer.toHexString(surfacePixel),
                canvas.toCsv(),
                surface.toCsv(),
                "${canvasProbe.x};${canvasProbe.y}",
                "${surfaceProbe.x};${surfaceProbe.y}",
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
                    "canvas-window-point",
                    "surface-window-point",
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
        val observedPreviewProperties = linkedMapOf<String, NodeSnapshot>()
        listOf(false, true).forEach { dark ->
            instrumentation.runOnMainSync {
                activity.setValidationContent(
                    sceneId = "appearance-theme-previews-${if (dark) "dark" else "light"}",
                    dark = dark,
                ) {
                    Navigator(screen = SettingsAppearanceScreen, onBackPressed = null) { _ ->
                        SettingsAppearanceScreen.Content()
                    }
                }
            }
            if (fixture.name == "wide") {
                val leftMarker = AndroidColor.rgb(246, 49, 166)
                val rightMarker = AndroidColor.rgb(25, 221, 242)
                assertEquals(
                    "Fit must preserve the complete source's left and right markers",
                    listOf(leftMarker, rightMarker),
                    markerPixels.values.map { it.color },
                )
            }
            val nodes = awaitSemantics(activity) { snapshot ->
                snapshot.count { it.tag.startsWith("appearance_theme_preview_") } >= 2
            }
            val previews = nodes.filter { it.tag.startsWith("appearance_theme_preview_") }
            assertTrue("Appearance should offer multiple real themes", previews.size >= 2)
            previews.forEach { preview ->
                observedPreviewProperties[preview.tag] = preview
                val ratio = preview.layoutBounds.width / preview.layoutBounds.height
                assertTrue("Theme sample ${preview.tag} should be compact landscape, ratio=$ratio", ratio > 1f)
                assertTrue(
                    "Theme sample must not dominate the viewport: ${preview.tag}",
                    preview.layoutBounds.height < activity.composeView.height * 0.30f,
                )
                assertEquals(
                    "Theme preview ${preview.tag} role=${preview.role} selected=${preview.selected} " +
                        "click=${preview.hasClickAction} name=${preview.contentDescription}",
                    Role.RadioButton,
                    preview.role,
                )
                assertTrue(
                    "Theme preview ${preview.tag} must remain selectable; " +
                        "role=${preview.role} selected=${preview.selected} name=${preview.contentDescription}",
                    preview.hasClickAction,
                )
                assertNotNull(
                    "Theme preview ${preview.tag} must expose selected state; role=${preview.role}",
                    preview.selected,
                )
                assertTrue(
                    "Theme preview must announce a real theme name",
                    preview.contentDescription.isNotEmpty(),
                )
            }
            val selectedChoices = previews.filter { it.selected == true }
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
            val selectedPreview = previews.single { it.selected == true }
            captureRenderedState(
                activity = activity,
                destination = File(artifactDirectory, if (dark) "appearance-dark.png" else "appearance-light.png"),
                expectedTheme = if (dark) "dark" else "light",
                fixtureId = "appearance-theme-previews",
                expectedSceneId = "appearance-theme-previews-${if (dark) "dark" else "light"}",
                targetTag = selectedPreview.tag,
                identityTags = listOf(selectedPreview.tag, "appearance_theme_mode", "appearance_amoled"),
            )

            val availableThemes = AppTheme.entries
                .filterNot { it.titleRes == null || (it == AppTheme.MONET && !DeviceUtil.isDynamicColorAvailable) }
            val expectedThemeKeys = availableThemes.map { it.name.lowercase() }.toSet()
            val observedThemeKeys = mutableSetOf<String>()
            availableThemes.forEachIndexed { index, theme ->
                val previewTag = "appearance_theme_preview_${theme.name.lowercase()}"
                assertTrue(
                    "Appearance theme rail must expose indexed horizontal scrolling",
                    scrollHorizontalThemeRailToIndex(activity, index),
                )
                val previewNodes = awaitSemantics(activity) { snapshot ->
                    snapshot.any { it.tag == previewTag }
                }
                val preview = previewNodes.singleNode(previewTag)
                observedPreviewProperties[preview.tag] = preview
                observedThemeKeys += theme.name.lowercase()
                assertEquals(
                    "Preview ${preview.tag} must expose radio-button semantics",
                    Role.RadioButton,
                    preview.role,
                )
                assertTrue("Preview ${preview.tag} must remain clickable", preview.hasClickAction)
                assertNotNull("Preview ${preview.tag} must expose selected state", preview.selected)
                assertTrue("Preview ${preview.tag} must announce its theme", preview.contentDescription.isNotEmpty())
            }
            assertEquals(
                "Every available real theme must remain represented/selectable",
                expectedThemeKeys,
                observedThemeKeys,
            )
            if (!dark) {
                val tealTag = "appearance_theme_preview_tealturquoise"
                val tealPreview = observedPreviewProperties[tealTag]
                    ?: throw AssertionError("The real Teal Turquoise preview was never exposed: $tealTag")
                assertEquals(
                    "Exact Teal Turquoise preview semantics; selected=${tealPreview.selected}, " +
                        "click=${tealPreview.hasClickAction}, name=${tealPreview.contentDescription}",
                    Role.RadioButton,
                    tealPreview.role,
                )
                assertTrue("Exact Teal Turquoise preview must remain selectable", tealPreview.hasClickAction)
                assertNotNull("Exact Teal Turquoise preview must expose selection", tealPreview.selected)
                assertTrue(
                    "Exact Teal Turquoise preview must announce its real name",
                    tealPreview.contentDescription.isNotEmpty(),
                )
            }
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
                activity.setValidationContent(sceneId = "appearance-mode-system-light") {
                    Navigator(screen = SettingsAppearanceScreen, onBackPressed = null) { _ ->
                        SettingsAppearanceScreen.Content()
                    }
                }
            }
            ThemeMode.entries.forEach { mode ->
                instrumentation.runOnMainSync {
                    preferences.themeMode.set(mode)
                    activity.markValidationScene("appearance-mode-${mode.name.lowercase()}")
                }
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
                captureRenderedState(
                    activity = activity,
                    destination = File(artifactDirectory, "appearance-mode-${mode.name.lowercase()}.png"),
                    expectedTheme = "light",
                    fixtureId = "theme-mode-${mode.name.lowercase()}",
                    expectedSceneId = "appearance-mode-${mode.name.lowercase()}",
                    targetTag = "appearance_theme_mode",
                    identityTags = listOf("appearance_theme_mode", "appearance_amoled", selectedPreview.tag),
                    requiredSemanticText = modeLabels.getValue(mode),
                )

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
            listOf(
                "preview,ratio,visible-bounds,unclipped-bounds,viewport-clipped,role,selected,click,accessible-name",
            ) +
                observedPreviewProperties.values.map {
                    listOf(
                        it.tag,
                        "${it.layoutBounds.width / it.layoutBounds.height}",
                        it.bounds.toCsv(),
                        it.layoutBounds.toCsv(),
                        (it.bounds != it.layoutBounds).toString(),
                        it.role,
                        it.selected,
                        it.hasClickAction,
                        it.contentDescription.joinToString("|"),
                    ).joinToString(",")
                },
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
                activity.setValidationContent(sceneId = "integrations-runtime-statuses") {
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
            captureRenderedState(
                activity = activity,
                destination = File(artifactDirectory(), "integrations-root.png"),
                expectedTheme = "light",
                fixtureId = "integrations-runtime-statuses",
                expectedSceneId = "integrations-runtime-statuses",
                targetTag = "integration_root_row_kitsu",
                identityTags = listOf(
                    "integration_root_row_mal",
                    "integration_status_mal",
                    "integration_root_row_kitsu",
                    "integration_status_kitsu",
                    "integration_root_row_komga",
                    "integration_status_komga",
                ),
            )
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
                activity.setValidationContent(sceneId = "integrations-mal-configured") {
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
            captureRenderedState(
                activity = activity,
                destination = File(artifactDirectory(), "integrations-mal-configured.png"),
                expectedTheme = "light",
                fixtureId = "integrations-mal-configured",
                expectedSceneId = "integrations-mal-configured",
                targetTag = "integration_root_row_mal",
                identityTags = listOf("integration_root_row_mal", "integration_status_mal"),
                requiredSemanticText = configuredStatusLabel,
            )

            val changed = CountDownLatch(1)
            val changeValue = AtomicReference<Boolean>()
            instrumentation.runOnMainSync {
                activity.setValidationContent(sceneId = "integration-detail-disabled-switch") {
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
            captureRenderedState(
                activity = activity,
                destination = File(artifactDirectory(), "integrations-detail.png"),
                expectedTheme = "light",
                fixtureId = "integration-detail-disabled-switch",
                expectedSceneId = "integration-detail-disabled-switch",
                targetTag = "integration_detail_enablement",
                identityTags = listOf("integration_detail_enablement"),
            )
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
                activity.setValidationContent(sceneId = "details-${fixture.name}", dark = fixture.dark) {
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
            }

            val screenshot = File(artifactDirectory, "details-${fixture.name}.png")
            val probes = captureRenderedState(
                activity = activity,
                destination = screenshot,
                expectedTheme = if (fixture.dark) "dark" else "light",
                fixtureId = fixture.name,
                expectedSceneId = "details-${fixture.name}",
                targetTag = "canonical_details_hero_content",
                identityTags = listOf(
                    "canonical_details_hero_content",
                    "canonical_details_hero_title",
                    "canonical_details_hero_metadata",
                ),
                requiredSemanticText = fixture.title,
            ) { bitmap, windowOrigin, _ ->
                if (frame != null && (fixture.name == "short-dark" || fixture.name == "long-light")) {
                    val sampleX = windowOrigin[0] + (frame.layoutBounds.left + frame.layoutBounds.width / 2f).toInt()
                    val sampleY = windowOrigin[1] + (frame.layoutBounds.top + frame.layoutBounds.height * 0.12f).toInt()
                    mapOf("painted-artwork" to PixelProbe(sampleX, sampleY, bitmap.getPixel(sampleX, sampleY)))
                } else {
                    emptyMap()
                }
            }
            probes["painted-artwork"]?.let { probe ->
                if (fixture.name == "short-dark") {
                    assertTrue(
                        "Dark Hero fixture must paint dark artwork, actual=${Integer.toHexString(probe.color)}",
                        AndroidColor.red(probe.color) in 20..100 && AndroidColor.green(probe.color) in 20..100,
                    )
                } else {
                    assertTrue(
                        "Light Hero fixture must paint light artwork, actual=${Integer.toHexString(probe.color)}",
                        AndroidColor.red(probe.color) > 180 &&
                            AndroidColor.green(probe.color) in 140..235 &&
                            AndroidColor.blue(probe.color) > 150,
                    )
                }
                measurements += "${fixture.name}-painted-artwork,${Integer.toHexString(probe.color)}"
            }
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

    private fun scrollHorizontalThemeRailToIndex(activity: DefaultSkinValidationActivity, index: Int): Boolean {
        var scrolled = false
        instrumentation.runOnMainSync {
            val scrollableNodes = activity.findViewRootForTest().semanticsOwner
                .getAllSemanticsNodes(mergingEnabled = false)
                .filter { node ->
                    node.config.contains(SemanticsProperties.HorizontalScrollAxisRange) &&
                        node.config.contains(SemanticsActions.ScrollToIndex)
                }
            if (scrollableNodes.size == 1) {
                scrolled = scrollableNodes.single()
                    .config[SemanticsActions.ScrollToIndex]
                    .action
                    ?.invoke(index) == true
            }
        }
        return scrolled
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

    private fun captureRenderedState(
        activity: DefaultSkinValidationActivity,
        destination: File,
        expectedTheme: String,
        fixtureId: String,
        expectedSceneId: String,
        targetTag: String,
        identityTags: List<String>,
        requiredSemanticText: String? = null,
        pixelProbes: (Bitmap, IntArray, Rect) -> Map<String, PixelProbe> = { _, _, _ -> emptyMap() },
    ): Map<String, PixelProbe> {
        fun matches(nodes: List<NodeSnapshot>): Boolean {
            val tags = nodes.map { it.tag }.toSet()
            val texts = nodes.flatMap { it.text + it.contentDescription }
            return identityTags.all { it in tags } &&
                (requiredSemanticText == null || requiredSemanticText in texts)
        }

        var sceneTag = ""
        var sceneGeneration = -1
        var activeSceneId = ""
        instrumentation.runOnMainSync {
            sceneTag = activity.validationSceneTagForEvidence
            sceneGeneration = activity.validationSceneGeneration
            activeSceneId = activity.validationSceneIdForEvidence
        }
        assertEquals("Capture request must name the active host fixture", expectedSceneId, activeSceneId)
        val before = awaitSemantics(activity) { nodes ->
            matches(nodes) && nodes.any { it.tag == sceneTag } && renderedTheme(nodes) == expectedTheme
        }
        val targetBefore = before.singleNode(targetTag)
        val actualThemeBefore = renderedTheme(before)
        var hostRequestedTheme = ""
        var windowOrigin = IntArray(2)
        var bitmapWidth = 0
        var bitmapHeight = 0
        instrumentation.runOnMainSync {
            hostRequestedTheme = activity.validationThemeForEvidence
            val composeView = activity.composeView
            assertTrue("Rendered capture requires hardware acceleration", composeView.isHardwareAccelerated)
            composeView.getLocationInWindow(windowOrigin)
            bitmapWidth = activity.window.decorView.width
            bitmapHeight = activity.window.decorView.height
        }
        assertEquals("Host theme request must agree with rendered configuration", expectedTheme, hostRequestedTheme)
        assertTrue("Window capture has no measured bounds", bitmapWidth > 0 && bitmapHeight > 0)

        val frameCommitted = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val view = activity.composeView
            val observer = view.viewTreeObserver
            assertTrue("Compose ViewTreeObserver is not alive", observer.isAlive)
            observer.registerFrameCommitCallback { frameCommitted.countDown() }
            view.invalidate()
        }
        val frameCommittedInTime = frameCommitted.await(5, TimeUnit.SECONDS)
        if (!frameCommittedInTime) {
            val diagnostics = writeHostTimeoutDiagnostics(activity, before)
            throw AssertionError("Timed out waiting for a committed frame for $fixtureId; $diagnostics")
        }

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        try {
            val copied = CountDownLatch(1)
            val pixelCopyResult = AtomicInteger(Int.MIN_VALUE)
            PixelCopy.request(
                activity.window,
                bitmap,
                { result ->
                    pixelCopyResult.set(result)
                    copied.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
            assertTrue(
                "Timed out copying committed window pixels for $fixtureId",
                copied.await(5, TimeUnit.SECONDS),
            )
            assertEquals("PixelCopy failed for $fixtureId", PixelCopy.SUCCESS, pixelCopyResult.get())

            val after = semanticsNodes(activity)
            assertTrue("Semantic state changed during capture for $fixtureId", matches(after))
            assertTrue(
                "Committed frame does not contain the expected scene tag for $fixtureId",
                after.any { it.tag == sceneTag },
            )
            val actualThemeAfter = renderedTheme(after)
            var sceneIdAfter = ""
            var sceneGenerationAfter = -1
            var themeAfter = ""
            instrumentation.runOnMainSync {
                sceneIdAfter = activity.validationSceneIdForEvidence
                sceneGenerationAfter = activity.validationSceneGeneration
                themeAfter = activity.validationThemeForEvidence
            }
            assertEquals("Active scene changed during capture for $fixtureId", expectedSceneId, sceneIdAfter)
            assertEquals(
                "Scene generation changed during capture for $fixtureId",
                sceneGeneration,
                sceneGenerationAfter,
            )
            assertEquals("Host theme changed during capture for $fixtureId", hostRequestedTheme, themeAfter)
            assertEquals("Rendered theme changed during capture for $fixtureId", actualThemeBefore, actualThemeAfter)
            assertEquals(
                "Rendered theme does not match requested fixture for $fixtureId",
                expectedTheme,
                actualThemeAfter,
            )
            val targetAfter = after.singleNode(targetTag)
            val beforeIdentity = semanticIdentity(
                before,
                identityTags,
                requiredSemanticText,
                sceneTag,
                sceneGeneration,
                actualThemeBefore,
            )
            val afterIdentity = semanticIdentity(
                after,
                identityTags,
                requiredSemanticText,
                sceneTag,
                sceneGeneration,
                actualThemeAfter,
            )
            assertEquals("Capture identity changed across frame commit for $fixtureId", beforeIdentity, afterIdentity)
            assertEquals("Target role changed during capture", targetBefore.role, targetAfter.role)
            assertEquals("Target selection changed during capture", targetBefore.selected, targetAfter.selected)
            assertEquals(
                "Target click semantics changed during capture",
                targetBefore.hasClickAction,
                targetAfter.hasClickAction,
            )

            val targetInWindow = Rect(
                (targetAfter.bounds.left + windowOrigin[0]).coerceAtLeast(0f),
                (targetAfter.bounds.top + windowOrigin[1]).coerceAtLeast(0f),
                (targetAfter.bounds.right + windowOrigin[0]).coerceAtMost(bitmap.width.toFloat()),
                (targetAfter.bounds.bottom + windowOrigin[1]).coerceAtMost(bitmap.height.toFloat()),
            )
            assertTrue(
                "Visible target does not intersect the captured app window: $targetInWindow",
                targetInWindow.width > 1f && targetInWindow.height > 1f,
            )
            val contentSamples = sampleRenderedPixels(bitmap, targetInWindow)
            val contentColors = contentSamples.map { it.color }.toSet()
            val probes = pixelProbes(bitmap, windowOrigin, targetAfter.bounds)
            probes.forEach { (name, probe) ->
                assertTrue(
                    "Pixel probe $name is outside captured window ${bitmap.width}x${bitmap.height}",
                    probe.x in 0 until bitmap.width && probe.y in 0 until bitmap.height,
                )
            }

            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                assertTrue(
                    "PNG encoding failed for $fixtureId",
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output),
                )
            }
            assertTrue("Expected a non-empty committed render for $fixtureId", destination.length() > 0L)
            writeCaptureSidecar(
                destination = destination,
                expectedTheme = expectedTheme,
                actualTheme = actualThemeBefore,
                hostRequestedTheme = hostRequestedTheme,
                fixtureId = fixtureId,
                sceneId = expectedSceneId,
                sceneTag = sceneTag,
                sceneGeneration = sceneGeneration,
                targetTag = targetTag,
                identityTags = identityTags,
                beforeIdentity = beforeIdentity,
                afterIdentity = afterIdentity,
                target = targetAfter,
                windowOrigin = windowOrigin,
                bitmap = bitmap,
                targetInWindow = targetInWindow,
                contentSamples = contentSamples,
                probes = probes,
            )
            assertTrue(
                "Capture target has no rendered content in its app-window region; target=$targetInWindow",
                contentColors.size >= 2,
            )
            return probes
        } finally {
            bitmap.recycle()
        }
    }

    private fun semanticIdentity(
        nodes: List<NodeSnapshot>,
        identityTags: List<String>,
        requiredSemanticText: String?,
        sceneTag: String,
        sceneGeneration: Int,
        actualTheme: String,
    ): String {
        val relevant = nodes.filter { it.tag in identityTags }.sortedBy { it.tag }
        assertEquals("Each captured semantic tag must identify one node", identityTags.size, relevant.size)
        return buildString {
            append("scene=").append(sceneTag).append('#').append(sceneGeneration)
                .append(";theme=").append(actualTheme)
                .append(";required=").append(requiredSemanticText.orEmpty()).append('|')
            relevant.forEach { node ->
                append(node.tag).append(':')
                    .append(node.role).append(':')
                    .append(node.selected).append(':')
                    .append(node.hasClickAction).append(':')
                    .append(node.text.joinToString("\u001f")).append(':')
                    .append(node.contentDescription.joinToString("\u001f")).append(':')
                    .append(node.layoutBounds.toCsv()).append(':')
                    .append(node.bounds.toCsv()).append('|')
            }
        }
    }

    private fun renderedTheme(nodes: List<NodeSnapshot>): String {
        val markers = nodes.map { it.tag }
            .filter { it.startsWith("validation_rendered_theme_") }
            .map { it.removePrefix("validation_rendered_theme_") }
        assertEquals("Validation host must expose one theme derived from LocalConfiguration", 1, markers.size)
        return markers.single()
    }

    private fun sampleRenderedPixels(bitmap: Bitmap, bounds: Rect): List<PixelProbe> {
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val samples = mutableListOf<PixelProbe>()
        for (row in 0..4) {
            for (column in 0..4) {
                val x = (left + ((right - left - 1) * (column + 0.5f) / 5f).toInt()).coerceIn(left, right - 1)
                val y = (top + ((bottom - top - 1) * (row + 0.5f) / 5f).toInt()).coerceIn(top, bottom - 1)
                samples += PixelProbe(x, y, bitmap.getPixel(x, y))
            }
        }
        return samples
    }

    private fun writeCaptureSidecar(
        destination: File,
        expectedTheme: String,
        actualTheme: String,
        hostRequestedTheme: String,
        fixtureId: String,
        sceneId: String,
        sceneTag: String,
        sceneGeneration: Int,
        targetTag: String,
        identityTags: List<String>,
        beforeIdentity: String,
        afterIdentity: String,
        target: NodeSnapshot,
        windowOrigin: IntArray,
        bitmap: Bitmap,
        targetInWindow: Rect,
        contentSamples: List<PixelProbe>,
        probes: Map<String, PixelProbe>,
    ) {
        fun rectJson(rect: Rect) = JSONObject()
            .put("left", rect.left)
            .put("top", rect.top)
            .put("right", rect.right)
            .put("bottom", rect.bottom)

        val probeJson = JSONObject()
        probes.forEach { (name, probe) ->
            probeJson.put(
                name,
                JSONObject()
                    .put("x", probe.x)
                    .put("y", probe.y)
                    .put("color", "%08x".format(java.util.Locale.ROOT, probe.color)),
            )
        }
        val identityTagJson = JSONArray().apply { identityTags.forEach { put(it) } }
        val uniqueContentColors = contentSamples.map { it.color }.toSet()
        val colorJson = JSONArray().apply {
            uniqueContentColors.forEach { put("%08x".format(java.util.Locale.ROOT, it)) }
        }
        val contentSamplesJson = JSONArray().apply {
            contentSamples.forEach { sample ->
                put(
                    JSONObject()
                        .put("x", sample.x)
                        .put("y", sample.y)
                        .put("color", "%08x".format(java.util.Locale.ROOT, sample.color)),
                )
            }
        }
        val sidecar = JSONObject()
            .put("runId", renderedRunId)
            .put("stateId", destination.nameWithoutExtension)
            .put("png", destination.name)
            .put("expectedTheme", expectedTheme)
            .put("actualTheme", actualTheme)
            .put("hostRequestedTheme", hostRequestedTheme)
            .put("fixtureId", fixtureId)
            .put("sceneId", sceneId)
            .put("sceneTag", sceneTag)
            .put("sceneGeneration", sceneGeneration)
            .put("targetTag", targetTag)
            .put("identityTags", identityTagJson)
            .put("semanticIdentityBefore", beforeIdentity)
            .put("semanticIdentityAfter", afterIdentity)
            .put("targetRole", target.role?.toString().orEmpty())
            .put("targetSelected", target.selected ?: JSONObject.NULL)
            .put("targetHasClickAction", target.hasClickAction)
            .put("targetText", JSONArray().apply { target.text.forEach { put(it) } })
            .put("targetContentDescription", JSONArray().apply { target.contentDescription.forEach { put(it) } })
            .put("targetVisibleBoundsRoot", rectJson(target.bounds))
            .put("targetRawBoundsRoot", rectJson(target.layoutBounds))
            .put("composeOriginInWindow", JSONArray().put(windowOrigin[0]).put(windowOrigin[1]))
            .put(
                "targetRawBoundsWindow",
                rectJson(
                    Rect(
                        target.layoutBounds.left + windowOrigin[0],
                        target.layoutBounds.top + windowOrigin[1],
                        target.layoutBounds.right + windowOrigin[0],
                        target.layoutBounds.bottom + windowOrigin[1],
                    ),
                ),
            )
            .put("targetBoundsWindow", rectJson(targetInWindow))
            .put("bitmapWidth", bitmap.width)
            .put("bitmapHeight", bitmap.height)
            .put("capturePath", "PixelCopy.Window")
            .put("hardwareAccelerated", true)
            .put("contentColors", colorJson)
            .put("contentColorSampleCount", uniqueContentColors.size)
            .put("contentPixelSamples", contentSamplesJson)
            .put("pixelProbes", probeJson)
            .put("pngSha256", destination.readBytes().sha256())
        File(destination.parentFile, "${destination.nameWithoutExtension}.json").writeText(sidecar.toString(2))
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    private data class PixelProbe(val x: Int, val y: Int, val color: Int)

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
            saveDiagnosticScreenshot(screenshot)
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

    private fun saveDiagnosticScreenshot(destination: File) {
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
