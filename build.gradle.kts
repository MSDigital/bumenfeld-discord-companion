import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

plugins {
    java
    `maven-publish`
}

group = "com.Bumenfeld"
val basePluginVersion = project.findProperty("plugin_version")?.toString()?.takeIf { it.isNotBlank() }
    ?: "1.0.0"

fun resolveGitVersion(): String? {
    val process = ProcessBuilder(
        "git",
        "describe",
        "--tags",
        "--long",
        "--dirty"
    )
        .directory(projectDir)
        .redirectErrorStream(true)
        .start()

    val finished = process.waitFor(5, TimeUnit.SECONDS)
    if (!finished || process.exitValue() != 0) {
        return null
    }

    val describe = process.inputStream.bufferedReader().readText().trim()
    if (describe.isBlank()) {
        return null
    }

    val dirty = describe.endsWith("-dirty")
    val cleanDescribe = if (dirty) describe.removeSuffix("-dirty") else describe
    val segments = cleanDescribe.split('-')
    if (segments.size < 3) {
        return segments.firstOrNull()?.removePrefix("v")
    }

    val baseTag = segments[0].removePrefix("v")
    val commitCount = segments[1].toIntOrNull() ?: 0
    return if (commitCount == 0 && !dirty) {
        baseTag
    } else {
        val suffix = if (commitCount > 0) "-$commitCount" else ""
        "$baseTag-dev$suffix"
    }
}

val gitCommitCount = runCatching {
    ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(projectDir)
        .redirectErrorStream(true)
        .start()
        .apply {
            waitFor(5, TimeUnit.SECONDS)
        }
        .inputStream
        .bufferedReader()
        .readText()
        .trim()
}.getOrNull()?.toIntOrNull() ?: 0

val outputVersion = project.findProperty("localVersion")?.toString()?.takeIf { it.isNotBlank() }
    ?: resolveGitVersion()

val computedVersion = outputVersion ?: basePluginVersion
version = computedVersion

val javaVersion = 25

repositories {
    mavenCentral()
    maven {
        name = "hytale-release"
        url = uri("https://maven.hytale.com/release")
    }
    maven {
        name = "hytale-pre-release"
        url = uri("https://maven.hytale.com/pre-release")
    }
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:latest.release")
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.jspecify)

    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.api)
    implementation(libs.snakeyaml)
    implementation(libs.okhttp)
    implementation(libs.jda) {
        exclude(group = "club.minnced", module = "opus-java")
        exclude(module = "tink")
    }
    runtimeOnly(libs.slf4j.simple)
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties = mapOf(
        "plugin_group" to findProperty("plugin_group"),
        "plugin_maven_group" to findProperty("plugin_maven_group"),
        "plugin_name" to findProperty("plugin_name"),
        "plugin_version" to computedVersion,
        "server_version" to findProperty("server_version"),
        "plugin_description" to findProperty("plugin_description"),
        "plugin_website" to findProperty("plugin_website"),
        "plugin_main_entrypoint" to findProperty("plugin_main_entrypoint"),
        "plugin_author" to findProperty("plugin_author"),
        "build_id" to buildId,
        "git_revision" to gitRevision,
        "build_timestamp" to buildTimestamp
    )

    filesMatching("manifest.json") {
        expand(replaceProperties)
    }

    inputs.properties(replaceProperties)
}

val buildTimestamp = OffsetDateTime.now(ZoneOffset.UTC)
    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
val gitRevision = runCatching {
    ByteArrayOutputStream().use { buffer ->
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.copyTo(buffer)
        if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
            buffer.toString().trim().takeIf { it.isNotBlank() } ?: "unknown"
        } else {
            "unknown"
        }
    }
}.getOrDefault("unknown")
val buildId = "$computedVersion-$buildTimestamp-$gitRevision"

tasks.withType<Jar> {
    manifest {
        attributes["Specification-Title"] = rootProject.name
        attributes["Specification-Version"] = version
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] =
            providers.environmentVariable("COMMIT_SHA_SHORT")
                .map { "${version}-${it}" }
                .getOrElse(version.toString())
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

val pluginJar = tasks.register<Jar>("pluginJar") {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    from(sourceSets.main.get().output)

    dependsOn(tasks.named("classes"))

    val runtimeClasspath = configurations.named("runtimeClasspath")
    inputs.files(runtimeClasspath)
    from({
        runtimeClasspath.get().files.flatMap { file ->
            when {
                !file.exists() -> emptyList()
                file.name == "HytaleServer.jar" -> emptyList()
                file.isDirectory -> listOf(file)
                else -> listOf(zipTree(file))
            }
        }
    })
}

tasks.named("build") {
    dependsOn(pluginJar)
}

tasks.register("release") {
    dependsOn(pluginJar)
}

tasks.named("assemble") {
    dependsOn(pluginJar)
}
tasks.named("jar") {
    enabled = false
}

tasks.findByName("sourcesJar")?.let { it.enabled = false }

publishing {
    repositories {}

    publications {
        create<MavenPublication>("maven") {
            artifact(pluginJar) {
                classifier = null
            }
        }
    }
}
