import java.net.URI
import java.security.MessageDigest

plugins {
    application
}

group = "logsift"
version = "1.3.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")
    // SVG icons (menus, toolbar) in the theme's colors
    implementation("com.formdev:flatlaf-extras:3.7.2")
    // SSH/SFTP for remote logs (maintained fork of JSch)
    implementation("com.github.mwiede:jsch:0.2.20")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // A real logging library that rotates files, for the rotation test.
    testImplementation("ch.qos.logback:logback-classic:1.5.18")
    // an SFTP server inside the tests, for remote logs
    testImplementation("org.apache.sshd:sshd-core:2.14.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.14.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// async-profiler (Apache 2.0) for profiling JVMs and for thread dumps on servers without JDK
// tools: downloaded once (checksums checked) and packed into the app as resources.
val asyncProfiler = "4.5"
val agentFiles = mapOf(
    "linux-x64" to "89546fbb9ee0fc5496c7edd4099b0709489bc78b0d8057ccbb4b801f6b032b62",
    "linux-arm64" to "64c41d1465d60097439c50d7e924b4946f1f62b1cbd21ce5b034fad09c0d6979",
    "macos" to "46d04ef81f532a065a0b3877e488aa706afa14aa2ea14433b323db9e6fda76dc")
fun agentArchive(platform: String) =
    "async-profiler-$asyncProfiler-$platform" + if (platform == "macos") ".zip" else ".tar.gz"
val agentDownloads = File(gradle.gradleUserHomeDir, "logsift-agents/$asyncProfiler")

val downloadAgents by tasks.registering {
    description = "Downloads async-profiler (once) and checks its checksums."
    val dir = agentDownloads
    val files = agentFiles.mapKeys { agentArchive(it.key) }
    outputs.dir(dir)
    doLast {
        fun sha256(f: File): String = MessageDigest.getInstance("SHA-256")
            .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        dir.mkdirs()
        files.forEach { (name, sha) ->
            val f = File(dir, name)
            if (!f.exists() || sha256(f) != sha) {
                val url = "https://github.com/async-profiler/async-profiler/releases/download/v$asyncProfiler/$name"
                URI(url).toURL().openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            }
            check(sha256(f) == sha) { "Wrong checksum for $name: delete $f and build again" }
        }
    }
}

val agents by tasks.registering(Sync::class) {
    description = "Puts async-profiler's launcher and library where the app finds them."
    dependsOn(downloadAgents)
    into(layout.buildDirectory.dir("generated/agents"))
    includeEmptyDirs = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    agentFiles.keys.forEach { platform ->
        val archive = File(agentDownloads, agentArchive(platform))
        from(if (platform == "macos") zipTree(archive) else tarTree(resources.gzip(archive))) {
            include("*/bin/asprof", "*/lib/libasyncProfiler.*", "*/LICENSE")
            eachFile {
                path = if (name == "LICENSE") "logsift/agents/LICENSE-async-profiler.txt"
                else "logsift/agents/$platform/$name"
            }
        }
    }
}

sourceSets.main {
    resources.srcDir(agents)
}

// Builds with any JDK 21+; output targets Java 21 (LTS).
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}

// The heap dump reader is compiled for Java 8 into a jar of its own: the app uses it, and it is
// copied to a server to index a dump there with whatever Java the server has.
val heap: SourceSet by sourceSets.creating {
    java.srcDir("src/heap/java")
}
tasks.named<JavaCompile>("compileHeapJava") {
    options.release = 8
    options.compilerArgs.add("-Xlint:-options") // "source value 8 is obsolete": on purpose
}
val heapJar by tasks.registering(Jar::class) {
    description = "The heap dump reader and its server, for Java 8 and later."
    archiveBaseName = "logsift-heap"
    from(heap.output)
    manifest {
        attributes("Implementation-Title" to "Logsift heap", "Implementation-Version" to project.version)
    }
}
dependencies {
    implementation(files(heapJar))
}

application {
    mainClass = "logsift.ui.LogsiftApp"
    applicationDefaultJvmArgs = listOf("-Xmx2g", "-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED")
}

