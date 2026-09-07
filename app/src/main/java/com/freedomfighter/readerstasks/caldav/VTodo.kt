package com.freedomfighter.readerstasks.caldav

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** The little iCalendar we need: read a VTODO, write one, flip its completion. */
object VTodo {
    fun unfold(text: String): List<String> =
        text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split("\n")

    fun prop(lines: List<String>, name: String): String? {
        for (l in lines) {
            val key = l.substringBefore(":").substringBefore(";").uppercase()
            if (key == name) return if (":" in l) l.substringAfter(":") else ""
        }
        return null
    }

    fun unescape(v: String): String =
        v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    fun escape(v: String): String =
        v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    fun fold(line: String): String {
        val out = StringBuilder()
        var chunk = line.toByteArray(Charsets.UTF_8)
        var first = true
        while (chunk.size > 72) {
            var cut = 72
            while (cut > 0 && (chunk[cut].toInt() and 0xC0) == 0x80) cut--
            out.append(if (first) "" else "\r\n ").append(String(chunk, 0, cut, Charsets.UTF_8))
            chunk = chunk.copyOfRange(cut, chunk.size); first = false
        }
        out.append(if (first) "" else "\r\n ").append(String(chunk, Charsets.UTF_8))
        return out.toString()
    }

    fun utcNow(): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    fun parseDate(v: String?): LocalDate? {
        if (v == null) return null
        val m = Regex("(\\d{4})(\\d{2})(\\d{2})").find(v.trim()) ?: return null
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }

    fun newIcs(summary: String, due: LocalDate? = null): Pair<String, String> {
        val now = utcNow(); val uid = UUID.randomUUID().toString()
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//readers-tasks//EN", "BEGIN:VTODO",
            "UID:$uid", "DTSTAMP:$now", "CREATED:$now", "LAST-MODIFIED:$now", "SUMMARY:${escape(summary)}", "STATUS:NEEDS-ACTION")
        if (due != null) lines += "DUE;VALUE=DATE:" + due.toString().replace("-", "")
        lines += listOf("END:VTODO", "END:VCALENDAR")
        return uid to lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    private val completionProps = setOf("STATUS", "COMPLETED", "PERCENT-COMPLETE", "LAST-MODIFIED", "DTSTAMP")

    /** Rewrite the VTODO with completion set (or cleared), keeping every other property. */
    fun withCompletion(ics: String, completed: Boolean): String {
        val out = ArrayList<String>(); var inTodo = false
        for (l in unfold(ics)) {
            val u = l.uppercase()
            if (u.startsWith("BEGIN:VTODO")) inTodo = true
            if (inTodo && u.substringBefore(":").substringBefore(";") in completionProps) continue
            if (u.startsWith("END:VTODO")) {
                val now = utcNow()
                out += listOf("DTSTAMP:$now", "LAST-MODIFIED:$now")
                out += if (completed) listOf("STATUS:COMPLETED", "COMPLETED:$now", "PERCENT-COMPLETE:100") else listOf("STATUS:NEEDS-ACTION")
                inTodo = false
            }
            out += l
        }
        return out.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    /** Rewrite X-APPLE-SORT-ORDER (the de facto manual-order property). */
    fun withSortOrder(ics: String, value: Long): String {
        val out = ArrayList<String>(); var inTodo = false
        for (l in unfold(ics)) {
            val u = l.uppercase(); val key = u.substringBefore(":").substringBefore(";")
            if (u.startsWith("BEGIN:VTODO")) inTodo = true
            if (inTodo && key in setOf("X-APPLE-SORT-ORDER", "LAST-MODIFIED", "DTSTAMP")) continue
            if (u.startsWith("END:VTODO")) {
                val now = utcNow()
                out += listOf("DTSTAMP:$now", "LAST-MODIFIED:$now", "X-APPLE-SORT-ORDER:$value")
                inTodo = false
            }
            out += l
        }
        return out.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    /** Rewrite SUMMARY (and DUE if given; null clears it). */
    fun withSummary(ics: String, summary: String, due: LocalDate?, keepDue: Boolean): String {
        val out = ArrayList<String>(); var inTodo = false
        for (l in unfold(ics)) {
            val u = l.uppercase(); val key = u.substringBefore(":").substringBefore(";")
            if (u.startsWith("BEGIN:VTODO")) inTodo = true
            if (inTodo && (key == "SUMMARY" || key == "LAST-MODIFIED" || key == "DTSTAMP" || (key == "DUE" && !keepDue))) continue
            if (u.startsWith("END:VTODO")) {
                val now = utcNow()
                out += listOf("DTSTAMP:$now", "LAST-MODIFIED:$now", "SUMMARY:${escape(summary)}")
                if (!keepDue && due != null) out += "DUE;VALUE=DATE:" + due.toString().replace("-", "")
                inTodo = false
            }
            out += l
        }
        return out.joinToString("\r\n") { fold(it) } + "\r\n"
    }
}
