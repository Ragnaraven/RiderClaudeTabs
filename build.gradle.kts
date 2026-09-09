import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 2.11: newest line usable with the Gradle 8.13 wrapper (2.12+ requires Gradle 9). Needed
    // over 2.10 because 2.10's runIde could not boot the unified 2026.1 layout (coroutines
    // -javaagent class not loadable → JVM abort).
    id("org.jetbrains.intellij.platform") version "2.11.0"
    kotlin("jvm") version "2.3.0"
}

group = "com.claudetabs"
version = "3.2.1"

repositories {
    mavenCentral()
    // Remote Robot (UI test library) is hosted at JetBrains' public Maven repo.
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")

    intellijPlatform {
        defaultRepositories()
        releases()
        snapshots()
    }
}

// IntelliJ Platform 2026.1 minimum. The plugin uses TerminalToolWindowManager.
// createShellWidget(...) for terminal creation — the supported public API (the official
// Claude Code plugin's toolbar button uses the same call); the 5-arg createNewSession
// overload is @ApiStatus.Internal. Per JetBrains docs, 2025.3+ unified the Community/
// Ultimate helper into intellijIdea() (intellijIdeaCommunity is legacy-only).
dependencies {
    intellijPlatform {
        intellijIdea("2026.1")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // JUnit 4 — IntelliJ platform brings its own version, but declaring it here makes the
    // intent clear and lets plain unit tests run without needing the full platform harness.
    testImplementation("junit:junit:4.13.2")

    // Remote Robot — spins up a real IDE and drives the UI over RMI (Layer 3b e2e tests).
    testImplementation("com.intellij.remoterobot:remote-robot:0.11.23")
    testImplementation("com.intellij.remoterobot:remote-fixtures:0.11.23")
}

// IntelliJ Platform 2026.1 requires JVM target 17. Pin Java + Kotlin to 17 so the build
// works regardless of the local JDK (matters when the only available JDK on the build
// machine is JDK 21+, which would otherwise default `compileJava` to 21 and fail the
// platform verifier).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// ── E2E (Layer 3b): sandboxed IDE + Robot Server for UI-driven end-to-end tests ──
// Launched by e2e/run-e2e.ps1, which prepares build/e2e (stub wrappers, isolated .claude home,
// throwaway project) and orchestrates the two-phase pre/post-restart scenario. The sandbox IDE
// reads/writes ONLY the isolated home, and every Claude launch resolves to the stub CLI (pinned
// by absolute path, see -Dclaudetabs.claudeCommand) — no real sessions, accounts, or IDE state
// are ever touched.
val e2eDir: File = layout.buildDirectory.dir("e2e").get().asFile

val runIdeForE2e by intellijPlatformTesting.runIde.registering {
    type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea
    version = "2026.1"
    task {
        // IPGP injects the IDE's kotlinx-coroutines debug agent; on unified 2026.1 the agent
        // class fails to load and ABORTS the JVM at boot (ClassNotFoundException: AgentPremain).
        // The E2E needs no coroutine debug probes — point at a nonexistent jar, which the
        // exists-guarded argument provider then skips entirely.
        coroutinesJavaAgentFile.set(layout.buildDirectory.file("e2e/no-coroutines-agent.jar"))
        jvmArgs(
            "-Drobot-server.port=8082",
            "-Drobot-server.host.public=false",
            "-Dclaudetabs.home=${File(e2eDir, "claude-home/.claude").absolutePath}",
            "-Dclaudetabs.fakeRoots=distroA=${File(e2eDir, "fake-root/.claude").absolutePath}",
            "-Dclaudetabs.disableWslDiscovery=true",
            // Pin the Windows-root launcher to the stub by ABSOLUTE path. The PATH prepend below
            // is kept as a convenience for anything typed by hand in a sandbox terminal, but it is
            // not load-bearing: an IDE shell-integration hiccup once swallowed it and the owned
            // tabs ran the machine's real CLI. This property cannot be swallowed.
            "-Dclaudetabs.claudeCommand=${File(e2eDir, "bin/claude.cmd").absolutePath}",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Djb.consents.confirmation.enabled=false",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Didea.trust.all.projects=true",
            "-Deap.require.license=false",
            // Registry override: keep the Windows Defender advisory toast out of the sandbox.
            "-Dignore.virus.scanning.warn.message=true",
        )
        environment("CLAUDE_STUB_HOME", File(e2eDir, "claude-home/.claude").absolutePath)
        environment("CLAUDE_STUB_LOG", File(e2eDir, "stub-argv.log").absolutePath)
        // Any `claude` typed/spawned in a sandbox terminal resolves to the stub, never the real CLI.
        environment("PATH", File(e2eDir, "bin").absolutePath + ";" + System.getenv("PATH"))
        args(File(e2eDir, "project").absolutePath)
    }
    plugins {
        robotServerPlugin()
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            // Verification IDE targets are supplied per-machine via
            //   ./gradlew verifyPlugin "-PverifierIdes=<install-dir>[;<install-dir>...]"
            // so no local installation paths live in the build file. Without the property the
            // task has no targets and reports so, which is the intended default.
            providers.gradleProperty("verifierIdes").orNull
                ?.split(';')
                ?.filter { it.isNotBlank() }
                ?.forEach { local(file(it)) }
        }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            // Forward-compatible with future IntelliJ versions. Setting the property to
            // a provider that resolves to null tells IPGP 2.x to omit the <until-build>
            // attribute entirely (rather than emitting an empty value, which fails the
            // Marketplace plugin descriptor validator with "does not match the multi-part
            // build number format").
            untilBuild = provider { null }
        }
    }
    instrumentCode = false
}

