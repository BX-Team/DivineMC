plugins {
    id("java")
    id("application")
    id("maven-publish")
    id("com.gradleup.shadow") version "8.3.8"
}

val mainClass = "org.bxteam.shuttle.Shuttle"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.sigpipe:jbsdiff:1.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.jar {
    val jar = tasks.named("shadowJar")
    dependsOn(jar)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(zipTree(jar.map { it.outputs.files.singleFile }))

    manifest {
        attributes(
            "Main-Class" to mainClass,
            "Enable-Native-Access" to "ALL-UNNAMED",
            "Premain-Class" to "org.bxteam.shuttle.patch.InstrumentationManager",
            "Agent-Class" to "org.bxteam.shuttle.patch.InstrumentationManager",
            "Launcher-Agent-Class" to "org.bxteam.shuttle.patch.InstrumentationManager",
            "Can-Redefine-Classes" to true,
            "Can-Retransform-Classes" to true
        )
    }
}

project.setProperty("mainClassName", mainClass)

tasks.shadowJar {
    val prefix = "paperclip.libs"
    listOf("org.apache", "org.tukaani", "io.sigpipe").forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }

    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE.txt")
}
