package eu.kanade.presentation.tsuzuki.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.manga.components.MangaCover
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
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
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
            } else if (state.continueReading.isNotEmpty() || state.sections.isNotEmpty()) {
                item(key = "home_safe_top") {
                    Spacer(Modifier.statusBarsPadding())
                }
            }

            if (state.continueReading.isNotEmpty()) {
                item(key = "continue_reading") {
                    ContinueReadingSection(
                        items = state.continueReading,
                        onContinueReading = onContinueReading,
                    )
                }
            }

            state.sections.forEach { section ->
                when (section) {
                    is HomeSection.CollectionSection -> {
                        if (section.folders.isNotEmpty()) {
                            item(key = "collection_${section.collectionId}") {
                                CollectionHomeSection(
                                    section = section,
                                    onFolder = { folderId ->
                                        onFolder(section.collectionId, folderId)
                                    },
                                )
                            }
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
private fun HomeHero(
    item: CatalogItem,
    activeIndex: Int,
    itemCount: Int,
    onRead: () -> Unit,
    onDetails: () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val foreground = MaterialTheme.colorScheme.onBackground
    val metadata = buildList {
        if (item.format != CatalogItemFormat.UNKNOWN) {
            add(
                item.format.name
                    .lowercase()
                    .replace('_', ' ')
                    .replaceFirstChar { it.uppercase() },
            )
        }
        item.genres.firstOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
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
            .background(background),
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
                        0.32f to background.copy(alpha = 0.08f),
                        0.47f to background.copy(alpha = 0.65f),
                        0.62f to background.copy(alpha = 0.91f),
                        0.83f to background.copy(alpha = 0.98f),
                        1.0f to background,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelMedium,
                    color = foreground.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = foreground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            item.synopsis
                ?.takeIf(String::isNotBlank)
                ?.let { synopsis ->
                    Text(
                        text = synopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = foreground.copy(alpha = 0.88f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onRead,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Ler")
                }
                OutlinedButton(
                    onClick = onDetails,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Detalhes")
                }
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
                                    foreground.copy(alpha = if (index == activeIndex) 0.94f else 0.42f),
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = stringResource(MR.strings.tsuzuki_home_continue_reading),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
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
    val trace = remember(item.canonicalTitleId, recorder.sessionId) {
        DiagnosticTrace.start(
            recorder = recorder,
            workflow = DiagnosticWorkflow.CONTINUE_READING,
            canonicalTitleId = item.canonicalTitleId,
            subsystem = DiagnosticSubsystem.IMAGE,
        )
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.width(304.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Cap. ${item.chapterDisplayNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.lastPageRead > 0) {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_home_page, item.lastPageRead),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
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

private const val HERO_ASPECT_RATIO = 0.71f
private const val HERO_ROTATION_INTERVAL_MILLIS = 8_000L
private const val HERO_CROSSFADE_MILLIS = 650
private const val MAX_HERO_ITEMS = 4
