package app.moolu.ai.llm

import app.moolu.ai.llm.internal.ChatCompletionChunk
import app.moolu.ai.llm.internal.ChatCompletionResponse
import app.moolu.ai.llm.internal.ChatUsage
import app.moolu.ai.llm.internal.OpenAiJson
import app.moolu.ai.llm.internal.toRequest
import app.moolu.foundation.logging.MooluLogger
import app.moolu.foundation.time.MooluClock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Production [LlmProvider] that streams chat completions over SSE from
 * `moolu-app-server` `/v1/ai/chat` (per ADR-base-018 + plan-15 ship).
 *
 * **Wire protocol**: OpenAI Chat Completions API 1:1 — see
 * [ADR-base-005](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/adr/0005-moolu-network-design.md)
 * (米鹿 baseline LLM Gateway abstraction) + [ADR-base-019](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/adr/0019-moolu-ai-kmp-sdk.md)
 * §A1+§B1 (verbatim port from 米鹿 GatewayLlmProvider + class rename +
 * URL const `/v1/chat/completions` → `/v1/ai/chat`). The server-side
 * `internal/ai/translator.go` (per ADR-base-018 §C1) maps LangGraph
 * agent output to OpenAI-compat events; this client never knows or
 * cares which upstream provider served the request.
 *
 * **HttpClient requirements** (composition root in plan-29 must
 * configure these on the injected client per ADR-base-019 §C1 —
 * BearerTokenProvider 5th cross-SDK reuse path):
 * 1. SSE plugin installed: `install(io.ktor.client.plugins.sse.SSE)`
 * 2. ContentNegotiation with kotlinx-json (for non-streaming `complete`)
 * 3. Bearer auth (optional V0.5 testing; **required once plan-15
 *    `/v1/ai/chat` JWT auth deployed** — see plan-19 platform-auth
 *    ships and plan-29 米鹿 V0.5 cutover)
 *
 * **Threading**: Implementations of [Flow] returned by [stream] are
 * cold; collection happens on whatever dispatcher the caller uses. The
 * SSE session itself runs IO on the engine's dispatcher (OkHttp on
 * Android, Darwin on iOS).
 *
 * **Cancellation**: `flow.cancel()` propagates into the underlying SSE
 * session (closing the HTTP connection mid-stream) — verified in
 * 米鹿 baseline `GatewayLlmProviderIntegrationTest` and inherited
 * behavior here per ADR-base-019 §A1 verbatim port.
 *
 * **API adaptation (ADR-base-019 §A1)**: 米鹿 baseline
 * `logger.debug(TAG, message)` (tag-per-call) → `logger.debug(message)`
 * (tag-at-construction per moolu-foundation 1.0.1 post-plan-02 PM §5.3
 * hotfix). Constructor still takes a [MooluLogger] interface
 * (composition root supplies the tagged instance).
 *
 * @property httpClient pre-configured by composition root (SSE +
 *   ContentNegotiation + Bearer auth already installed)
 * @property baseUrl backend root, e.g. `https://api.moolu.app`
 *   (NOT AI Gateway 直连;is `moolu-app-server` per spec §5.6 + plan-15)
 * @property assistantId LangGraph agent identifier for plan-15 server's
 *   `ChatRequest.assistant_id` `binding:"required"` field (per
 *   ADR-base-018 §B1 + T12 plan-16 review C-CRIT-1 fix). V0.5 single-app
 *   `moolu_companion` per spec §8.5.1; V1+ multi-agent app may pass
 *   `moolu_proactive` / `moolu_intent_recognizer` per plan-17 LangGraph
 *   agents config.
 * @property clock used only for breadcrumb timestamps
 * @property logger debug-channel observability (no prompt content logged)
 * @property eventReporter Sentry-style hook for failure metadata; V0.5
 *   default is [AiEventReporter.NoOp], plan-21 wires real Sentry-KMP
 * @property capabilities advertised feature surface; default = streaming
 *   only. Override only when composition root has confirmed the backend
 *   supports tools / vision / etc.
 */
