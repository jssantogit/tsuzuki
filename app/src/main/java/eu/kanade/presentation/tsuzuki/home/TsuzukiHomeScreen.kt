package eu.kanade.presentation.tsuzuki.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.tsuzuki.collections.FolderTile
import eu.kanade.presentation.tsuzuki.components.DefaultSkinCard
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.tsuzuki.recordArtworkLoad
import eu.kanade.tachiyomi.ui.tsuzuki.home.TsuzukiHomeScreenState
import kotlinx.coroutines.delay
import mihon.app.di.appGraph
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.home.model.HomeSection
import tachiyomi.domain.tsuzuki.integration.model.metadataStartYear
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TsuzukiHomeScreen(
    state: TsuzukiHomeScreenState,
    onHeroDetails: (CatalogItem) -> Unit,
    onContinueReading: (HomeContinueReadingItem) -> Unit,
    onFolder: (collectionId: String, folderId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = colors.canvas,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvas)
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            val heroes = state.heroes.take(MAX_HERO_ITEMS)
            if (heroes.isNotEmpty()) {
                item(key = "home_hero") {
                    HomeHeroCarousel(
                        items = heroes,
                        onDetails = onHeroDetails,
                    )
                }
            }

            var previousModuleExists = false
            if (state.continueReading.isNotEmpty()) {
                val firstModule = !previousModuleExists
                val moduleTopSpacing = homeModuleTopSpacing(
                    previousModuleExists = previousModuleExists,
                    heroPrecedesModule = heroes.isNotEmpty() && firstModule,
                )
                val addStatusBarInset = heroes.isEmpty() && firstModule
                item(key = "continue_reading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .then(if (addStatusBarInset) Modifier.statusBarsPadding() else Modifier)
                            .padding(top = moduleTopSpacing),
                    ) {
                        ContinueReadingSection(items = state.continueReading, onContinueReading = onContinueReading)
                    }
                }
                previousModuleExists = true
            }

            state.sections.forEach { section ->
                when (section) {
                    is HomeSection.CollectionSection -> {
                        if (section.folders.isNotEmpty()) {
                            val firstModule = !previousModuleExists
                            val moduleTopSpacing = homeModuleTopSpacing(
                                previousModuleExists = previousModuleExists,
                                heroPrecedesModule = heroes.isNotEmpty() && firstModule,
                            )
                            val addStatusBarInset = heroes.isEmpty() && firstModule
                            item(key = "collection_${section.collectionId}") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .then(if (addStatusBarInset) Modifier.statusBarsPadding() else Modifier)
                                        .padding(top = moduleTopSpacing),
                                ) {
                                    CollectionHomeSection(
                                        section = section,
                                        onFolder = { folderId -> onFolder(section.collectionId, folderId) },
                                    )
                                }
                            }
                            previousModuleExists = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeroCarousel(
    items: List<CatalogItem>,
    onDetails: (CatalogItem) -> Unit,
) {
    var activeIndex by remember(items) { mutableIntStateOf(0) }

    LaunchedEffect(items) {
        activeIndex = 0
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(HERO_ROTATION_INTERVAL_MILLIS)
            activeIndex = (activeIndex + 1) % items.size
        }
    }

    val activeItem = items[activeIndex.coerceIn(items.indices)]
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val statusBarInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navigationBarInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(
                homeHeroHeight(
                    screenHeightDp = configuration.screenHeightDp,
                    statusBarInset = statusBarInset,
                    navigationBarInset = navigationBarInset,
                    fontScale = configuration.fontScale,
                ),
            ),
    ) {
        Crossfade(
            targetState = activeItem,
            animationSpec = tween(durationMillis = HERO_CROSSFADE_MILLIS),
            modifier = Modifier.fillMaxSize(),
        ) { item ->
            HomeHero(
                item = item,
                activeIndex = activeIndex,
                itemCount = items.size,
                onDetails = { onDetails(item) },
            )
        }
    }
}

