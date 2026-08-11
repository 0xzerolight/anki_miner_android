package com.ankiminer.android.anki.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalSchemaFingerprintTest {
    @Test
    fun canonicalizationIgnoresOnlySqlFormattingWhitespace() {
        val compact = "CREATE TRIGGER exact_guard BEFORE UPDATE ON parents BEGIN SELECT 'keep  two'; END"
        val formatted =
            """
            CREATE   TRIGGER exact_guard
            BEFORE UPDATE ON parents
            BEGIN SELECT 'keep  two'; END
            """.trimIndent()

        assertEquals(JournalSchema.definitionHash(compact), JournalSchema.definitionHash(formatted))
        assertNotEquals(
            JournalSchema.definitionHash(compact),
            JournalSchema.definitionHash(compact.replace("keep  two", "keep two")),
        )
    }

    @Test
    fun definitionHashDetectsSameNameNoOpTriggersAndWrongPartialIndexes() {
        val realTrigger =
            "CREATE TRIGGER exact_guard BEFORE UPDATE ON parents BEGIN SELECT RAISE(ABORT, 'blocked'); END"
        val noOpTrigger = "CREATE TRIGGER exact_guard BEFORE UPDATE ON parents BEGIN SELECT 1; END"
        val realIndex = "CREATE UNIQUE INDEX exact_index ON media_leases((1)) WHERE state = 'ACTIVE'"
        val wrongIndex = "CREATE UNIQUE INDEX exact_index ON media_leases((1)) WHERE state = 'RELEASED'"

        assertNotEquals(JournalSchema.definitionHash(realTrigger), JournalSchema.definitionHash(noOpTrigger))
        assertNotEquals(JournalSchema.definitionHash(realIndex), JournalSchema.definitionHash(wrongIndex))
    }

    @Test
    fun definitionHashRejectsUnbalancedParenthesesOutsideQuotedText() {
        val malformed =
            "CREATE TRIGGER exact_guard BEFORE UPDATE ON parents " +
                "BEGIN SELECT CASE WHEN NOT (NEW.state = OLD.state THEN RAISE(ABORT, 'blocked)'); END; END"

        assertThrows(IllegalStateException::class.java) {
            JournalSchema.definitionHash(malformed)
        }

        JournalSchema.definitionHash(
            "CREATE TRIGGER exact_guard BEFORE UPDATE ON parents " +
                "BEGIN SELECT CASE WHEN NOT (NEW.state = OLD.state) THEN RAISE(ABORT, 'blocked)'); END; END",
        )
    }

    @Test
    fun expectedFingerprintOwnsEveryDeclaredProjectObject() {
        val definitions = JournalSchema.expectedDefinitionHashes
        assertEquals(JournalSchema.requiredTables, definitions.keys.filter { it.startsWith("table:") }.map { it.removePrefix("table:") }.toSet())
        assertEquals(JournalSchema.requiredTriggers, definitions.keys.filter { it.startsWith("trigger:") }.map { it.removePrefix("trigger:") }.toSet())
        assertEquals(JournalSchema.requiredIndexes, definitions.keys.filter { it.startsWith("index:") }.map { it.removePrefix("index:") }.toSet())
        assertTrue(definitions.values.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun migrationPlanRebuildsOnlyAuthenticVersionOneRemediations() {
        assertEquals(5, JournalSchema.VERSION)
        assertTrue(JournalSchema.requiresRemediationRebuild(1))
        assertTrue(!JournalSchema.requiresRemediationRebuild(2))
        assertTrue(!JournalSchema.requiresRemediationRebuild(3))
        assertTrue(!JournalSchema.requiresRemediationRebuild(4))
    }
}
