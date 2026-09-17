package com.freedomfighter.readerstasks.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Reader's credentials file, shared by the phone and desktop apps: one JSON object with
 * "format": "readers-credentials", "version": 1 and a section per app ("readers-tasks": {url,
 * username, password}, "readers-notes": {…}, …). An app writes only its own section and reads only
 * its own section's keys it knows; everything else in the file is left alone.
 */
object Credentials {
    const val FORMAT = "readers-credentials"
    const val VERSION = 1

    /** Not JSON, or JSON that is not a credentials file. */
    class NotCredentials : Exception("not a Reader's credentials file")
    /** A credentials file with nothing this app can use. */
    class NothingFor(val section: String) : Exception("this file holds nothing for $section")

    private val json = Json { prettyPrint = true }

    /** A file holding only this app's section; blank values are left out. */
    fun build(section: String, values: Map<String, String>): String {
        val obj = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put(section, buildJsonObject { values.filterValues { it.isNotBlank() }.forEach { (k, v) -> put(k, v) } })
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** This app's known keys from a credentials file (text values only; unknown keys ignored). */
    fun read(text: String, section: String, keys: Set<String>): Map<String, String> {
        val root: JsonElement = try { Json.parseToJsonElement(text) } catch (e: Exception) { throw NotCredentials() }
        val obj = root as? JsonObject ?: throw NotCredentials()
        val format = (obj["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != FORMAT) throw NotCredentials()
        val sec = obj[section] as? JsonObject ?: throw NothingFor(section)
        val out = LinkedHashMap<String, String>()
        for (k in keys) {
            val v = sec[k] as? JsonPrimitive ?: continue
            if (v.isString && v.content.isNotEmpty()) out[k] = v.content
        }
        if (out.isEmpty()) throw NothingFor(section)
        return out
    }
}
