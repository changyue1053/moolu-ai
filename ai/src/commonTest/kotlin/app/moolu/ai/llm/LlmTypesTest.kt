package app.moolu.ai.llm

import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

/**
 * Unit coverage for the data contracts in `app.moolu.ai.llm`.
 *
 * These tests are the Source-of-Truth for the OpenAI-compatible wire
 * shape — if any test here changes, ADR-base-019 §F1 + the backend
 * `moolu-app-server` `internal/ai/translator.go` MUST be updated in
 * lockstep (the on-the-wire JSON is what plan-15 server-side translator
 * outputs and what client-side `parseSseEventData` parses).
 *
 * Plan-16 §T4 (verbatim port from 米鹿 core-ai LlmTypesTest;包名 rename only).
 */
class LlmTypesTest {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

    @Test
    fun message_role_serialises_to_lowercase_wire_strings() {
        val systemMsg = Message(role = Message.Role.System, content = "you are an assistant")
        val userMsg = Message(role = Message.Role.User, content = "hi")
        val assistantMsg = Message(role = Message.Role.Assistant, content = "hello")
        val toolMsg = Message(role = Message.Role.Tool, content = "{\"ok\":true}")

        listOf(
            systemMsg to "system",
            userMsg to "user",
            assistantMsg to "assistant",
            toolMsg to "tool",
        ).forEach { (msg, expected) ->
            val obj = json.encodeToJsonElement(Message.serializer(), msg).jsonObject
            obj.getValue("role").jsonPrimitive.content shouldBe expected
        }
    }

    @Test
    fun message_round_trips_through_json() {
        val original =
            Message(
                role = Message.Role.User,
                content = "今天吃什么？",
                name = "alice",
            )
        val raw = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), raw)
        decoded shouldBe original
    }

    @Test
    fun gen_options_omits_nulls_when_explicitNulls_off() {
        val opts = GenOptions()
        val raw = json.encodeToString(GenOptions.serializer(), opts)
        // No fields set → empty `{}` because all fields default to null/empty
        // and our JSON config skips defaults + nulls.
        raw shouldBe "{}"
    }

    @Test
    fun gen_options_carries_typed_fields_through_round_trip() {
        val opts =
            GenOptions(
                model = "deepseek-chat",
                temperature = 0.7,
                topP = 0.9,
                maxTokens = 256,
                stop = listOf("###", "<|END|>"),
                seed = 42L,
            )
        val raw = json.encodeToString(GenOptions.serializer(), opts)
        val decoded = json.decodeFromString(GenOptions.serializer(), raw)
        decoded shouldBe opts
        decoded.stop shouldContainExactly listOf("###", "<|END|>")
    }

    @Test
    fun usage_round_trips_with_required_fields_only() {
        val usage = Usage(promptTokens = 10, completionTokens = 20, totalTokens = 30)
        val raw = json.encodeToString(Usage.serializer(), usage)
        val decoded = json.decodeFromString(Usage.serializer(), raw)
        decoded shouldBe usage
    }

    @Test
    fun llm_capabilities_default_streaming_true_other_features_false() {
        val caps = LlmCapabilities()
        caps.streaming shouldBe true
        caps.tools shouldBe false
        caps.vision shouldBe false
        caps.audio shouldBe false
        caps.reasoning shouldBe false
    }

    @Test
    fun token_chunk_finish_is_terminal_and_carries_optional_usage() {
        val finishWithUsage =
            TokenChunk.Finish(
                reason = FinishReason.Stop,
                usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2),
            )
        finishWithUsage.reason shouldBe FinishReason.Stop
        finishWithUsage.usage?.totalTokens shouldBe 2

        val finishNoUsage = TokenChunk.Finish(reason = FinishReason.Length)
        finishNoUsage.usage shouldBe null
    }

    @Test
    fun token_chunk_error_carries_recoverable_flag_and_message() {
        val err =
            TokenChunk.Error(
                code = "rate_limited",
                message = "TPM exceeded — retry in 30s",
                recoverable = true,
            )
        err.code shouldBe "rate_limited"
        err.recoverable shouldBe true
    }

    @Test
    fun token_chunk_delta_and_reasoning_delta_are_distinct_subtypes() {
        val text: TokenChunk = TokenChunk.Delta(text = "hi")
        val reasoning: TokenChunk = TokenChunk.ReasoningDelta(text = "thinking…")
        // Sealed exhaustive when ensures both branches are reachable.
        when (text) {
            is TokenChunk.Delta -> text.text shouldBe "hi"
            is TokenChunk.ReasoningDelta -> error("expected Delta")
            TokenChunk.KeepAlive,
            is TokenChunk.Finish,
            is TokenChunk.Error,
            -> error("expected Delta")
        }
        when (reasoning) {
            is TokenChunk.ReasoningDelta -> reasoning.text shouldBe "thinking…"
            is TokenChunk.Delta -> error("expected ReasoningDelta")
            TokenChunk.KeepAlive,
            is TokenChunk.Finish,
            is TokenChunk.Error,
            -> error("expected ReasoningDelta")
        }
    }

    @Test
    fun completion_aggregates_message_finish_reason_and_usage() {
        val completion =
            Completion(
                message = Message(role = Message.Role.Assistant, content = "ok"),
                finishReason = FinishReason.Stop,
                usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2),
            )
        completion.message.role shouldBe Message.Role.Assistant
        completion.usage?.completionTokens shouldBe 1
    }

    @Test
    fun finish_reason_enum_covers_all_known_terminal_states() {
        val reasons = FinishReason.entries.map { it.name }.toSet()
        reasons shouldBe
            setOf(
                "Stop",
                "Length",
                "ToolCall",
                "ContentFilter",
                "Network",
                "Cancelled",
                "Unknown",
            )
    }

    @Test
    fun message_decode_ignores_unknown_keys_for_forward_compat() {
        val futureWire =
            """{"role":"assistant","content":"hi","tool_calls":[],"audio_url":"x"}"""
        // Older binaries should not throw when newer wire formats add keys.
        shouldNotThrow<Throwable> {
            val decoded = json.decodeFromString(Message.serializer(), futureWire)
            decoded.content shouldBe "hi"
        }
    }
}
