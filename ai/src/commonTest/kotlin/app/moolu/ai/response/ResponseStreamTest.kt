/*
 * Cite: ADR-34 + spec §11.5.4 Flow 1 verbatim + spec §16.2 P6.
 *
 * Stage 0 CSO D-3 + D-7 + D-8 INLINE absorption verification:
 * - D-3 cancel idempotent · Mutex.withLock atomic state transition
 * - D-7 ResponseChunk plain sealed class (NOT @Serializable · in-process domain)
 * - D-8 graceful unknown event-name fallback to ResponseChunk.Status
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · scaffold-grade.
 */
package app.moolu.ai.response

import app.moolu.ai.response.internal.ResponseStreamImpl
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponseStreamTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `D-7 INLINE · ResponseChunk Complete is data object singleton`() {
        // Plain sealed class · not @Serializable · in-process domain type
        val a: ResponseChunk = ResponseChunk.Complete
        val b: ResponseChunk = ResponseChunk.Complete
        a shouldBe b
        (a === b) shouldBe true
    }

    @Test
    fun `D-3 INLINE · cancel is idempotent · multiple calls no-op after first transition`() = runTest {
        val stream = ResponseStreamImpl(flowOf())
        assertTrue(stream.isActive)
        stream.cancel()
        assertFalse(stream.isActive)
        stream.cancel()
        stream.cancel()
        assertFalse(stream.isActive)
    }

    @Test
    fun `D-3 INLINE · isActive monotonically false after cancel`() = runTest {
        val stream = ResponseStreamImpl(flowOf(ResponseChunk.TextDelta("hi"), ResponseChunk.Complete))
        assertTrue(stream.isActive)
        stream.cancel()
        assertFalse(stream.isActive)
    }

    @Test
    fun `D-3 INLINE · observe terminates within one upstream emission after cancel`() = runTest {
        val stream =
            ResponseStreamImpl(
                flowOf(
                    ResponseChunk.TextDelta("a"),
                    ResponseChunk.TextDelta("b"),
                    ResponseChunk.Complete,
                ),
            )
        stream.cancel()
        val emissions = stream.observe().toList()
        emissions shouldBe emptyList()
    }

    @Test
    fun `D-7 INLINE · observe emits all chunks before completion when not cancelled`() = runTest {
        val stream =
            ResponseStreamImpl(
                flowOf(
                    ResponseChunk.TextDelta("hello"),
                    ResponseChunk.TextDelta(" world"),
                    ResponseChunk.Complete,
                ),
            )
        val emissions = stream.observe().toList()
        emissions.size shouldBe 3
        (emissions[0] as ResponseChunk.TextDelta).delta shouldBe "hello"
        (emissions[1] as ResponseChunk.TextDelta).delta shouldBe " world"
        emissions[2] shouldBe ResponseChunk.Complete
    }

    @Test
    fun `D-8 INLINE · parseResponseChunk text_delta with delta field`() {
        val chunk = parseResponseChunk("text_delta", """{"delta":"hi"}""", json)
        chunk.shouldBeInstanceOf<ResponseChunk.TextDelta>()
        chunk.delta shouldBe "hi"
    }

    @Test
    fun `D-8 INLINE · parseResponseChunk text_delta with raw data fallback`() {
        val chunk = parseResponseChunk("text_delta", "raw delta text", json)
        chunk.shouldBeInstanceOf<ResponseChunk.TextDelta>()
        chunk.delta shouldBe "raw delta text"
    }

    @Test
    fun `D-8 INLINE · parseResponseChunk tool_call accepts both wire field aliases`() {
        val snake = parseResponseChunk(
            eventName = "tool_call",
            data = """{"tool_name":"search","args":{"q":"x"},"call_id":"c1"}""",
            json = json,
        )
        snake.shouldBeInstanceOf<ResponseChunk.ToolCall>()
        snake.toolName shouldBe "search"
        snake.callId shouldBe "c1"

        val camel = parseResponseChunk(
            eventName = "tool_call",
            data = """{"toolName":"calc","arguments":{"n":1},"callId":"c2"}""",
            json = json,
        )
        camel.shouldBeInstanceOf<ResponseChunk.ToolCall>()
        camel.toolName shouldBe "calc"
        camel.callId shouldBe "c2"
    }

    @Test
    fun `D-8 INLINE · parseResponseChunk tool_result maps to ResponseChunk_ToolResult`() {
        val chunk = parseResponseChunk(
            eventName = "tool_result",
            data = """{"call_id":"c1","result":{"answer":42}}""",
            json = json,
        )
        chunk.shouldBeInstanceOf<ResponseChunk.ToolResult>()
        chunk.callId shouldBe "c1"
        chunk.result["answer"]?.toString() shouldBe "42"
    }

    @Test
    fun `D-8 INLINE · parseResponseChunk response_completed maps to Complete`() {
        parseResponseChunk("response.completed", "", json) shouldBe ResponseChunk.Complete
        parseResponseChunk("complete", "", json) shouldBe ResponseChunk.Complete
    }

    @Test
    fun `D-8 INLINE · parseResponseChunk error event maps to Failed with safe message`() {
        val chunk = parseResponseChunk("error", """{"message":"upstream gone"}""", json)
        chunk.shouldBeInstanceOf<ResponseChunk.Failed>()
        chunk.error.shouldBeInstanceOf<ResponseStreamException>()
        chunk.error.message shouldBe "upstream gone"
    }

    @Test
    fun `D-8 INLINE · parseResponseChunk unknown event-name graceful fallback to Status`() {
        val chunk = parseResponseChunk("server_invented_event_v2", """{}""", json)
        chunk.shouldBeInstanceOf<ResponseChunk.Status>()
        chunk.text shouldStartWith "unknown:"
    }

    @Test
    fun `D-8 INLINE · parseResponseChunk null event-name graceful fallback to Status`() {
        val chunk = parseResponseChunk(null, """{}""", json)
        chunk.shouldBeInstanceOf<ResponseChunk.Status>()
        chunk.text shouldBe "unknown:<null>"
    }

    @Test
    fun `D-8 INLINE · parseResponseChunk malformed JSON for tool_call yields Status fallback`() {
        val chunk = parseResponseChunk("tool_call", "not-json{", json)
        chunk.shouldBeInstanceOf<ResponseChunk.Status>()
        chunk.text shouldBe "malformed:tool_call"
    }
}
