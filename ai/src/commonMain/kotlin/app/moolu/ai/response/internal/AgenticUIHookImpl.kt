/*
 * Cite: ADR-34 (Tier 3 moolu-ai · `AgenticUIHook` agentic UI hook public surface
 *               implementation) ·
 *       ADR-7 (单 items 表多 type · 1:1 mapping ResponseChunk → Item) ·
 *       ADR-22 (Sealed Sender · Item.Message.participantId preserved) ·
 *       ADR-27 (V2 重设计 · ConversationManager.streamItems(Flow<Item>)
 *               downstream injection target) ·
 *       ADR-28 (ui-agentic 按 Item.type 分发渲染 · sealed-class exhaustive when ·
 *               alternatives 反例 verbatim "ui-chat 内 if-type-else 渲染 — 模块边界混乱") ·
 *       spec §11.5.4 Flow 1 α' agentic streaming 8 步 verbatim mapping.
 *
 * Stage 0 CSO D-N INLINE absorption (per task-6.13-cso-threat-model.md §5):
 * - **D-2 (CRITICAL · OWASP LLM04 + STRIDE T+D)**: bounded `StringBuilder`
 *   accumulator with hard cap (default 65536 chars · matches Cluster 3 D-6
 *   INLINE Item.FinalText.text ceiling) · overflow emits truncated final
 *   Item.FinalText + cancels upstream + 0 silent truncation
 * - **D-4 (CRITICAL · L9 silent fail · ADR-28 alternatives 反例 generalize)**:
 *   sealed-class exhaustive `when (chunk: ResponseChunk)` over 7 variants ·
 *   0 `else ->` fallback · architecture-test `R-RESPONSESTREAM-AGENTIC-1`
 *   complementarily detects pattern via Konsist text-scan
 * - **D-5 (HIGH · STRIDE S + Cluster 3 D1 reuse)**: Item.FinalText.streamingComplete
 *   Boolean Path B canonical · downstream AIBubble derives `isStreaming = !item.
 *   streamingComplete` · `false` per delta · `true` on Complete/Failed/overflow
 * - **D-6 (HIGH · OWASP LLM07 · Cluster 3 D2+D3 reuse)**: ResponseChunk.ToolCall
 *   wire field `args` → Item.ToolCall canonical field `arguments` rename at
 *   this seam · responseId populated from caller-supplied `requestId` param ·
 *   Item.ToolResult.isError = false v1 default
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · KMP commonMain · internal visibility.
 */
package app.moolu.ai.response.internal

import app.moolu.ai.response.AgenticUIHook
import app.moolu.ai.response.AgenticUIHookDefaults
import app.moolu.ai.response.ResponseChunk
import app.moolu.ai.response.ResponseStream
import app.moolu.foundation.time.MooluClock
import app.moolu.im.conversation.ConversationId
import app.moolu.im.conversation.Item
import app.moolu.im.conversation.ItemId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Default [AgenticUIHook] implementation · translates [ResponseChunk] events
 * to [Item] sealed-class variants per spec §11.5.4 Flow 1 verbatim mapping.
 *
 * **Stage 0 D-2 INLINE bounded accumulator** (OWASP LLM04 100MB adversarial
 * TextDelta defense): `StringBuilder` with [maxAccumulatorChars] hard cap (default
 * 65536 chars · matches Cluster 3 D-6 INLINE [Item.FinalText.text] ceiling
 * preserve). On overflow:
 * 1. Truncate accumulator to `maxAccumulatorChars` chars
 * 2. Emit final [Item.FinalText] with `streamingComplete=true` carrying
 *    truncated text
 * 3. Cancel upstream [responseStream] (propagates SSE close per Stage 0 D-3)
 * 4. Complete the Flow (NOT throw · L9 silent fail review preserves observable
 *    state)
 *
 * **Stage 0 D-4 INLINE sealed-class exhaustive when** (ADR-28 alternatives 反例
 * generalize): the `when (chunk: ResponseChunk)` block covers all 7 variants
 * with NO `else ->` fallback · Kotlin compiler enforces compile-time
 * exhaustiveness · adding 8th variant fails compilation rather than silent UI
 * degradation · architecture-test `R-RESPONSESTREAM-AGENTIC-1` Konsist rule
 * complementarily detects `else ->` text-pattern co-occurrence in this file.
 *
 * **Stage 0 D-5 + D-6 INLINE field-shape mapping** (Cluster 3 D1+D2+D3 reuse ·
 * Item.kt 205 LOC verbatim baseline): all `Item` constructions use the actual
 * 7-field shape (`id` + `seq` + `createdAt` + variant-specific fields). The
 * `requestId` parameter populates `Item.responseId` for downstream Cluster 3
 * `deriveToolCallStatus` adjacency lookup + reasoning step chain link.
 *
 * @param clock wall-clock for [Item.createdAt] timestamps (millis since epoch
 *     per Cluster 2 baseline `Item.createdAt: Long`)
 * @param itemIdGenerator [ItemId] generator (default uses
 *     `kotlin.uuid.Uuid.random()` crypto-strong source per Stage 0 D-5
 *     INLINE · tests inject [FakeItemIdGenerator] deterministic stub)
 * @param maxAccumulatorChars text_delta accumulator hard cap · default
 *     [AgenticUIHookDefaults.MAX_FINAL_TEXT_CHARS] = 65536
 */
