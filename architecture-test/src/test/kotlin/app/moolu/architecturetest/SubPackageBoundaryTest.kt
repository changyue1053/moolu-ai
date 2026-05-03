package app.moolu.architecturetest

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/**
 * moolu-ai sub-package shape — production code lives in `app.moolu.ai.llm` (public API) +
 * `app.moolu.ai.llm.internal` (impl details: OpenAI-compat SSE payloads)。
 *
 * 此 rule 防止意外引入新顶层 sub-package(e.g., `app.moolu.ai.persona` / `app.moolu.ai.tools`)
 * 而绕过 ADR-019 §A1 模块映射 + scope lock。如真需要新 sub-package(plan-32+ V1.x extensions
 * per ADR-019 §A1 vision/audio/multi-agent routing),先 amend ADR-019 + write a new ADR
 * articulating the sub-package design intent + plan-32 polish backlog clearance。
 *
 * Plan-32 T2 (per ADR-base-027 §B1) — D4 backfill 3/3 rules.
 */
class SubPackageBoundaryTest {
    @Test
    fun `production code lives in app_moolu_ai_llm or app_moolu_ai_llm_internal only`() {
        Konsist
            .scopeFromProduction()
            .files
            .filter { it.path.contains("ai/src/commonMain") }
            .assertTrue { file ->
                val pkg = file.packagee?.name ?: ""
                pkg == "app.moolu.ai.llm" || pkg == "app.moolu.ai.llm.internal"
            }
    }
}
