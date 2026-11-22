package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaAllFutureStrictJsonTest {
    @Test
    fun strict_future_page_json_accepts_nested_valid_tokens() {
        val valid = listOf(
            """{"items":[],"nextCursor":null}""",
            """{"items":[{"id":"a","chapter":1.5,"dated":true}],"nextCursor":"m1:131:en:0000000000000001:1"}""",
            """ { "items" : [ {"text" : "\u4e16\tquoted\""} ], "nextCursor":null } """,
            """{"items":[1,-1,0,0.05,1e3,false,null],"nextCursor":null}""",
        )
        valid.all(MangaAllFutureStrictJson::accepts) shouldBe true
    }

    @Test
    fun strict_future_page_json_rejects_unquoted_literals_and_invalid_grammar() {
        val invalid = listOf(
            "not-json",
            """{"items":[not-json],"nextCursor":null}""",
            """{"items":[{"text":bare}],"nextCursor":null}""",
            """{"items":[1,],"nextCursor":null}""",
            """{"items":[],"nextCursor":null,}""",
            """{"items":[01],"nextCursor":null}""",
            """{"items":[1.],"nextCursor":null}""",
            """{"items":[1e],"nextCursor":null}""",
            """{"items":["\q"],"nextCursor":null}""",
            "{\"items\":[\"newline\n\"],\"nextCursor\":null}",
            """{"items":[],"nextCursor":null}trailing""",
            "[".repeat(40) + "0" + "]".repeat(40),
        )
        invalid.any(MangaAllFutureStrictJson::accepts) shouldBe false
    }
}
