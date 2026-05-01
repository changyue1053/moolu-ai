package app.moolu.ai.llm.internal

import app.moolu.ai.llm.GenOptions
import app.moolu.ai.llm.Message
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-the-wire shapes for the OpenAI-compatible Chat Completions API
 * (`POST /v1/ai/chat`). Internal to `moolu-ai`: the public surface is
 * [app.moolu.ai.llm.LlmProvider]; these models are private
 * implementation details of [app.moolu.ai.llm.RemoteLlmProvider].
 *
 * Field names follow the OpenAI spec exactly so the same JSON
 * serialises cleanly against `moolu-app-server` `internal/ai/translator.go`
 * output (per plan-15 ship + ADR-base-018 §C1) — which itself maps
 * LangGraph events to OpenAI-compat events without per-provider
 * branching (see ADR-base-005 §背景调研 evidence).
 *
 * Visibility constraint (per ADR-base-019 §A1 + spec §1.1 row D):
 * `apiValidation.ignoredPackages.add("app.moolu.ai.llm.internal")` —
 * internal types do NOT enter public ABI snapshot.
 */
@Serializable
internal data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatRequestMessage>,
    val stream: Boolean = false,
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

internal val OpenAiJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
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

internal fun GenOptions.toRequest(
    messages: List<Message>,
    stream: Boolean,
): ChatCompletionRequest =
    ChatCompletionRequest(
        model = model,
        messages = messages.toRequestMessages(),
        stream = stream,
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        stop = stop.takeIf { it.isNotEmpty() },
        seed = seed,
    )
