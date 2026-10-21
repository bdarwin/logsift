plugins {
    application
}

group = "logmaster"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Builds with any JDK 21+; output targets Java 21 (LTS).
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}

application {
    mainClass = "logmaster.ui.LogMasterApp"
    applicationDefaultJvmArgs = listOf("-Xmx2g", "-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED")
}

// Fast unit tests (no UI windows).
tasks.test {
    useJUnitPlatform {
        excludeTags("visual")
    }
    maxHeapSize = "1g"
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
    systemProperty("lm.visual.sizeMb", (project.findProperty("sizeMb") ?: "1024").toString())
    systemProperty("lm.visual.dir", layout.buildDirectory.dir("visual").get().asFile.absolutePath)
    systemProperty("lm.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
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
    mainClass = "logmaster.tools.LogGenerator"
    val out = (project.findProperty("out") ?: layout.buildDirectory.file("sample.log").get().asFile.absolutePath).toString()
    val size = (project.findProperty("sizeMb") ?: "200").toString()
    val follow = project.findProperty("follow")?.toString()
    args = listOfNotNull(out, size, follow?.let { "--follow=$it" })
}
