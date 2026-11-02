import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.24"
}

paperweight {
    upstreams.paper {
        ref = providers.gradleProperty("paperRef")

        // Paper's build scripts are patched (not copied) so upstream changes flow through on updates.
        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("janky-paper-server/build.gradle.kts")
            patchFile = file("janky-paper-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("janky-paper-api/build.gradle.kts")
            patchFile = file("janky-paper-api/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-checkstyle/build.gradle.kts"
            outputFile = file("janky-paper-checkstyle/build.gradle.kts")
            patchFile = file("janky-paper-checkstyle/build.gradle.kts.patch")
        }

        // Paper sources are checked out into paper-api / paper-checkstyle / .checkstyle and our patches applied on top.
        // (paper-server is handled by the fork block inside janky-paper-server/build.gradle.kts.patch)
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("janky-paper-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("paperCheckstyle") {
            upstreamPath = "paper-checkstyle"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("janky-paper-checkstyle/paper-patches")
            outputDir = file("paper-checkstyle")
        }
        patchDir("paperCheckstyleConfig") {
            upstreamPath = ".checkstyle"
            patchesDir = file("janky-paper-checkstyle/config-patches")
            outputDir = file(".checkstyle")
        }
    }
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://artifactory.papermc.io/artifactory/releases/") {
                name = "paperReleases"
                credentials(PasswordCredentials::class)
            }
        }
    }
}

tasks.register("printMinecraftVersion") {
    val mcVersion = providers.gradleProperty("mcVersion")
    doLast { println(mcVersion.get().trim()) }
}

tasks.register("printJankyPaperVersion") {
    val v = provider { project.version }
    doLast { println(v.get()) }
}
