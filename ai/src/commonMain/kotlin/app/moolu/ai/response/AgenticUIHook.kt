/*
 * Cite: ADR-34 (Tier 3 moolu-ai · `ResponseStream·Tool · Mention parser·UI hook`
 *               agentic UI hook public surface verbatim) ·
 *       ADR-7 (单 items 表多 type · 1:1 mapping ResponseChunk → Item) ·
 *       ADR-22 (Sealed Sender · Item.Message.participantId preserved at
 *               downstream Cluster 3 LazyColumnDispatcher injection) ·
 *       ADR-27 (V2 重设计 · ConversationManager.streamItems(Flow<Item>)
 *               downstream injection target) ·
 *       ADR-28 (ui-agentic 按 Item.type 分发渲染 · sealed-class exhaustive when
 *               + alternatives 反例 verbatim "ui-chat 内 if-type-else 渲染 — 模块边界混乱") ·
 *       spec §11.1 figure verbatim Tier 3 moolu-ai 行 ·
 *       spec §11.5.4 Flow 1 α' agentic streaming 8 步 verbatim mapping
 *               (status → Item.Status · tool_call → Item.ToolCall ·
 *                tool_result → Item.ToolResult · text_delta accumulator →
 *                Item.FinalText 累积 streaming · complete → Item.FinalText 最终) ·
 *       spec §16.2 P6 (KMP ABI lock · Konsist ≥ 60).
 *
 * Stage 0 CSO D-N INLINE absorption (per task-6.13-cso-threat-model.md §5):
 * - **D-4 (CRITICAL · L9 silent fail · ADR-28 alternatives 反例 generalize)**:
 *   sealed-class exhaustive `when (chunk: ResponseChunk)` over 7 variants ·
 *   0 `else ->` fallback · adding 8th variant fails compilation rather than
 *   silent UI degradation · architecture-test [R-RESPONSESTREAM-AGENTIC-1]
 *   Konsist rule complementarily detects pattern via text-scan
 * - **D-5 (HIGH · STRIDE S + Cluster 3 D1 reuse · Item.FinalText.streamingComplete
 *   Boolean Path B canonical)**: maps [ResponseChunk.TextDelta] × N →
 *   [app.moolu.im.conversation.Item.FinalText] with `streamingComplete = false`
 *   per delta · `[ResponseChunk.Complete]` → final commit with
 *   `streamingComplete = true` · downstream `AIBubble` derives
 *   `isStreaming = !item.streamingComplete` (Cluster 3 D1 baseline preserve)
 * - **D-6 (HIGH · OWASP LLM07 · Cluster 3 D2+D3 reuse · ToolCall/ToolResult
 *   actual fields)**: ResponseChunk.ToolCall(toolName, args, callId) →
 *   Item.ToolCall(toolName, arguments, callId, responseId=requestId) wire field
 *   `args` maps to canonical Item field `arguments` at this seam ·
 *   ResponseChunk.ToolResult(callId, result) → Item.ToolResult(callId, result,
 *   isError=false v1, responseId=requestId) per Cluster 3 D-3 INLINE consumer
 *   contract (errors flow through ResponseChunk.Failed terminal frame)
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · KMP commonMain · 0 modify existing
 * `app.moolu.ai.llm/*` package 920 LOC Phase 0 baseline preserved.
 *
 * Subagent model used: claude-opus-4-7-thinking-xhigh per
 * `subagent-protocol.md §4` Iron rule #1 verbatim canonical primary.
 */
package app.moolu.ai.response

import app.moolu.im.conversation.ConversationId
import app.moolu.im.conversation.Item
import kotlinx.coroutines.flow.Flow

/**
 * Translation seam between [ResponseStream] (7-variant [ResponseChunk] in-process
 * domain type) and [app.moolu.im.conversation.ConversationManager.streamItems]
 * downstream consumer (7-variant [Item] sealed class · downstream Cluster 3
 * `LazyColumnDispatcher` renders per spec §11.3).
 *
 * **Architecture invariant** (Stage 0 D-4 INLINE · ADR-28 alternatives 反例
 * verbatim "ui-chat 内 if-type-else 渲染 — 模块边界混乱" generalize): the
 * `[AgenticUIHookImpl]` translation MUST use sealed-class exhaustive
 * `when (chunk: ResponseChunk)` over 7 variants · 0 `else ->` fallback ·
 * Kotlin compiler enforces compile-time exhaustiveness · architecture-test
 * `R-RESPONSESTREAM-AGENTIC-1` Konsist rule complementarily detects
 * `else ->` text-pattern co-occurrence · defense-in-depth.
 *
 * **Mapping table** (per spec §11.5.4 Flow 1 α' agentic streaming 8 步 verbatim):
 *
 * | ResponseChunk | Item | streamingComplete | Notes |
 * | --- | --- | --- | --- |
 * | [ResponseChunk.TextDelta] | [Item.FinalText] | false | accumulator append + emit per chunk |
 * | [ResponseChunk.ReasoningStep] | [Item.ReasoningStep] | n/a | parentId=null v1 |
 * | [ResponseChunk.ToolCall] | [Item.ToolCall] | n/a | args → arguments field rename |
 * | [ResponseChunk.ToolResult] | [Item.ToolResult] | n/a | isError=false v1 (Stage 0 D-6) |
 * | [ResponseChunk.Status] | [Item.Status] | n/a | UI replaces previous |
 * | [ResponseChunk.Complete] | [Item.FinalText] | true | accumulator final commit |
 * | [ResponseChunk.Failed] | [Item.FinalText] | true | accumulator final commit (Stage 0 D-3) |
 *
 * Default impl supplied via [defaultAgenticUIHook] factory.
 */
