package app.moolu.ai.llm

import kotlinx.coroutines.flow.Flow

/**
 * The single client-side LLM abstraction. Every consumer
 * (`feature-chat-impl` / proactive bubble / future memory extraction
 * worker) talks to this and only this — never directly to Ktor /
 * DeepSeek / Doubao SDKs.
 *
 * Two implementations ship in plan-16 V0.5:
 * - [RemoteLlmProvider] — production: streams over SSE from
 *   `moolu-app-server` `/v1/ai/chat` (per ADR-base-018 plan-15 ship).
 *   See [ADR-base-019](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/adr/0019-moolu-ai-kmp-sdk.md).
 * - [StubLlmProvider] — fallback / unit-test fake: emits canned text
 *   tokens at a configurable interval. Used (a) when consumer-level
 *   AI consent gate is `false` (consumer concern;留 plan-19 ui-chat),
 *   and (b) when the backend `moolu-app-server` hasn't been wired in
 *   composition root yet (V0.5 ship-date overlap).
 *
 * **Why two methods (`stream` + `complete`) instead of one?** Because
 * non-streaming use cases (rolling-summary generation, fact extraction)
 * collect the entire response anyway; forcing them through `stream`
 * adds a coroutine + flow allocation per call for no benefit. Concrete
 * providers MAY implement `complete` as `stream(...).toList()` or as a
 * separate `stream:false` HTTP call; that's an implementation detail.
 *
 * Any provider implementation MUST be safe to call from any dispatcher;
 * IO is its own responsibility (Ktor handles this for [RemoteLlmProvider];
 * [StubLlmProvider] uses [kotlinx.coroutines.delay]).
 */
interface LlmProvider {
    /** Static feature surface — see [LlmCapabilities]. */
    val capabilities: LlmCapabilities

    /**
     * Stream a chat completion as [TokenChunk]s. The flow is cold and
     * MUST emit exactly one terminal frame ([TokenChunk.Finish] or
     * [TokenChunk.Error]) before completing. Cancellation propagates
     * to the underlying transport (closes the SSE connection /
     * cancels the delay).
     */
    fun stream(
        messages: List<Message>,
        opts: GenOptions = GenOptions(),
    ): Flow<TokenChunk>

    /**
     * One-shot non-streaming completion. May internally use the
     * provider's `stream:false` endpoint or collect [stream]; callers
     * MUST NOT depend on which approach is used.
     */
    suspend fun complete(
        messages: List<Message>,
        opts: GenOptions = GenOptions(),
    ): Completion
}