@Composable
private fun HomeHero(
    item: CatalogItem,
    activeIndex: Int,
    itemCount: Int,
    onDetails: () -> Unit,
) {
    val colors = defaultSkinColors()
    val metadata = buildList {
        catalogFormatLabel(item.format)?.let(::add)
        metadataStartYear(item.startDate)?.let(::add)
    }.take(3).joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        AsyncImage(
            model = item.bannerUrl ?: item.coverUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        0.48f to colors.canvas.copy(alpha = 0.12f),
                        0.62f to colors.canvas.copy(alpha = 0.58f),
                        0.78f to colors.canvas.copy(alpha = 0.90f),
                        0.94f to colors.canvas.copy(alpha = 0.98f),
                        1.0f to colors.canvas,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = DefaultSkinSpacing.PageHorizontal, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.secondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.foreground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            DefaultSkinPrimaryButton(
                text = stringResource(MR.strings.tsuzuki_home_view_work),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDetails,
            )
            if (itemCount > 1) {
                val carouselPosition = stringResource(
                    MR.strings.tsuzuki_home_hero_position,
                    activeIndex + 1,
                    itemCount,
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .semantics { stateDescription = carouselPosition },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(itemCount) { index ->
                        Box(
                            modifier = Modifier
                                .size(if (index == activeIndex) 16.dp else 6.dp, 6.dp)
                                .background(
                                    colors.foreground.copy(alpha = if (index == activeIndex) 0.94f else 0.42f),
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun catalogFormatLabel(format: CatalogItemFormat): String? = when (format) {
    CatalogItemFormat.MANGA -> stringResource(MR.strings.tsuzuki_details_format_manga)
    CatalogItemFormat.NOVEL -> stringResource(MR.strings.tsuzuki_details_format_novel)
    CatalogItemFormat.ONE_SHOT -> stringResource(MR.strings.tsuzuki_details_format_one_shot)
    CatalogItemFormat.MANHWA -> stringResource(MR.strings.tsuzuki_details_format_manhwa)
    CatalogItemFormat.MANHUA -> stringResource(MR.strings.tsuzuki_details_format_manhua)
    CatalogItemFormat.DOUJIN -> stringResource(MR.strings.tsuzuki_details_format_doujin)
    CatalogItemFormat.WEBTOON -> stringResource(MR.strings.tsuzuki_details_format_webtoon)
    CatalogItemFormat.UNKNOWN -> null
}

internal fun homeHeroHeight(
    screenHeightDp: Int,
    statusBarInset: Dp,
    navigationBarInset: Dp,
    fontScale: Float,
): Dp {
    val usefulViewport = (screenHeightDp.dp - statusBarInset - navigationBarInset).coerceAtLeast(1.dp)
    val fontScaleMinimum = (360.dp + ((fontScale - 1f).coerceAtLeast(0f) * 180f).dp)
        .coerceAtMost(HOME_HERO_MAX_HEIGHT)
    return (usefulViewport * 0.65f).coerceIn(fontScaleMinimum, HOME_HERO_MAX_HEIGHT)
}

@Composable
private fun ContinueReadingSection(
    items: List<HomeContinueReadingItem>,
    onContinueReading: (HomeContinueReadingItem) -> Unit,
) {
    val colors = defaultSkinColors()
    Column(verticalArrangement = Arrangement.spacedBy(DefaultSkinSpacing.Group)) {
        Text(
            text = stringResource(MR.strings.tsuzuki_home_continue_reading),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.foreground,
            modifier = Modifier.padding(horizontal = DefaultSkinSpacing.PageHorizontal),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = DefaultSkinSpacing.PageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = HomeContinueReadingItem::canonicalTitleId) { item ->
                ContinueReadingCard(item) { onContinueReading(item) }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    item: HomeContinueReadingItem,
    onClick: () -> Unit,
) {
    val recorder = LocalContext.current.appGraph.structuredDiagnosticRecorder
    val colors = defaultSkinColors()
    val trace = remember(item.canonicalTitleId, recorder.sessionId) {
        DiagnosticTrace.start(
            recorder = recorder,
            workflow = DiagnosticWorkflow.CONTINUE_READING,
            canonicalTitleId = item.canonicalTitleId,
            subsystem = DiagnosticSubsystem.IMAGE,
        )
    }
    DefaultSkinCard(
        onClick = onClick,
        modifier = Modifier.width(304.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                data = item.coverUrl ?: item.sourceCover,
                fallbackData = listOf(item.sourceCover, item.sourceCover?.url),
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(68.dp),
                onLoadEvent = { trace.recordArtworkLoad(it) },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(MR.strings.tsuzuki_details_chapter_label, item.chapterDisplayNumber),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
                if (item.lastPageRead > 0) {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_home_page, item.lastPageRead),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionHomeSection(
    section: HomeSection.CollectionSection,
    onFolder: (folderId: String) -> Unit,
) {
    val colors = defaultSkinColors()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.foreground,
            modifier = Modifier.padding(horizontal = DefaultSkinSpacing.PageHorizontal),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = DefaultSkinSpacing.PageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = section.folders,
                key = { folder -> folder.folderId },
            ) { folder ->
                FolderTile(
                    folder = folder,
                    onClick = { onFolder(folder.folderId) },
                )
            }
        }
    }
}

internal fun homeModuleTopSpacing(previousModuleExists: Boolean, heroPrecedesModule: Boolean): Dp = when {
    previousModuleExists -> DefaultSkinSpacing.Section
    heroPrecedesModule -> homeHeroFirstModuleSpacing
    else -> 0.dp
}

private val homeHeroFirstModuleSpacing = 8.dp
internal val HOME_HERO_MAX_HEIGHT = 640.dp
private const val HERO_ROTATION_INTERVAL_MILLIS = 8_000L
private const val HERO_CROSSFADE_MILLIS = 650
private const val MAX_HERO_ITEMS = 4
