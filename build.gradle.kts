import java.io.ByteArrayOutputStream

// Janky Paper - the repository root only builds the version lines.
//
// Every supported Minecraft version is a complete paperweight project in versions/<minecraft version>/ (listed in
// versions.txt, which CI and the release workflow read too). The tasks below (group "janky versions" in the IDE's
// Gradle tool window) build them from this checkout: each one by its own Gradle wrapper in its folder, and the jar is
// copied into dist/. The ver/* branches are mirrors the release workflow writes - edit the code here on main.
//
// To work on one version (patches, runDevServer, ...), open its versions/<version>/ folder as its own Gradle project,
// or run ./gradlew <task> inside it.

plugins {
    base
    `java-base` // javaToolchains: the JDK the nested builds run on
}

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

// The versions build with their own Gradle wrappers (9.2 - 9.8); run those on JDK 21, which all of them support,
// whatever JVM the IDE runs this build on (each line's toolchain still compiles with its own JDK 21 / 25).
// Each builds with --no-daemon so no idle daemon per version stays behind.
val jankyNestedJavaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    .map { it.metadata.installationPath.asFile.absolutePath }
val jankyIsWindows = System.getProperty("os.name").lowercase().startsWith("windows")
// (full path: cmd does not look in the working directory when NoDefaultCurrentDirectoryInExePath is set)
fun jankyGradlew(checkout: File, vararg args: String): List<String> =
    (if (jankyIsWindows) listOf("cmd", "/c", checkout.resolve("gradlew.bat").absolutePath) else listOf(checkout.resolve("gradlew").absolutePath)) +
        listOf("--no-daemon", "--console=plain") + args

/** Runs one version's own Gradle build in its folder. */
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
    val table = jankyVersions.joinToString("\n") { (mc, folder) -> "  %-8s %s".format(mc, folder) }
    doLast { println("Minecraft  folder\n$table\n(26.1 clients join the 26.1.x jars)") }
}
