pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    error("Janky Paper must be cloned with git (paperweight needs the .git directory) - do not download a zip.")
}

rootProject.name = "janky-paper"

include("janky-paper-api")
include("janky-paper-server")
include("janky-paper-checkstyle")

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val versionChannel = providers.gradleProperty("channel").get().trim()
    val buildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    version = if (buildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$buildNumber-${versionChannel.lowercase()}"
    }
}
