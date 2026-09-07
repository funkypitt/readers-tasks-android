package com.freedomfighter.readerstasks.caldav

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class CalDavException(message: String) : IOException(message)

data class TaskList(val name: String, val url: String)
data class RemoteTask(val href: String, val etag: String?, val ics: String)

/**
 * The four CalDAV requests a task client needs, over HttpURLConnection:
 * PROPFIND (discovery), REPORT calendar-query (VTODO), PUT (new / changed), DELETE.
 * A straight port of the desktop client.
 */
class CalDav(baseUrl: String, private val username: String, private val password: String) {
    private val base = baseUrl.trim()
    private val auth = "Basic " + Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)

    private class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

    private fun request(method: String, url: String, body: String? = null, depth: Int? = null, headers: Map<String, String> = emptyMap(), contentType: String = "application/xml; charset=utf-8"): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
        } catch (e: java.net.ProtocolException) {
            // HttpURLConnection only knows the HTTP/1.1 verbs; WebDAV ones go through reflection.
            setMethodByReflection(c, method)
        }
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Authorization", auth)
        c.setRequestProperty("User-Agent", "readers-tasks-android")
        if (depth != null) c.setRequestProperty("Depth", depth.toString())
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", contentType) }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw CalDavException("wrong username or app password")
            if (code >= 400) throw CalDavException("$method: HTTP $code")
            return Resp(code, text, c.headerFields)
        } finally { c.disconnect() }
    }

    private fun setMethodByReflection(c: HttpURLConnection, method: String) {
        var target: Any = c
        // OkHttp-backed connections keep the real one in a "delegate" field.
        runCatching { val f = c.javaClass.getDeclaredField("delegate"); f.isAccessible = true; f.get(c)?.let { target = it } }
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try { val f = cls.getDeclaredField("method"); f.isAccessible = true; f.set(target, method); return } catch (_: NoSuchFieldException) { cls = cls.superclass }
        }
        throw CalDavException("cannot send $method on this device")
    }

    private fun resolve(href: String, against: String): String = URL(URL(against), href).toString()

    // ---- XML: a flat walk over multistatus responses -------------------------------------

    private class Response { var href: String? = null; var displayName: String? = null; var isCalendar = false; var comps = ArrayList<String>(); var etag: String? = null; var data: String? = null; var principal: String? = null; var home: String? = null }

    private fun parseMultistatus(xml: String): List<Response> {
        val out = ArrayList<Response>(); var cur: Response? = null
        val p = Xml.newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); p.setInput(xml.reader())
        val path = ArrayList<String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = p.name.lowercase(); path += name
                    when (name) {
                        "response" -> cur = Response().also { out += it }
                        "calendar" -> if (path.contains("resourcetype")) cur?.isCalendar = true
                        "comp" -> if (path.contains("supported-calendar-component-set")) p.getAttributeValue(null, "name")?.let { cur?.comps?.add(it.uppercase()) }
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = p.text?.trim().orEmpty(); if (t.isNotEmpty()) {
                        val parent = path.getOrNull(path.size - 1); val grand = path.getOrNull(path.size - 2)
                        when {
                            parent == "href" && grand == "response" -> cur?.href = t
                            parent == "href" && grand == "current-user-principal" -> cur?.principal = t
                            parent == "href" && grand == "calendar-home-set" -> cur?.home = t
                            parent == "displayname" -> cur?.displayName = t
                            parent == "getetag" -> cur?.etag = t
                            parent == "calendar-data" -> cur?.data = t
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (path.isNotEmpty()) path.removeAt(path.size - 1)
            }
            ev = p.next()
        }
        return out
    }

    private fun propfind(url: String, props: String, depth: Int): List<Response> {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop>$props</d:prop></d:propfind>"
        return parseMultistatus(request("PROPFIND", url, body, depth).body)
    }

    /** Discover the VTODO collections: principal → home → calendars, or the URL itself. */
    fun taskLists(): List<TaskList> {
        val candidates = ArrayList<String>()
        runCatching {
            val r = propfind(base, "<d:current-user-principal/><d:resourcetype/><c:calendar-home-set/>", 0).firstOrNull()
            var home = r?.home?.let { resolve(it, base) }
            val principal = r?.principal?.let { resolve(it, base) }
            if (home == null && principal != null) home = propfind(principal, "<c:calendar-home-set/>", 0).firstOrNull()?.home?.let { resolve(it, principal) }
            if (home != null) candidates += home
        }
        candidates += base
        for (home in candidates) {
            val lists = ArrayList<TaskList>()
            for (r in propfind(home, "<d:displayname/><d:resourcetype/><c:supported-calendar-component-set/>", 1)) {
                if (!r.isCalendar || r.href == null) continue
                if (r.comps.isNotEmpty() && "VTODO" !in r.comps) continue
                val url = resolve(r.href!!, home)
                lists += TaskList(r.displayName?.ifBlank { null } ?: url.trimEnd('/').substringAfterLast('/'), url)
            }
            if (lists.isNotEmpty()) return lists
        }
        throw CalDavException("no task list found at this address")
    }

    fun tasks(listUrl: String): List<RemoteTask> {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">" +
            "<d:prop><d:getetag/><c:calendar-data/></d:prop><c:filter><c:comp-filter name=\"VCALENDAR\"><c:comp-filter name=\"VTODO\"/></c:comp-filter></c:filter></c:calendar-query>"
        return parseMultistatus(request("REPORT", listUrl, body, 1).body)
            .filter { it.href != null && !it.data.isNullOrBlank() }
            .map { RemoteTask(resolve(it.href!!, listUrl), it.etag, it.data!!) }
    }

    fun put(url: String, ics: String, etag: String?, create: Boolean) {
        val h = HashMap<String, String>()
        if (create) h["If-None-Match"] = "*" else if (etag != null) h["If-Match"] = etag
        request("PUT", url, ics, headers = h, contentType = "text/calendar; charset=utf-8")
    }

    fun delete(url: String) { request("DELETE", url) }
}
