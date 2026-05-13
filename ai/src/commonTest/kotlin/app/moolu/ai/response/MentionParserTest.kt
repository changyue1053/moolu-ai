/*
 * Cite: ADR-34 + ADR-2 + ADR-22 + spec §11.1 figure verbatim.
 *
 * Stage 0 CSO D-1 + D-8 + D-9 + D-10 INLINE absorption verification:
 * - D-1 linear-time regex + 8192-char input cap + bidi strip pre-pass
 * - D-8 3-variant MentionType { HUMAN, AGENT, BOT } per ADR-2
 * - D-9 Unicode TR29 CJK boundary support (4 codepoint range allowlist)
 * - D-10 PII-redacted toSafeLogString (ParticipantId 8-char prefix)
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · scaffold-grade · per Phase 6 Cluster 1+2+3+4
 * Tasks 6.10/6.11/6.12 precedent (actual ./gradlew :ai:commonTest execution
 * deferred Cluster 6 closure batch).
 */
package app.moolu.ai.response

import app.moolu.im.conversation.ParticipantId
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MentionParserTest {
    private val parser = MentionParser()

    private val alice = ParticipantId("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa") to MentionType.HUMAN
    private val agent = ParticipantId("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb") to MentionType.AGENT
    private val bot = ParticipantId("cccccccc-3333-4333-8333-cccccccccccc") to MentionType.BOT

    private val lookup: (String) -> Pair<ParticipantId, MentionType>? = { name ->
        when (name) {
            "alice" -> alice
            "米鹿" -> agent
            "テスト" -> agent
            "김민지" -> agent
            "moolu_bot" -> bot
            else -> null
        }
    }

    @Test
    fun `D-1 INLINE · empty input returns empty result`() {
        val result = parser.parse("", lookup)
        result.cleanText shouldBe ""
        result.mentions shouldHaveSize 0
    }

    @Test
    fun `D-1 INLINE · 8192+1 char input throws IllegalArgumentException per Stage 0 D-1 fail-fast`() {
        val oversized = "@".repeat(MentionParser.MAX_INPUT_CHARS + 1)
        assertFailsWith<IllegalArgumentException> {
            parser.parse(oversized, lookup)
        }
    }

    @Test
    fun `D-1 INLINE · ReDoS adversarial input @aaa exclamation pattern bounded eval time`() {
        val adversarial = "@" + "a".repeat(64) + "!@" + "a".repeat(64) + "!"
        val result = parser.parse(adversarial, lookup)
        result.mentions shouldHaveSize 0
    }

    @Test
    fun `D-9 INLINE · CJK Han identifier @米鹿 parses + maps to AGENT type`() {
        val result = parser.parse("Hello @米鹿!", lookup)
        result.mentions shouldHaveSize 1
        result.mentions[0].displayName shouldBe "米鹿"
        result.mentions[0].targetId.value shouldBe agent.first.value
        result.mentions[0].type shouldBe MentionType.AGENT
    }

    @Test
    fun `D-9 INLINE · Hangul identifier @kim parses correctly`() {
        val result = parser.parse("Hi @김민지", lookup)
        result.mentions shouldHaveSize 1
        result.mentions[0].displayName shouldBe "김민지"
        result.mentions[0].type shouldBe MentionType.AGENT
    }

    @Test
    fun `D-9 INLINE · Katakana identifier @test parses correctly`() {
        val result = parser.parse("Yo @テスト", lookup)
        result.mentions shouldHaveSize 1
        result.mentions[0].displayName shouldBe "テスト"
    }

    @Test
    fun `D-1 + D-9 INLINE · Unicode bidi control u202E stripped pre-pass`() {
        val bidiInput = "Hi @alice\u202EsiM"
        val result = parser.parse(bidiInput, lookup)
        result.cleanText shouldNotContain "\u202E"
        result.mentions shouldHaveSize 1
        result.mentions[0].displayName shouldBe "alice"
    }

    @Test
    fun `D-1 + D-9 INLINE · all 13 bidi+zero-width controls stripped`() {
        val raw = "@alice\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u200B\u200C\u200D\uFEFF"
        val result = parser.parse(raw, lookup)
        result.cleanText shouldBe "@alice"
        result.mentions shouldHaveSize 1
    }

    @Test
    fun `D-1 INLINE · multiple mentions in document order`() {
        val result = parser.parse("hi @alice and @米鹿 and @moolu_bot", lookup)
        result.mentions shouldHaveSize 3
        result.mentions[0].displayName shouldBe "alice"
        result.mentions[1].displayName shouldBe "米鹿"
        result.mentions[2].displayName shouldBe "moolu_bot"
    }

    @Test
    fun `D-1 INLINE · unrecognized mention silently dropped (D-14 BACKLOG default behavior)`() {
        val result = parser.parse("@unknownuser hello", lookup)
        result.mentions shouldHaveSize 0
        result.cleanText shouldBe "@unknownuser hello"
    }

    @Test
    fun `D-9 INLINE · emoji NOT captured beyond identifier boundary`() {
        val result = parser.parse("@alice😀", lookup)
        result.mentions shouldHaveSize 1
        result.mentions[0].displayName shouldBe "alice"
    }

    @Test
    fun `D-10 INLINE · toSafeLogString redacts ParticipantId to 8-char prefix`() {
        val result = parser.parse("@alice", lookup)
        val mention = result.mentions[0]
        val log = mention.toSafeLogString()
        log shouldNotContain alice.first.value
        kotlin.test.assertTrue(log.contains("aaaaaaaa..."), "expected truncated UUID prefix in $log")
        kotlin.test.assertTrue(log.contains("type=HUMAN"), "expected type discriminator in $log")
    }

    @Test
    fun `D-8 INLINE · MentionType has 3 variants per ADR-2 baseline`() {
        // Verifies 3-variant enum (NOT 2-variant per Stage 0 §0.5 D-8 INLINE absorbed plan-body drift).
        val variants = MentionType.entries.toSet()
        variants shouldBe setOf(MentionType.HUMAN, MentionType.AGENT, MentionType.BOT)
    }

    @Test
    fun `MentionParser ctor rejects invalid maxInputChars`() {
        assertFailsWith<IllegalArgumentException> { MentionParser(maxInputChars = 0) }
        assertFailsWith<IllegalArgumentException> { MentionParser(maxInputChars = -1) }
        assertFailsWith<IllegalArgumentException> {
            MentionParser(maxInputChars = MentionParser.MAX_INPUT_CHARS_HARD_CAP + 1)
        }
    }
}
