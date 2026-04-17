plugins {
    id("org.jetbrains.intellij") version "1.17.4"
    kotlin("jvm") version "1.9.25"
}

group = "com.claudetabs"
version = "1.0.0"

repositories {
    mavenCentral()
}

// Target IntelliJ Platform 2024.3 for wide compatibility.
// Tested on Rider 2026.1. Build number 243 = 2024.3; higher-numbered builds stay compatible
// since untilBuild is left empty (see patchPluginXml).
intellij {
    version.set("2024.3")
    type.set("IC")
    plugins.set(listOf("terminal"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("243")
        // Empty untilBuild = forward-compatible with future IntelliJ versions.
        // plugin.xml declares the same via <idea-version since-build="243"/>.
        untilBuild.set("")
    }

    buildSearchableOptions {
        enabled = false
    }

    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }
}
