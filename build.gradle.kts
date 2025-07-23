import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import kotlin.system.measureTimeMillis

plugins {
    java
    id("io.papermc.paperweight.patcher") version "2.0.0-SNAPSHOT"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    upstreams.register("purpur") {
        repo = github("PurpurMC", "Purpur")
        ref = providers.gradleProperty("purpurRef")

        patchFile {
            path = "purpur-server/build.gradle.kts"
            outputFile = file("divinemc-server/build.gradle.kts")
            patchFile = file("divinemc-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "purpur-api/build.gradle.kts"
            outputFile = file("divinemc-api/build.gradle.kts")
            patchFile = file("divinemc-api/build.gradle.kts.patch")
        }
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("divinemc-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("purpurApi") {
            upstreamPath = "purpur-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("divinemc-api/purpur-patches")
            outputDir = file("purpur-api")
        }
    }
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.compileJava {
        options.compilerArgs.add("-Xlint:-deprecation")
        options.isWarnings = false
    }

    tasks.withType(JavaCompile::class.java).configureEach {
        options.isFork = true
        options.forkOptions.memoryMaximumSize = "4G"
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = 21
        options.isFork = true
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven("https://jitpack.io")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://repo.bxteam.org/snapshots") {
                name = "divinemc"

                credentials.username = System.getenv("REPO_USERNAME")
                credentials.password = System.getenv("REPO_PASSWORD")
            }
        }
    }
}

tasks.register<Jar>("createMojmapShuttleJar") {
    dependsOn(":divinemc-server:createMojmapPaperclipJar", "shuttle:shadowJar")

    outputs.upToDateWhen { false }

    val paperclipJarTask = project(":divinemc-server").tasks.getByName("createMojmapPaperclipJar")
    val shuttleJarTask = project(":shuttle").tasks.getByName("shadowJar")

    val paperclipJar = paperclipJarTask.outputs.files.singleFile
    val shuttleJar = shuttleJarTask.outputs.files.singleFile
    val outputDir = paperclipJar.parentFile
    val tempDir = File(outputDir, "tempJarWork")
    val newJarName = "divinemc-shuttle-${properties["version"]}-mojmap.jar"

    doFirst {
        val time = measureTimeMillis {
            println("Recompiling Paperclip with Shuttle sources...")

            tempDir.deleteRecursively()
            tempDir.mkdirs()

            copy {
                from(zipTree(paperclipJar))
                into(tempDir)
            }

            val oldPackagePath = "io/papermc/paperclip/"
            tempDir.walkTopDown()
                .filter { it.isFile && it.relativeTo(tempDir).path.startsWith(oldPackagePath) }
                .forEach { it.delete() }

            val shuttlePackagePath = "org/bxteam/shuttle/"
            copy {
                from(zipTree(shuttleJar))
                include("$shuttlePackagePath**")
                into(tempDir)
            }

            tempDir.walkBottomUp()
                .filter { it.isDirectory && it.listFiles().isNullOrEmpty() }
                .forEach { it.delete() }

            val metaInfDir = File(tempDir, "META-INF")
            metaInfDir.mkdirs()
            File(metaInfDir, "main-class").writeText("net.minecraft.server.Main")
        }
        println("Finished build in ${time}ms")
    }

    archiveFileName.set(newJarName)
    destinationDirectory.set(outputDir)
    from(tempDir)

    manifest {
        attributes(
            "Main-Class" to "org.bxteam.shuttle.Shuttle",
            "Enable-Native-Access" to "ALL-UNNAMED",
            "Premain-Class" to "org.bxteam.shuttle.patch.InstrumentationManager",
            "Agent-Class" to "org.bxteam.shuttle.patch.InstrumentationManager",
            "Launcher-Agent-Class" to "org.bxteam.shuttle.patch.InstrumentationManager",
            "Can-Redefine-Classes" to true,
            "Can-Retransform-Classes" to true
        )
    }

    doLast {
        tempDir.deleteRecursively()
    }
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}
