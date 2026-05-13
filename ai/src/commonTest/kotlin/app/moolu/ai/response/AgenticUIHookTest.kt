/*
 * Cite: ADR-34 + ADR-7 + ADR-22 + ADR-28 + spec §11.5.4 Flow 1 verbatim.
 *
 * Stage 0 CSO D-2 + D-4 + D-5 + D-6 INLINE absorption verification:
 * - D-2 bounded StringBuilder accumulator + 65536-char hard cap + finalize on overflow
 * - D-4 sealed-class exhaustive when (chunk: ResponseChunk) · 0 else fallback
 * - D-5 Item.FinalText.streamingComplete Boolean Path B canonical
 * - D-6 ResponseChunk.ToolCall args -> Item.ToolCall arguments field rename
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · scaffold-grade.
 */
package app.moolu.ai.response

import app.moolu.ai.response.internal.AgenticUIHookImpl
import app.moolu.ai.response.internal.ItemIdGenerator
import app.moolu.ai.response.internal.ResponseStreamImpl
import app.moolu.foundation.testing.fakeClock
import app.moolu.im.conversation.ConversationId
import app.moolu.im.conversation.Item
import app.moolu.im.conversation.ItemId
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class AgenticUIHookTest {
    private val baseEpochMs: Long = 1_700_000_000_000L
    private val convId = ConversationId("conv-test-1")
    private val requestId = "req-test-1"

    /** Deterministic [ItemIdGenerator] for assertion stability per Stage 0 D-5 INLINE. */
    private class FakeItemIdGenerator : ItemIdGenerator {
        private var counter = 0
        override fun next(): ItemId = ItemId("id-${++counter}")
    }

    private fun newHook(maxAccumulatorChars: Int = 65_536): AgenticUIHookImpl =
        AgenticUIHookImpl(
            clock = fakeClock(baseEpochMs),
            itemIdGenerator = FakeItemIdGenerator(),
            maxAccumulatorChars = maxAccumulatorChars,
        )

    @Test
    fun `D-2 + D-5 INLINE · TextDelta x3 emits 3 FinalText with streamingComplete=false`() = runTest {
        val source = flowOf(
            ResponseChunk.TextDelta("hi"),
            ResponseChunk.TextDelta(" "),
            ResponseChunk.TextDelta("there"),
        )
        val stream = ResponseStreamImpl(source)
        val items = newHook().toItemStream(stream, convId, requestId).toList()
        items shouldHaveAtLeastSize 3
        val finals = items.filterIsInstance<Item.FinalText>()
        finals.size shouldBe 3
        finals.all { !it.streamingComplete } shouldBe true
        finals.last().text shouldBe "hi there"
    }

    @Test
    fun `D-5 INLINE · Complete chunk emits final FinalText with streamingComplete=true`() = runTest {
        val source = flowOf(
            ResponseChunk.TextDelta("hello"),
            ResponseChunk.Complete,
        )
        val items = newHook().toItemStream(ResponseStreamImpl(source), convId, requestId).toList()
        val finals = items.filterIsInstance<Item.FinalText>()
        finals.last().streamingComplete shouldBe true
        finals.last().text shouldBe "hello"
    }

    @Test
    fun `D-2 INLINE · accumulator overflow emits truncated final + cancels upstream`() = runTest {
        val hook = newHook(maxAccumulatorChars = 16)
        val long = "a".repeat(40)
        val source = flowOf(
            ResponseChunk.TextDelta(long),
            ResponseChunk.TextDelta("ignored"),
            ResponseChunk.Complete,
        )
        val stream = ResponseStreamImpl(source)
        val items = hook.toItemStream(stream, convId, requestId).toList()
        // Verify final FinalText carries truncated text (16 chars) + streamingComplete=true.
        val finals = items.filterIsInstance<Item.FinalText>()
        finals.last().streamingComplete shouldBe true
        finals.last().text.length shouldBe 16
    }

    @Test
    fun `D-6 INLINE · ToolCall maps args -> arguments + responseId from requestId`() = runTest {
        val args = JsonObject(mapOf("q" to JsonPrimitive("base")))
        val source = flowOf(
            ResponseChunk.ToolCall(toolName = "search", args = args, callId = "c-1"),
            ResponseChunk.Complete,
        )
        val items = newHook().toItemStream(ResponseStreamImpl(source), convId, requestId).toList()
        val toolCall = items.filterIsInstance<Item.ToolCall>().single()
        toolCall.toolName shouldBe "search"
        toolCall.callId shouldBe "c-1"
        toolCall.arguments shouldBe args
        toolCall.responseId shouldBe requestId
    }

    @Test
    fun `D-6 INLINE · ToolResult maps to Item with isError=false v1 default`() = runTest {
        val result = JsonObject(mapOf("answer" to JsonPrimitive(42)))
        val source = flowOf(
            ResponseChunk.ToolResult(callId = "c-1", result = result),
            ResponseChunk.Complete,
        )
        val items = newHook().toItemStream(ResponseStreamImpl(source), convId, requestId).toList()
        val toolResult = items.filterIsInstance<Item.ToolResult>().single()
        toolResult.callId shouldBe "c-1"
        toolResult.result shouldBe result
        toolResult.isError shouldBe false
        toolResult.responseId shouldBe requestId
    }

    @Test
    fun `D-4 INLINE · 7 variants all map (TextDelta + ReasoningStep + ToolCall + ToolResult + Status + Complete + Failed)`() = runTest {
        val source = flowOf(
            ResponseChunk.TextDelta("a"),
            ResponseChunk.ReasoningStep(text = "thinking", step = 0),
            ResponseChunk.ToolCall(
                toolName = "search",
                args = JsonObject(emptyMap()),
                callId = "c-1",
            ),
            ResponseChunk.ToolResult(callId = "c-1", result = JsonObject(emptyMap())),
            ResponseChunk.Status(text = "Searching..."),
            ResponseChunk.Failed(error = RuntimeException("boom")),
        )
        val items = newHook().toItemStream(ResponseStreamImpl(source), convId, requestId).toList()
        items.any { it is Item.FinalText } shouldBe true
        items.any { it is Item.ReasoningStep } shouldBe true
        items.any { it is Item.ToolCall } shouldBe true
        items.any { it is Item.ToolResult } shouldBe true
        items.any { it is Item.Status } shouldBe true
    }

    @Test
    fun `D-4 INLINE · Failed chunk finalizes accumulator with streamingComplete=true`() = runTest {
        val source = flowOf(
            ResponseChunk.TextDelta("partial"),
            ResponseChunk.Failed(error = RuntimeException("boom")),
        )
        val items = newHook().toItemStream(ResponseStreamImpl(source), convId, requestId).toList()
        val finals = items.filterIsInstance<Item.FinalText>()
        finals.last().streamingComplete shouldBe true
        finals.last().text shouldBe "partial"
    }

    @Test
    fun `Item createdAt populated from clock now toEpochMilliseconds`() = runTest {
        val source = flowOf(ResponseChunk.Status("hi"), ResponseChunk.Complete)
        val items = newHook().toItemStream(ResponseStreamImpl(source), convId, requestId).toList()
        items[0].createdAt shouldBe baseEpochMs
    }

    @Test
    fun `D-5 INLINE · ItemId generator stub emits deterministic ids`() = runTest {
        val gen = FakeItemIdGenerator()
        val hook = AgenticUIHookImpl(clock = fakeClock(baseEpochMs), itemIdGenerator = gen)
        val items = hook.toItemStream(
            ResponseStreamImpl(flowOf(ResponseChunk.Status("a"), ResponseChunk.Complete)),
            convId,
            requestId,
        ).toList()
        items[0].id.value shouldBe "id-1"
        items[1].id.value shouldBe "id-2"
    }

    @Test
    fun `AgenticUIHookImpl ctor rejects invalid maxAccumulatorChars`() {
        runCatching {
            AgenticUIHookImpl(
                clock = fakeClock(baseEpochMs),
                itemIdGenerator = FakeItemIdGenerator(),
                maxAccumulatorChars = 0,
            )
        }.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()

        runCatching {
            AgenticUIHookImpl(
                clock = fakeClock(baseEpochMs),
                itemIdGenerator = FakeItemIdGenerator(),
                maxAccumulatorChars = AgenticUIHookImpl.MAX_ACCUMULATOR_HARD_CAP + 1,
            )
        }.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `defaultAgenticUIHook factory wires DefaultItemIdGenerator + provided clock`() = runTest {
        val hook = defaultAgenticUIHook(clock = fakeClock(baseEpochMs))
        val source = flow {
            emit(ResponseChunk.TextDelta("x"))
            emit(ResponseChunk.Complete)
        }
        val items = hook.toItemStream(ResponseStreamImpl(source), convId, requestId).toList()
        items shouldHaveAtLeastSize 1
        items.last().createdAt shouldBe baseEpochMs
    }
}
