/*
 * Cite: ADR-34 (Tier 3 moolu-ai · `ResponseStream·Tool · Mention parser·UI hook`
 *               agentic UI hook public surface verbatim) ·
 *       ADR-7 (单 items 表多 type · 1:1 mapping ResponseChunk → Item) ·
 *       ADR-20 (MCP federation · tool_call/tool_result wire envelope · ai-gateway
 *               authoritative trust gate · client SDK descriptive only) ·
 *       ADR-22 (Sealed Sender · Mention.targetId 解析后 ParticipantId preserved) ·
 *       ADR-27 (V2 重设计 · ConversationManager.streamItems(Flow<Item>) downstream
 *               injection target) ·
 *       spec §11.1 figure verbatim Tier 3 moolu-ai 行 ·
 *       spec §11.5.4 Flow 1 α' agentic streaming 8 步 verbatim mapping
 *               (status → Item.Status · tool_call → Item.ToolCall ·
 *                tool_result → Item.ToolResult · text_delta accumulator →
 *                Item.FinalText 累积 streaming · complete → Item.FinalText 最终) ·
 *       spec §16.2 P6 (KMP ABI lock · Konsist ≥ 60) ·
 *       spec §16.9.1 Stable (L2+ cluster + crypto/KMS/destructive scope mandatory) ·
 *       spec §17 V0.5 plan-16 sunset verbatim "moolu-ai KMP SDK 接口完全重设计
 *               (RemoteLlmProvider → ResponseStream + agentic UI hook) V2 ADR-27 重写".
 *
 * Stage 0 CSO D-N INLINE absorption (per task-6.13-cso-threat-model.md §5):
 * - **D-7 (MED · STRIDE C-confusion · ADR-34 implicit + spec §17)**: NEW
 *   [ResponseStream] is **parallel** to legacy
 *   [app.moolu.ai.llm.LlmProvider.stream()] returning [TokenChunk] (5-variant
 *   Phase 0 baseline preserved 920 LOC). Composition root MUST pick exactly one
 *   API per conversation. Do NOT open both concurrently — cost amplification ·
 *   server-side ai-gateway gate per ADR-20 authoritative · client-side UI gate
 *   hint Cluster 3 baseline.
 * - **D-D7 (semantic clarification)**: [ResponseChunk] is plain `sealed class`
 *   (NOT `@Serializable` · NOT `@JsonClassDiscriminator`) · in-process domain
 *   type · ResponseStreamImpl translates upstream SSE wire events to in-process
 *   ResponseChunk variants.
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · KMP commonMain · 0 modify existing
 * `app.moolu.ai.llm/*` package 920 LOC Phase 0 baseline preserved.
 *
 * Subagent model used: claude-opus-4-7-thinking-xhigh per
 * `subagent-protocol.md §4` Iron rule #1 verbatim canonical primary.
 */
package app.moolu.ai.response

