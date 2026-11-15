import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.io.ByteArrayOutputStream // Janky Paper - version line tasks

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

// Janky Paper start - one jar per Minecraft version
//
// Every supported Minecraft version is a complete project on main: the newest one is this root project, the older
// ones live in versions/<minecraft version>/ (listed in versions.txt, which CI and the release workflow read too).
// The tasks below (group "janky versions" in the IDE's Gradle tool window) build them from this checkout: the root
// version with plain task dependencies, every other one by its own Gradle wrapper in its folder, and the jar is
// copied into dist/. The ver/* branches are mirrors the release workflow writes - edit the code here on main.
val jankyVersions: List<Pair<String, String>> = providers.fileContents(layout.projectDirectory.file("versions.txt")).asText.get()
    .lines()
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() }
    .map { line -> line.split(Regex("\\s+")).let { it[0] to it[1].trimEnd('/') } } // <minecraft version> to <folder>, newest first
val jankyVersionsGroup = "janky versions"

/** Copies the newest Paperclip jar of one version line into dist/, replacing older builds of that version. */
abstract class CollectJankyJar : DefaultTask() {
    @get:Internal abstract val libsDir: DirectoryProperty
    @get:Internal abstract val distDir: DirectoryProperty
    @get:Internal abstract val minecraftVersion: Property<String>

    @TaskAction
    fun collect() {
        val jar = libsDir.get().asFile
            .listFiles { f -> f.isFile && f.name.startsWith("jankypaper-paperclip-") && f.name.endsWith(".jar") && !f.name.endsWith("-reobf.jar") }
            .orEmpty().maxByOrNull { it.lastModified() }
            ?: throw GradleException("No jankypaper-paperclip jar in ${libsDir.get().asFile}")
        val dist = distDir.get().asFile.apply { mkdirs() }
        val sameVersion = Regex("^jankypaper-paperclip-" + Regex.escape(minecraftVersion.get()) + "\\.(build\\.|local-).*\\.jar$")
        dist.listFiles { f -> sameVersion.matches(f.name) && f.name != jar.name }.orEmpty().forEach { it.delete() }
        val target = dist.resolve(jar.name)
        jar.copyTo(target, overwrite = true)
        logger.lifecycle("Janky Paper ${minecraftVersion.get()}: $target")
    }
}

/**
 * Refuses to go on when a version line's generated sources (paper-server/, paper-api/, the Minecraft sources) have
 * uncommitted edits: applyAllPatches would throw them away. Turn them into patches first (fixup/rebuild tasks).
 */
abstract class CheckGeneratedSourcesClean : DefaultTask() {
    @get:Internal abstract val checkoutDir: DirectoryProperty
    @get:javax.inject.Inject abstract val execOps: org.gradle.process.ExecOperations

    @TaskAction
    fun check() {
        val root = checkoutDir.get().asFile
        val dirty = listOf("paper-api", "paper-server", "paper-checkstyle", "janky-paper-server/src/minecraft/java", "janky-paper-server/src/minecraft/resources")
            .map { root.resolve(it) }
            .filter { it.resolve(".git").exists() }
            .filter { dir ->
                val out = ByteArrayOutputStream()
                execOps.exec {
                    workingDir = dir
                    commandLine("git", "status", "--porcelain")
                    standardOutput = out
                    isIgnoreExitValue = true
                }
                out.toString().isNotBlank()
            }
        if (dirty.isNotEmpty()) {
            throw GradleException(
                "Uncommitted edits in ${dirty.joinToString { it.relativeTo(root).path }} (in $root) would be lost by applyAllPatches. " +
                    "Rebuild them into patches (fixup.../rebuild... tasks) or discard them, then run this again."
            )
        }
    }
}

