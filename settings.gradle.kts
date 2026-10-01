pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "dashbuddy-census"
include(":server")

// The Apache-2.0 contract is a sibling app checkout; CI overrides censusContractPath.
includeBuild(providers.gradleProperty("censusContractPath").getOrElse("../DashBuddy/census-contract"))
