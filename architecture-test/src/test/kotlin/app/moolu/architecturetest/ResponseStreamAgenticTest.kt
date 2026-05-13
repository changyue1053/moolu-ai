package app.moolu.architecturetest

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/**
 * **R-RESPONSESTREAM-AGENTIC-1** · the [app.moolu.ai.response.internal.AgenticUIHookImpl]
 * `when (chunk: ResponseChunk)` translation block MUST be sealed-class exhaustive
 * over the 7 [app.moolu.ai.response.ResponseChunk] variants · NEVER `else ->`
 * fallback (per ADR-28 alternatives 反例 verbatim "ui-chat 内 if-type-else 渲染 — 模块边界混乱"
 * generalize · L9 silent fail · OWASP A04 + STRIDE T+E).
 *
 * **Defense-in-depth complement** to:
 * - Kotlin compiler sealed-class exhaustive `when` enforcement (compile-time)
 * - Cluster 2 [`R-ITEM-SEALED-EXHAUSTIVE-1`] baseline (Item sealed class downstream)
 * - Cluster 3 [`R-RENDER-DISPATCH-1`] baseline (LazyColumnDispatcher downstream)
 *
 * Scope: strictly `app.moolu.ai.response` package files filtered to
 * `AgenticUIHookImpl.kt` per Phase 6 Cluster 4 Task 6.13 dispatch §1 + Stage 0
 * D-12 INLINE absorption · this rule complements but does NOT replace the Kotlin
 * compiler's sealed-class exhaustive `when` check (that operates at the
 * type-system layer · this Konsist text-pattern check operates at the source
 * layer for human-readable architecture enforcement).
 *
 * **Implementation note** (per Stage 0 D-12 INLINE · per Task 6.10
 * R-NO-SQL-CONCAT-SEARCH-1 + Cluster 3 R-RENDER-DISPATCH-1 precedent):
 * real Konsist API call · `Konsist.scopeFromPackage("app.moolu.ai.response")`
 * actual scope · `files.assertTrue { ... }` actual assertion · NOT vacuous
 * `assertTrue { true }` body. Detects `when (chunk` text-pattern presence
 * paired with `else ->` co-occurrence · adversarial new `else ->` branch in
 * the dispatcher fails this Konsist gate.
 *
 * Cite: ADR-28 verbatim alternatives 反例 + L9 silent fail review + spec §11.5.4
 *       Flow 1 verbatim 8 步 + spec §16.2 P6 (Konsist ≥ 60 Quality Gate) +
 *       Cluster 2 R-ITEM-SEALED-EXHAUSTIVE-1 baseline + Cluster 3 R-RENDER-
 *       DISPATCH-1 baseline + Cluster 4 Task 6.10 R-NO-SQL-CONCAT-SEARCH-1
 *       precedent (real Konsist API call NOT vacuous).
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · scaffold-grade · Subagent model used:
 * `claude-opus-4-7-thinking-xhigh`.
 *
 * Expected to be GREEN at Cluster 4 Task 6.13 ship (2026-05-13).
 */
class ResponseStreamAgenticTest {
    @Test
    fun `R-RESPONSESTREAM-AGENTIC-1 AgenticUIHookImpl when chunk ResponseChunk exhaustive`() {
        Konsist
            .scopeFromProduction()
            .files
            .filter { it.path.contains("ai/src/commonMain") }
            .filter { it.name == "AgenticUIHookImpl.kt" }
            .assertTrue { file ->
                // Strip KDoc + line comments before pattern detection to avoid false-positive
                // matches against KDoc text that legitimately quotes the forbidden patterns
                // (per Cluster 3 R-RENDER-DISPATCH-1 baseline scaffold-grade limitation
                // generalize · Cluster 6 closure batch may swap regex for AST-based check).
                val codeOnly = file.text.lineSequence()
                    .filterNot { it.trimStart().startsWith("*") }
                    .filterNot { it.trimStart().startsWith("/*") }
                    .filterNot { it.trimStart().startsWith("//") }
                    .joinToString("\n")
                // 1. Must contain a sealed-class dispatcher `when (chunk` block.
                val hasWhenChunk = "when (chunk" in codeOnly
                // 2. Must NOT contain `else ->` fallback branch in the dispatcher
                //    (defense-in-depth complement to Kotlin sealed-class compile-time
                //    exhaustive `when` check · scoped to non-comment source lines only).
                val hasElseFallback = Regex("""\belse\s*->""").containsMatchIn(codeOnly)
                // 3. Must reference all 7 ResponseChunk variants by name (smoke check that
                //    the dispatcher actually covers the 7-variant surface · adding 8th
                //    variant requires updating this assertion in coordinated server+client
                //    release per binary-compat contract).
                val variants = listOf(
                    "ResponseChunk.TextDelta",
                    "ResponseChunk.ReasoningStep",
                    "ResponseChunk.ToolCall",
                    "ResponseChunk.ToolResult",
                    "ResponseChunk.Status",
                    "ResponseChunk.Complete",
                    "ResponseChunk.Failed",
                )
                val hasAll7Variants = variants.all { it in codeOnly }
                hasWhenChunk && !hasElseFallback && hasAll7Variants
            }
    }
}
