/*
 * Cite: ADR-34 + ADR-22 + spec §11.1 figure verbatim.
 *
 * Stage 0 CSO D-1 + D-9 INLINE absorption verification at the tokenizer layer
 * (separated from MentionParser to enable focused regex + Unicode tokenization
 * tests without coupling to ParticipantId lookup contract).
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · scaffold-grade.
 */
package app.moolu.ai.response

import app.moolu.ai.response.internal.MentionTokenizer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MentionTokenizerTest {
    private val tokenizer = MentionTokenizer()

    @Test
    fun `D-1 INLINE · empty input returns empty token list`() {
        tokenizer.findMentionTokens("").shouldBeEmpty()
    }

    @Test
    fun `D-1 INLINE · stripBidiControls preserves non-control chars`() {
        val ascii = "@alice plain text"
        tokenizer.stripBidiControls(ascii) shouldBe ascii
    }

    @Test
    fun `D-1 INLINE · stripBidiControls removes all 13 codepoints`() {
        val raw = "abc\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u200B\u200C\u200D\uFEFFdef"
        tokenizer.stripBidiControls(raw) shouldBe "abcdef"
    }

    @Test
    fun `D-9 INLINE · finds @米鹿 CJK Han identifier`() {
        val tokens = tokenizer.findMentionTokens("@米鹿 hi")
        tokens shouldHaveSize 1
        tokens[0].identifier shouldBe "米鹿"
        tokens[0].span.first shouldBe 0
    }

    @Test
    fun `D-9 INLINE · finds @テスト Katakana identifier`() {
        val tokens = tokenizer.findMentionTokens("@テスト")
        tokens shouldHaveSize 1
        tokens[0].identifier shouldBe "テスト"
    }

    @Test
    fun `D-9 INLINE · finds @김민지 Hangul identifier`() {
        val tokens = tokenizer.findMentionTokens("Hi @김민지!")
        tokens shouldHaveSize 1
        tokens[0].identifier shouldBe "김민지"
    }

    @Test
    fun `D-1 INLINE · enforces 64-char identifier cap`() {
        val tooLong = "@" + "a".repeat(70)
        val tokens = tokenizer.findMentionTokens(tooLong)
        tokens shouldHaveSize 1
        tokens[0].identifier.length shouldBe 64
    }
}