// The older versions build with their own Gradle wrappers (9.2 - 9.8); run those on JDK 21, which all of them support,
// whatever JVM the IDE runs this build on. Each builds with --no-daemon so no idle daemon per version stays behind.
val jankyNestedJavaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    .map { it.metadata.installationPath.asFile.absolutePath }
val jankyIsWindows = System.getProperty("os.name").lowercase().startsWith("windows")
// (full path: cmd does not look in the working directory when NoDefaultCurrentDirectoryInExePath is set)
fun jankyGradlew(checkout: File, vararg args: String): List<String> =
    (if (jankyIsWindows) listOf("cmd", "/c", checkout.resolve("gradlew.bat").absolutePath) else listOf(checkout.resolve("gradlew").absolutePath)) +
        listOf("--no-daemon", "--console=plain") + args

/** Runs one of the older versions' own Gradle builds in its folder. */
fun Exec.jankyNestedGradle(checkout: File, vararg args: String) {
    workingDir(checkout)
    commandLine(jankyGradlew(checkout, *args))
    val javaHome = jankyNestedJavaHome
    doFirst { (this as Exec).environment("JAVA_HOME", javaHome.get()) }
}

val jankyBuildJarTasks = mutableListOf<String>()
var jankyPreviousBuild: String? = null
for ((mc, folder) in jankyVersions) {
    val applyName = "applyPatches-$mc"
    val buildName = "buildJar-$mc"
    val previous = jankyPreviousBuild
    if (folder == ".") {
        // this project: plain task dependencies, no nested build
        tasks.register(applyName) {
            group = jankyVersionsGroup
            description = "Applies the Minecraft $mc patches of this project (same as applyAllPatches)."
            dependsOn("applyAllPatches")
        }
        tasks.register<CollectJankyJar>(buildName) {
            group = jankyVersionsGroup
            description = "Builds the Minecraft $mc server jar from this project as its patches are applied now " +
                "(run $applyName first after changing patches) and copies it into dist/."
            dependsOn(":janky-paper-server:createPaperclipJar")
            libsDir = layout.projectDirectory.dir("janky-paper-server/build/libs")
            distDir = layout.projectDirectory.dir("dist")
            minecraftVersion = mc
        }
    } else {
        val checkout = rootDir.resolve(folder)
        val check = tasks.register<CheckGeneratedSourcesClean>("checkGeneratedSources-$mc") {
            description = "Fails when $folder has uncommitted edits in its generated sources that applyAllPatches would discard."
            checkoutDir = layout.projectDirectory.dir(folder)
            if (previous != null) mustRunAfter(previous) // one version at a time
        }
        val apply = tasks.register<Exec>(applyName) {
            group = jankyVersionsGroup
            description = "Applies the Minecraft $mc patches in $folder."
            dependsOn(check)
            jankyNestedGradle(checkout, "applyAllPatches")
        }
        val paperclip = tasks.register<Exec>("createPaperclipJar-$mc") {
            description = "Runs createPaperclipJar in $folder."
            dependsOn(apply)
            jankyNestedGradle(checkout, "createPaperclipJar")
        }
        tasks.register<CollectJankyJar>(buildName) {
            group = jankyVersionsGroup
            description = "Builds the Minecraft $mc server jar in $folder - patches are applied first - and copies it into dist/."
            dependsOn(paperclip)
            libsDir = layout.projectDirectory.dir("$folder/janky-paper-server/build/libs")
            distDir = layout.projectDirectory.dir("dist")
            minecraftVersion = mc
        }
    }
    jankyBuildJarTasks += buildName
    jankyPreviousBuild = buildName
}

tasks.register("buildAllJars") {
    group = jankyVersionsGroup
    description = "Builds the server jar of every Minecraft version (${jankyVersions.joinToString { it.first }}) into dist/, one at a time."
    dependsOn(jankyBuildJarTasks)
}

tasks.register("listVersions") {
    group = jankyVersionsGroup
    description = "Lists the Minecraft versions and the folder each one lives in."
    val table = jankyVersions.joinToString("\n") { (mc, folder) ->
        "  %-8s %s".format(mc, if (folder == ".") ". (this project)" else folder)
    }
    doLast { println("Minecraft  folder\n$table\n(26.1 clients join the 26.1.x jars)") }
}
// Janky Paper end - one jar per Minecraft version
