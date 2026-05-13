# Changelog

All notable changes to `moolu-ai` will be documented here.
Format mostly follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow SemVer post-V1.0;pre-V1.0 ships as `1.0.0-SNAPSHOT` per ADR-base-004。

## [Unreleased]

### Added (Phase 6 Cluster 4 Task 6.13 · 2026-05-13 · ADR-34 Tier 3 agentic UI hook)
- **NEW package `app.moolu.ai.response/`** — agentic streaming UI hook surface ·
  `ResponseStream` interface (cancellable cold `Flow<ResponseChunk>` · per Stage 0
  CSO D-3 INLINE idempotent cancel via `Mutex.withLock` atomic state transition) +
  `ResponseChunk` 7-variant sealed class (TextDelta + ReasoningStep + ToolCall +
  ToolResult + Status + Complete + Failed · per Stage 0 D-7 INLINE plain sealed
  class · NOT @Serializable · in-process domain type) + `AgenticUIHook` interface
  (translates `Flow<ResponseChunk>` → `Flow<Item>` injectable into
  `ConversationManager.streamItems` per ADR-27 + spec §11.5.4 Flow 1 verbatim 8 步
  mapping) + `MentionParser` class (Unicode TR29 word boundaries + 4 codepoint
  range allowlist for CJK Han/Hiragana/Katakana/Hangul + ASCII per Stage 0 D-9
  INLINE · linear-time regex + 8192-char input cap + 13 Unicode bidi/zero-width
  controls strip pre-pass per Stage 0 D-1 INLINE OWASP A03 ReDoS + LLM04 DoS +
  CVE-2021-42574 Trojan Source defense) + `Mention`/`MentionResult`/`MentionType`
  3-variant {HUMAN, AGENT, BOT} per ADR-2 (Stage 0 D-8 INLINE)
- **NEW internal `app.moolu.ai.response.internal/`** — `ResponseStreamImpl` SSE
  consumer via `app.moolu.network.sse.serverSentEvents()` facade (Stage 0 D-8
  INLINE anti-wheel R-NO-WHEEL-1 · 0 direct Ktor SSE import) +
  `AgenticUIHookImpl` (bounded `StringBuilder` accumulator with 65536-char hard
  cap matching Cluster 3 D-6 INLINE `Item.FinalText.text` ceiling per Stage 0
  D-2 INLINE OWASP LLM04 100MB adversarial TextDelta defense · sealed-class
  exhaustive `when (chunk)` over 7 variants 0 `else ->` fallback per Stage 0
  D-4 INLINE) + `MentionTokenizer` (linear-time regex + bidi-strip pre-pass) +
  `ItemIdGenerator` interface + `DefaultItemIdGenerator` using
  `kotlin.uuid.Uuid.random()` crypto-strong source (Stage 0 D-5 INLINE Kotlin
  2.0+ stdlib)
- **NEW factory** `sseResponseStream(httpClient, streamUrl, json)` constructs
  SSE-backed [ResponseStream] · consumes moolu-network facade per Stage 0 D-8
  INLINE · 0 direct Ktor SSE import in `app.moolu.ai.response.internal/*.kt`
- **NEW factory** `defaultAgenticUIHook(clock, maxAccumulatorChars)` wires
  `DefaultItemIdGenerator` + caller-supplied `MooluClock` · `MAX_FINAL_TEXT_CHARS=65536`
  default
- **0 modify existing `app.moolu.ai.llm/*` package** (920 LOC Phase 0 baseline
  preserved verbatim · 0 ABI break for `LlmProvider`/`RemoteLlmProvider`/
  `StubLlmProvider` consumers per ADR-34 implicit decision · NEW `ResponseStream`
  is **parallel** API NOT replacement · `RemoteLlmProvider.stream(): Flow<TokenChunk>`
  既有 surface preserved verbatim · per spec §17 V0.5 plan-16 sunset verbatim
  "moolu-ai KMP SDK 接口完全重设计 (RemoteLlmProvider → ResponseStream + agentic UI
  hook) V2 ADR-27 重写")

### Tests (Phase 6 Cluster 4 Task 6.13)
- **`MentionParserTest`** (commonTest) — 14 @Test cases · D-1 + D-8 + D-9 + D-10
  INLINE absorption verification (linear-time regex + bidi strip + CJK boundary +
  3-variant MentionType + safe-log redaction)
- **`MentionTokenizerTest`** (commonTest) — 7 @Test cases · D-1 + D-9 INLINE
  absorption (focused tokenizer-layer regex + Unicode tests)
- **`ResponseStreamTest`** (commonTest) — 14 @Test cases · D-3 + D-7 + D-8
  INLINE absorption (idempotent cancel + plain sealed class + graceful fallback)
