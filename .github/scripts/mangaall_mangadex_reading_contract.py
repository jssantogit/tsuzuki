"""Exact byte patches for nonshipping MangaDex internal-reader research.

Apply after the existing pinned code11 source/language patches. The ordinary
signed source and previous unsigned baseline remain unchanged until opt-in.
"""

def patch_mangadex_reading_contract(blob: bytes) -> bytes:
    def change(old: bytes, new: bytes, label: str):
        nonlocal blob
        if blob.count(old) != 1:
            raise ValueError("MANGADEX_READING_CONTRACT_SOURCE_PIN_DRIFT_" + label)
        blob = blob.replace(old, new, 1)

    change(b'[["includes[]", "author"]]',
           b'[["includes[]", "author"], ["includes[]", "artist"]]', "STAFF_INCLUDES")
    change(
        b'  const authors = [...new Set(record.relationships\n'
        b'    .filter((rel) => rel?.type === "author" && ID_PATTERN.test(rel.id || ""))',
        b'  if (!Array.isArray(record.relationships)) return { detailOutcome: "INVALID_RESPONSE" };\n'
        b'  const authors = [...new Set(record.relationships\n'
        b'    .filter((rel) => (rel?.type === "author" || rel?.type === "artist") && ID_PATTERN.test(rel.id || ""))',
        "STAFF_ROLES",
    )
    change(
        b'function numericChapter(label) {',
        b'// The internal Reader cannot load publisher/external chapter URLs.\n'
        b'function externallyDelivered(attrs) {\n'
        b'  const url = attrs?.externalUrl;\n'
        b'  return url != null && (typeof url !== "string" || url.trim().length > 0);\n'
        b'}\n\n'
        b'function numericChapter(label) {',
        "INTERNAL_DELIVERY_PREDICATE",
    )
    change(b'chapter.attributes.externalUrl != null ||',
           b'externallyDelivered(chapter.attributes) ||', "COVERAGE_DELIVERY")
    change(
        b'    const rawChapter = text(attrs.chapter, 32);\n'
        b'    const number = numericChapter(rawChapter);\n'
        b'    if (number === null || seen.has(item.id)) continue;\n'
        b'    seen.add(item.id);',
        b'    const normalizedId = item.id.toLowerCase();\n'
        b'    if (seen.has(normalizedId)) throw new Error("MangaAll duplicate chapter identity");\n'
        b'    seen.add(normalizedId);\n'
        b'    // The upstream offset still advances over every raw row. Never\n'
        b'    // offer an external release as an internally deliverable option.\n'
        b'    if (externallyDelivered(attrs)) continue;\n'
        b'    const rawChapter = text(attrs.chapter, 32);\n'
        b'    const number = numericChapter(rawChapter);\n'
        b'    if (number === null) continue;',
        "INTERNAL_CHAPTERS",
    )
    return blob
