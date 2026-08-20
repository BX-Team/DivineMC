import java.util.Locale

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    val errorText = """
        
        =====================[ ERROR ]=====================
         The DivineMC project directory is not a properly cloned Git repository.
         
         In order to build DivineMC from source you must clone
         the DivineMC repository using Git, not download a code
         zip from GitHub.
         
         Built DivineMC jars are available for download at
         https://bxteam.org/downloads/divinemc or 
         at https://mcjars.app/DIVINEMC/versions
         
         See https://bxteam.org/docs/divinemc/development/contributing
         for further information on building and modifying DivineMC.
        ===================================================
    """.trimIndent()
    error(errorText)
}

rootProject.name = "DivineMC"

for (name in listOf("divinemc-api", "divinemc-server", "divinemc-checkstyle")) {
    val projName = name.lowercase(Locale.ENGLISH)
    include(projName)
    findProject(":$projName")!!.projectDir = file(name)
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val divinemcChannel = providers.gradleProperty("channel").get().trim()
    val divinemcBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (divinemcBuildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$divinemcBuildNumber-${divinemcChannel.lowercase()}"
    }
    version = versionString
}
