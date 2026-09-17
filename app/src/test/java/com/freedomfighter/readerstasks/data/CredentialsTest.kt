package com.freedomfighter.readerstasks.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CredentialsTest {
    private val section = "readers-tasks"
    private val keys = setOf("url", "username", "password")
    private val values = mapOf("url" to "https://sync.infomaniak.com", "username" to "AB12345", "password" to "p@ss \"quoted\" \\ é")

    @Test fun buildThenReadGivesTheSameValues() {
        val text = Credentials.build(section, values)
        assertTrue(text.contains("\"format\": \"readers-credentials\""))
        assertTrue(text.contains("\"version\": 1"))
        assertEquals(values, Credentials.read(text, section, keys))
    }

    @Test fun blankValuesAreLeftOut() {
        val text = Credentials.build(section, mapOf("url" to "https://x", "username" to "", "password" to " "))
        assertEquals(mapOf("url" to "https://x"), Credentials.read(text, section, keys))
    }

    @Test fun unknownKeysAndOtherSectionsAreIgnored() {
        // a desktop file: several apps, the calendar's Google connection, keys this app doesn't know
        val file: JsonObject = buildJsonObject {
            put("format", "readers-credentials"); put("version", 1)
            putJsonObject("readers-calendar") {
                put("url", "https://cal"); put("username", "c"); put("password", "cp")
                putJsonObject("google") { put("client_id", "id"); putJsonObject("tokens") { put("refresh_token", "r") } }
                put("subscriptions", buildJsonArray { add(buildJsonObject { put("name", "a"); put("url", "b") }) })
            }
            putJsonObject("readers-notes") { put("server", "https://dav"); put("folder", "Notes"); put("username", "n"); put("password", "np") }
            putJsonObject(section) {
                values.forEach { (k, v) -> put(k, v) }
                put("future_key", "zzz"); putJsonObject("nested") { put("a", 1) }; put("number", 42)
            }
        }
        assertEquals(values, Credentials.read(file.toString(), section, keys))
    }

    @Test fun aForeignFileIsRefused() {
        for (text in listOf("", "not json", "[1, 2]", "{\"hello\": 1}", "{\"format\": \"something-else\", \"readers-tasks\": {\"url\": \"u\"}}", "{\"format\": 1}")) {
            try { Credentials.read(text, section, keys); fail("accepted: $text") } catch (e: Credentials.NotCredentials) { }
        }
    }

    @Test fun aFileWithoutThisAppSaysSo() {
        for (text in listOf(
            "{\"format\": \"readers-credentials\", \"version\": 1, \"readers-notes\": {\"server\": \"s\"}}",
            "{\"format\": \"readers-credentials\", \"version\": 1, \"readers-tasks\": {}}",
            "{\"format\": \"readers-credentials\", \"version\": 1, \"readers-tasks\": {\"server_url\": \"x\", \"url\": 5}}",
            "{\"format\": \"readers-credentials\", \"version\": 1, \"readers-tasks\": \"not an object\"}")) {
            try { Credentials.read(text, section, keys); fail("accepted: $text") } catch (e: Credentials.NothingFor) { assertEquals(section, e.section) }
        }
    }
}
