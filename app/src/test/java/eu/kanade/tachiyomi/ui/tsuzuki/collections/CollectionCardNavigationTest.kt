package eu.kanade.tachiyomi.ui.tsuzuki.collections

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection

class CollectionCardNavigationTest {

    @Test
    fun `user collection body opens consumption without editing`() {
        val collection = collection(CollectionOrigin.USER)
        val opened = mutableListOf<String>()
        val edited = mutableListOf<TsuzukiCollection>()

        handleCollectionCardAction(collection, CollectionCardAction.OPEN, opened::add, edited::add)

        opened shouldBe listOf(collection.id)
        edited shouldBe emptyList()
    }

    @Test
    fun `user collection pencil edits without opening consumption`() {
        val collection = collection(CollectionOrigin.USER)
        val opened = mutableListOf<String>()
        val edited = mutableListOf<TsuzukiCollection>()

        handleCollectionCardAction(collection, CollectionCardAction.EDIT, opened::add, edited::add)

        opened shouldBe emptyList()
        edited shouldBe listOf(collection)
    }

    @Test
    fun `built in collection body also opens consumption`() {
        val collection = collection(CollectionOrigin.SYSTEM)
        val opened = mutableListOf<String>()
        val edited = mutableListOf<TsuzukiCollection>()

        handleCollectionCardAction(collection, CollectionCardAction.OPEN, opened::add, edited::add)

        opened shouldBe listOf(collection.id)
        edited shouldBe emptyList()
    }

    private fun collection(origin: CollectionOrigin) = TsuzukiCollection(
        id = "collection",
        title = "Collection",
        sortOrder = 0,
        origin = origin,
        createdAt = 1,
        updatedAt = 1,
    )
}