import app.moolu.network.client.MooluHttpClient
import app.moolu.network.sse.serverSentEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agentic streaming response from the ai-gateway · 7-variant [ResponseChunk]
 * sealed class · cancellable · backed by a Server-Sent-Events long-connection.
 *
 * **CRITICAL trust boundary** (Stage 0 CSO D-7 INLINE + ADR-34 implicit + spec §17
 * V0.5 plan-16 verbatim "RemoteLlmProvider → ResponseStream + agentic UI hook V2
 * ADR-27 重写"): NEW [ResponseStream] is **parallel** to legacy
 * [app.moolu.ai.llm.LlmProvider.stream()] returning [app.moolu.ai.llm.TokenChunk]
 * (5-variant Phase 0 baseline preserved 920 LOC verbatim · 0 ABI break for
 * `LlmProvider`/`RemoteLlmProvider`/`StubLlmProvider` consumers). Composition
 * root MUST pick exactly one API per conversation. Do NOT open both
 * concurrently — cost amplification · server-side ai-gateway per ADR-20 is the
 * authoritative gate · client-side UI gate is hint-only per Cluster 3 baseline.
 *
 * **Cancellation contract** (Stage 0 D-3 INLINE · per kotlinx-coroutines Flow
 * structured-concurrency contract): [cancel] is idempotent (multiple calls no-op
 * after first transition) · atomic state transition guarded by `Mutex.withLock`
 * pattern (per Phase 4-5-6 SingleFlight precedent generalize) · [isActive]
 * monotonically reflects post-[cancel] state · [observe] terminates within
 * one upstream emission after [cancel] (per `takeWhile` consumer-side check).
 *
 * **AgenticUIHook integration**: pair with [AgenticUIHook.toItemStream] to fan
 * the 7-variant [ResponseChunk] into a 7-variant [app.moolu.im.conversation.Item]
 * `Flow<Item>` injectable into the
 * [app.moolu.im.conversation.ConversationManager.streamItems] downstream
 * consumer · Cluster 3 [LazyColumnDispatcher] renders per spec §11.3.
 *
 * Per [spec §11.5.4 Flow 1] verbatim 8-step α' agentic streaming sequence:
 * status → [ResponseChunk.Status] → [Item.Status] · tool_call →
 * [ResponseChunk.ToolCall] → [Item.ToolCall] · tool_result →
 * [ResponseChunk.ToolResult] → [Item.ToolResult] · text_delta accumulator →
 * [ResponseChunk.TextDelta] × N → [Item.FinalText] streaming + final commit ·
 * complete → [ResponseChunk.Complete] → [Item.FinalText.streamingComplete=true].
 */
public interface ResponseStream {
    /**
     * Cold [Flow] of [ResponseChunk] events from the upstream ai-gateway · MUST
     * emit exactly one terminal frame ([ResponseChunk.Complete] or
     * [ResponseChunk.Failed]) before completing. Flow cancellation propagates
     * to the underlying SSE long-connection (closes the HTTP connection
     * mid-stream per Ktor SSE structured-concurrency contract).
     *
     * After [cancel], emissions stop within one upstream emission per
     * `takeWhile { isActive }` consumer-side check.
     */
    public fun observe(): Flow<ResponseChunk>

    /**
     * Idempotent cancel · multiple calls no-op after first transition (per
     * Stage 0 D-3 INLINE · `Mutex.withLock` atomic state transition · per
     * Phase 4-5-6 SingleFlight precedent generalize). Cancels the underlying
     * SSE long-connection + Job tear-down propagates per kotlinx-coroutines
     * structured-concurrency.
     */
    public suspend fun cancel()

    /**
     * Monotonic state · `true` until [cancel] called · `false` after first
     * [cancel] (and stays false). Use as a hint only — `observe` may still
     * emit one final terminal frame after `cancel` due to upstream race.
     */
    public val isActive: Boolean
}

/**
 * Server-emitted streaming event from the ai-gateway · 7-variant sealed class
 * verbatim per [spec §11.5.4 Flow 1] α' agentic streaming 8-step sequence.
 *
 * **Stage 0 CSO D-7 INLINE semantic clarification**: this is plain `sealed class`
 * (NOT `@Serializable` · NOT `@JsonClassDiscriminator`) — it is an **in-process
 * domain type** translated from upstream SSE wire events by [ResponseStreamImpl] ·
 * NOT a wire-format type · 0 stdlib serialization surface · simplifies ABI
 * footprint. Adding an 8th server-side variant requires a coordinated client
 * release (Konsist [R-RESPONSESTREAM-AGENTIC-1] enforces compile-time
 * exhaustiveness via `when (chunk: ResponseChunk)` text-pattern detect ·
 * Kotlin compiler additionally enforces sealed-class exhaustive `when`).
 */
public sealed class ResponseChunk {
    /**
     * Streaming text delta · accumulated by [AgenticUIHookImpl] into
     * [app.moolu.im.conversation.Item.FinalText.text] per spec §11.5.4 Flow 1
     * verbatim "text_delta accumulator → Item.FinalText 累积 streaming".
     *
     * @property delta single text fragment to append to the running accumulator ·
     *     bounded by [AgenticUIHookImpl.maxAccumulatorChars] = 65536 hard cap
     *     (per Stage 0 D-2 INLINE OWASP LLM04 · matches Cluster 3 D-6 INLINE)
     */
    public data class TextDelta(
        public val delta: String,
    ) : ResponseChunk()

    /**
     * Inner-loop reasoning step · maps to [Item.ReasoningStep] per spec §11.5.3
     * "💭 折叠 panel 展开后 markdown 渲染推理 step".
     *
     * @property text reasoning step content (markdown · downstream Cluster 3
     *     `CollapsibleReasoning` Composable renders collapsed by default)
     * @property step monotonic per-response step index (0-based)
     */
    public data class ReasoningStep(
        public val text: String,
        public val step: Int,
    ) : ResponseChunk()

    /**
     * Tool invocation initiated by the agent · maps to
     * [Item.ToolCall] per ADR-20 MCP federation wire envelope.
     *
     * **CSO D-6 INLINE**: server-side ai-gateway per ADR-20 is the
     * authoritative whitelist gate · client SDK [toolName] is descriptive only ·
     * UI renders with 🔧 icon per spec §11.5.3 row 3 (Cluster 3
     * `ToolCallCard` baseline). [args] field name maps to canonical
     * [Item.ToolCall.arguments] at the [AgenticUIHook] mapping seam.
     *
     * @property toolName MCP tool identifier (descriptive · NOT trust signal)
     * @property args tool invocation arguments (JsonObject · adversarial keys
     *     defended by ai-gateway per ADR-20 · client SDK pass-through only)
     * @property callId unique invocation identifier · pairs with
     *     [ResponseChunk.ToolResult.callId] for status derivation
     */
    public data class ToolCall(
        public val toolName: String,
        public val args: JsonObject,
        public val callId: String,
    ) : ResponseChunk()

    /**
     * Tool invocation result · maps to [Item.ToolResult] per ADR-20.
     *
     * **CSO D-3+D-6 INLINE**: server emits [ResponseChunk.ToolResult] ONLY for
     * successful tool execution · errors flow through [ResponseChunk.Failed]
     * terminal frame. v1 baseline maps to [Item.ToolResult.isError=false] at
     * the [AgenticUIHook] mapping seam · v2 BACKLOG documents per-tool error
     * variant if server adds `ResponseChunk.ToolError(callId, errorCode)`
     * (Stage 0 D-13 BACKLOG · plan v1.7 candidate).
     *
     * @property callId unique invocation identifier · pairs with
     *     [ResponseChunk.ToolCall.callId]
     * @property result tool execution output (JsonObject · ai-gateway sanitized)
     */
    public data class ToolResult(
        public val callId: String,
        public val result: JsonObject,
    ) : ResponseChunk()

    /**
     * Transient agent status update ("Searching..." · "Thinking...") · maps to
     * [Item.Status] · UI replaces previous Status per spec §11.5.3 row 5.
     *
     * @property text status display text (1-line · UI truncates to 80 chars)
     */
    public data class Status(
        public val text: String,
    ) : ResponseChunk()

    /**
     * Terminal success frame · signals end of stream · triggers
     * [Item.FinalText.streamingComplete=true] final commit at the
     * [AgenticUIHook] mapping seam · accumulator state captured.
     */
    public data object Complete : ResponseChunk()

    /**
     * Terminal failure frame · signals stream-level error (network · server
     * error · MCP tool failure rolled up). [AgenticUIHook] still finalizes
     * accumulator state before propagating per Stage 0 D-3 INLINE
     * cancel-mid-stream semantics.
     *
     * @property error in-process exception (NOT serialized · @Transient analog
     *     not needed since ResponseChunk is plain sealed class per CSO D-7
     *     INLINE)
     */
    public data class Failed(
        public val error: Throwable,
    ) : ResponseChunk()
}

/**
 * Construct a [ResponseStream] backed by an SSE long-connection to the
 * ai-gateway.
 *
 * **Stage 0 CSO D-8 INLINE · CRITICAL anti-wheel R-NO-WHEEL-1 network**: this
 * factory consumes the moolu-network facade [serverSentEvents] extension fun ·
 * 0 direct `io.ktor.client.plugins.sse.sse` import in
 * `app.moolu.ai.response.internal/*.kt` package. Note: existing
 * [app.moolu.ai.llm.RemoteLlmProvider.stream] (920 LOC `llm/` package) calls
 * `httpClient.sse(...)` directly per V0.5 plan-15 baseline · pre-Phase-6
 * baseline preserved 0 modify per Stage 0 D-15 REJECT scope discipline.
 *
 * The host's [MooluHttpClient] MUST have the SSE plugin installed via
 * [app.moolu.network.sse.installSse] at composition root.
 *
 * @param httpClient pre-configured by composition root (SSE +
 *     ContentNegotiation + Bearer auth installed)
 * @param streamUrl absolute URL of the ai-gateway agentic streaming endpoint
 *     (e.g., `https://ai.moolu.app/v1/responses/stream` · TBD Phase 7
 *     ai-gateway scope · v1 SDK is wire-endpoint-agnostic)
 * @param json optional [Json] instance for parsing event payloads · defaults
 *     to `Json { ignoreUnknownKeys = true }` for forward-compat (server adds
 *     fields without breaking client)
 * @return [ResponseStream] consuming the SSE source · cancel propagates per
 *     Stage 0 D-3 INLINE
 */
public fun sseResponseStream(
    httpClient: MooluHttpClient,
    streamUrl: String,
    json: Json = DefaultResponseJson,
): ResponseStream {
    val source: Flow<ResponseChunk> =
        httpClient.serverSentEvents(streamUrl).map { sseEvent ->
            parseResponseChunk(sseEvent.event, sseEvent.data, json)
        }
    return app.moolu.ai.response.internal.ResponseStreamImpl(source)
}

/**
 * Default forward-compat [Json] instance for [sseResponseStream] event payload
 * parsing · `ignoreUnknownKeys = true` lets the server add fields without
 * breaking the client (per kotlinx-serialization forward-compat best practice).
 */
internal val DefaultResponseJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Translate an SSE wire event (event-name discriminator + JSON data payload)
 * into the canonical 7-variant [ResponseChunk] in-process type.
 *
 * **Stage 0 CSO D-8 INLINE graceful fallback** (per Cluster 2 Task 6.6
 * `ItemType.fromServerString` D-7 INLINE pattern preserve · L9 silent fail
 * review): unknown event-name yields [ResponseChunk.Status] fallback
 * `"unknown:$eventName"` · NOT throw · prevents stream-freeze on server
 * additions. Adding a recognized event-name to this dispatcher is a
 * coordinated server+client release (binary-compat contract).
 *
 * @param eventName SSE `event:` field discriminator (nullable · default
 *     `"message"` per SSE spec)
 * @param data SSE `data:` field JSON payload (empty string when absent)
 * @param json [Json] instance for payload parsing
 */
internal fun parseResponseChunk(
    eventName: String?,
    data: String,
    json: Json,
): ResponseChunk {
    return when (eventName) {
        "text_delta" -> ResponseChunk.TextDelta(delta = parseStringField(data, key = "delta", json) ?: data)
        "reasoning_step" -> {
            val obj = parseJsonObject(data, json) ?: return ResponseChunk.Status("malformed:reasoning_step")
            ResponseChunk.ReasoningStep(
                text = obj["text"]?.jsonPrimitive?.content ?: "",
                step = obj["step"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
        "tool_call" -> {
            val obj = parseJsonObject(data, json) ?: return ResponseChunk.Status("malformed:tool_call")
            ResponseChunk.ToolCall(
                toolName = obj["tool_name"]?.jsonPrimitive?.content
                    ?: obj["toolName"]?.jsonPrimitive?.content
                    ?: "",
                args = obj["args"]?.jsonObject ?: obj["arguments"]?.jsonObject ?: JsonObject(emptyMap()),
                callId = obj["call_id"]?.jsonPrimitive?.content
                    ?: obj["callId"]?.jsonPrimitive?.content
                    ?: "",
            )
        }
        "tool_result" -> {
            val obj = parseJsonObject(data, json) ?: return ResponseChunk.Status("malformed:tool_result")
            ResponseChunk.ToolResult(
                callId = obj["call_id"]?.jsonPrimitive?.content
                    ?: obj["callId"]?.jsonPrimitive?.content
                    ?: "",
                result = obj["result"]?.jsonObject ?: JsonObject(emptyMap()),
            )
        }
        "status" -> ResponseChunk.Status(text = parseStringField(data, key = "text", json) ?: data)
        "response.completed", "complete" -> ResponseChunk.Complete
        "error", "failed" -> ResponseChunk.Failed(
            error = ResponseStreamException(
                message = parseStringField(data, key = "message", json) ?: "stream failed",
            ),
        )
        else -> ResponseChunk.Status(text = "unknown:${eventName ?: "<null>"}")
    }
}

/**
 * Lightweight runtime exception carried by [ResponseChunk.Failed] when a wire
 * `error` event is received from the ai-gateway. Static safe message · upstream
 * detail intentionally NOT serialized to avoid log-pivot exfil per Stage 0 D-10
 * INLINE PII redaction.
 */
public class ResponseStreamException(
    message: String,
) : RuntimeException(message)

private fun parseJsonObject(
    data: String,
    json: Json,
): JsonObject? {
    if (data.isBlank()) return null
    return try {
        json.parseToJsonElement(data).jsonObject
    } catch (_: Throwable) {
        null
    }
}

private fun parseStringField(
    data: String,
    key: String,
    json: Json,
): String? {
    val obj = parseJsonObject(data, json) ?: return null
    return obj[key]?.jsonPrimitive?.content
}
