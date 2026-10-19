package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DurableIoTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun writeAtomicReplacesAnExistingFile() {
        val f = File(tmp.root, "entry.json")
        DurableIo.writeAtomic(f, "one")
        DurableIo.writeAtomic(f, "two")
        assertEquals("two", DurableIo.readText(f))
        assertEquals(listOf("entry.json"), tmp.root.list()!!.toList())
    }

    /**
     * The flicker that put bound tabs out of the snapshot: several project loops rewriting one
     * entry while another reads it. The old Windows path was delete-then-rename, so a reader in
     * that instant found NO file. A reader must always see a complete file — either version.
     */
    @Test fun aConcurrentReaderNeverSeesAMissingOrTornFile() {
        val f = File(tmp.root, "entry.json")
        val versions = (0 until 200).map { "version-$it-" + "x".repeat(200) }
        DurableIo.writeAtomic(f, versions[0])
        val stop = AtomicBoolean(false)
        val misses = AtomicInteger(0)
        val torn = AtomicInteger(0)
        val reads = AtomicInteger(0)
        val readers = (1..3).map {
            Thread {
                while (!stop.get()) {
                    reads.incrementAndGet()
                    val text = try { DurableIo.readText(f) } catch (_: Exception) { misses.incrementAndGet(); continue }
                    if (text !in versions) torn.incrementAndGet()
                }
            }.apply { start() }
        }
        val writers = (1..2).map { w ->
            Thread {
                for (i in 1 until versions.size) {
                    if (i % 2 != w % 2) continue
                    DurableIo.writeAtomic(f, versions[i])
                }
            }.apply { start() }
        }
        writers.forEach { it.join() }
        stop.set(true)
        readers.forEach { it.join() }
        assertTrue("readers must have exercised the race (${reads.get()} reads)", reads.get() > 100)
        assertEquals("a reader found the file missing during a rewrite", 0, misses.get())
        assertEquals("a reader saw a torn file", 0, torn.get())
        assertTrue(DurableIo.readText(f) in versions)
    }
}
