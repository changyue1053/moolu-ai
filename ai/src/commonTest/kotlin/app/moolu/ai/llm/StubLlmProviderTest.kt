package app.moolu.ai.llm

import app.moolu.foundation.testing.FakeMooluLogger
import app.moolu.foundation.testing.fakeClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Coverage for [StubLlmProvider] — the offline / pre-backend
 * fallback. The composition root (plan-29) treats this as a normal
 * [LlmProvider] when AI consent is not granted, so its emission
 * shape matters for the rest of the system.
 *
 * Plan-16 §T5 (verbatim port from 米鹿 core-ai StubLlmProviderTest;
 * NoOpMooluLogger → FakeMooluLogger + FixedMooluClock → fakeClock per
 * moolu-foundation 1.0.1 testing API per ADR-base-019 §A1 adaptation note).
 */
class StubLlmProviderTest {
    private val baseEpochMs: Long = 1_700_000_000_000L

    @Test
    fun stream_emits_one_delta_per_character_then_finish() =
        runTest {
            val provider =
                StubLlmProvider(
                    clock = fakeClock(baseEpochMs),
                    logger = FakeMooluLogger(),
                    cannedResponses = listOf("hi"),
                    tokenDelayMillis = 0L,
                )
            val emissions = provider.stream(messages = listOf(Message(Message.Role.User, "x"))).toList()
            // 'h', 'i', then Finish — total 3 frames.
            emissions shouldHaveSize 3
            (emissions[0] as TokenChunk.Delta).text shouldBe "h"
            (emissions[1] as TokenChunk.Delta).text shouldBe "i"
            val finish = emissions[2] as TokenChunk.Finish
            finish.reason shouldBe FinishReason.Stop
        }

    @Test
    fun stream_finish_reports_usage_summed_from_messages_and_response() =
        runTest {
            val provider =
                StubLlmProvider(
                    clock = fakeClock(baseEpochMs),
                    logger = FakeMooluLogger(),
                    cannedResponses = listOf("ok"),
                    tokenDelayMillis = 0L,
                )
            val msgs =
                listOf(
                    Message(Message.Role.System, "you are a helper"), // 16
                    Message(Message.Role.User, "do thing"), // 8
                )
            val emissions = provider.stream(msgs).toList()
            val finish = emissions.last() as TokenChunk.Finish
            // Stub uses content.length (chars) as a placeholder for token count.
            finish.usage?.promptTokens shouldBe 16 + 8
            finish.usage?.completionTokens shouldBe 2
            finish.usage?.totalTokens shouldBe 16 + 8 + 2
        }

    @Test
    fun complete_returns_assistant_message_with_picked_canned_response() =
        runTest {
            val provider =
                StubLlmProvider(
                    clock = fakeClock(baseEpochMs),
                    logger = FakeMooluLogger(),
                    cannedResponses = listOf("only-one"),
                    tokenDelayMillis = 0L,
                )
            val completion = provider.complete(messages = emptyList())
            completion.message.role shouldBe Message.Role.Assistant
            completion.message.content shouldBe "only-one"
            completion.finishReason shouldBe FinishReason.Stop
        }

    @Test
    fun round_robin_picker_cycles_through_canned_responses() =
        runTest {
            val canned = listOf("a", "b", "c")
            val provider =
                StubLlmProvider(
                    clock = fakeClock(baseEpochMs),
                    logger = FakeMooluLogger(),
                    cannedResponses = canned,
                    tokenDelayMillis = 0L,
                )
            val seen = (0..3).map { provider.complete(emptyList()).message.content }
            seen shouldBe listOf("a", "b", "c", "a")
        }

    @Test
    fun custom_picker_allows_deterministic_test_runs() =
        runTest {
            val provider =
                StubLlmProvider(
                    clock = fakeClock(baseEpochMs),
                    logger = FakeMooluLogger(),
                    cannedResponses = listOf("alpha", "beta"),
                    tokenDelayMillis = 0L,
                    responsePicker = { it.last() },
                )
            provider.complete(emptyList()).message.content shouldBe "beta"
            provider.complete(emptyList()).message.content shouldBe "beta"
        }

    @Test
    fun capabilities_advertise_streaming_only() {
        val provider =
            StubLlmProvider(
                clock = fakeClock(baseEpochMs),
                logger = FakeMooluLogger(),
            )
        provider.capabilities shouldBe
            LlmCapabilities(
                streaming = true,
                tools = false,
                vision = false,
                audio = false,
                reasoning = false,
            )
    }

    @Test
    fun init_rejects_empty_canned_responses_list() {
        shouldThrow<IllegalArgumentException> {
            StubLlmProvider(
                clock = fakeClock(baseEpochMs),
                logger = FakeMooluLogger(),
                cannedResponses = emptyList(),
            )
        }
    }

    @Test
    fun init_rejects_negative_token_delay() {
        shouldThrow<IllegalArgumentException> {
            StubLlmProvider(
                clock = fakeClock(baseEpochMs),
                logger = FakeMooluLogger(),
                tokenDelayMillis = -1L,
            )
        }
    }

    @Test
    fun default_responses_are_all_within_proactive_bubble_max_chars() {
        // The 8 canned 米鹿-style replies double as fallback bubble copy
        // when the proactive bubble is asked without a real LLM. Keeping
        // them ≤35 chars matches the home bubble UI envelope.
        StubLlmProvider.DEFAULT_RESPONSES.forEach { reply ->
            (reply.length <= 35) shouldBe true
        }
    }
}
