# moolu-ai

Base AI SDK for the moolu platform — KMP `commonMain` `LlmProvider` abstraction + `RemoteLlmProvider`(OpenAI-compat SSE consumer of `moolu-app-server` `/v1/ai/chat` per [ADR-base-018](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/adr/0018-moolu-app-server-business.md) + plan-15)+ `StubLlmProvider`(offline / pre-backend fallback)。米鹿 `core-ai` 直迁 part per [architecture spec §8.5.1](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/specs/2026-04-29-im-ai-platform-architecture-design.md)。

GAV: `app.moolu:moolu-ai:1.0.0`(Maven Local pre-V1.0 per ADR-base-004)。

## 功能(plan-16 ship — V0.5)

| API | 文件 | 用途 |
| --- | --- | --- |
| `LlmProvider` interface | `LlmProvider.kt` | 单一客户端 LLM 抽象;`stream` Flow<TokenChunk> + `complete` suspend Completion + `capabilities` |
| `LlmTypes` data classes | `LlmTypes.kt` | `Message`(Role enum)+ `GenOptions`(model/temp/topP/max/stop/seed)+ `LlmCapabilities`(streaming/tools/vision/audio/reasoning)+ `TokenChunk` sealed(Delta/ReasoningDelta/KeepAlive/Finish/Error)+ `FinishReason` enum + `Usage` + `Completion` |
| `RemoteLlmProvider` | `RemoteLlmProvider.kt` | Production impl;Ktor SSE plugin client + OpenAI-compat parser;POSTs to `${baseUrl}/v1/ai/chat`(matches plan-15 ship endpoint) |
| `StubLlmProvider` | `StubLlmProvider.kt` | Offline / pre-backend fallback;8 默认中文 canned responses + round-robin picker + token delay;`AiConsentGate.granted == false` 时也用 |
| `AiEventReporter` | `AiEventReporter.kt` | Pluggable observation hook(`captureLlmError(throwable, endpoint, model)`);`NoOp` 默认;plan-21 wire Sentry-KMP |

**internal**: `internal.OpenAiCompatPayloads`(8 @Serializable wire types + `OpenAiJson` Json instance + 3 helper extensions)— 不入 public ABI(`apiValidation.ignoredPackages.add("app.moolu.ai.llm.internal")`)。

## 依赖

- `app.moolu:moolu-foundation:1.0.0`(`MooluClock` + `MooluLogger` interfaces)
- `app.moolu:moolu-network:1.0.0`(`BearerTokenProvider` 类型;composition root wires HttpClient with Bearer plugin per ADR-base-019 §C1 — 5th cross-SDK reuse)
- `io.ktor:ktor-client-core:3.4.3`(SSE 内置 from 3.4.x;不需 ktor-client-sse 单独 artifact)
- `io.ktor:ktor-client-content-negotiation:3.4.3`(non-stream `complete` JSON 解析)
- `io.ktor:ktor-serialization-kotlinx-json:3.4.3`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1`(Flow + cancel)
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0`(@Serializable wire types)

## Quick start

```kotlin
// composition root (consumer SDK / app — plan-29 米鹿 V0.5 cutover OR plan-31 demo)
import app.moolu.ai.llm.RemoteLlmProvider
import app.moolu.ai.llm.LlmProvider
import app.moolu.foundation.logging.mooluLogger
import app.moolu.foundation.time.DefaultClock
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.serialization.kotlinx.json.json

val httpClient = HttpClient(/* OkHttp on Android, Darwin on iOS */) {
    install(SSE)
    install(ContentNegotiation) { json() }
    install(Auth) {
        bearer {
            // moolu-account.OidcClient + TokenStorage (5th BearerTokenProvider reuse per ADR-base-019 §C1)
            loadTokens {
                val access = tokenStorage.loadAccessToken().orEmpty()
                val refresh = tokenStorage.loadRefreshToken().orEmpty()
                if (access.isNotBlank()) BearerTokens(access, refresh) else null
            }
            refreshTokens { /* oidcClient.refresh() …→ tokenStorage.save(...);loadTokens() again */ }
        }
    }
}

val llmProvider: LlmProvider = RemoteLlmProvider(
    httpClient = httpClient,
    baseUrl = "https://api.moolu.app",   // moolu-app-server prod (NOT AI Gateway 直连)
    assistantId = "moolu_companion",     // plan-15 ChatRequest.assistant_id binding:"required" (per ADR-base-018 §B1 + plan-17 LangGraph agent ID)
    clock = DefaultClock,
    logger = mooluLogger("ai"),
    // eventReporter = SentryAiEventReporter(...)  // plan-21 wire
)

// 流式 chat
llmProvider.stream(
    messages = listOf(
        Message(role = Message.Role.System, content = "你是米鹿,温暖陪伴用户。"),
        Message(role = Message.Role.User, content = "今天感觉有点累..."),
    ),
    opts = GenOptions(model = "moolu-companion", temperature = 0.7),
).collect { chunk ->
    when (chunk) {
        is TokenChunk.Delta -> appendToUi(chunk.text)
        is TokenChunk.ReasoningDelta -> /* V0.1 不渲染 reasoning */ Unit
        is TokenChunk.KeepAlive -> /* heartbeat;keep connection alive */ Unit
        is TokenChunk.Finish -> finalize(chunk.usage)
        is TokenChunk.Error -> showError(chunk.message)
    }
}
```