// bin/logsift-mcp next to bin/logsift: Logsift's tools for coding agents, over standard input and output.
val mcpStartScripts by tasks.registering(CreateStartScripts::class) {
    description = "Start scripts for logsift-mcp (Logsift's tools for coding agents)."
    applicationName = "logsift-mcp"
    mainClass = "logsift.agent.mcp.McpMain"
    outputDir = layout.buildDirectory.dir("mcp-scripts").get().asFile
    classpath = tasks.startScripts.get().classpath
    defaultJvmOpts = listOf("-Xmx2g", "-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
}
// ZGC needs Windows 10 1803 / Server 2019 or later: Java won't start with it on Server 2016 or
// Windows 10 LTSB 1607. The Windows start scripts use G1, which runs everywhere.
listOf(tasks.startScripts, mcpStartScripts).forEach { t ->
    t.configure {
        doLast {
            val bat = outputDir!!.resolve(applicationName + ".bat")
            bat.writeText(bat.readText().replace("-XX:+UseZGC", "-XX:+UseG1GC"))
        }
    }
}
distributions {
    main {
        contents {
            from(mcpStartScripts) { into("bin") }
        }
    }
}

// Fast unit tests (no UI windows).
tasks.test {
    useJUnitPlatform {
        excludeTags("visual", "perf")
    }
    maxHeapSize = "1g"
    systemProperty("logsift.prefs", "/logsift-test/ui")
    systemProperty("logsift.updateUrl", "none")
    systemProperty("logsift.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    // GuideLinksTest checks the user guide's links and pictures: run it again when they change
    inputs.dir("docs/guide").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("docs/screenshots").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Slow measurements (a million-row CSV compare):  ./gradlew perfTest
val perfTest by tasks.registering(Test::class) {
    description = "Measures time and memory of slow operations (not part of the build)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("perf")
    }
    // -PperfHeap=256m checks it fits in less
    maxHeapSize = (project.findProperty("perfHeap") ?: "1g").toString()
    systemProperty("logsift.prefs", "/logsift-test/ui")
    systemProperty("logsift.updateUrl", "none")
    systemProperty("logsift.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    testLogging {
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}

// Visual tests: generate large log files, open them in the real UI,
// check results and write screenshots to build/screenshots.
//   ./gradlew visualTest                 (1 GB file, default)
//   ./gradlew visualTest -PsizeMb=3072   (bigger file)
val visualTest by tasks.registering(Test::class) {
    description = "Runs UI tests on generated large log files and saves screenshots."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("visual")
    }
    maxHeapSize = "2g"
    jvmArgs("-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED")
    systemProperty("java.awt.headless", "false")
    systemProperty("logsift.visual.sizeMb", (project.findProperty("sizeMb") ?: "1024").toString())
    // -PvisualDir=/tmp/logs puts the test logs somewhere neutral (for README screenshots)
    systemProperty("logsift.visual.dir", (project.findProperty("visualDir")
            ?: layout.buildDirectory.dir("visual").get().asFile.absolutePath).toString())
    systemProperty("logsift.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    // -Pdemo=/some/dir records a video of the find bar into that folder (DemoRecording)
    project.findProperty("demo")?.let { systemProperty("logsift.demo", it.toString()) }
    // keep tests away from the user's own settings, recent files and ~/.logsift
    systemProperty("logsift.prefs", "/logsift-test/ui")
    systemProperty("logsift.updateUrl", "none")
    systemProperty("logsift.home", layout.buildDirectory.dir("visual/home").get().asFile.absolutePath)
    // tests expect a file's tab right after opening it; allow slow CI machines time for that
    systemProperty("logsift.openWaitMs", "10000")
    // no message boxes that wait for a click (notes go to the status line)
    systemProperty("logsift.visual.noNotes", "true")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    outputs.upToDateWhen { false }
}

// Generate a sample log:  ./gradlew genLog -Pout=/tmp/sample.log -PsizeMb=500
// Keep appending (for tail testing): add -Pfollow=2000   (lines per second)
val genLog by tasks.registering(JavaExec::class) {
    description = "Generates a realistic sample log file."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "logsift.tools.LogGenerator"
    val out = (project.findProperty("out") ?: layout.buildDirectory.file("sample.log").get().asFile.absolutePath).toString()
    val size = (project.findProperty("sizeMb") ?: "200").toString()
    val follow = project.findProperty("follow")?.toString()
    args = listOfNotNull(out, size, follow?.let { "--follow=$it" })
}

// Stress test with very large logs (writes many GB; not part of the normal tests):
//   ./gradlew stress -PstressGb=10 -PstressDir=/some/disk
val stress by tasks.registering(JavaExec::class) {
    description = "Measures Logsift with a multi-GB log, a huge line and 300 million short lines."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "logsift.tools.Stress"
    jvmArgs("-Xmx2g", "-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED")
    args = listOf((project.findProperty("stressGb") ?: "10").toString(),
        (project.findProperty("stressDir") ?: layout.buildDirectory.dir("stress").get().asFile.absolutePath).toString())
}

// Regenerate the installer icons in packaging/icons (macOS .icns needs iconutil):
//   ./gradlew genIcons && iconutil -c icns packaging/icons/logsift.iconset -o packaging/icons/logsift.icns
val genIcons by tasks.registering(JavaExec::class) {
    description = "Draws the app icon and writes packaging/icons."
    group = "build"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "logsift.tools.IconGenerator"
    args = listOf(layout.projectDirectory.dir("packaging/icons").asFile.absolutePath)
    jvmArgs("-Djava.awt.headless=true")
}

// Regenerate the splash screen in packaging/splash:  ./gradlew genSplash
val genSplash by tasks.registering(JavaExec::class) {
    description = "Draws the splash screen and writes packaging/splash."
    group = "build"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "logsift.tools.SplashGenerator"
    args = listOf(layout.projectDirectory.dir("packaging/splash").asFile.absolutePath)
    jvmArgs("-Djava.awt.headless=true")
}

// Prints the test classpath (used to check the packaged runtime's modules by hand).
val printTestClasspath by tasks.registering {
    doLast { println(sourceSets.test.get().runtimeClasspath.asPath) }
}

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "Logsift", "Implementation-Version" to project.version)
    }
}
