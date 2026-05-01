package app.moolu.ai.llm

import app.moolu.foundation.testing.FakeMooluLogger
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Wire-protocol coverage for [parseSseEventData] — the pure mapping
 * extracted from [RemoteLlmProvider.stream] so we can verify SSE-frame
 * → [TokenChunk] semantics without standing up an SSE-capable
 * `HttpClientEngine`. Mirrors the behaviour table in the function's
 * KDoc.
 *
 * **Contract**: this test is the source-of-truth for the wire format
 * that `moolu-app-server` `internal/ai/translator.go` (per ADR-base-018
 * §C1 plan-15 ship) must produce. Any regression here implies a
 * server-side translator change requires a synced client SDK update +
 * major bump (per ADR-base-019 §F1).
 *
 * Plan-16 §T6 (verbatim port from 米鹿 core-ai GatewayLlmParserTest;
 * class rename only — 12 cases identical) + §T12 plan-16 review C-H1
 * fix (4 NEW cases for parseStreamEvent dispatcher: error event name +
 * garbage error data + message name + null name delegate to data parser).
 */
class RemoteLlmProviderParserTest {
    private val logger = FakeMooluLogger()

    @Test
    fun null_data_emits_keepalive_only() {
        val outcome = SseEventOutcome()
        val frames = parseSseEventData(data = null, logger = logger, outcome = outcome)
        frames shouldHaveSize 1
        frames[0] shouldBe TokenChunk.KeepAlive
        outcome.terminated shouldBe false
    }

    @Test
    fun empty_data_emits_keepalive_only() {
        val outcome = SseEventOutcome()
        val frames = parseSseEventData(data = "", logger = logger, outcome = outcome)
        frames shouldHaveSize 1
        frames[0] shouldBe TokenChunk.KeepAlive
        outcome.terminated shouldBe false
    }

    @Test
    fun done_sentinel_emits_finish_stop_and_marks_terminated() {
        val outcome = SseEventOutcome()
        val frames = parseSseEventData(data = "[DONE]", logger = logger, outcome = outcome)
        frames shouldHaveSize 1
        val finish = frames[0] as TokenChunk.Finish
        finish.reason shouldBe FinishReason.Stop
        finish.usage shouldBe null
        outcome.terminated shouldBe true
    }

    @Test
    fun chunk_with_content_only_emits_one_delta() {
        val outcome = SseEventOutcome()
        val data = chunk(content = "hi")
        val frames = parseSseEventData(data, logger, outcome)
        frames shouldHaveSize 1
        (frames[0] as TokenChunk.Delta).text shouldBe "hi"
        outcome.terminated shouldBe false
    }

    @Test
    fun chunk_with_reasoning_content_emits_one_reasoning_delta() {
        val outcome = SseEventOutcome()
        val data = chunk(reasoningContent = "thinking…")
        val frames = parseSseEventData(data, logger, outcome)
        frames shouldHaveSize 1
        (frames[0] as TokenChunk.ReasoningDelta).text shouldBe "thinking…"
        outcome.terminated shouldBe false
    }

    @Test
    fun chunk_with_finish_reason_emits_finish_with_mapped_reason_and_marks_terminated() {
        val outcome = SseEventOutcome()
        val data = chunk(finishReason = "length")
        val frames = parseSseEventData(data, logger, outcome)
        frames shouldHaveSize 1
        val finish = frames[0] as TokenChunk.Finish
        finish.reason shouldBe FinishReason.Length
        outcome.terminated shouldBe true
    }

    @Test
    fun chunk_with_finish_reason_attaches_usage_when_present() {
        val outcome = SseEventOutcome()
        val data =
            chunk(
                content = "ok",
                finishReason = "stop",
                usage = """{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}""",
            )
        val frames = parseSseEventData(data, logger, outcome)
        frames shouldHaveSize 2
        (frames[0] as TokenChunk.Delta).text shouldBe "ok"
        val finish = frames[1] as TokenChunk.Finish
        finish.reason shouldBe FinishReason.Stop
        finish.usage shouldBe Usage(promptTokens = 12, completionTokens = 8, totalTokens = 20)
        outcome.terminated shouldBe true
    }

    @Test
    fun chunk_with_content_and_reasoning_emits_both_in_order() {
        val outcome = SseEventOutcome()
        val data = chunk(content = "answer", reasoningContent = "because…")
        val frames = parseSseEventData(data, logger, outcome)
        frames shouldHaveSize 2
        (frames[0] as TokenChunk.Delta).text shouldBe "answer"
        (frames[1] as TokenChunk.ReasoningDelta).text shouldBe "because…"
    }

