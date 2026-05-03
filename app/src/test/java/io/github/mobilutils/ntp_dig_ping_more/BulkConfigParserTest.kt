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

        // Extract "timeout" value (mirrors production parsing)
        val timeoutMs = extractLongField(trimmed, "timeout")?.takeIf { it > 0 }?.let { it * 1000L }

        // Extract "url-proxypac" value
        val urlProxyPac = extractStringField(trimmed, "url-proxypac")

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

        return BulkConfig(outputFile, commands, timeoutMs, urlProxyPac = urlProxyPac)
    }

    /** Extracts a long field value from JSON, returning null if not found. */
    private fun extractLongField(json: String, fieldName: String): Long? {
        val pattern = Regex("""\"$fieldName\"\s*:\s*(-?\d+)""")
        val match = pattern.find(json)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull()
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

    @Test
    fun `parseConfigWithTimeout returns timeoutMs`() {
        val json = """
            {
                "timeout": 60,
                "run": {
                    "cmd1": "ping -c 1 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals(60_000L, config.timeoutMs)
    }

    @Test
    fun `parseConfigWithoutTimeout returns null`() {
        val json = """
            {
                "run": {
                    "cmd1": "ping -c 1 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertNull(config.timeoutMs)
    }

    @Test
    fun `parseConfigWithZeroTimeout returns null`() {
        val json = """
            {
                "timeout": 0,
                "run": {
                    "cmd1": "ping -c 1 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertNull(config.timeoutMs)
    }

    @Test
    fun `parseConfigWithNegativeTimeout returns null`() {
        val json = """
            {
                "timeout": -10,
                "run": {
                    "cmd1": "ping -c 1 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertNull(config.timeoutMs)
    }

    @Test
    fun `extractCommandTimeoutWithValidValueReturnsMs`() {
        assertEquals(10_000L, BulkConfigParser.extractCommandTimeout("ping -c 4 -t 10 google.com"))
        assertEquals(30_000L, BulkConfigParser.extractCommandTimeout("dig @1.1.1.1 -t 30 example.com"))
        assertEquals(5_000L, BulkConfigParser.extractCommandTimeout("port-scan -p 80 -t 5 host"))
    }

    @Test
    fun `extractCommandTimeoutWithNoTFlagReturnsNull`() {
        assertNull(BulkConfigParser.extractCommandTimeout("ping -c 4 google.com"))
        assertNull(BulkConfigParser.extractCommandTimeout("device-info"))
    }

    @Test
    fun `extractCommandTimeoutWithZeroOrNegativeReturnsNull`() {
        assertNull(BulkConfigParser.extractCommandTimeout("ping -c 4 -t 0 google.com"))
        assertNull(BulkConfigParser.extractCommandTimeout("ping -c 4 -t -5 google.com"))
    }

    @Test
    fun `extractCommandTimeoutWithNonNumericReturnsNull`() {
        assertNull(BulkConfigParser.extractCommandTimeout("ping -c 4 -t abc google.com"))
    }


    @Test
    fun `bulkCommandClosed_isSubtypeOfBulkCommandResult`() {
        val result = BulkCommandClosed(
            commandName = "port-scan-test",
            command = "port-scan -p 443 10.0.0.1",
            outputLines = listOf("No open ports found."),
            durationMs = 2000L,
        )

         // Verify it's a valid BulkCommandResult
        assertTrue(result is BulkCommandResult)
        assertEquals("port-scan-test", result.commandName)
        assertEquals("port-scan -p 443 10.0.0.1", result.command)
     }

    @Test
    fun `bulkCommandClosed_containsOutputLinesAndDuration`() {
        val lines = listOf(
             "[2026-04-30 09:53:19] port-scan -p 443 10.0.0.1",
             "[2026-04-30 09:53:19] Status: CLOSED (2004ms)",
             "  No open ports found.",
        )
        val result = BulkCommandClosed(
            commandName = "test",
            command = "port-scan -p 443 10.0.0.1",
            outputLines = lines,
            durationMs = 2004L,
        )

        assertEquals(3, result.outputLines.size)
        assertEquals(2004L, result.durationMs)
        assertTrue(result.outputLines.any { "No open ports found" in it })
     }

    // ────────────────────────────────────────────────────────────────
    // url-proxypac tests
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `parseConfigWithUrlProxyPac returns value`() {
        val json = """
            {
                "url-proxypac": "http://proxy.corp.com/proxy.pac",
                "run": {
                    "cmd1": "checkcert -p 443 google.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals("http://proxy.corp.com/proxy.pac", config.urlProxyPac)
    }

    @Test
    fun `parseConfigWithoutUrlProxyPac returns null`() {
        val json = """
            {
                "run": {
                    "cmd1": "ping -c 1 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertNull(config.urlProxyPac)
    }

    @Test
    fun `parseConfigWithBlankUrlProxyPac returns null`() {
        val json = """
            {
                "url-proxypac": "",
                "run": {
                    "cmd1": "ping -c 1 example.com"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertNull(config.urlProxyPac)
    }

    @Test
    fun `parseConfigWithUrlProxyPacAndOtherFields preserves all`() {
        val json = """
            {
                "output-file": "/tmp/out.txt",
                "timeout": 30,
                "url-proxypac": "http://pac.example.org/auto.pac",
                "run": {
                    "cert": "checkcert -p 443 example.com",
                    "sync": "google-timesync"
                }
            }
        """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals("/tmp/out.txt", config.outputFile)
        assertEquals(30_000L, config.timeoutMs)
        assertEquals("http://pac.example.org/auto.pac", config.urlProxyPac)
        assertEquals(2, config.commands.size)
        assertEquals("checkcert -p 443 example.com", config.commands["cert"])
        assertEquals("google-timesync", config.commands["sync"])
    }


     // ────────────────────────────────────────────────────────────────
     // sleep pseudo-command parsing tests
      // ────────────────────────────────────────────────────────────────

      @Test
    fun `parseConfigWithSleepCommand_parsesCorrectly`() {
        val json = """
              {
                  "run": {
                      "wait-5s": "sleep 5"
                  }
              }
          """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals(1, config.commands.size)
        assertEquals("sleep 5", config.commands["wait-5s"])
     }

      @Test
    fun `parseConfigWithMultipleSleepCommands_parsesAll`() {
        val json = """
              {
                  "run": {
                      "ping-google": "ping -c 3 google.com",
                      "wait-5s": "sleep 5",
                      "dig-example": "dig @8.8.8.8 example.com",
                      "wait-short": "sleep 1"
                  }
              }
          """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals(4, config.commands.size)
        assertEquals("ping -c 3 google.com", config.commands["ping-google"])
        assertEquals("sleep 5", config.commands["wait-5s"])
        assertEquals("dig @8.8.8.8 example.com", config.commands["dig-example"])
        assertEquals("sleep 1", config.commands["wait-short"])
     }

      @Test
    fun `parseConfigWithMaxSleepValue_parsesCorrectly`() {
        val json = """
              {
                  "run": {
                      "long-wait": "sleep 3600"
                  }
              }
          """.trimIndent()

        val config = parseBulkConfig(json)

        assertEquals(1, config.commands.size)
        assertEquals("sleep 3600", config.commands["long-wait"])
     }
}
