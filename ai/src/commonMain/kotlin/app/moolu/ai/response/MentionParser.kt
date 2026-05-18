/*
 * Cite: ADR-34 (Tier 3 moolu-ai · `Mention parser` agentic UI hook public surface
 *               verbatim per spec §11.1 figure) ·
 *       ADR-2 (Conversation 一等公民 · participant ∈ {human, agent, bot} ·
 *               3-variant `Participant` sealed class verbatim per moolu-im
 *               Cluster 2 Task 6.6 baseline) ·
 *       ADR-22 (Sealed Sender 元数据保护 v1 · Mention.targetId 解析后 ParticipantId
 *               preserved · 0 raw value log) ·
 *       ADR-27 (V2 重设计 · ConversationManager.sendMessage(content, mentions)
 *               server-side cross-tenant fanout validation per Cluster 2
 *               D-4 INLINE) ·
 *       spec §11.1 figure verbatim Tier 3 moolu-ai 行 ·
 *       spec §16.2 P6 (KMP ABI lock · Konsist ≥ 60).
 *
 * Stage 0 CSO D-N INLINE absorption (per task-6.13-cso-threat-model.md §5):
 * - **D-1 (CRITICAL · OWASP A03 ReDoS + LLM04 DoS + STRIDE D+T)**: linear-time
 *   regex (no nested quantifiers · bounded `{1,64}` capture · `+?` non-greedy) +
 *   8192-char input cap + Unicode bidi/zero-width strip pre-pass (13 controls
 *   per Task 6.10 D-2 + Task 6.11 D-1 + Task 6.12 D-1 INLINE generalize · CVE-
 *   2021-42574 Trojan Source defense)
 * - **D-8 (HIGH · ADR-2 3-variant Participant baseline alignment)**: [MentionType]
 *   3-variant `{ HUMAN, AGENT, BOT }` matching `Participant` sealed-class
 *   subtypes per ADR-2 verbatim "participant ∈ {human, agent, bot}" · UI display
 *   can render `@米鹿` as agent/bot icon distinctly (Cluster 5 BotProfile UI
 *   scope) · v1 baseline 3-variant prevents v2 ABI break
 * - **D-9 (HIGH · OWASP A03 + Unicode TR29 verbatim L6 WebFetch mandate)**: CJK
 *   boundary support via 4 Unicode codepoint range allowlist
 *   `[A-Za-z0-9_\u4E00-\u9FFF\u3040-\u30FF\uAC00-\uD7AF]` covering CJK Unified
 *   Ideographs + Hiragana + Katakana + Hangul Syllables (Han/Japanese/Korean ·
 *   米鹿 Chinese username `@米鹿` valid input · emoji + ZWJ NOT part of
 *   identifier · avoids identifier-spoofing surface)
 * - **D-10 (HIGH · STRIDE I + ADR-22 + Cluster 2 D-9 + Task 6.11 D-10 + Task 6.12
 *   D-10 PII-redaction generalize)**: [Mention.targetId] raw `ParticipantId.value`
 *   UUID v4 NEVER logged raw · use [Mention.toSafeLogString] returning 8-char
 *   prefix only (per `R-NO-PII-LOGGING-1` Phase 5 baseline preserve · `MooluLogger`
 *   redaction at SDK boundary)
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · KMP commonMain · 0 modify existing
 * `app.moolu.ai.llm/` package 920 LOC Phase 0 baseline preserved (escape
 * removed `*` glob char per Kotlin nested-comment lexer fix · Phase 8 Cluster 3
 * Task 8.7 Stage 1b' D16 source fix).
 *
 * Subagent model used: claude-opus-4-7-thinking-xhigh per
 * `subagent-protocol.md §4` Iron rule #1 verbatim canonical primary.
 */
package app.moolu.ai.response

import app.moolu.ai.response.internal.MentionTokenizer
import app.moolu.im.conversation.ParticipantId

