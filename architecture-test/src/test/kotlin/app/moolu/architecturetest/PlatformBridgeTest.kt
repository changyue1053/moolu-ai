package app.moolu.architecturetest

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * moolu-ai 0 platform actuals per ADR-base-019 §E1 — assert no androidMain / iosMain
 * production files exist (mirror of CommonMainOnlyTest invariant from the platform-bridge
 * angle for defense-in-depth)。
 *
 * Plan-32 T2 (per ADR-base-027 §B1) — D4 backfill 2/3 rules.
 */
class PlatformBridgeTest {
    @Test
    fun `no androidMain or iosMain files exist per ADR-019 §E1 commonMain only mandate`() {
        val platformFiles =
            Konsist
                .scopeFromProduction()
                .files
                .filter { it.path.contains("ai/src/androidMain") || it.path.contains("ai/src/iosMain") }

        assertTrue(
            platformFiles.isEmpty(),
            "Expected 0 platform actuals per ADR-019 §E1 (moolu-ai is commonMain only). " +
                "Found ${platformFiles.size} platform files: ${platformFiles.map { it.path }}",
        )
    }
}
