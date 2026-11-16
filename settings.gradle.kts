// Janky Paper - the repository root holds no server code: every Minecraft version is a complete project in
// versions/<minecraft version>/ (versions.txt). This build only has the "janky versions" tasks that build them
// (build.gradle.kts). Open a versions/<version>/ folder as its own Gradle project to work on that version.
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "janky-paper-versions"