tasks {
    // Skip searchable-options indexing — the plugin has no settings UI worth indexing
    // and the task spins up a full IDE which slows builds significantly.
    named("buildSearchableOptions") {
        enabled = false
    }

    compileKotlin {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    compileTestKotlin {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    // Shared test logging — print test names + pass/fail + summary so you don't
    // have to go hunt through build/reports/tests/... after every run.
    val applyTestLogging: Test.() -> Unit = {
        val taskName = name
        // Always run — Gradle's up-to-date check otherwise skips the task when nothing
        // changed, which means no test summary prints. We want feedback every time.
        outputs.upToDateWhen { false }
        testLogging {
            events(
                org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
            )
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
        // Always print a summary at the end, even when nothing ran (UP-TO-DATE case).
        afterSuite(KotlinClosure2<TestDescriptor, TestResult, Void>({ desc, result ->
            if (desc.parent == null) { // root suite
                val total = result.testCount
                val passed = result.successfulTestCount
                val failed = result.failedTestCount
                val skipped = result.skippedTestCount
                val duration = (result.endTime - result.startTime) / 1000.0
                println("")
                println("── Test summary ($taskName) ──")
                println("  $total total | $passed passed | $failed failed | $skipped skipped | ${"%.2f".format(duration)}s")
                if (failed > 0) println("  Full report: build/reports/tests/$taskName/index.html")
                println("")
            }
            null
        }))
    }

    test {
        useJUnit()
        // Keep Layer 3b (Remote Robot UI tests) out of the default test run — they need a
        // running IDE and are slow. Run them via `./gradlew uiTest` instead.
        exclude("**/ui/**")
        applyTestLogging()
    }

    // Dedicated task for Remote Robot UI tests. Run via `./gradlew uiTest` after spinning
    // up an IDE sandbox separately (or use the platform 2.x runIdeForUiTests task).
    register<Test>("uiTest") {
        description = "Run Remote Robot UI tests. Requires an IDE sandbox to be running."
        group = "verification"
        useJUnit()
        // remote-robot's Gson deserializes server error envelopes containing a Throwable; on
        // JDK 17+ that reflection is sealed and every such response throws JsonIOException in
        // the CLIENT (masking the real server-side error). Open java.lang for the test JVM.
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
        include("**/ui/**")
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        shouldRunAfter("test")
        applyTestLogging()
    }
}
