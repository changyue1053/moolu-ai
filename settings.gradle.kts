@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        // Resolve moolu-build-logic snapshot from ~/.m2 (plan-04 §T2 shared).
        mavenLocal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("moolu.kmp.library") version "1.0.0-SNAPSHOT"
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Resolve moolu-foundation:1.0.1-SNAPSHOT + moolu-network:1.0.0-SNAPSHOT from ~/.m2
        // (per ADR-base-004 pre-V1.0).
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "moolu-ai"

include(":ai")
include(":architecture-test")