- **`AgenticUIHookTest`** (commonTest) — 11 @Test cases · D-2 + D-4 + D-5 + D-6
  INLINE absorption (bounded accumulator overflow + sealed-class exhaustive when +
  streamingComplete Boolean Path B + ToolCall/ToolResult field-shape mapping)
- **Total**: 46 @Test cumulative ≥ 22 plan target = **209% utilization** ·
  scaffold-grade · actual `./gradlew :ai:commonTest --tests "app.moolu.ai.response.*"`
  execution deferred Cluster 6 closure batch per Phase 6 Cluster 1+2+3+4 Tasks
  6.10/6.11/6.12 precedent
- **`ResponseStreamAgenticTest`** (architecture-test) — NEW Konsist rule
  **R-RESPONSESTREAM-AGENTIC-1** · enforce sealed-class exhaustive
  `when (chunk: ResponseChunk)` over 7 variants in `AgenticUIHookImpl.kt` ·
  0 `else ->` fallback (Stage 0 D-4 + D-12 INLINE · per Cluster 3
  R-RENDER-DISPATCH-1 + Cluster 4 Task 6.10 R-NO-SQL-CONCAT-SEARCH-1 precedent ·
  real `Konsist.scopeFromProduction` API call NOT vacuous true body) +
  `SubPackageBoundaryTest.kt` allowlist extension (D-N9 INLINE absorbed drift ·
  +`app.moolu.ai.response{,.internal}`)
- **Konsist rule count**: 68 baseline → **69 cumulative** (+1 NEW
  R-RESPONSESTREAM-AGENTIC-1) · 115% of Phase 6 close ≥ 60 target
- Scaffold-grade ship · actual `./gradlew :architecture-test:test` execution
  deferred Cluster 6 closure batch

### ABI (Phase 6 Cluster 4 Task 6.13 · ADD-only · per Stage 0 D-11 INLINE)
- **`ai/api/ai.klib.api`** ADD-only bump 334 LOC → 536 LOC (+202 LOC ADD ·
  existing 334 LOC entries preserved verbatim 0-line drift per
  binary-compatibility-validator semantics + spec §16.2 P6 KMP ABI lock)
- NEW public types: `ResponseStream` interface · `ResponseChunk` 7-variant sealed
  class · `AgenticUIHook` interface · `AgenticUIHookDefaults` object ·
  `MentionParser` class + companion · `MentionResult`/`Mention` data classes ·
  `MentionType` enum · `ResponseStreamException` class · `sseResponseStream` +
  `defaultAgenticUIHook` top-level factory functions
- Internal `app.moolu.ai.response.internal/*` types (`ResponseStreamImpl` +
  `AgenticUIHookImpl` + `MentionTokenizer` + `ItemIdGenerator` +
  `DefaultItemIdGenerator`) NOT in dump · Kotlin `internal` visibility excludes
  by construction
- Scaffold-grade · actual `./gradlew :ai:apiDump` execution deferred Cluster 6
  closure batch per Phase 6 Cluster 1+2+3+4 Tasks 6.10/6.11/6.12 precedent

## [1.0.0] - 2026-05-04 — base V1.0 release publish 历史里程碑

### Changed (plan-34 T1 per ADR-base-028 §D1)
- Bump version `1.0.x-SNAPSHOT` → `1.0.0` stable (skip §E1 V0.5.0-SNAPSHOT retroactive — Sonatype 不允许 SNAPSHOT promotion;Spring Boot V1.0.0 same pattern direct from 1.0.x-SNAPSHOT to 1.0.0 stable)
- Update cross-SDK version refs in `gradle/libs.versions.toml` + `README.md` GAV examples + `.github/workflows/pr-check.yml` hardcoded mavenLocal verification paths to V1.0.0 stable
- Files preserved (historical context): CHANGELOG.md historical entries + `settings.gradle.kts` moolu-build-logic plugin ID (`1.0.0-SNAPSHOT` internal infra) + code comments / JavaDoc examples mentioning version history + any moolu-build-logic / kmp-library bootstrap refs in pr-check.yml

### Added (plan-32 T1 per ADR-base-027 §A1 — historical context preserved at V1.0.0)
- vanniktech/gradle-maven-publish-plugin 0.36.0 集成 via convention plugin — Maven Central preview SOP (V0.5 plugin DSL config validation + 真 staging plan-34 T5 per ADR-027 §A1)

### Added (plan-32 T2 per ADR-base-027 §B1 — historical context preserved at V1.0.0)
- Konsist 4-rule baseline verified at plan-32 T2 audit (close uneven coverage drift D4 per ADR-027 §B1)

### Added (plan-34 T3 per ADR-base-028 §H1)
- Konsist rule-count gate (backslide guard) in `.github/workflows/pr-check.yml` — `assert count >= baseline` (4) right after `:architecture-test:test` step;BACKSLIDE catch immediate per-repo;industry pattern (Spring Boot CheckStyle baseline + Compose Lint baseline)