public interface AgenticUIHook {
    /**
     * Cold [Flow] of [Item] translated from a [responseStream] · injectable
     * into [app.moolu.im.conversation.ConversationManager.streamItems]
     * downstream consumer.
     *
     * **Cancellation contract**: cancelling the returned Flow's collector
     * triggers [ResponseStream.cancel] propagation upstream (per Stage 0 D-3
     * INLINE atomic state transition · idempotent). Mid-stream cancel still
     * emits a final [Item.FinalText] with `streamingComplete = true` carrying
     * the accumulator's captured text (Stage 0 D-2 INLINE prevents silent
     * accumulator state loss).
     *
     * **Bounded buffer** (Stage 0 D-2 INLINE · OWASP LLM04): accumulator hard
     * cap = 65536 chars (matches Cluster 3 D-6 INLINE
     * `Item.FinalText.text` ceiling). Overflow emits truncated final
     * [Item.FinalText] + propagates [ResponseStream.cancel] upstream + 0 silent
     * truncation without observable state.
     *
     * @param responseStream upstream agentic streaming source
     * @param conversationId target conversation for the resulting [Item]s ·
     *     downstream `ConversationManager.streamItems(conversationId)` MUST
     *     match for correct routing
     * @param requestId opaque per-request identifier · populated to
     *     [Item.responseId] field across all emitted [Item] variants for
     *     downstream Cluster 3 `deriveToolCallStatus` adjacency lookup +
     *     reasoning step chain link
     */
    public fun toItemStream(
        responseStream: ResponseStream,
        conversationId: ConversationId,
        requestId: String,
    ): Flow<Item>
}

/**
 * Construct the default [AgenticUIHook] implementation backed by
 * [app.moolu.ai.response.internal.AgenticUIHookImpl].
 *
 * **Stage 0 D-4 INLINE** (`MooluClock` Phase 0 baseline · per Task 6.11 D4 +
 * Task 6.12 D4 pattern reuse): clock dependency is injected for test
 * determinism (`TestClock` substitution).
 *
 * **Stage 0 D-5 INLINE** (`ItemIdGenerator` NEW interface · crypto-strong
 * source): default uses `kotlin.uuid.Uuid.random()` (Kotlin 2.0+ stdlib
 * crypto-strong per platform) · tests inject `FakeItemIdGenerator` deterministic
 * stub for assertion stability.
 *
 * @param clock wall-clock used for [Item.createdAt] timestamps (millis since
 *     epoch per Cluster 2 Task 6.6 baseline)
 * @param maxAccumulatorChars text_delta accumulator hard cap · default 65536
 *     matches Cluster 3 D-6 INLINE [Item.FinalText.text] ceiling preserve
 * @return [AgenticUIHook] ready for composition root injection
 */
public fun defaultAgenticUIHook(
    clock: app.moolu.foundation.time.MooluClock,
    maxAccumulatorChars: Int = AgenticUIHookDefaults.MAX_FINAL_TEXT_CHARS,
): AgenticUIHook =
    app.moolu.ai.response.internal.AgenticUIHookImpl(
        clock = clock,
        itemIdGenerator = app.moolu.ai.response.internal.DefaultItemIdGenerator,
        maxAccumulatorChars = maxAccumulatorChars,
    )

/**
 * Public companion-style constants for the default [AgenticUIHook]
 * implementation · ABI-stable across versions.
 */
public object AgenticUIHookDefaults {
    /**
     * Hard cap on the [Item.FinalText.text] accumulator · matches Cluster 3
     * D-6 INLINE ceiling preserve (per Stage 0 D-2 INLINE OWASP LLM04 100MB
     * adversarial TextDelta defense · 65536 chars ≈ 64KB UTF-16 in-memory).
     */
    public const val MAX_FINAL_TEXT_CHARS: Int = 65_536
}