/**
 * Extract `@username`-style mentions from a composer text string OR an LLM-
 * generated [Item.FinalText.text] via Unicode TR29-aware tokenization +
 * caller-supplied participant lookup.
 *
 * **Linear-time defense** (Stage 0 D-1 INLINE · OWASP A03 ReDoS + LLM04 DoS):
 * the underlying regex has bounded `{1,64}` capture · NO nested quantifiers
 * (`(.+)+@`-class catastrophic backtracking impossible · matches in O(n) input
 * length · `Regex.findAll` lazy enumeration · adversarial `@aaa...!@aaa...!`
 * × N input has bounded evaluation time).
 *
 * **Bounded input** (Stage 0 D-1 INLINE · 8192-char cap matches Cluster 2 D-1
 * message body length cap + Task 6.12 D-1 systemPrompt 8192-char ceiling
 * pattern preserve): `text.length > maxInputChars` throws
 * `IllegalArgumentException` to fail fast at the call site rather than
 * exhausting heap on 100MB adversarial input.
 *
 * **Bidi defense** (Stage 0 D-1 + D-9 INLINE · CVE-2021-42574 Trojan Source ·
 * per Task 6.10 D-2 + Task 6.11 D-1 + Task 6.12 D-1 INLINE generalize):
 * 13 Unicode bidi/zero-width control codepoints stripped pre-pass before
 * regex tokenization · adversarial `@user1\u202EsiM` cannot spoof
 * display-vs-stored mention identifier.
 *
 * **CJK support** (Stage 0 D-9 INLINE · per Unicode TR29 verbatim L6 WebFetch
 * mandate): 4 codepoint range allowlist enables `@米鹿` (Han) · `@テスト`
 * (Katakana) · `@김민지` (Hangul) plus ASCII Latin/digits/underscore.
 *
 * @param maxInputChars max input text length cap · default 8192
 *     ([MAX_INPUT_CHARS]) · throws on overflow per Stage 0 D-1 INLINE
 */
public class MentionParser(
    public val maxInputChars: Int = MAX_INPUT_CHARS,
) {
    init {
        require(maxInputChars in 1..MAX_INPUT_CHARS_HARD_CAP) {
            "maxInputChars must be in 1..$MAX_INPUT_CHARS_HARD_CAP, got $maxInputChars"
        }
    }

    private val tokenizer = MentionTokenizer()

    /**
     * Parse [text] for mentions · returns [MentionResult.cleanText] (text with
     * Unicode bidi/zero-width controls stripped) plus
     * [MentionResult.mentions] list of resolved [Mention].
     *
     * **Lookup contract**: [lookup] is invoked once per token (max
     * `text.length / 2` calls bounded by `findAll` enumeration · O(n)) ·
     * returns `null` for unrecognized usernames (silently dropped per Stage 0
     * D-14 BACKLOG · v2 may add `acceptUnknownMentions: Boolean` ctor flag).
     *
     * @param text input text (composer body OR LLM-generated text accumulator)
     * @param lookup resolver `(displayName: String) -> Pair<ParticipantId,
     *     MentionType>?` · `null` skips unknown mentions silently
     * @return [MentionResult] with sanitized text + resolved mentions
     * @throws IllegalArgumentException if `text.length > maxInputChars`
     *     (Stage 0 D-1 INLINE fail-fast)
     */
    public fun parse(
        text: String,
        lookup: (displayName: String) -> Pair<ParticipantId, MentionType>?,
    ): MentionResult {
        require(text.length <= maxInputChars) {
            "text length ${text.length} exceeds maxInputChars=$maxInputChars (Stage 0 D-1 INLINE OWASP LLM04)"
        }
        val sanitized = tokenizer.stripBidiControls(text)
        val tokens = tokenizer.findMentionTokens(sanitized)
        val mentions =
            tokens.mapNotNull { token ->
                val resolved = lookup(token.identifier) ?: return@mapNotNull null
                Mention(
                    span = token.span,
                    displayName = token.identifier,
                    targetId = resolved.first,
                    type = resolved.second,
                )
            }
        return MentionResult(cleanText = sanitized, mentions = mentions)
    }

    public companion object {
        /**
         * Default max input length per Stage 0 D-1 INLINE · matches Cluster 2
         * D-1 message body cap + Task 6.12 D-1 systemPrompt ceiling.
         */
        public const val MAX_INPUT_CHARS: Int = 8_192

        /**
         * Hard ceiling for [MentionParser.maxInputChars] ctor param · prevents
         * caller from configuring above this absolute defense limit (per Stage 0
         * D-1 OWASP LLM04 100MB adversarial input defense · 1MB hard cap).
         */
        public const val MAX_INPUT_CHARS_HARD_CAP: Int = 1_048_576

        /**
         * Max length of a single mention identifier (the `@username` part after
         * the `@`) · per Stage 0 D-1 INLINE bounded regex `{1,64}` capture
         * group prevents identifier-bombing attacks.
         */
        public const val MAX_IDENTIFIER_LEN: Int = 64
    }
}