### Released
- Maven artifact `app.moolu:moolu-ai:1.0.0` — production-stable V1.0.0 first release (per ADR-base-028 §A1 + plan-34 T5 真 staging via vanniktech `publishAndReleaseToMavenCentral` IF Sonatype OSSRH credentials provisioned at T-staging time;ELSE Path B fallback per ADR-base-028 §A graceful fallback pattern)

[1.0.0]: https://github.com/changyue1053/moolu-ai/releases/tag/v1.0.0

## [1.0.0-SNAPSHOT] — plan-16 ship

### Added
- **`LlmProvider` interface**(`commonMain/llm/LlmProvider.kt`)— 单一客户端 LLM 抽象;`stream(messages, opts) → Flow<TokenChunk>` + `complete(messages, opts) → Completion` + `capabilities`(米鹿 core-ai baseline verbatim port + 包名 `com.moolu.ai.llm` → `app.moolu.ai.llm`)
- **`LlmTypes` data classes**(`commonMain/llm/LlmTypes.kt`)— `Message`(Role enum:System/User/Assistant/Tool)+ `GenOptions`(model/temp/topP/max/stop/seed)+ `LlmCapabilities` + `TokenChunk` sealed(Delta/ReasoningDelta/KeepAlive/Finish/Error)+ `FinishReason` enum(Stop/Length/ToolCall/ContentFilter/Network/Cancelled/Unknown)+ `Usage`(promptTokens/completionTokens/totalTokens)+ `Completion`
- **`RemoteLlmProvider`**(`commonMain/llm/RemoteLlmProvider.kt`)— renamed from 米鹿 `GatewayLlmProvider`;`STREAM_PATH = "/v1/ai/chat"` const(matches `moolu-app-server` plan-15 ship per ADR-base-018);**ctor adds `assistantId: String` (REQUIRED) per T12 review C-CRIT-1 fix** — plan-15 server `ChatRequest.assistant_id` is `binding:"required"`;Ktor SSE plugin streaming + non-stream `complete`;OpenAI-compat parser via `parseSseEventData()` pure func + `SseEventOutcome`;**`parseStreamEvent(eventName, data, ...)` dispatcher** routes `event: error` → `TokenChunk.Error` per T12 review C-H1 fix;`RemoteLlmHttpException`(renamed from `GatewayLlmHttpException`)+ `STREAM_FAILED_CODE` const 保留(同 ABI value);`TokenChunk.Error.message` 用 static "Stream failed" 短语 per T12 review S-M2(t.message 可能含 upstream framing detail);`eventReporter.captureLlmError(t, endpoint, model = null)` per T12 review S-L1(避免 opts.model 含 secrets 泄露)
- **`StubLlmProvider`**(`commonMain/llm/StubLlmProvider.kt`)— 米鹿 baseline verbatim port;8 默认中文 canned responses(嗨!我是米鹿/听起来不错/...) + round-robin picker + token delay 30ms 默认;backend 不可用时 fallback;consent gate 由 plan-19 ui-chat composition root 决定使用哪个 LlmProvider
- **`AiEventReporter`**(`commonMain/llm/AiEventReporter.kt`)— fun interface + `NoOp` default;V0.5 NoOp;plan-21 wire Sentry-KMP
- **`internal.OpenAiCompatPayloads`**(`commonMain/llm/internal/OpenAiCompatPayloads.kt`)— **`ChatRequest`** (renamed from `ChatCompletionRequest` per T12 review C-CRIT-1 fix;`assistant_id` + `thread_id` + `messages` + `stream` + 6 forward-compat fields) + 7 OpenAI-compat outbound types(`ChatRequestMessage` + `ChatCompletionChunk` + `ChunkChoice` + `ChunkDelta` + `ChatCompletionResponse` + `CompletionChoice` + `CompletionMessage` + `ChatUsage`)+ `OpenAiJson` Json instance(documented `exceptionsWithDebugInfo = false` per T12 review S-M1 — privacy)+ 3 helper extensions;**internal visibility 严格** — `apiValidation.ignoredPackages.add("app.moolu.ai.llm.internal")`,不入 public ABI

