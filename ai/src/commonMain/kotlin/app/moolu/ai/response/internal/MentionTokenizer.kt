/*
 * Cite: ADR-34 (Tier 3 moolu-ai · `Mention parser` agentic UI hook public surface
 *               implementation) ·
 *       ADR-22 (Sealed Sender · Mention.targetId 解析后 ParticipantId preserved) ·
 *       spec §11.1 figure verbatim Tier 3 moolu-ai 行 ·
 *       Unicode TR29 word boundaries verbatim L6 WebFetch read.
 *
 * Stage 0 CSO D-N INLINE absorption (per task-6.13-cso-threat-model.md §5):
 * - **D-1 (CRITICAL · OWASP A03 ReDoS + LLM04 DoS + STRIDE D+T)**: linear-time
 *   regex bounded `{1,64}` capture · NO nested quantifiers ·
 *   `Regex.findAll` lazy enumeration
 * - **D-9 (HIGH · OWASP A03 + Unicode TR29 verbatim L6 WebFetch mandate)**:
 *   4 Unicode codepoint range allowlist for CJK + ASCII identifier characters
 * - **D-1 + D-9 INLINE bidi defense**: 13 Unicode bidi/zero-width control
 *   codepoints stripped pre-pass per Task 6.10 D-2 + Task 6.11 D-1 + Task 6.12
 *   D-1 INLINE generalize · CVE-2021-42574 Trojan Source defense
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · KMP commonMain · internal visibility.
 */
package app.moolu.ai.response.internal

/**
 * Low-level token detection helper for [app.moolu.ai.response.MentionParser]
 * public surface · separated to enable focused testing of the regex + Unicode
 * tokenization without coupling to [app.moolu.im.conversation.ParticipantId]
 * lookup contract.
 *
 * **Stage 0 D-1 + D-9 INLINE linear-time regex** (per Unicode TR29 verbatim
 * L6 WebFetch read): mention identifier regex `[A-Za-z0-9_\u4E00-\u9FFF
 * \u3040-\u30FF\uAC00-\uD7AF]{1,64}` enforces:
 * - bounded `{1,64}` capture group (per [MAX_IDENTIFIER_LEN_INTERNAL]) ·
 *   NO nested quantifiers ·  `(.+)+@`-class catastrophic backtracking
 *   impossible
 * - 4 codepoint range allowlist:
 *   - `\u4E00-\u9FFF` CJK Unified Ideographs (20K+ Han characters)
 *   - `\u3040-\u30FF` Hiragana + Katakana (Japanese kana)
 *   - `\uAC00-\uD7AF` Hangul Syllables (Korean)
 *   - `A-Za-z0-9_` ASCII fallback (Latin scripts)
 * - emoji + ZWJ NOT part of mention identifier (avoids identifier-spoofing
 *   surface per OWASP A03)
 *
 * **Stage 0 D-1 + D-9 INLINE bidi strip** (CVE-2021-42574 Trojan Source ·
 * per Task 6.10 D-2 + Task 6.11 D-1 + Task 6.12 D-1 INLINE generalize): 13
 * Unicode bidi/zero-width control codepoints stripped before regex
 * tokenization · adversarial `@user1\u202EsiM` cannot spoof display-vs-stored
 * identifier.
 */
internal class MentionTokenizer {
    /**
     * Strip 13 Unicode bidi/zero-width control codepoints from [input] · used
     * pre-pass before [findMentionTokens] regex enumeration.
     *
     * @param input raw text (composer body OR LLM-generated text)
     * @return sanitized text with bidi/zero-width controls removed
     */
    fun stripBidiControls(input: String): String {
        if (input.isEmpty()) return input
        return buildString(input.length) {
            for (ch in input) {
                if (ch !in BIDI_CONTROLS) append(ch)
            }
        }
    }

    /**
     * Find all `@identifier` mention tokens in [sanitized] (bidi-controls
     * pre-stripped per [stripBidiControls]).
     *
     * **Linear time guarantee** (Stage 0 D-1 INLINE OWASP A03 + LLM04): the
     * underlying [MENTION_REGEX] has bounded `{1,64}` capture · NO nested
     * quantifiers · `findAll` lazy enumeration is O(n) input length where
     * n = sanitized.length.
     *
     * @param sanitized text with Unicode bidi/zero-width controls stripped
     * @return list of [MentionToken] in document order · each token spans
     *     `@identifier` inclusive of the `@` char
     */
    fun findMentionTokens(sanitized: String): List<MentionToken> {
        if (sanitized.isEmpty()) return emptyList()
        return MENTION_REGEX.findAll(sanitized).map { match ->
            MentionToken(
                identifier = match.groupValues[1],
                span = match.range,
            )
        }.toList()
    }

    internal companion object {
        /**
         * Mention identifier regex per Stage 0 D-1 + D-9 INLINE absorption ·
         * 4 Unicode codepoint range allowlist + bounded `{1,64}` capture ·
         * linear-time evaluation (no nested quantifiers).
         */
        val MENTION_REGEX: Regex = Regex(
            "@([A-Za-z0-9_\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF]{1,64})",
        )

        /**
         * Internal copy of identifier length cap for [MENTION_REGEX] verification
         * (mirrors [app.moolu.ai.response.MentionParser.MAX_IDENTIFIER_LEN]
         * constant).
         */
        const val MAX_IDENTIFIER_LEN_INTERNAL: Int = 64

        /**
         * 13 Unicode bidi/zero-width control codepoints stripped pre-pass per
         * Stage 0 D-1 + D-9 INLINE absorption (CVE-2021-42574 Trojan Source ·
         * per Task 6.10 D-2 + Task 6.11 D-1 + Task 6.12 D-1 INLINE generalize):
         *
         * - `\u202A`-`\u202E` LRE/RLE/PDF/LRO/RLO (4 + PDF = 5 left-to-right /
         *   right-to-left embedding/override controls)
         * - `\u2066`-`\u2069` LRI/RLI/FSI/PDI (4 isolate controls)
         * - `\u200B`-`\u200D` ZWSP/ZWNJ/ZWJ (3 zero-width word-joiner controls)
         * - `\uFEFF` BOM (zero-width no-break space)
         */
        val BIDI_CONTROLS: Set<Char> = setOf(
            '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069',
            '\u200B', '\u200C', '\u200D', '\uFEFF',
        )
    }
}

/**
 * Single `@identifier` token detected by [MentionTokenizer.findMentionTokens] ·
 * paired with its character span in the sanitized text.
 *
 * @property identifier captured identifier text (the `@username` part minus
 *     the `@` prefix · NEVER includes Unicode bidi controls per Stage 0 D-1
 *     INLINE pre-strip)
 * @property span character range in sanitized text (inclusive of the `@` char)
 */
internal data class MentionToken(
    val identifier: String,
    val span: IntRange,
)
