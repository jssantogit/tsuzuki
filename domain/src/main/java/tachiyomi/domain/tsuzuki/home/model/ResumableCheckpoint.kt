package tachiyomi.domain.tsuzuki.home.model

/** Shared eligibility for Continue Reading and the Hero's resume target. */
fun isResumableCheckpoint(
    read: Boolean,
    updatedAt: Long,
    lastPageRead: Long,
    lastVariantId: String?,
): Boolean = !read && updatedAt > 0L && (lastPageRead > 0L || lastVariantId != null)
