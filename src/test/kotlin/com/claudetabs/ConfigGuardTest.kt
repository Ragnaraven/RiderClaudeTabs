package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [ConfigGuard] — the self-healing guard for claude's global config.
 *
 * Regression focus: claude's own non-atomic read-modify-write cycles interleave under
 * process churn and leave the file as a valid JSON document followed by trailing garbage
 * (e.g. a stray `}`). Every subsequent claude launch then aborts with a configuration
 * error. The guard must detect stable corruption, prefer the newest-data repair (strip
 * the garbage), fall back to a last-good mirror, and never destroy data it can't fix.
 */
class ConfigGuardTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var config: File
    private lateinit var lastGood: File
    private lateinit var guard: ConfigGuard

    private fun setUp() {
        config = File(tmp.root, "config.json")
        lastGood = File(tmp.root, "config.json.last-good")
        guard = ConfigGuard(config, lastGood)
    }

    // ── isValidJson ────────────────────────────────────────────────────────

    @Test fun isValidJson_acceptsRealisticConfigShape() {
        assertTrue(
            ConfigGuard.isValidJson(
                """{"projects":{"/path/to/project":{"history":[{"display":"hi","pastedContents":{}}]}},"count":3,"flag":true,"none":null,"pi":-1.5e2}"""
            )
        )
    }

    @Test fun isValidJson_acceptsAllTopLevelValueTypes() {
        for (doc in listOf("{}", "[]", "\"s\"", "42", "-0.5", "true", "false", "null", "  {\"a\":[1,2]}  \n"))
            assertTrue(doc, ConfigGuard.isValidJson(doc))
    }

    @Test fun isValidJson_rejectsBrokenDocuments() {
        for (doc in listOf(
            "", " ", "{", "}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[1 2]",
            "{'a':1}", "{\"a\":01}", "\"unterminated", "{\"a\":1}}", "{\"a\":1} garbage",
            "tru", "nul", "+1", "1.", "1e", "\"bad\\x\"",
        )) assertFalse(doc, ConfigGuard.isValidJson(doc))
    }

    @Test fun isValidJson_rejectsRawControlCharInString() {
        assertFalse(ConfigGuard.isValidJson("\"ctrl" + '\u0001' + "char\""))
        assertFalse(ConfigGuard.isValidJson("\"tab\tchar\""))
    }

    @Test fun isValidJson_validatesUnicodeEscapes() {
        assertTrue(ConfigGuard.isValidJson("\"\\u00e9\""))
        assertFalse(ConfigGuard.isValidJson("\"\\u00g9\""))
        assertFalse(ConfigGuard.isValidJson("\"\\u00e"))
    }

    // ── stripTrailingGarbage ───────────────────────────────────────────────

    @Test fun strip_removesStrayBrace_theInterleavedWriterSignature() {
        // The exact observed corruption shape: full valid document + a stray `}` from the
        // longer loser of a concurrent-writer race.
        assertEquals("""{"a":{"b":1}}""", ConfigGuard.stripTrailingGarbage("""{"a":{"b":1}}}"""))
    }

    @Test fun strip_removesLongerTail() {
        assertEquals("""{"a":1}""", ConfigGuard.stripTrailingGarbage("""{"a":1},"c":2}}"""))
    }

    @Test fun strip_returnsNullWhenAlreadyValid() {
        assertNull(ConfigGuard.stripTrailingGarbage("""{"a":1}"""))
        assertNull(ConfigGuard.stripTrailingGarbage("  {\"a\":1}  "))
    }

    @Test fun strip_returnsNullWhenDocumentItselfBroken() {
        assertNull(ConfigGuard.stripTrailingGarbage("""{"a":"""))
        assertNull(ConfigGuard.stripTrailingGarbage(""))
        assertNull(ConfigGuard.stripTrailingGarbage("}garbage"))
    }

    // ── check(): valid path + mirror ───────────────────────────────────────

    @Test fun check_missingFile_isValid() {
        setUp()
        assertEquals(ConfigGuard.Status.VALID, guard.check())
        assertFalse(lastGood.exists())
    }

    @Test fun check_validFile_mirrorsToLastGood() {
        setUp()
        config.writeText("""{"a":1}""")
        assertEquals(ConfigGuard.Status.VALID, guard.check())
        assertEquals("""{"a":1}""", lastGood.readText())
    }

    @Test fun check_mirrorTracksNewestValidContent() {
        setUp()
        config.writeText("""{"v":1}""")
        guard.check()
        config.writeText("""{"v":2}""")
        guard.check()
        assertEquals("""{"v":2}""", lastGood.readText())
    }

    // ── check(): strike behavior ───────────────────────────────────────────

    @Test fun check_firstInvalidRead_isSuspect_fileUntouched() {
        setUp()
        val corrupt = """{"a":1}}"""
        config.writeText(corrupt)
        assertEquals(ConfigGuard.Status.SUSPECT, guard.check())
        assertEquals(corrupt, config.readText()) // no repair on one strike — could be mid-write
    }

    @Test fun check_validReadResetsStrikes() {
        setUp()
        config.writeText("""{"a":1}}""")
        assertEquals(ConfigGuard.Status.SUSPECT, guard.check())
        config.writeText("""{"a":1}""") // writer finished — corruption was transient
        assertEquals(ConfigGuard.Status.VALID, guard.check())
        config.writeText("""{"a":2}}""") // new corruption must need a fresh 2 strikes
        assertEquals(ConfigGuard.Status.SUSPECT, guard.check())
    }

    // ── check(): repair paths ──────────────────────────────────────────────

    @Test fun check_stableTrailingGarbage_isStripped_newestDataSurvives() {
        setUp()
        config.writeText("""{"projects":{"/path/to/project":{}},"newest":true}}""")
        assertEquals(ConfigGuard.Status.SUSPECT, guard.check())
        assertEquals(ConfigGuard.Status.REPAIRED_STRIPPED, guard.check())
        assertEquals("""{"projects":{"/path/to/project":{}},"newest":true}""", config.readText())
        assertTrue(ConfigGuard.isValidJson(config.readText()))
        // The repaired content becomes the new last-good snapshot.
        assertEquals(config.readText(), lastGood.readText())
    }

    @Test fun check_brokenDocument_restoresFromLastGoodMirror() {
        setUp()
        config.writeText("""{"good":true}""")
        guard.check() // seeds the mirror
        config.writeText("""{"truncated":""")
        assertEquals(ConfigGuard.Status.SUSPECT, guard.check())
        assertEquals(ConfigGuard.Status.REPAIRED_RESTORED, guard.check())
        assertEquals("""{"good":true}""", config.readText())
    }

    @Test fun check_stripPreferredOverMirror_whenBothApply() {
        setUp()
        config.writeText("""{"old":true}""")
        guard.check() // mirror = old content
        config.writeText("""{"new":true}}""") // strippable AND mirror exists
        guard.check()
        assertEquals(ConfigGuard.Status.REPAIRED_STRIPPED, guard.check())
        // Newest data wins — the guard must not roll back to the older mirror.
        assertEquals("""{"new":true}""", config.readText())
    }

    @Test fun check_brokenDocumentNoMirror_isUnrepairable_fileLeftUntouched() {
        setUp()
        val corrupt = """{"truncated":"""
        config.writeText(corrupt)
        guard.check()
        assertEquals(ConfigGuard.Status.UNREPAIRABLE, guard.check())
        assertEquals(corrupt, config.readText()) // never destroy what we can't fix
    }

    @Test fun check_selfHealedBeforeRepair_isValid_noRewrite() {
        setUp()
        config.writeText("""{"a":1}}""")
        guard.check() // strike 1
        config.writeText("""{"a":1,"b":2}""") // writer finished between polls
        assertEquals(ConfigGuard.Status.VALID, guard.check())
        assertEquals("""{"a":1,"b":2}""", config.readText())
    }
}
