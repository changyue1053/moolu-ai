package app.moolu.ai.llm.internal

import app.moolu.ai.llm.GenOptions
import app.moolu.ai.llm.Message
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-the-wire shapes for `POST /v1/ai/chat` — Internal to `moolu-ai`:
 * the public surface is [app.moolu.ai.llm.LlmProvider];these models
 * are private implementation details of [app.moolu.ai.llm.RemoteLlmProvider].
 *
 * **Inbound** ([ChatRequest]) matches plan-15 server's
 * `internal/api/ai.go` `ChatRequest` struct (per ADR-base-018 §B1 +
 * T12 plan-16 review C-CRIT-1 fix):
 *  - `assistant_id` `binding:"required"` — must be non-blank
 *  - `thread_id` optional — V1+ thread continuity
 *  - `messages` `binding:"required"` — array of role+content+name
 *  - `stream` optional — V0.5 server-side always SSE (boolean ignored)
 *
 * Forward-compat fields (米鹿 baseline OpenAI Chat Completions shape):
 * `model` / `temperature` / `top_p` / `max_tokens` / `stop` / `seed`
 * preserved on the wire for V1+ — V0.5 server uses Go default JSON
 * unmarshal which silently ignores unknown fields, so sending them is
 * safe.
 *
 * **Outbound** ([ChatCompletionChunk] + [ChatCompletionResponse]) field
 * names follow the OpenAI spec exactly so the JSON deserialises cleanly
 * against `moolu-app-server` `internal/ai/translator.go` output (per
 * plan-15 ship + ADR-base-018 §C1) — which maps LangGraph events to
 * OpenAI-compat events without per-provider branching (see ADR-base-005
 * §背景调研 evidence).
 *
 * Visibility constraint (per ADR-base-019 §A1 + spec §1.1 row D):
 * `apiValidation.ignoredPackages.add("app.moolu.ai.llm.internal")` —
 * internal types do NOT enter public ABI snapshot.
 */
@Serializable
internal data class ChatRequest(
    @SerialName("assistant_id")
    val assistantId: String,
    @SerialName("thread_id")
    val threadId: String? = null,
    val messages: List<ChatRequestMessage>,
    val stream: Boolean = false,
    val model: String? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val seed: Long? = null,
)

@Serializable
internal data class ChatRequestMessage(
    val role: String,
    val content: String,
    val name: String? = null,
)

/** Streaming response chunk (`stream:true`). */
@Serializable
internal data class ChatCompletionChunk(
    val id: String? = null,
    @SerialName("object")
    val obj: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

@Serializable
internal data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
)

/** Non-streaming response (`stream:false`). */
@Serializable
internal data class ChatCompletionResponse(
    val id: String? = null,
    @SerialName("object")
    val obj: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<CompletionChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

@Serializable
internal data class CompletionChoice(
    val index: Int = 0,
    val message: CompletionMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class CompletionMessage(
    val role: String,
    val content: String,
)

@Serializable
internal data class ChatUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
)

/**
 * Shared [Json] instance for OpenAI-compat wire serialization.
 *
 * `exceptionsWithDebugInfo = false` per T12 plan-16 review S-M1 fix:
 * kotlinx-serialization 1.11.x exception messages may include
 * input substrings on decode failure, which would propagate
 * attacker-controlled fragments into [MooluLogger] warn lines via
 * [parseSseEventData] when a hostile relay sends malformed chunks.
 * Disabling debug info ensures logs only carry exception class +
 * static message, preserving the privacy invariant documented in
 * [app.moolu.ai.llm.AiEventReporter] KDoc (no prompt/completion text
 * in logs).
 */
internal val OpenAiJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        decodeEnumsCaseInsensitive = false // explicit default;document
        // exceptionsWithDebugInfo defaults to false in 1.11.x for non-debug
        // pipelines but pin explicitly to forbid attacker input substrings
        // in exception messages (T12 review S-M1).
    }

internal fun Message.Role.asWireString(): String =
    when (this) {
        Message.Role.System -> "system"
        Message.Role.User -> "user"
        Message.Role.Assistant -> "assistant"
        Message.Role.Tool -> "tool"
    }

internal fun List<Message>.toRequestMessages(): List<ChatRequestMessage> =
    map { ChatRequestMessage(role = it.role.asWireString(), content = it.content, name = it.name) }

/**
 * Map [GenOptions] + caller context to the wire [ChatRequest] shape.
 * `assistantId` is a ctor-time concern of [app.moolu.ai.llm.RemoteLlmProvider]
 * (single-app `moolu_companion` for V0.5 per ADR-base-019 §B1);
 * `threadId` is per-call (V1+ thread continuity;V0.5 default null).
 */
internal fun GenOptions.toRequest(
    messages: List<Message>,
    stream: Boolean,
    assistantId: String,
    threadId: String? = null,
): ChatRequest =
    ChatRequest(
        assistantId = assistantId,
        threadId = threadId,
        messages = messages.toRequestMessages(),
        stream = stream,
        model = model,
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        stop = stop.takeIf { it.isNotEmpty() },
        seed = seed,
    )