/**
 * Result of [MentionParser.parse] · sanitized text + resolved mentions list.
 *
 * @property cleanText input text with Unicode bidi/zero-width controls stripped
 *     (per Stage 0 D-1 + D-9 INLINE) · downstream consumers MUST use this
 *     instead of the raw input (defense-in-depth at SDK boundary)
 * @property mentions resolved [Mention]s in document order · `emptyList()`
 *     when no `@` tokens detected OR all unrecognized
 */
public data class MentionResult(
    val cleanText: String,
    val mentions: List<Mention>,
)

/**
 * One resolved mention · pairs an in-text span with the canonical
 * [ParticipantId] + 3-variant [MentionType] discriminator.
 *
 * **Stage 0 D-10 INLINE PII redaction**: callers logging mentions MUST use
 * [toSafeLogString] (8-char prefix of UUID v4) · NEVER raw `targetId.value`
 * (per ADR-22 Sealed Sender preserve + Cluster 2 D-9 INLINE generalize +
 * Phase 5 `R-NO-PII-LOGGING-1` Konsist baseline).
 *
 * @property span character range in [MentionResult.cleanText] (start
 *     inclusive · end exclusive · 0-indexed)
 * @property displayName captured identifier text (the `@username` part minus
 *     the `@` prefix · NEVER includes Unicode bidi controls per Stage 0 D-1
 *     INLINE pre-strip)
 * @property targetId resolved participant identifier (UUID v4 per Cluster 2
 *     baseline · ParticipantId init enforces shape)
 * @property type 3-variant participant kind discriminator per ADR-2 Stage 0 D-8
 *     INLINE
 */
public data class Mention(
    val span: IntRange,
    val displayName: String,
    val targetId: ParticipantId,
    val type: MentionType,
) {
    /**
     * Safe-log representation per Stage 0 D-10 INLINE · 8-char ParticipantId
     * UUID v4 prefix + truncated displayName. Use this in `MooluLogger.debug`
     * + `analytics.track` calls · NEVER raw [targetId] or full [displayName].
     *
     * Format: `Mention(span=10..15, name=米鹿, id=a3f2c8b1..., type=AGENT)`
     */
    public fun toSafeLogString(): String =
        "Mention(span=$span, name=${displayName.take(MAX_LOGGED_NAME)}, " +
            "id=${targetId.value.take(8)}..., type=$type)"

    private companion object {
        const val MAX_LOGGED_NAME: Int = 16
    }
}

/**
 * 3-variant participant kind discriminator per ADR-2 verbatim "participant ∈
 * {human, agent, bot}" (Stage 0 D-8 INLINE absorption · NOT plan-body 2-variant
 * `{ HUMAN, BOT }` · matches `app.moolu.im.conversation.Participant` sealed
 * class subtypes Cluster 2 Task 6.6 baseline).
 *
 * UI display can render `@米鹿` as Agent vs Bot icon distinctly (Cluster 5
 * BotProfile UI scope · cross-cluster integration).
 */
public enum class MentionType {
    /** Human participant · Keycloak user_id-backed [ParticipantId]. */
    HUMAN,

    /** AI agent participant · `agents.id`-backed [ParticipantId]. */
    AGENT,

    /** External bot participant · `agents.id`-backed [ParticipantId] · ADR-21
     *  Bot/Agent device + KP per ai-gateway integration scope. */
    BOT,
}
