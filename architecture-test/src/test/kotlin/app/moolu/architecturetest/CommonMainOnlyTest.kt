package app.moolu.architecturetest

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/**
 * moolu-ai 是 commonMain only library per ADR-base-019 §E1 — 0 platform actuals(LLM 是 pure
 * transport,无 platform-specific 资源 / API 需要 expect/actual)。如未来需要加 platform-specific
 * implementations(e.g., 米鹿 V0.1+ 接 Android Keystore for OpenAI API key on-device storage)
 * 必先 amend ADR-019 §E1 + write a new ADR articulating the platform actuals exception。
 *
 * Plan-32 T2 (per ADR-base-027 §B1) — D4 backfill 1/3 rules to bring moolu-ai from 1 rule
 * baseline (PackageNamingTest only) to 4-rule sibling baseline (close uneven coverage drift D4).
 */
class CommonMainOnlyTest {
    @Test
    fun `all production code lives in commonMain — zero platform actuals per ADR-019 §E1`() {
        Konsist
            .scopeFromProduction()
            .files
            .filter { it.path.contains("ai/src") }
            .assertTrue {
                it.path.contains("commonMain")
            }
    }
}
