package app.moolu.ai.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One conversational message exchanged with an LLM.
 *
 * Shape mirrors the OpenAI Chat Completions [`messages[]`] entry — see
 * [ADR-base-005](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/adr/0005-moolu-network-design.md)
 * (米鹿 baseline LLM Gateway abstraction) + [ADR-base-019](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/adr/0019-moolu-ai-kmp-sdk.md)
 * §A1 (verbatim port + 包名 rename only). Keep fields stable: any
 * additional fields added later (tool_calls, images, audio, ...) MUST
 * be optional with a sane default so older client builds still
 * round-trip when received from the backend.
 */
@Serializable
data class Message(
    val role: Role,
    val content: String,
    val name: String? = null,
) {
    @Serializable
    enum class Role {
        @SerialName("system")
        System,

        @SerialName("user")
        User,

        @SerialName("assistant")
        Assistant,

        @SerialName("tool")
        Tool,
    }
}

/**
 * Provider-agnostic generation knobs. Forwarded by `RemoteLlmProvider`
 * to our backend `moolu-app-server` `/v1/ai/chat` endpoint, which
 * translates them per upstream provider via the AI Gateway HMAC adapter
 * (per ADR-base-018 plan-15 ship).
 *
 * `null` = "use server default" so the client never has to know what
 * each upstream provider considers a sensible value.
 */
@Serializable
data class GenOptions(
    val model: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stop: List<String> = emptyList(),
    val seed: Long? = null,
)

/**
 * Provider self-description. Used by upper layers (`feature-chat` /
 * proactive bubble) to decide which features to expose in the UI
 * without issuing a probe request.
 */
@Serializable
data class LlmCapabilities(
    val streaming: Boolean = true,
    val tools: Boolean = false,
    val vision: Boolean = false,
    val audio: Boolean = false,
    val reasoning: Boolean = false,
)

/**
 * Token-stream event emitted by [LlmProvider.stream]. Modelled as a
 * sealed hierarchy so consumers `when`-exhaust on the kind without
 * parsing provider-specific JSON shapes.
 *
 * - [Delta]: assistant text fragment to render incrementally.
 * - [ReasoningDelta]: separate "thinking" channel (DeepSeek-R1
 *   `reasoning_content`). Surfacing it in UI is opt-in per ADR-base-005
 *   §"待观察"; V0.5 ships the type but doesn't render it.
 * - [KeepAlive]: heartbeat with no content; useful to keep proxies
 *   from dropping the SSE connection. Emitted whenever the upstream
 *   sends a comment-only `:` line.
 * - [Finish]: terminal frame with the reason and (when available) usage.
 * - [Error]: terminal frame for recoverable / non-recoverable failures.
 *
 * Exactly one of [Finish] or [Error] terminates the flow; downstream
 * collectors do not need to also catch exceptions for the success path.
 */
sealed class TokenChunk {
    data class Delta(
        val text: String,
    ) : TokenChunk()

    data class ReasoningDelta(
        val text: String,
    ) : TokenChunk()

    data object KeepAlive : TokenChunk()

    data class Finish(
        val reason: FinishReason,
        val usage: Usage? = null,
    ) : TokenChunk()

    data class Error(
        val code: String,
        val message: String,
        val recoverable: Boolean,
    ) : TokenChunk()
}

/** Terminal reason for a streamed or non-streamed completion. */
enum class FinishReason {
    Stop,
    Length,
    ToolCall,
    ContentFilter,
    Network,
    Cancelled,
    Unknown,
}

/**
 * Token accounting reported by the backend. Optional everywhere because
 * not all providers return it on streaming responses (e.g. DeepSeek
 * omits usage on `stream:true` unless you opt in via `stream_options`).
 */
@Serializable
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

/**
 * One-shot completion result. Returned by [LlmProvider.complete] for
 * the non-streaming path (e.g. memory summarisation, where collecting
 * an entire summary token-by-token would just slow the caller down).
 */
data class Completion(
    val message: Message,
    val finishReason: FinishReason,
    val usage: Usage? = null,
)
