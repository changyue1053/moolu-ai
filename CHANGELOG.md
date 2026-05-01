# Changelog

All notable changes to `moolu-ai` will be documented here.
Format mostly follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow SemVer post-V1.0;pre-V1.0 ships as `1.0.0-SNAPSHOT` per ADR-base-004。

## [Unreleased] — plan-16 ship

### Added
- **`LlmProvider` interface**(`commonMain/llm/LlmProvider.kt`)— 单一客户端 LLM 抽象;`stream(messages, opts) → Flow<TokenChunk>` + `complete(messages, opts) → Completion` + `capabilities`(米鹿 core-ai baseline verbatim port + 包名 `com.moolu.ai.llm` → `app.moolu.ai.llm`)
- **`LlmTypes` data classes**(`commonMain/llm/LlmTypes.kt`)— `Message`(Role enum:System/User/Assistant/Tool)+ `GenOptions`(model/temp/topP/max/stop/seed)+ `LlmCapabilities` + `TokenChunk` sealed(Delta/ReasoningDelta/KeepAlive/Finish/Error)+ `FinishReason` enum(Stop/Length/ToolCall/ContentFilter/Network/Cancelled/Unknown)+ `Usage`(promptTokens/completionTokens/totalTokens)+ `Completion`
- **`RemoteLlmProvider`**(`commonMain/llm/RemoteLlmProvider.kt`)— renamed from 米鹿 `GatewayLlmProvider`;`STREAM_PATH = "/v1/ai/chat"` const(matches `moolu-app-server` plan-15 ship per ADR-base-018);Ktor SSE plugin streaming + non-stream `complete`;OpenAI-compat parser via `parseSseEventData()` pure func + `SseEventOutcome`;`RemoteLlmHttpException`(renamed from `GatewayLlmHttpException`)+ `STREAM_FAILED_CODE` const 保留(同 ABI value)
- **`StubLlmProvider`**(`commonMain/llm/StubLlmProvider.kt`)— 米鹿 baseline verbatim port;8 默认中文 canned responses(嗨!我是米鹿/听起来不错/...) + round-robin picker + token delay 30ms 默认;`AiConsentGate.granted == false` / 后端不可用时 fallback
- **`AiEventReporter`**(`commonMain/llm/AiEventReporter.kt`)— fun interface + `NoOp` default;V0.5 NoOp;plan-21 wire Sentry-KMP
- **`internal.OpenAiCompatPayloads`**(`commonMain/llm/internal/OpenAiCompatPayloads.kt`)— 8 @Serializable wire types(`ChatCompletionRequest` + `ChatRequestMessage` + `ChatCompletionChunk` + `ChunkChoice` + `ChunkDelta` + `ChatCompletionResponse` + `CompletionChoice` + `CompletionMessage` + `ChatUsage`)+ `OpenAiJson` Json instance + 3 helper extensions(`Message.Role.asWireString`, `List<Message>.toRequestMessages`, `GenOptions.toRequest`);**internal visibility 严格** — `apiValidation.ignoredPackages.add("app.moolu.ai.llm.internal")`,不入 public ABI

### Tests
- **`LlmTypesTest`**(`commonTest`)— 类型 ABI 锁定(米鹿 baseline verbatim port)
- **`StubLlmProviderTest`**(`commonTest`)— 5 cases:round-robin picker / canned response 选取 / token delay = 0 / token delay > 0 / stream emits one Delta per char + Finish + Usage
- **`RemoteLlmProviderParserTest`**(`commonTest`;renamed from `GatewayLlmParserTest`)— 13 cases SSE wire-protocol 黄金:null_data → KeepAlive / empty_data → KeepAlive / `[DONE]` → Finish.Stop terminated / chunk_with_content → Delta / chunk_with_reasoning → ReasoningDelta / chunk_with_finish_reason → Finish + Usage / chunk_with_content_and_reasoning → both / garbage_json → drop / no_choices → drop / unknown_finish → Unknown / aliases map / chunk_with_finish_attaches_usage / parser is_pure
- **`RemoteLlmProviderJvmTest`**(`jvmTest`;renamed from `GatewayLlmProviderJvmTest`)— 5 cases via Ktor MockEngine:rejects_blank_baseUrl / rejects_baseUrl_with_trailing_slash / complete_returns_completion_with_usage_on_2xx / complete_throws_gateway_http_exception_on_non_2xx_and_reports / complete_throws_when_choices_array_is_empty

### Infrastructure
- KMP commonMain + commonTest + jvmTest(0 platform actuals per ADR-base-019 §E1;commonMain only — Ktor 跨平台 + foundation/network actuals 已 ship)
- 4 targets:`androidLibrary` + `iosArm64` + `iosSimulatorArm64` + `jvm()`(jvm() for tests)
- `binary-compat-validator` 0.18.1 KLIB ABI lock(per master plan §6.3 + plan-10 范例)
- Prefix-only `artifactId` override per ERRATA #1(defense-in-depth — "ai" 不在 "iossimulatorarm64" 但保留 pattern)
- `apiValidation.ignoredPackages.add("app.moolu.ai.llm.internal")` — internal 不入 public ABI
- CI workflow `.github/workflows/pr-check.yml`(参考 moolu-im 模式)
- Konsist `:architecture-test` PackageNamingTest — 强制全 production code 在 `app.moolu.ai.*` 包下

### Skills applied
- brainstorming(compact 8-step;5 AskQuestion options 全 ✅ recommendations 2026-05-01)
- architecture-decision-records + mp-grill-me(ADR-base-019 6 design points;每 §决策 显式 §回应最强反对)
- verification-before-completion(0 NEW deps for plan-16;全 STAY per ADR-base-001 §5.6 1-week tolerance)
- test-driven-development strict(per task red→green;米鹿 baseline 25+ test cases verbatim port)
- parallel-code-review 4-lens(security/correctness/performance/readability;plan-15 proven reliable 4 次)
- babysitting-pr(CI green watch)

### Initial release
- GitHub repo created 2026-05-01;plan-16 是 base 第 15 个 GitHub repo
- Apache 2.0 License

## [0.0.0] — initial repo create

- Repo scaffolding(settings.gradle.kts + build.gradle.kts + gradle wrapper + libs.versions.toml + .gitignore + LICENSE + README + CHANGELOG)