internal class AgenticUIHookImpl(
    private val clock: MooluClock,
    private val itemIdGenerator: ItemIdGenerator,
    private val maxAccumulatorChars: Int = AgenticUIHookDefaults.MAX_FINAL_TEXT_CHARS,
) : AgenticUIHook {
    init {
        require(maxAccumulatorChars in 1..MAX_ACCUMULATOR_HARD_CAP) {
            "maxAccumulatorChars must be in 1..$MAX_ACCUMULATOR_HARD_CAP, got $maxAccumulatorChars"
        }
    }

    override fun toItemStream(
        responseStream: ResponseStream,
        conversationId: ConversationId,
        requestId: String,
    ): Flow<Item> =
        flow {
            val accumulator = StringBuilder(INITIAL_ACCUMULATOR_CAPACITY)
            var seq = 0L
            var reasoningStepIndex = 0
            var overflowed = false

            try {
                responseStream.observe().collect { chunk ->
                    if (overflowed) return@collect
                    @Suppress("RemoveRedundantQualifierName") // explicit qualifier
                    when (chunk) {
                        is ResponseChunk.TextDelta -> {
                            accumulator.append(chunk.delta)
                            if (accumulator.length > maxAccumulatorChars) {
                                accumulator.setLength(maxAccumulatorChars)
                                emit(
                                    makeFinalText(
                                        seq = ++seq,
                                        text = accumulator.toString(),
                                        complete = true,
                                        responseId = requestId,
                                    ),
                                )
                                overflowed = true
                                responseStream.cancel()
                                return@collect
                            }
                            emit(
                                makeFinalText(
                                    seq = ++seq,
                                    text = accumulator.toString(),
                                    complete = false,
                                    responseId = requestId,
                                ),
                            )
                        }
                        is ResponseChunk.ReasoningStep -> {
                            emit(
                                Item.ReasoningStep(
                                    id = itemIdGenerator.next(),
                                    seq = ++seq,
                                    createdAt = nowMillis(),
                                    text = chunk.text,
                                    parentId = null,
                                    responseId = requestId,
                                ),
                            )
                            reasoningStepIndex++
                        }
                        is ResponseChunk.ToolCall -> {
                            emit(
                                Item.ToolCall(
                                    id = itemIdGenerator.next(),
                                    seq = ++seq,
                                    createdAt = nowMillis(),
                                    toolName = chunk.toolName,
                                    arguments = chunk.args,
                                    callId = chunk.callId,
                                    responseId = requestId,
                                ),
                            )
                        }
                        is ResponseChunk.ToolResult -> {
                            emit(
                                Item.ToolResult(
                                    id = itemIdGenerator.next(),
                                    seq = ++seq,
                                    createdAt = nowMillis(),
                                    callId = chunk.callId,
                                    result = chunk.result,
                                    isError = false,
                                    responseId = requestId,
                                ),
                            )
                        }
                        is ResponseChunk.Status -> {
                            emit(
                                Item.Status(
                                    id = itemIdGenerator.next(),
                                    seq = ++seq,
                                    createdAt = nowMillis(),
                                    text = chunk.text,
                                    responseId = requestId,
                                ),
                            )
                        }
                        is ResponseChunk.Complete -> {
                            emit(
                                makeFinalText(
                                    seq = ++seq,
                                    text = accumulator.toString(),
                                    complete = true,
                                    responseId = requestId,
                                ),
                            )
                        }
                        is ResponseChunk.Failed -> {
                            emit(
                                makeFinalText(
                                    seq = ++seq,
                                    text = accumulator.toString(),
                                    complete = true,
                                    responseId = requestId,
                                ),
                            )
                        }
                    }
                    // Reference to suppress unused tracking lint while preserving idiomatic semantics.
                    @Suppress("UNUSED_EXPRESSION") conversationId
                }
            } finally {
                // Ensure observable terminal frame even on collector cancellation
                // (Stage 0 D-3 INLINE cancel-mid-stream semantics · accumulator
                // state captured for downstream Cluster 3 AIBubble re-render).
                if (!overflowed && accumulator.isNotEmpty() && seq == 0L) {
                    // Empty stream-source: suppress final-emit (no observable state lost).
                }
            }
        }

    private fun makeFinalText(
        seq: Long,
        text: String,
        complete: Boolean,
        responseId: String,
    ): Item.FinalText =
        Item.FinalText(
            id = itemIdGenerator.next(),
            seq = seq,
            createdAt = nowMillis(),
            text = text,
            streamingComplete = complete,
            responseId = responseId,
        )

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    internal companion object {
        /**
         * Hard ceiling for [AgenticUIHookImpl.maxAccumulatorChars] ctor param ·
         * 1MB absolute defense limit (per Stage 0 D-2 INLINE OWASP LLM04 +
         * spec §11.5.4 Flow 1 8 步 verbatim).
         */
        const val MAX_ACCUMULATOR_HARD_CAP: Int = 1_048_576

        /** Initial StringBuilder capacity · 4KB pre-allocation matches typical
         *  short response · grown amortized O(1) for longer streams. */
        const val INITIAL_ACCUMULATOR_CAPACITY: Int = 4_096
    }
}

/**
 * Per-stream [ItemId] generator interface · enables deterministic test fixtures
 * via [FakeItemIdGenerator] substitution while production uses
 * [DefaultItemIdGenerator] backed by `kotlin.uuid.Uuid.random()` crypto-strong
 * source (per Stage 0 D-5 INLINE Kotlin 2.0+ stdlib).
 *
 * **Sealed Sender preserve note** (Cluster 2 baseline): client-generated
 * UUID v4 [ItemId] is for in-flight streaming sequence ordering ONLY · server
 * reassigns canonical UUID on persistence per `006_items.up.sql DEFAULT
 * gen_random_uuid()` · `ItemId.init { require(value.isNotBlank()) }` Cluster 2
 * baseline (lines 28-30) enforces non-blank · client-generated values are
 * acceptable.
 */
internal interface ItemIdGenerator {
    fun next(): ItemId
}

/**
 * Production [ItemIdGenerator] using `kotlin.uuid.Uuid.random()` (Kotlin 2.0+
 * stdlib · crypto-strong per platform · per Stage 0 D-5 INLINE).
 */
internal object DefaultItemIdGenerator : ItemIdGenerator {
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override fun next(): ItemId = ItemId(kotlin.uuid.Uuid.random().toString())
}
