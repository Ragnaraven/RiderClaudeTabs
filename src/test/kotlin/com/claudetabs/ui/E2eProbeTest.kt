package com.claudetabs.ui

import com.intellij.remoterobot.RemoteRobot
import org.junit.Test

/** Scratch diagnostics for harness bring-up: surface the ACTUAL client exception that the
 *  connect() retry loop swallows. Not part of the driver's phases. */
class E2eProbeTest {

    @Test fun probeCallJs() {
        val robot = RemoteRobot("http://127.0.0.1:8082")
        try {
            val r = robot.callJs<Boolean>("true")
            println("[probe] callJs(true) OK -> $r")
        } catch (e: Throwable) {
            println("[probe] callJs FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
        }
        try {
            val n = robot.callJs<Int>(
                "com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length",
                true,
            )
            println("[probe] openProjects -> $n")
        } catch (e: Throwable) {
            println("[probe] openProjects FAILED: ${e.javaClass.name}: ${e.message}")
        }
    }
}