    @Test
    fun garbage_json_drops_silently_and_keeps_outcome_open() {
        val outcome = SseEventOutcome()
        val frames = parseSseEventData(data = "not-valid-json{", logger = logger, outcome = outcome)
        frames shouldHaveSize 0
        outcome.terminated shouldBe false
    }

    @Test
    fun chunk_with_no_choices_drops_silently() {
        val outcome = SseEventOutcome()
        val frames = parseSseEventData(data = """{"choices":[]}""", logger, outcome)
        frames shouldHaveSize 0
        outcome.terminated shouldBe false
    }

    @Test
    fun unknown_finish_reason_maps_to_unknown() {
        val outcome = SseEventOutcome()
        val data = chunk(finishReason = "moon-phase")
        val frames = parseSseEventData(data, logger, outcome)
        (frames[0] as TokenChunk.Finish).reason shouldBe FinishReason.Unknown
    }

    @Test
    fun finish_reason_aliases_map_correctly() {
        listOf(
            "stop" to FinishReason.Stop,
            "length" to FinishReason.Length,
            "tool_calls" to FinishReason.ToolCall,
            "function_call" to FinishReason.ToolCall,
            "content_filter" to FinishReason.ContentFilter,
        ).forEach { (raw, expected) ->
            // Per-iteration outcome (T12 review C-M2 fix — was reusing single SseEventOutcome
            // which carries `terminated=true` across iterations and assertions still pass but
            // smell;each alias should start fresh)
            val outcome = SseEventOutcome()
            val frames = parseSseEventData(chunk(finishReason = raw), logger, outcome)
            (frames.last() as TokenChunk.Finish).reason shouldBe expected
        }
    }

    // ─── parseStreamEvent dispatcher (T12 review C-H1 fix) ─────────────────────

    @Test
    fun stream_event_with_error_name_emits_token_chunk_error_with_parsed_message() {
        // Server-side moolu-app-server `internal/api/ai.go` line 133 emits:
        //   c.SSEvent("error", `{"error":{"message":"upstream_failed"}}`)
        // (per ADR-base-018 §B1 + §C1 translator end-of-stream behavior)
        val outcome = SseEventOutcome()
        val frames =
            parseStreamEvent(
                eventName = "error",
                data = """{"error":{"message":"upstream_failed"}}""",
                logger = logger,
                outcome = outcome,
            )
        frames shouldHaveSize 1
        val err = frames[0] as TokenChunk.Error
        err.code shouldBe RemoteLlmProvider.STREAM_FAILED_CODE
        err.message shouldBe "upstream_failed"
        err.recoverable shouldBe true
        outcome.terminated shouldBe true
    }

    @Test
    fun stream_event_with_error_name_falls_back_to_generic_message_on_garbage_data() {
        val outcome = SseEventOutcome()
        val frames =
            parseStreamEvent(
                eventName = "error",
                data = "not-json",
                logger = logger,
                outcome = outcome,
            )
        frames shouldHaveSize 1
        val err = frames[0] as TokenChunk.Error
        err.message shouldBe "upstream stream error"
        outcome.terminated shouldBe true
    }

    @Test
    fun stream_event_with_message_name_delegates_to_data_parser() {
        val outcome = SseEventOutcome()
        val frames =
            parseStreamEvent(
                eventName = "message",
                data = chunk(content = "ok"),
                logger = logger,
                outcome = outcome,
            )
        frames shouldHaveSize 1
        (frames[0] as TokenChunk.Delta).text shouldBe "ok"
    }

    @Test
    fun stream_event_with_null_name_delegates_to_data_parser() {
        val outcome = SseEventOutcome()
        val frames =
            parseStreamEvent(
                eventName = null,
                data = "[DONE]",
                logger = logger,
                outcome = outcome,
            )
        frames shouldHaveSize 1
        (frames[0] as TokenChunk.Finish).reason shouldBe FinishReason.Stop
        outcome.terminated shouldBe true
    }

    private fun chunk(
        content: String? = null,
        reasoningContent: String? = null,
        finishReason: String? = null,
        usage: String? = null,
    ): String =
        buildString {
            append("{\"choices\":[{")
            append("\"delta\":{")
            val parts = mutableListOf<String>()
            if (content != null) parts += "\"content\":\"$content\""
            if (reasoningContent != null) parts += "\"reasoning_content\":\"$reasoningContent\""
            append(parts.joinToString(","))
            append("}")
            if (finishReason != null) append(",\"finish_reason\":\"$finishReason\"")
            append("}]")
            if (usage != null) append(",\"usage\":$usage")
            append("}")
        }
}
