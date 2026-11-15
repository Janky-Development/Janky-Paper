pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Janky Paper - the .git directory may be a parent folder's: older Minecraft versions live in versions/<mc>/ on main
if (generateSequence(rootDir) { it.parentFile }.none { it.resolve(".git").exists() }) {
    error("Janky Paper must be cloned with git (paperweight needs the .git directory) - do not download a zip.")
}

rootProject.name = "janky-paper"

include("janky-paper-api")
include("janky-paper-server")

// Janky Paper - plugins parse Paper's version strings ("<mc>.build.<n>-<channel>" from 26.1 on; on 1.21.11 the API
// version is "<mc>-R0.1-SNAPSHOT", see below); "<mc>.local-SNAPSHOT" makes them fail or fall back to degraded modes.
// Without BUILD_NUMBER the git commit count numbers the build, so locally built jars look like releases too.
// (Everything stays inside the action: isolated project actions may not reference the settings script.)
gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val versionChannel = providers.gradleProperty("channel").get().trim()
    // Janky Paper start
    val gitBuildNumber: Int? = try {
        if (generateSequence(rootDir) { it.parentFile }.any { it.resolve(".git").exists() }) { // also in versions/<mc>/
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
    // Janky Paper start - on 1.21.11, Paper's Bukkit.getBukkitVersion() is "1.21.11-R0.1-SNAPSHOT" (the "<mc>.build.<n>"
    // shape only exists from 26.1 on). Plugins with per-version tables reject anything else here: Simple Voice Chat
    // logged "Incompatible version 1.21.11.build.12-alpha" and fell back to its degraded Spigot API mode. The API
    // version (apiVersioning.json) is taken from janky-paper-api's project version; the server jar keeps the build number.
    if (name == "janky-paper-api") {
        version = "$mcVersion-R0.1-SNAPSHOT"
    }
    // Janky Paper end
}
