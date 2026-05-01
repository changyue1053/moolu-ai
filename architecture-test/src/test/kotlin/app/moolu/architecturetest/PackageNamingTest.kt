package app.moolu.architecturetest

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

class PackageNamingTest {
    @Test
    fun `all production code lives under app_moolu_ai package`() {
        Konsist
            .scopeFromModule("ai")
            .files
            .assertTrue {
                it.packagee?.name?.startsWith("app.moolu.ai") ?: false
            }
    }
}
