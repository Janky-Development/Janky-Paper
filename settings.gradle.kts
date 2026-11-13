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

// Janky Paper - plugins parse Bukkit.getBukkitVersion() expecting Paper's "<mc>.build.<n>-<channel>" shape
// (ItemEdit, voicechat, NBT-API, ...); "<mc>.local-SNAPSHOT" makes them fail or fall back to degraded modes.
// Without BUILD_NUMBER the git commit count numbers the build, so locally built jars look like releases too.
// (Everything stays inside the action: isolated project actions may not reference the settings script.)
gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val versionChannel = providers.gradleProperty("channel").get().trim()
    // Janky Paper start
    val gitBuildNumber: Int? = try {
        if (rootDir.resolve(".git").exists()) {
            providers.exec {
                workingDir(rootDir)
                commandLine("git", "rev-list", "--count", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.map { it.trim().toIntOrNull() }.orNull
        } else null
    } catch (e: Exception) {
        null
    }
    val buildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt() ?: gitBuildNumber
    // Janky Paper end
    extra["jankyBuildNumber"] = buildNumber?.toString() // Janky Paper - read by janky-paper-server/build.gradle.kts for the manifest
    version = if (buildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$buildNumber-${versionChannel.lowercase()}"
    }
}
