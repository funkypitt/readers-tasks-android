package com.freedomfighter.readerstasks.data

import android.content.Context
import android.util.Log
import com.freedomfighter.readerstasks.caldav.CalDav
import com.freedomfighter.readerstasks.caldav.CalDavException
import com.freedomfighter.readerstasks.caldav.RemoteTask
import com.freedomfighter.readerstasks.caldav.VTodo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

@Serializable
data class ListInfo(val name: String, val url: String, val hidden: Boolean = false)

@Serializable
data class TaskRow(val href: String, val etag: String? = null, val ics: String) {
    val lines: List<String> by lazy { VTodo.unfold(ics) }
    val uid: String get() = VTodo.prop(lines, "UID") ?: href
    val summary: String get() = VTodo.unescape(VTodo.prop(lines, "SUMMARY") ?: "")
    val completed: Boolean get() = (VTodo.prop(lines, "STATUS") ?: "").uppercase() == "COMPLETED" || VTodo.prop(lines, "COMPLETED") != null
    val cancelled: Boolean get() = (VTodo.prop(lines, "STATUS") ?: "").uppercase() == "CANCELLED"
    val due: LocalDate? get() = VTodo.parseDate(VTodo.prop(lines, "DUE"))
    val created: String get() = VTodo.prop(lines, "CREATED") ?: VTodo.prop(lines, "DTSTAMP") ?: ""
    val completedAt: String get() = VTodo.prop(lines, "COMPLETED") ?: ""
    val isSubtask: Boolean get() = lines.any { it.uppercase().startsWith("RELATED-TO") && "RELTYPE=CHILD" !in it.uppercase() }
    val sortOrder: Long? get() = VTodo.prop(lines, "X-APPLE-SORT-ORDER")?.trim()?.toLongOrNull()
}

@Serializable
data class Cache(
    val lists: List<ListInfo> = emptyList(),
    val tasks: Map<String, List<TaskRow>> = emptyMap(),
    val syncedAt: Long = 0L,
    val currentList: String? = null
)

sealed class SyncState {
    data object Idle : SyncState()
    data object Running : SyncState()
    data class Error(val message: String) : SyncState()
}

/**
 * Local cache of every list and task (one JSON file), refreshed from the server on demand.
 * Actions go to the server first and then refresh the list they touched. Order of lists =
 * order in the cache; hiding is a flag.
 */
