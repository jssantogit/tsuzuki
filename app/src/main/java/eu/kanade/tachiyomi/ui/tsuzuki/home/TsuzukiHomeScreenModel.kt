package eu.kanade.tachiyomi.ui.tsuzuki.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.home.interactor.GetConfiguredHomeSections
import tachiyomi.domain.tsuzuki.home.interactor.GetHomeHero
import tachiyomi.domain.tsuzuki.home.interactor.ObserveContinueReadingArtwork
import tachiyomi.domain.tsuzuki.home.interactor.ObserveHomeContinueReading
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.home.model.HomeSection
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.reader.interactor.ImportLegacyCanonicalProgress
import tachiyomi.domain.tsuzuki.source.interactor.ResolveCanonicalSourceManga
import kotlin.time.Clock

@Immutable
data class TsuzukiHomeScreenState(
    val heroes: List<CatalogItem> = emptyList(),
    val continueReading: List<HomeContinueReadingItem> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
)

sealed interface TsuzukiHomeEvent {
    data class OpenCanonicalTitle(val canonicalTitleId: String) : TsuzukiHomeEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiHomeScreenModel(
    observeHomeContinueReading: ObserveHomeContinueReading,
    getConfiguredHomeSections: GetConfiguredHomeSections,
    private val getHomeHero: GetHomeHero,
    private val visibilityRepository: ContinueReadingVisibilityRepository,
    private val observeCanonicalLibrary: ObserveCanonicalLibrary,
    private val historyRepository: HistoryRepository,
    private val importLegacyCanonicalProgress: ImportLegacyCanonicalProgress,
    private val materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog,
    private val resolveCanonicalSourceManga: ResolveCanonicalSourceManga,
    private val titleArtworkRepository: TitleArtworkRepository,
) : ViewModel() {

    private val eventChannel = Channel<TsuzukiHomeEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private val importedLegacyProgressTitles = mutableSetOf<String>()
    private val heroes = MutableStateFlow<List<CatalogItem>>(emptyList())

    val state: StateFlow<TsuzukiHomeScreenState> = combine(
        heroes,
        ObserveContinueReadingArtwork(
            observeHomeContinueReading,
            titleArtworkRepository,
            resolveCanonicalSourceManga,
        ).subscribe(),
        getConfiguredHomeSections.subscribe(),
    ) { heroItems, continueReading, sections ->
        TsuzukiHomeScreenState(
            heroes = heroItems,
            continueReading = continueReading,
            sections = sections,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TsuzukiHomeScreenState(),
    )

    init {
        viewModelScope.launch {
            heroes.value = try {
                getHomeHero.await(
                    limit = HOME_HERO_DISCOVERY_LIMIT,
                    count = HOME_HERO_COUNT,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                emptyList()
            }
        }

        viewModelScope.launch {
            combine(
                observeCanonicalLibrary.subscribe(),
                historyRepository.getHistory(""),
            ) { libraryItems, _ ->
                libraryItems
            }.collectLatest { libraryItems ->
                for (item in libraryItems) {
                    val titleId = item.title.id
                    if (titleId in importedLegacyProgressTitles) continue
                    try {
                        importLegacyCanonicalProgress.execute(titleId)
                        importedLegacyProgressTitles += titleId
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        // Legacy local compatibility must never block Home.
                    }
                }
            }
        }
    }

    fun removeFromContinueReading(item: HomeContinueReadingItem) {
        viewModelScope.launch {
            visibilityRepository.hide(
                canonicalTitleId = item.canonicalTitleId,
                hiddenAt = maxOf(
                    item.updatedAt,
                    Clock.System.now().toEpochMilliseconds(),
                ),
            )
        }
    }

    fun openCatalogItem(item: CatalogItem) {
        viewModelScope.launch {
            try {
                val title = materializeCanonicalTitleFromCatalog.execute(item)
                eventChannel.send(TsuzukiHomeEvent.OpenCanonicalTitle(title.id))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // A failed catalog materialization must not destabilize Home.
            }
        }
    }

    private companion object {
        const val HOME_HERO_DISCOVERY_LIMIT = 12
        const val HOME_HERO_COUNT = 4
    }
}
