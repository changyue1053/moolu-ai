package app.moolu.ai.llm

import app.moolu.foundation.testing.FakeMooluLogger
import app.moolu.foundation.testing.fakeClock
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * commonTest integration suite for [RemoteLlmProvider]'s HTTP-backed
 * paths (non-streaming `complete()` + constructor invariants).
 *
 * **Why two surfaces** — Ktor 3.4.x's `MockEngine` does not declare
 * `SSECapability`, and even with the capability flag overridden the SSE
 * client plugin requires the engine to return a real `SSESession`
 * object (not a static [ByteReadChannel]). Standing up
 * `ktor-server-test-host` + `ktor-server-sse` purely for the
 * wire-protocol test would expand the test dep surface beyond what's
 * needed (we don't take a server dependency anywhere in production), so
 * the SSE-frame mapping is exercised via the extracted pure parser
 * [parseSseEventData] (covered by [RemoteLlmProviderParserTest]). This
 * file then covers the parts that genuinely need an HTTP client:
 *  - non-streaming `complete()` round-trip on 2xx
 *  - non-streaming `complete()` failure path on 5xx
 *  - reporter notification on engine-level failures
 *  - constructor invariants for `baseUrl`
 *
 * Plan-16 §T6 (verbatim port from 米鹿 core-ai GatewayLlmProviderJvmTest
 * with structural rename `Gateway*` → `Remote*` + jvmTest →
 * commonTest move per ADR-base-019 §F1 — Ktor MockEngine + kotest +
 * kotlinx-datetime are all multiplatform).
 */
class RemoteLlmProviderProtocolTest {
    private val baseUrl: String = "https://api.moolu.test"
    private val streamPath: String = "/v1/ai/chat"
    private val streamUrl: String = "$baseUrl$streamPath"
    private val assistantId: String = "moolu_companion"
    private val clock = fakeClock(initialEpochMs = 1_700_000_000_000L)
    private val logger = FakeMooluLogger()

    @Test
    fun complete_returns_completion_with_usage_on_2xx() =
        runTest {
            val responseBody =
                """
                {
                  "id":"cmpl-1",
                  "object":"chat.completion",
                  "model":"moolu-companion",
                  "choices":[
                    {"index":0,
                     "message":{"role":"assistant","content":"hi there"},
                     "finish_reason":"stop"}
                  ],
                  "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}
                }
                """.trimIndent()
            val captured = mutableListOf<HttpRequestData>()
            val client =
                jsonMockClient { request ->
                    captured += request
                    respond(
                        content = ByteReadChannel(responseBody),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider = newProvider(client)
            val completion = provider.complete(listOf(Message(Message.Role.User, "hi")))
            completion.message.role shouldBe Message.Role.Assistant
            completion.message.content shouldBe "hi there"
            completion.finishReason shouldBe FinishReason.Stop
            completion.usage shouldBe Usage(promptTokens = 3, completionTokens = 2, totalTokens = 5)
            captured shouldHaveSize 1
            captured[0].url.toString() shouldBe streamUrl
        }

    @Test
    fun complete_throws_remote_llm_http_exception_on_non_2xx_and_reports() =
        runTest {
            val captured = mutableListOf<Throwable>()
            val reporter = AiEventReporter { t, _, _ -> captured += t }
            val client =
                jsonMockClient { _ ->
                    respond(
                        content = ByteReadChannel("upstream timed out"),
                        status = HttpStatusCode.GatewayTimeout,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            val provider =
                RemoteLlmProvider(
                    httpClient = client,
                    baseUrl = baseUrl,
                    assistantId = assistantId,
                    clock = clock,
                    logger = logger,
                    eventReporter = reporter,
                )
            val ex =
                assertFailsWith<RemoteLlmProvider.RemoteLlmHttpException> {
                    provider.complete(listOf(Message(Message.Role.User, "?")))
                }
            ex.status shouldBe HttpStatusCode.GatewayTimeout
            captured shouldHaveSize 1
            captured[0].shouldBeInstanceOf<RemoteLlmProvider.RemoteLlmHttpException>()
        }

    @Test
    fun complete_throws_when_choices_array_is_empty() =
        runTest {
            val responseBody =
                """{"id":"cmpl-empty","object":"chat.completion","choices":[]}"""
            val client =
                jsonMockClient { _ ->
                    respond(
                        content = ByteReadChannel(responseBody),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider = newProvider(client)
            val ex =
                assertFailsWith<RemoteLlmProvider.RemoteLlmHttpException> {
                    provider.complete(emptyList())
                }
            ex.status shouldBe HttpStatusCode.UnprocessableEntity
        }

    @Test
    fun init_rejects_blank_baseUrl() {
        val client = jsonMockClient { _ -> respond("{}") }
        assertFailsWith<IllegalArgumentException> {
            RemoteLlmProvider(client, baseUrl = "", assistantId = assistantId, clock = clock, logger = logger)
        }
    }

    @Test
    fun init_rejects_baseUrl_with_trailing_slash() {
        val client = jsonMockClient { _ -> respond("{}") }
        assertFailsWith<IllegalArgumentException> {
            RemoteLlmProvider(client, baseUrl = "https://x.test/", assistantId = assistantId, clock = clock, logger = logger)
        }
    }

    @Test
    fun init_rejects_blank_assistantId() {
        // T12 plan-16 review C-CRIT-1 fix — plan-15 ChatRequest.assistant_id is binding:required
        val client = jsonMockClient { _ -> respond("{}") }
        assertFailsWith<IllegalArgumentException> {
            RemoteLlmProvider(client, baseUrl = baseUrl, assistantId = "", clock = clock, logger = logger)
        }
    }

    private fun newProvider(client: HttpClient): RemoteLlmProvider =
        RemoteLlmProvider(
            httpClient = client,
            baseUrl = baseUrl,
            assistantId = assistantId,
            clock = clock,
            logger = logger,
        )

    private fun jsonMockClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
        }
}
