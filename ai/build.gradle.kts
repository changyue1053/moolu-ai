import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("moolu.kmp.library")
    alias(libs.plugins.binary.compat.validator)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidLibrary {
        namespace = "app.moolu.ai"
        compileSdk =
            libs.versions.android.compile.sdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.min.sdk
                .get()
                .toInt()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Ai"
            isStatic = true
        }
    }

    // NOTE: 0 jvm() target — moolu-foundation 1.0.1 + moolu-network 1.0.0 are published
    // from mavenLocal with android-jvm + iOS variants only (no pure JVM variant). Adding
    // jvm() here breaks transitive dep resolution. Tests run in commonTest (shared between
    // androidHostTest + iosSimulatorArm64Test runs) — same pattern as moolu-im / moolu-account.
    // Per ADR-base-019 §F1 — RemoteLlmProviderJvmTest renamed to RemoteLlmProviderProtocolTest
    // and moved from jvmTest → commonTest (Ktor MockEngine + kotest-assertions + kotlinx-datetime
    // are all multiplatform).

    sourceSets {
        commonMain.dependencies {
            // Foundation SDK (1.0.1-SNAPSHOT — provides MooluClock + MooluLogger interfaces)
            api(libs.moolu.foundation)
            // Network SDK (BearerTokenProvider type — composition root wires HttpClient with Bearer plugin
            // per ADR-base-019 §C1; 5th cross-SDK reuse path: network → account → app-server → 米鹿 wire → ai SDK)
            api(libs.moolu.network)
            // Phase 8 Cluster 3 Task 8.7 Stage 1b' D20: IM SDK conversation/ package
            // (Item / ConversationId / ParticipantId / ItemId) consumed by Task 6.13 NEW
            // app.moolu.ai.response/* package at AgenticUIHook → Flow<Item> translation seam.
            api(libs.moolu.im)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Ktor SSE = built-in `ktor-client-core` plugin since 3.4.x
            // (no extra `ktor-client-sse` artifact; same as 米鹿 core-ai baseline per ADR-019 §A1)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
            // ktor-client-mock for RemoteLlmProviderJvmTest (米鹿 baseline 模式 verbatim port per ADR-019 §F1)
            implementation(libs.ktor.client.mock)
        }

        // V0.5 SDK ship: 0 platform actuals (commonMain only; per ADR-019 §E1 +
        // master plan §6.3 — same as moolu-im baseline). Real platform engine
        // (OkHttp on Android, Darwin on iOS) injected by composition root via
        // pre-configured HttpClient (per ADR-019 §C1).
    }
}

// Binary compatibility validator (per master plan §6.3 + ADR-base-013 §F1).
// `apiDump` 生成 `ai/api/` baseline (checked in); `apiCheck` 是 CI gate.
// Major version < 1.0 (current 1.0.0-SNAPSHOT pre-1.0) 允许破坏 ABI; ≥ 1.0 必走 deprecation cycle.
apiValidation {
    // ignoredPackages — internal OpenAI-compat wire payloads (NOT public ABI surface);
    // 防止 ChatCompletionRequest / ChunkChoice / ChatUsage 等 internal types 出现在 public ABI snapshot
    // (per ADR-base-019 §A1 + spec §1.1 row D — internal visibility 严格)
    ignoredPackages.add("app.moolu.ai.llm.internal")

    // KLIB validation enabled per master plan §6.3 — cross-platform ABI lock for KMP.
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

group = "app.moolu"
version = "2.0.0"

publishing {
    publications.withType<MavenPublication> {
        // Common config in shared moolu-build-logic. Per-module POM fields:
        pom {
            name.set("moolu-ai")
            description.set(
                "Base AI SDK — KMP commonMain LlmProvider abstraction + RemoteLlmProvider " +
                    "(OpenAI-compat SSE consumer of moolu-app-server `/v1/ai/chat` per ADR-base-018 + plan-15) " +
                    "+ StubLlmProvider (offline / pre-backend fallback);米鹿 core-ai 直迁 part per spec §8.5.1 " +
                    "+ ADR-base-019 §A1 verbatim port + 1 rename + URL const 切换",
            )
        }
    }
}

// PLAN-10/11/13/14 ERRATA #1: shared moolu-build-logic convention plugin uses
// `artifactId.replace(project.name, rootProject.name)` which corrupts iOS targets
// when project.name substring appears inside platform suffix. Override the
// artifactId AFTER the convention plugin's afterEvaluate hook (module-local
// afterEvaluate fires last) using prefix-only replacement.
//
// "ai" not in "iossimulatorarm64" (s-i-m-u-l-a-t-o-r contains no "a-i" sequence),
// but defense-in-depth — same prefix-only override pattern as moolu-im / moolu-media / moolu-account.
afterEvaluate {
    publishing {
        publications.withType<MavenPublication>().configureEach {
            val original =
                when (name) {
                    "kotlinMultiplatform" -> "moolu-ai"
                    "androidRelease" -> "moolu-ai-android"
                    "iosArm64" -> "moolu-ai-iosarm64"
                    "iosSimulatorArm64" -> "moolu-ai-iossimulatorarm64"
                    else -> artifactId
                }
            artifactId = original
        }
    }
}
