package eu.kanade.presentation.tsuzuki.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.tsuzuki.collections.FolderTile
import eu.kanade.presentation.tsuzuki.components.DefaultSkinCard
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSecondaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.tsuzuki.recordArtworkLoad
import eu.kanade.tachiyomi.ui.tsuzuki.home.TsuzukiHomeScreenState
import kotlinx.coroutines.delay
import mihon.app.di.appGraph
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.home.model.HomeSection
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TsuzukiHomeScreen(
    state: TsuzukiHomeScreenState,
    onHeroRead: (CatalogItem) -> Unit,
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
                        onRead = onHeroRead,
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
    onRead: (CatalogItem) -> Unit,
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(HERO_ASPECT_RATIO),
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
                onRead = { onRead(item) },
                onDetails = { onDetails(item) },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun HomeHero(
    item: CatalogItem,
    activeIndex: Int,
    itemCount: Int,
    onRead: () -> Unit,
    onDetails: () -> Unit,
) {
    val colors = defaultSkinColors()
    val metadata = buildList {
        if (item.format != CatalogItemFormat.UNKNOWN) {
            add(
                item.format.name
                    .lowercase()
                    .replace('_', ' ')
                    .replaceFirstChar { it.uppercase() },
            )
        }
        item.genres.firstOrNull()?.takeIf(String::isNotBlank)?.let(::add)
        if (item.status != CatalogItemStatus.UNKNOWN) {
            add(
                item.status.name
                    .lowercase()
                    .replace('_', ' ')
                    .replaceFirstChar { it.uppercase() },
            )
        }
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
                        0.0f to Color.Black.copy(alpha = 0.22f),
                        0.18f to Color.Transparent,
                        0.32f to colors.canvas.copy(alpha = 0.08f),
                        0.47f to colors.canvas.copy(alpha = 0.65f),
                        0.62f to colors.canvas.copy(alpha = 0.91f),
                        0.83f to colors.canvas.copy(alpha = 0.98f),
                        1.0f to colors.canvas,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = DefaultSkinSpacing.PageHorizontal, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = colors.foreground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            item.synopsis?.takeIf(String::isNotBlank)?.let { synopsis ->
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                DefaultSkinPrimaryButton(
                    text = stringResource(MR.strings.tsuzuki_home_read),
                    onClick = onRead,
                )
                DefaultSkinSecondaryButton(
                    text = stringResource(MR.strings.tsuzuki_home_details),
                    onClick = onDetails,
                )
            }
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
    heroPrecedesModule -> DefaultSkinSpacing.Group
    else -> 0.dp
}

private const val HERO_ASPECT_RATIO = 0.71f
private const val HERO_ROTATION_INTERVAL_MILLIS = 8_000L
private const val HERO_CROSSFADE_MILLIS = 650
private const val MAX_HERO_ITEMS = 4
