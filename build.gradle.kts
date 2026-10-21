plugins {
    application
}

group = "logsift"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")
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

// Builds with any JDK 21+; output targets Java 21 (LTS).
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}

application {
    mainClass = "logsift.ui.LogsiftApp"
    applicationDefaultJvmArgs = listOf("-Xmx2g", "-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED")
}

// Fast unit tests (no UI windows).
tasks.test {
    useJUnitPlatform {
        excludeTags("visual")
    }
    maxHeapSize = "1g"
    systemProperty("logsift.prefs", "/logsift-test/ui")
    systemProperty("logsift.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
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
    systemProperty("logsift.visual.dir", layout.buildDirectory.dir("visual").get().asFile.absolutePath)
    systemProperty("logsift.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    // keep tests away from the user's own settings, recent files and ~/.logsift
    systemProperty("logsift.prefs", "/logsift-test/ui")
    systemProperty("logsift.home", layout.buildDirectory.dir("visual/home").get().asFile.absolutePath)
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

// Prints the test classpath (used to check the packaged runtime's modules by hand).
val printTestClasspath by tasks.registering {
    doLast { println(sourceSets.test.get().runtimeClasspath.asPath) }
}
