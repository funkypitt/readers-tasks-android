package com.freedomfighter.readerstasks.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.freedomfighter.readerstasks.App

/**
 * What the launcher tile reads. Signature-protected, so only apps signed with the same key see it.
 *
 *  content://com.freedomfighter.readerstasks/lists            → _id, title, url
 *  content://com.freedomfighter.readerstasks/tasks?list=<url> → _id, href, title, due, completed
 *  update(tasks?list=<url>, {href, completed}) → 1 on success
 *  insert(tasks?list=<url>, {title})           → the tasks uri
 *
 * A query also triggers a background refresh when the cache is older than ten minutes, and
 * every change notifies AUTHORITY so observers re-read.
 */
class TasksProvider : ContentProvider() {
    private val app get() = context!!.applicationContext as App

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val store = app.store
        if (store.isStale() && app.prefs.settings.value.hasAccount) store.syncAll()
        return when (MATCHER.match(uri)) {
            LISTS -> MatrixCursor(arrayOf("_id", "title", "url")).apply {
                store.visibleLists().forEachIndexed { i, l -> addRow(arrayOf(i, l.name, l.url)) }
            }
            TASKS -> {
                val list = uri.getQueryParameter("list") ?: store.cache.value.currentList ?: ""
                MatrixCursor(arrayOf("_id", "href", "title", "due", "completed")).apply {
                    store.openTasks(list).forEachIndexed { i, t -> addRow(arrayOf(i, t.href, t.summary, t.due?.toString(), 0)) }
                }
            }
            else -> throw IllegalArgumentException("unknown uri $uri")
        }
    }

    override fun getType(uri: Uri): String = when (MATCHER.match(uri)) {
        LISTS -> "vnd.android.cursor.dir/vnd.readerstasks.list"
        else -> "vnd.android.cursor.dir/vnd.readerstasks.task"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (MATCHER.match(uri) != TASKS) return null
        val list = uri.getQueryParameter("list") ?: return null
        val title = values?.getAsString("title")?.trim().orEmpty()
        if (title.isEmpty()) return null
        return if (app.store.addBlocking(list, title)) uri else null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        if (MATCHER.match(uri) != TASKS) return 0
        val list = uri.getQueryParameter("list") ?: return 0
        val href = values?.getAsString("href") ?: return 0
        val completed = (values.getAsInteger("completed") ?: 1) != 0
        val task = (app.store.cache.value.tasks[list] ?: emptyList()).firstOrNull { it.href == href } ?: return 0
        return if (app.store.completeBlocking(list, task, completed)) 1 else 0
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.freedomfighter.readerstasks"
        val BASE: Uri = Uri.parse("content://$AUTHORITY")
        private const val LISTS = 1
        private const val TASKS = 2
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "lists", LISTS)
            addURI(AUTHORITY, "tasks", TASKS)
        }
    }
}
