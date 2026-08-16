import java.time.Instant
import java.time.format.DateTimeFormatter

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.5.0"
}

group = "de.tasticgames"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()

    maven {
        name = "papermc"
        url = uri(
            "https://repo.papermc.io/repository/maven-public/"
        )
    }
}

dependencies {
    compileOnly(
        "com.velocitypowered:velocity-api:3.5.0-SNAPSHOT"
    )

    annotationProcessor(
        "com.velocitypowered:velocity-api:3.5.0-SNAPSHOT"
    )

    implementation(
        "de.tasticgames:tasticgames-api-client:1.0.0"
    )

    testImplementation(
        platform("org.junit:junit-bom:6.0.0")
    )

    testImplementation(
        "org.junit.jupiter:junit-jupiter"
    )

    testImplementation(
        "com.velocitypowered:velocity-api:3.5.0-SNAPSHOT"
    )

    testRuntimeOnly(
        "org.junit.platform:junit-platform-launcher"
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

val buildTimestamp: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

val gitCommit: String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{7,40}"))) output else "unknown"
} catch (e: Exception) {
    "unknown"
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("gitCommit", gitCommit)
    filesMatching("build-info.properties") {
        expand(
            "version" to project.version,
            "buildTimestamp" to buildTimestamp,
            "gitCommit" to gitCommit
        )
    }
}

tasks.shadowJar {
    archiveClassifier.set("")

    duplicatesStrategy =
        DuplicatesStrategy.EXCLUDE

    exclude(
        "META-INF/LICENSE",
        "META-INF/LICENSE.txt",
        "META-INF/NOTICE",
        "META-INF/NOTICE.txt",
        "META-INF/DEPENDENCIES",
        "META-INF/INDEX.LIST"
    )

    relocate(
        "de.tasticgames.client",
        "de.tasticgames.proxy.libs.apiclient"
    )

    relocate(
        "com.fasterxml.jackson",
        "de.tasticgames.proxy.libs.jackson"
    )

    mergeServiceFiles()
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(
        tasks.shadowJar
    )
}