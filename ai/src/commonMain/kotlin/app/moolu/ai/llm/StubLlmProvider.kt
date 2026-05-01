package app.moolu.ai.llm

import app.moolu.foundation.logging.MooluLogger
import app.moolu.foundation.time.MooluClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Offline / pre-backend [LlmProvider] that "streams" canned responses
 * by emitting a [TokenChunk.Delta] per character with a configurable
 * inter-character delay.
 *
 * Used by composition root (plan-29) as the default binding when **any**
 * of the following is true:
 * 1. Consumer-level AI consent gate is `false` (consumer concern;
 *    plan-19 ui-chat 自然落地处实施 per ADR-base-019 §D1)
 * 2. Remote Config flag `ai.gateway.enabled == false`
 * 3. The build is missing a backend base URL (debug builds without
 *    `local.properties.backend.baseUrl`)
 *
 * Production builds with consent + a healthy backend wire
 * [RemoteLlmProvider] instead.
 *
 * **Test usage:** unit tests inject `StubLlmProvider(cannedResponses =
 * listOf("ok"), tokenDelayMillis = 0)` to drive consumers
 * deterministically without spinning up a real `HttpClient`.
 *
 * **API adaptation note (per ADR-base-019 §A1)**: 米鹿 baseline used
 * `logger.debug(TAG, message)` (tag-per-call). moolu-foundation 1.0.1
 * post-plan-02 PM §5.3 hotfix uses tag-at-construction via
 * `mooluLogger("tag")` factory + plain `debug(message)` API. Constructor
 * still takes a [MooluLogger] interface (composition root supplies the
 * tagged instance).
 */
class StubLlmProvider(
    private val clock: MooluClock,
    private val logger: MooluLogger,
    private val cannedResponses: List<String> = DEFAULT_RESPONSES,
    private val tokenDelayMillis: Long = DEFAULT_TOKEN_DELAY_MILLIS,
    private val responsePicker: (List<String>) -> String = ROUND_ROBIN_PICKER(),
) : LlmProvider {
    override val capabilities: LlmCapabilities =
        LlmCapabilities(
            streaming = true,
            tools = false,
            vision = false,
            audio = false,
            reasoning = false,
        )

    init {
        require(cannedResponses.isNotEmpty()) { "StubLlmProvider needs at least one canned response" }
        require(tokenDelayMillis >= 0) { "tokenDelayMillis must be >= 0, got $tokenDelayMillis" }
    }

    override fun stream(
        messages: List<Message>,
        opts: GenOptions,
    ): Flow<TokenChunk> =
        flow {
            val pick = responsePicker(cannedResponses)
            logger.debug("stream() picking canned response (${pick.length} chars)")
            for (ch in pick) {
                emit(TokenChunk.Delta(ch.toString()))
                if (tokenDelayMillis > 0) delay(tokenDelayMillis)
            }
            emit(
                TokenChunk.Finish(
                    reason = FinishReason.Stop,
                    usage =
                        Usage(
                            promptTokens = messages.sumOf { it.content.length },
                            completionTokens = pick.length,
                            totalTokens = messages.sumOf { it.content.length } + pick.length,
                        ),
                ),
            )
        }

    override suspend fun complete(
        messages: List<Message>,
        opts: GenOptions,
    ): Completion {
        val pick = responsePicker(cannedResponses)
        logger.debug("complete() picking canned response (${pick.length} chars) at ${clock.now()}")
        return Completion(
            message = Message(role = Message.Role.Assistant, content = pick),
            finishReason = FinishReason.Stop,
            usage =
                Usage(
                    promptTokens = messages.sumOf { it.content.length },
                    completionTokens = pick.length,
                    totalTokens = messages.sumOf { it.content.length } + pick.length,
                ),
        )
    }

    companion object {
        const val DEFAULT_TOKEN_DELAY_MILLIS: Long = 30L

        /**
         * Default 8 米鹿-style replies covering greeting, encouragement,
         * empathy, closing turn-taking. Intentionally short (≤ 35 字 each)
         * so they fit the proactive bubble UI envelope without truncation.
         */
        val DEFAULT_RESPONSES: List<String> =
            listOf(
                "嗨！我是米鹿，今天感觉怎么样？",
                "听起来不错！要不要一起记一下今天吃的？",
                "辛苦啦，先深呼吸几秒钟，把节奏放慢一点。",
                "我在这里陪你，有什么想聊的？",
                "今天有没有让自己开心的小事呀？",
                "饿了的话，喝一杯温水也是个不错的开始。",
                "走两步、动一动，身体会感谢你的。",
                "晚安啦，今天已经做得很好了。",
            )

        /** Cycle through canned responses round-robin for deterministic tests. */
        @Suppress("FunctionName")
        fun ROUND_ROBIN_PICKER(): (List<String>) -> String {
            var i = 0
            return { list ->
                val pick = list[i % list.size]
                i = (i + 1) % list.size
                pick
            }
        }
    }
}
