plugins {
    id("org.jetbrains.intellij") version "1.17.4"
    kotlin("jvm") version "1.9.25"
}

group = "com.claudetabs"
version = "1.0.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2024.3")
    type.set("IC")
    plugins.set(listOf("terminal"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("243")
        // No untilBuild — compatible with all future versions
        untilBuild.set("")
    }

    buildSearchableOptions {
        enabled = false
    }

    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }
}