class RemoteLlmProvider(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val assistantId: String,
    private val clock: MooluClock,
    private val logger: MooluLogger,
    private val eventReporter: AiEventReporter = AiEventReporter.NoOp,
    override val capabilities: LlmCapabilities =
        LlmCapabilities(
            streaming = true,
            tools = false,
            vision = false,
            audio = false,
            reasoning = false,
        ),
) : LlmProvider {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(!baseUrl.endsWith('/')) {
            "baseUrl must not end with '/' (got '$baseUrl'); endpoint paths are appended verbatim"
        }
        require(assistantId.isNotBlank()) {
            "assistantId must not be blank (plan-15 ChatRequest.assistant_id is binding:required)"
        }
    }

    private val streamEndpoint: String get() = "$baseUrl$STREAM_PATH"

    override fun stream(
        messages: List<Message>,
        opts: GenOptions,
    ): Flow<TokenChunk> =
        flow {
            val payload = OpenAiJson.encodeToString(opts.toRequest(messages, stream = true, assistantId = assistantId))
            logger.debug(
                "stream() POST $streamEndpoint assistantId=$assistantId messages=${messages.size} startedAt=${clock.now()}",
            )
            httpClient.sse(
                urlString = streamEndpoint,
                request = {
                    headers {
                        append(HttpHeaders.Accept, "text/event-stream")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                },
            ) {
                val outcome = SseEventOutcome()
                incoming.collect { event ->
                    val frames = parseStreamEvent(event.event, event.data, logger, outcome)
                    frames.forEach { emit(it) }
                }
                if (!outcome.terminated) {
                    emit(TokenChunk.Finish(reason = FinishReason.Stop, usage = null))
                }
            }
        }.catch { t ->
            // Note: kotlinx Flow.catch does NOT catch CancellationException
            // (per kotlinx-coroutines docs) — cancellation propagates without
            // being mapped to TokenChunk.Error or reaching eventReporter.
            // Static phrase preserves the privacy invariant — t.message can
            // contain upstream/framing detail (T12 review S-M2). Pass null
            // model to avoid potentially-secret opts.model in reporter (S-L1).
            eventReporter.captureLlmError(t, streamEndpoint, model = null)
            emit(
                TokenChunk.Error(
                    code = STREAM_FAILED_CODE,
                    message = "Stream failed",
                    recoverable = true,
                ),
            )
        }

    override suspend fun complete(
        messages: List<Message>,
        opts: GenOptions,
    ): Completion {
        val payload = OpenAiJson.encodeToString(opts.toRequest(messages, stream = false, assistantId = assistantId))
        logger.debug(
            "complete() POST $streamEndpoint assistantId=$assistantId messages=${messages.size} startedAt=${clock.now()}",
        )
        return runCatching {
            val response: HttpResponse =
                httpClient.post(streamEndpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            if (!response.status.isSuccess()) {
                throw RemoteLlmHttpException(response.status, "HTTP ${response.status.value}")
            }
            val raw = response.body<String>()
            val parsed = OpenAiJson.decodeFromString<ChatCompletionResponse>(raw)
            val choice =
                parsed.choices.firstOrNull()
                    ?: throw RemoteLlmHttpException(
                        HttpStatusCode.UnprocessableEntity,
                        "moolu-app-server returned 0 choices",
                    )
            Completion(
                message =
                    Message(
                        role = Message.Role.Assistant,
                        content = choice.message.content,
                    ),
                finishReason = choice.finishReason.orEmpty().toFinishReason(),
                usage = parsed.usage?.toDomainUsage(),
            )
        }.onFailure { t ->
            // Static null model — model field is V1+ forward-compat (per ADR-019 §A1
            // 米鹿 baseline forward-compat) but not used by plan-15 server binding.
            // Avoids passing potentially-secret opts.model to reporter (T12 review S-L1).
            eventReporter.captureLlmError(t, streamEndpoint, model = null)
        }.getOrThrow()
    }

    /** `moolu-app-server` `/v1/ai/chat` returned a non-2xx HTTP status. */
    class RemoteLlmHttpException(
        val status: HttpStatusCode,
        message: String,
    ) : RuntimeException(message)

    companion object {
        /**
         * `moolu-app-server` SSE relay endpoint per ADR-base-018 §B1
         * + plan-15 ship (replaces 米鹿 baseline `/v1/chat/completions`
         * which talked AI Gateway directly per ADR-base-019 §B1).
         *
         * Internal const — V0.5 single backend;V1+ multi-backend
         * routing via ctor param is source-compat upgrade
         * (per ADR-base-019 §B1 trade-off analysis).
         */
        internal const val STREAM_PATH: String = "/v1/ai/chat"
        internal const val DONE_SENTINEL: String = "[DONE]"

        /** Stable error code for [TokenChunk.Error.code] when stream collection fails. */
        const val STREAM_FAILED_CODE: String = "stream-failed"
    }
}

/**
 * Mutable cursor passed through the per-event parsing loop so the
 * parser can mark "stream is terminally finished" without owning the
 * outer flow state. Lives only for the lifetime of one `stream()`
 * collection.
 */
internal class SseEventOutcome(
    var terminated: Boolean = false,
)

/**
 * Top-level SSE event dispatcher: routes by `eventName` first, then
 * delegates to [parseSseEventData] for default `message` / null-name
 * events. Added in T12 plan-16 review C-H1 fix to handle
 * `event: error` SSE frames from `moolu-app-server` `/v1/ai/chat`
 * (per ADR-base-018 §B1 + plan-15 ai.go line 133 `c.SSEvent("error", ...)`).
 *
 * Behaviour:
 *  - `eventName == "error"` → 1 [TokenChunk.Error] with parsed message,
 *    `outcome.terminated = true` (no further frames expected;server
 *    closed connection)
 *  - else → delegate to [parseSseEventData] (米鹿 baseline pure parser)
 */
internal fun parseStreamEvent(
    eventName: String?,
    data: String?,
    logger: MooluLogger,
    outcome: SseEventOutcome,
): List<TokenChunk> {
    if (eventName == "error") {
        outcome.terminated = true
        val msg = parseErrorEventMessage(data) ?: "upstream stream error"
        return listOf(
            TokenChunk.Error(
                code = RemoteLlmProvider.STREAM_FAILED_CODE,
                message = msg,
                recoverable = true,
            ),
        )
    }
    return parseSseEventData(data, logger, outcome)
}

/**
 * Parse `{"error":{"message":"..."}}` payload from server-emitted
 * `event: error` SSE frame (per ADR-base-018 §C1 translator + ai.go
 * end-of-stream emission). Returns null if data is null/blank/garbage.
 */
internal fun parseErrorEventMessage(data: String?): String? {
    if (data.isNullOrBlank()) return null
    return runCatching {
        val root = OpenAiJson.parseToJsonElement(data).jsonObject
        root["error"]
            ?.jsonObject
            ?.get("message")
            ?.jsonPrimitive
            ?.content
    }.getOrNull()
}

/**
 * Pure mapping from one SSE `data:` payload to zero-or-more
 * [TokenChunk] emissions. Extracted from [RemoteLlmProvider.stream] so
 * the wire-protocol behaviour can be unit-tested without standing up
 * an SSE-capable engine — the Ktor [io.ktor.client.engine.mock.MockEngine]
 * does not declare `SSECapability` in 3.4.x, so end-to-end SSE drives
 * have to live behind a real backend or `testApplication`.
 *
 * Behaviour table (exhaustive, mirrored by `RemoteLlmProviderParserTest`):
 *  - `data` is null or empty       → 1 [TokenChunk.KeepAlive]
 *  - `data` == `[DONE]`            → 1 [TokenChunk.Finish] (Stop), terminates
 *  - `data` is invalid JSON        → 0 chunks (warn + drop)
 *  - chunk has no choices          → 0 chunks
 *  - chunk has `delta.content`     → 1 [TokenChunk.Delta]
 *  - chunk has `delta.reasoning_content` → 1 [TokenChunk.ReasoningDelta]
 *  - chunk has `finish_reason`     → 1 [TokenChunk.Finish] (mapped + usage), terminates
 *  - all of the above can co-emit in a single chunk
 */
internal fun parseSseEventData(
    data: String?,
    logger: MooluLogger,
    outcome: SseEventOutcome,
): List<TokenChunk> {
    if (data.isNullOrEmpty()) {
        return listOf(TokenChunk.KeepAlive)
    }
    if (data == RemoteLlmProvider.DONE_SENTINEL) {
        outcome.terminated = true
        return listOf(TokenChunk.Finish(reason = FinishReason.Stop, usage = null))
    }
    val chunk =
        runCatching { OpenAiJson.decodeFromString<ChatCompletionChunk>(data) }
            .getOrElse {
                logger.warn("stream() failed to decode chunk", it)
                return emptyList()
            }
    val choice = chunk.choices.firstOrNull() ?: return emptyList()
    val emissions = mutableListOf<TokenChunk>()
    val deltaContent = choice.delta.content
    if (!deltaContent.isNullOrEmpty()) {
        emissions += TokenChunk.Delta(deltaContent)
    }
    val reasoning = choice.delta.reasoningContent
    if (!reasoning.isNullOrEmpty()) {
        emissions += TokenChunk.ReasoningDelta(reasoning)
    }
    val finish = choice.finishReason
    if (!finish.isNullOrEmpty()) {
        emissions +=
            TokenChunk.Finish(
                reason = finish.toFinishReason(),
                usage = chunk.usage?.toDomainUsage(),
            )
        outcome.terminated = true
    }
    return emissions
}

private fun String.toFinishReason(): FinishReason =
    when (this.lowercase()) {
        "stop" -> FinishReason.Stop
        "length" -> FinishReason.Length
        "tool_calls", "function_call" -> FinishReason.ToolCall
        "content_filter" -> FinishReason.ContentFilter
        "" -> FinishReason.Unknown
        else -> FinishReason.Unknown
    }

private fun ChatUsage.toDomainUsage(): Usage =
    Usage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
    )
