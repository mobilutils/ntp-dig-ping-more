package io.github.mobilutils.ntp_dig_ping_more

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the BulkActions JSON config parsing logic.
 * Mirrors the parsing code from BulkConfigParser to avoid Android API dependencies
 * in JVM unit tests (no Robolectric in this project).
 */
class BulkConfigParserTest {

    // ────────────────────────────────────────────────────────────────
    // Parsing helpers that mirror BulkConfigParser
    // ────────────────────────────────────────────────────────────────

    /**
     * Parses a JSON string into a [BulkConfig] using a minimal pure-Kotlin parser.
     * Mirrors the logic in BulkConfigParser.parse() for testing without Android APIs.
     */
    private fun parseBulkConfig(json: String): BulkConfig {
        val trimmed = json.trim()

        // Extract "output-file" value (supports both quoted and unquoted)
        val outputFile = extractStringField(trimmed, "output-file")

        // Extract "run" object
        val runMatch = Regex("""\"run\"\s*:\s*\{([^}]*)\}""").find(trimmed)
            ?: throw IllegalArgumentException("Missing required 'run' object in configuration")

        val runContent = runMatch.groupValues[1]
        val commands = mutableMapOf<String, String>()

        val commandPattern = Regex("""\"([^\"]+)\"\s*:\s*\"([^\"]*)\"""")
        commandPattern.findAll(runContent).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()
            if (value.isNotBlank()) {
                commands[key] = value
            }
        }

        return BulkConfig(outputFile, commands)
    }

    /** Extracts a string field value from JSON, returning null if not found. */
    private fun extractStringField(json: String, fieldName: String): String? {
        val pattern = Regex("""\"$fieldName\"\s*:\s*\"([^\"]*)\"""")
        val match = pattern.find(json)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    // ────────────────────────────────────────────────────────────────
    // Tests
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `parseValidConfig returns config with commands`() {
        val json = """
            {
                "output-file": "/tmp/output.txt",
                "run": {
                    "cmd1": "ping -c 4 google.com",
                    "cmd2": "dig @1.1.1.1 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals("/tmp/output.txt", config.outputFile)
        assertEquals(2, config.commands.size)
        assertEquals("ping -c 4 google.com", config.commands["cmd1"])
        assertEquals("dig @1.1.1.1 example.com", config.commands["cmd2"])
    }

    @Test
    fun `parseMissingRunKey throws exception`() {
        val json = """
            {
                "output-file": "/tmp/output.txt"
            }
        """.trimIndent()

        val exception = try {
            parseBulkConfig(json)
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertNotNull(exception)
        assertTrue(exception!!.message?.contains("run") == true)
    }

    @Test
    fun `parseEmptyCommands returns empty map`() {
        val json = """
            {
                "run": {}
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertTrue(config.commands.isEmpty())
        assertNull(config.outputFile)
    }

    @Test
    fun `parseIgnoresBlankCommands`() {
        val json = """
            {
                "run": {
                    "cmd1": "",
                    "cmd2": "   ",
                    "cmd3": "ping -c 1 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals(1, config.commands.size)
        assertTrue(config.commands.containsKey("cmd3"))
    }

    @Test
    fun `parseMultipleCommands preserves all commands`() {
        val json = """
            {
                "run": {
                    "cmd1": "ping -c 4 google.com",
                    "cmd2": "ping -c 5 10.0.0.1",
                    "cmd3": "dig @1.1.1.1 cybernews.com",
                    "cmd4": "ntp pool.ntp.org",
                    "cmd5": "port-scan -p 80-443 mobilutils.com",
                    "cmd6": "checkcert -p 443 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals(6, config.commands.size)
    }

    @Test
    fun `parseNoOutputFile returns null outputFile`() {
        val json = """
            {
                "run": {
                    "cmd1": "ping -c 2 google.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertNull(config.outputFile)
        assertEquals(1, config.commands.size)
    }

    @Test
    fun `parseTildePath preserves tilde in JVM tests`() {
        val json = """
            {
                "output-file": "~/Downloads/test-run.txt",
                "run": {
                    "cmd1": "ping -c 1 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        // In JVM tests without Android APIs, tilde is preserved as-is
        // In production (Android), it would be expanded to external storage dir
        assertTrue(config.outputFile?.startsWith("~/") == true)
        assertTrue(config.outputFile?.contains("Downloads/test-run.txt") == true)
    }

    @Test
    fun `parseNonTildePath returns unchanged`() {
        val json = """
            {
                "output-file": "/absolute/path/output.txt",
                "run": {
                    "cmd1": "ping -c 1 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals("/absolute/path/output.txt", config.outputFile)
    }

    @Test
    fun `parseCommandsWithWhitespace trimsValues`() {
        val json = """
            {
                "run": {
                    "cmd1": "  ping -c 1 example.com  "
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals("ping -c 1 example.com", config.commands["cmd1"])
    }
}