测试 / 离线 fallback:

```kotlin
val stub: LlmProvider = StubLlmProvider(DefaultClock, mooluLogger("ai-stub"))
// 默认 8 中文 canned + round-robin (米鹿 baseline 行为)
```

## V0.5 限制(per ADR-base-019 §D1 + §E1;plan-16 OUT scope)

❌ **NOT shipped in plan-16**(留 plan-17/19/29/31):
- `ProactiveOrchestrator` / `ProactiveTemplateLibrary` / `ProactiveMessageCache` — let plan-17 LangGraph `moolu_proactive` agent 接管 orchestration
- `MemoryStore`(三层瀑布)+ `chat_summary.sq` / `memory_fragment.sq` / `chat_message.sq` — server-side(plan-17 加 `memory_fragments` Postgres + pgvector)
- `AiConsentGate`(用户 AI 开关)— plan-19 ui-chat 自然落地处实施
- `ConnectionRetry` / 心跳 keep-alive 主动恢复 — plan-32 polish backlog;V0.5 默认 no retry per plan-15 §2.4 backpressure design;consumer 用 `flow.retry(3) { it is ConnectException }` wrap
- `HttpClient` factory + Ktor engine 配置 — composition root concern(plan-29 wire OkHttp Android + Darwin iOS)
- 双平台 actuals(androidMain / iosMain platform-specific code)— 0 SDK-level need;all logic in `commonMain`

## 协议契约

**Inbound** (`POST /v1/ai/chat` body): `RemoteLlmProvider` sends `internal.ChatRequest` matching plan-15 server's `internal/api/ai.go` `ChatRequest` struct (per ADR-base-018 §B1 + ADR-base-019 §A1):
- `assistant_id` `binding:"required"`(SDK ctor param,e.g. `"moolu_companion"` per plan-17 agents)
- `thread_id` optional(V1+ thread continuity via `GenOptions.threadId`)
- `messages` `binding:"required"` 数组

**Outbound** (SSE): `parseStreamEvent(eventName, data, ...)` 路由:
- `event: error` → `TokenChunk.Error`(parse `{"error":{"message":"..."}}`)
- `event: message` / `null` → `parseSseEventData(data, ...)` pure func(13+ case parser test enforces wire-protocol contract)

输入必须 1:1 match `moolu-app-server` `internal/ai/translator.go` `Translate()` 输出格式。**16 case `RemoteLlmProviderParserTest`**(12 米鹿 baseline verbatim + 4 NEW for parseStreamEvent dispatcher)自动 enforce 此 contract。改 server-side translator 时必同步 client SDK + bump major(per ADR-base-019 §F1)。

## CI

| job | 命令 | gate |
| --- | --- | --- |
| ktlintCheck | `./gradlew ktlintCheck --no-daemon` | format gate |
| Konsist architecture | `./gradlew :architecture-test:test --no-daemon` | package boundary (`app.moolu.ai.*`) |
| KMP allTests(Android + iosSimulatorArm64)| `./gradlew :ai:allTests --no-daemon` | per ADR-base-002 TDD strict |
| ABI lock(apiCheck)| `./gradlew :ai:apiCheck --no-daemon` | binary-compat-validator KLIB ABI gate per master plan §6.3 |
| publishToMavenLocal | `./gradlew :ai:publishToMavenLocal --no-daemon` | 4 artifact prefix-only verify per ERRATA #1 |

## 构建

```bash
# 1. 把 moolu-build-logic + moolu-foundation + moolu-network publishToMavenLocal 一次
cd /tmp && git clone https://github.com/changyue1053/moolu-build-logic.git
(cd moolu-build-logic && ./gradlew :convention:publishToMavenLocal --no-daemon)
git clone https://github.com/changyue1053/moolu-foundation.git
(cd moolu-foundation && ./gradlew :foundation:publishToMavenLocal --no-daemon)
git clone https://github.com/changyue1053/moolu-network.git
(cd moolu-network && ./gradlew :network:publishToMavenLocal --no-daemon)

# 2. build moolu-ai
cd <this-repo>
./gradlew :ai:assemble :ai:allTests :ai:apiCheck :ai:publishToMavenLocal --no-daemon
```

## 关联文档(`moolu-platform-meta`)

- [β'' master plan §2 Step 2 plan-16](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/specs/2026-05-01-base-v1-master-plan-bbeta.md)
- [Architecture spec §5.2.1 + §8.5.1 + §8.5.2](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/specs/2026-04-29-im-ai-platform-architecture-design.md)
- [Plan-16 design spec](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/specs/2026-05-01-plan-16-moolu-ai-kmp-sdk-design.md)
- [ADR-base-019 — moolu-ai KMP SDK](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/adr/0019-moolu-ai-kmp-sdk.md)
- [ADR-base-005 — LLM Gateway + Provider abstraction(米鹿 baseline)](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/adr/0005-moolu-network-design.md)
- [ADR-base-018 — moolu-app-server 业务真打(plan-15 ship `/v1/ai/chat`)](https://github.com/changyue1053/moolu-platform-meta/blob/main/docs/adr/0018-moolu-app-server-business.md)

## License

Apache 2.0(per ADR-base-011 + master plan §1.3)。