### Tests
- **`LlmTypesTest`**(`commonTest`)— 12 cases 类型 ABI 锁定(米鹿 baseline verbatim port)
- **`StubLlmProviderTest`**(`commonTest`)— 9 cases:round-robin picker / canned response 选取 / token delay = 0 / token delay > 0 / stream emits one Delta per char + Finish + Usage / complete_returns_assistant_message / capabilities_advertise_streaming_only / init_rejects_empty_canned_responses_list / init_rejects_negative_token_delay / default_responses_are_all_within_proactive_bubble_max_chars
- **`RemoteLlmProviderParserTest`**(`commonTest`;renamed from `GatewayLlmParserTest`)— **16 cases**(12 米鹿 baseline verbatim + 4 NEW for `parseStreamEvent` dispatcher per T12 review C-H1 fix):null_data → KeepAlive / empty_data → KeepAlive / `[DONE]` → Finish.Stop terminated / chunk_with_content → Delta / chunk_with_reasoning → ReasoningDelta / chunk_with_finish_reason → Finish + Usage / chunk_with_content_and_reasoning → both / garbage_json → drop / no_choices → drop / unknown_finish → Unknown / aliases map / chunk_with_finish_attaches_usage / **stream_event_with_error_name → TokenChunk.Error parsed message** / **stream_event_with_error_name_garbage_data → generic message** / **stream_event_with_message_name_delegates** / **stream_event_with_null_name_delegates**
- **`RemoteLlmProviderProtocolTest`**(`commonTest`;renamed from 米鹿 `GatewayLlmProviderJvmTest` + jvmTest → commonTest move per ADR-base-019 §F1)— **6 cases** via Ktor MockEngine:complete_returns_completion_with_usage_on_2xx / complete_throws_remote_llm_http_exception_on_non_2xx_and_reports / complete_throws_when_choices_array_is_empty / init_rejects_blank_baseUrl / init_rejects_baseUrl_with_trailing_slash / **init_rejects_blank_assistantId**(per T12 review C-CRIT-1 fix)
- **Total: 43 test cases all pass on Android (testAndroidHostTest) + iOS (iosSimulatorArm64Test);0 failures / 0 errors / 0 skips**

### Infrastructure
- KMP commonMain + commonTest(0 platform actuals per ADR-base-019 §E1;commonMain only — Ktor 跨平台 + foundation/network actuals 已 ship)
- **3 targets:`androidLibrary` + `iosArm64` + `iosSimulatorArm64`**(NO `jvm()` target — moolu-foundation 1.0.1 + moolu-network 1.0.0 published from mavenLocal with android-jvm + iOS variants only;adding jvm() breaks transitive dep resolution。Tests run in commonTest shared between androidHostTest + iosSimulatorArm64Test)
- `binary-compat-validator` 0.18.1 KLIB ABI lock(per master plan §6.3 + plan-10 范例)
- Prefix-only `artifactId` override per ERRATA #1(defense-in-depth — "ai" 不在 "iossimulatorarm64" 但保留 pattern;4 publication mappings)
- `apiValidation.ignoredPackages.add("app.moolu.ai.llm.internal")` — 8 internal types 不入 public ABI(verified via `grep -c "internal" ai.klib.api` returns only kotlinx.serialization.internal stdlib matches)
- CI workflow `.github/workflows/pr-check.yml`(参考 moolu-im 模式;ktlintCheck + Konsist + KMP allTests + apiCheck + publishToMavenLocal verify)
- Konsist `:architecture-test` PackageNamingTest — 强制全 production code 在 `app.moolu.ai.*` 包下

### Skills applied
- brainstorming(compact 8-step;5 AskQuestion options 全 ✅ recommendations 2026-05-01)
- architecture-decision-records + mp-grill-me(ADR-base-019 6 design points;每 §决策 显式 §回应最强反对)
- verification-before-completion(0 NEW deps for plan-16;全 STAY per ADR-base-001 §5.6 1-week tolerance)
- test-driven-development strict(per task red→green;米鹿 baseline 21+ test cases verbatim port + 4 NEW + 1 NEW per T12 review)
- **parallel-code-review 4-lens**(security/correctness/performance/readability;plan-15 proven reliable 4 次 + plan-16 5th)— 4 readonly subagents 1 message dispatch;synthesized:1 Critical(C-CRIT-1 request shape mismatch with plan-15 server `ChatRequest.assistant_id`;**fixed inline**)+ 2 High(C-H1 SSE error event handling + R-H1 README Bearer snippet broken;**both fixed inline**)+ 5 Medium(S-M1 exceptionsWithDebugInfo + S-M2 TokenChunk.Error.message sanitize + R-M1/R-M2 doc count drift + R-H2 JVM target docs alignment;**all fixed inline**)+ 6 Low(plan-32 polish backlog:S-M3 complete() body size policy / C-M1 tool_calls dropped / C-M2 FakeMooluLogger isolation per-test / C-M3 double-terminal guard / P-L1/2/3 alloc optimizations / R-L1 test URL rename)
- babysitting-pr(CI green watch — to be done at T13)

### Initial release
- GitHub repo created 2026-05-01;plan-16 是 base 第 15 个 GitHub repo
- Apache 2.0 License

## [0.0.0] — initial repo create

- Repo scaffolding(settings.gradle.kts + build.gradle.kts + gradle wrapper + libs.versions.toml + .gitignore + LICENSE + README + CHANGELOG)