class Store(private val context: Context, private val prefs: Prefs) {
    private val file = File(context.filesDir, "cache.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _cache = MutableStateFlow(load())
    val cache: StateFlow<Cache> = _cache
    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync

    /** Called after any change so the content provider's clients (the launcher tile) refresh. */
    var onChanged: () -> Unit = {}

    private fun load(): Cache = try {
        if (file.exists()) json.decodeFromString(Cache.serializer(), file.readText()) else Cache()
    } catch (e: Exception) { Log.w(TAG, "cache unreadable", e); Cache() }

    @Synchronized
    private fun update(transform: (Cache) -> Cache) {
        val next = transform(_cache.value)
        if (next == _cache.value) return
        _cache.value = next
        runCatching { file.writeText(json.encodeToString(Cache.serializer(), next)) }
        onChanged()
    }

    private fun client(): CalDav {
        val s = prefs.settings.value
        if (!s.hasAccount) throw CalDavException("no account")
        return CalDav(s.serverUrl, s.username, s.password)
    }

    fun visibleLists(): List<ListInfo> = _cache.value.lists.filter { !it.hidden }
    fun openTasks(listUrl: String): List<TaskRow> =
        (_cache.value.tasks[listUrl] ?: emptyList()).filter { !it.completed && !it.cancelled && !it.isSubtask }
            // Manual order first (X-APPLE-SORT-ORDER), then the rest by due date and creation.
            .sortedWith(compareBy({ it.sortOrder == null }, { it.sortOrder ?: 0L }, { it.due == null }, { it.due ?: LocalDate.MAX }, { it.created }))
    fun doneTasks(listUrl: String): List<TaskRow> =
        (_cache.value.tasks[listUrl] ?: emptyList()).filter { it.completed }.sortedByDescending { it.completedAt }

    fun setCurrentList(url: String) = update { it.copy(currentList = url) }
    fun setHidden(url: String, hidden: Boolean) = update { c -> c.copy(lists = c.lists.map { if (it.url == url) it.copy(hidden = hidden) else it }) }
    fun move(url: String, delta: Int) = update { c ->
        val l = c.lists.toMutableList(); val i = l.indexOfFirst { it.url == url }; val j = i + delta
        if (i < 0 || j !in l.indices) c else { val x = l.removeAt(i); l.add(j, x); c.copy(lists = l) }
    }

    fun isStale(maxAgeMs: Long = 10 * 60_000L) = System.currentTimeMillis() - _cache.value.syncedAt > maxAgeMs

    /** Refresh lists and every list's tasks. */
    fun syncAll() = launchSync {
        val c = client()
        val remote = c.taskLists()
        val known = _cache.value.lists.associateBy { it.url }
        val lists = remote.map { known[it.url]?.copy(name = it.name) ?: ListInfo(it.name, it.url) }
            .sortedBy { l -> _cache.value.lists.indexOfFirst { it.url == l.url }.let { if (it < 0) Int.MAX_VALUE else it } }
        val tasks = HashMap<String, List<TaskRow>>()
        for (l in lists) tasks[l.url] = c.tasks(l.url).map { it.toRow() }
        update { it.copy(lists = lists, tasks = tasks, syncedAt = System.currentTimeMillis(), currentList = it.currentList ?: lists.firstOrNull()?.url) }
    }

    fun syncList(url: String) = launchSync { refreshList(client(), url) }

    private fun refreshList(c: CalDav, url: String) {
        val rows = c.tasks(url).map { it.toRow() }
        update { it.copy(tasks = it.tasks + (url to rows), syncedAt = System.currentTimeMillis()) }
    }

    fun add(listUrl: String, title: String, due: LocalDate? = null) = launchSync {
        val c = client(); val (uid, ics) = VTodo.newIcs(title, due)
        c.put(listUrl.trimEnd('/') + "/" + uid + ".ics", ics, null, create = true)
        refreshList(c, listUrl)
    }

    fun setCompleted(listUrl: String, task: TaskRow, completed: Boolean) {
        // Optimistic: the row moves at once; the server confirms on the next refresh.
        update { c -> c.copy(tasks = c.tasks + (listUrl to (c.tasks[listUrl] ?: emptyList()).map { if (it.href == task.href) it.copy(ics = VTodo.withCompletion(it.ics, completed), etag = null) else it })) }
        launchSync { val c = client(); c.put(task.href, VTodo.withCompletion(task.ics, completed), task.etag, create = false); refreshList(c, listUrl) }
    }

    fun rename(listUrl: String, task: TaskRow, title: String, due: LocalDate?, keepDue: Boolean) = launchSync {
        val c = client(); c.put(task.href, VTodo.withSummary(task.ics, title, due, keepDue), task.etag, create = false); refreshList(c, listUrl)
    }

    fun delete(listUrl: String, task: TaskRow) {
        update { c -> c.copy(tasks = c.tasks + (listUrl to (c.tasks[listUrl] ?: emptyList()).filterNot { it.href == task.href })) }
        launchSync { val c = client(); c.delete(task.href); refreshList(c, listUrl) }
    }

    /**
     * Tasks in their wanted order → the sort values that must change. Existing values are kept
     * when they already increase along the list; a moved task gets a value between its
     * neighbours; when no gap is left everything is renumbered.
     */
    fun planSortOrders(ordered: List<TaskRow>): Map<TaskRow, Long> {
        val values = ordered.map { it.sortOrder }
        if (values.all { it != null } && values.zipWithNext().all { (a, b) -> a!! < b!! }) return emptyMap()
        val changes = LinkedHashMap<TaskRow, Long>()
        var prev: Long? = null
        for ((i, t) in ordered.withIndex()) {
            val next = ordered.drop(i + 1).firstOrNull { it.sortOrder != null && it !in changes }?.sortOrder
            val v = t.sortOrder
            if (v != null && (prev == null || v > prev) && (next == null || v < next)) { prev = v; continue }
            val lo = prev ?: 0L
            val hi = next ?: (lo + 2000)
            if (hi - lo < 2) return ordered.withIndex().associate { (j, x) -> x to (j + 1) * 1000L }
            val mid = (lo + hi) / 2
            changes[t] = mid; prev = mid
        }
        return changes
    }

    /** Apply a new manual order of the open tasks of a list: local first, then the server. */
    fun reorder(listUrl: String, ordered: List<TaskRow>) {
        val changes = planSortOrders(ordered)
        if (changes.isEmpty()) return
        update { c -> c.copy(tasks = c.tasks + (listUrl to (c.tasks[listUrl] ?: emptyList()).map { row ->
            changes[row]?.let { row.copy(ics = VTodo.withSortOrder(row.ics, it), etag = null) } ?: row })) }
        launchSync {
            val c = client()
            for ((t, v) in changes) c.put(t.href, VTodo.withSortOrder(t.ics, v), t.etag, create = false)
            refreshList(c, listUrl)
        }
    }

    /** Blocking variants for the content provider (already on a binder thread). */
    fun addBlocking(listUrl: String, title: String): Boolean = runCatching {
        val c = client(); val (uid, ics) = VTodo.newIcs(title); c.put(listUrl.trimEnd('/') + "/" + uid + ".ics", ics, null, true); refreshList(c, listUrl); true
    }.getOrElse { Log.w(TAG, "add failed", it); false }

    fun completeBlocking(listUrl: String, task: TaskRow, completed: Boolean): Boolean = runCatching {
        val c = client(); c.put(task.href, VTodo.withCompletion(task.ics, completed), task.etag, false); refreshList(c, listUrl); true
    }.getOrElse { Log.w(TAG, "complete failed", it); false }

    private fun launchSync(block: suspend () -> Unit) {
        scope.launch {
            lock.withLock {
                _sync.value = SyncState.Running
                try { block(); _sync.value = SyncState.Idle } catch (e: Exception) {
                    Log.w(TAG, "sync failed", e); _sync.value = SyncState.Error(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun RemoteTask.toRow() = TaskRow(href, etag, ics)

    companion object { const val TAG = "Store" }
}
