package app.moolu.architecturetest

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/**
 * moolu-ai sub-package shape — production code lives in one of the canonical sub-packages:
 * - `app.moolu.ai.llm` (public V0.5 LLM API · `LlmProvider` + `Message`/`TokenChunk`/...)
 * - `app.moolu.ai.llm.internal` (V0.5 impl details · OpenAI-compat SSE payloads)
 * - `app.moolu.ai.response` (V2 agentic UI hook public API · `ResponseStream` +
 *   `ResponseChunk` + `AgenticUIHook` + `MentionParser` per ADR-34 Tier 3 +
 *   spec §11.1 + Phase 6 Cluster 4 Task 6.13 ship)
 * - `app.moolu.ai.response.internal` (V2 agentic UI hook impl details ·
 *   `ResponseStreamImpl` + `AgenticUIHookImpl` + `MentionTokenizer` +
 *   `ItemIdGenerator`)
 *
 * 此 rule 防止意外引入新顶层 sub-package(e.g., `app.moolu.ai.persona` / `app.moolu.ai.tools`)
 * 而绕过 ADR-019 §A1 + ADR-34 Tier 3 模块映射 + scope lock。如真需要新 sub-package(plan-32+
 * V1.x extensions per ADR-019 §A1 vision/audio/multi-agent routing OR ADR-34 V2 expansions),
 * 先 amend ADR-019 / ADR-34 + write a new ADR articulating the sub-package design intent。
 *
 * Plan-32 T2 (per ADR-base-027 §B1) — D4 backfill 3/3 rules · Phase 6 Cluster 4 Task 6.13
 * INLINE D-N9 drift absorption: extend allowlist to `app.moolu.ai.response{,.internal}` for
 * NEW agentic UI hook surface per ADR-34 Tier 3 + spec §11.1 figure verbatim.
 */
class SubPackageBoundaryTest {
    @Test
    fun `production code lives in app_moolu_ai_llm or response sub-packages only`() {
        Konsist
            .scopeFromProduction()
            .files
            .filter { it.path.contains("ai/src/commonMain") }
            .assertTrue { file ->
                val pkg = file.packagee?.name ?: ""
                pkg == "app.moolu.ai.llm" ||
                    pkg == "app.moolu.ai.llm.internal" ||
                    pkg == "app.moolu.ai.response" ||
                    pkg == "app.moolu.ai.response.internal"
            }
    }
}
