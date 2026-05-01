plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.binary.compat.validator) apply false
}

allprojects {
    afterEvaluate {
        if (plugins.hasPlugin("org.jlleitschuh.gradle.ktlint")) {
            extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
                version.set("1.5.0")
                android.set(false)
                ignoreFailures.set(false)
                reporters {
                    reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
                    reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
                }
            }
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
