package com.freedomfighter.readerstasks.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.freedomfighter.readerstasks.App
import com.freedomfighter.readerstasks.R
import com.freedomfighter.readerstasks.data.TaskRow
import kotlin.concurrent.thread

/** Standard home-screen widgets for any launcher: the first open task of the current list, or the list. */
object TasksWidgets {
    private const val PKG = "com.freedomfighter.readerstasks"
    const val ACTION_ADD = "$PKG.ADD"
    const val ACTION_COMPLETE = "$PKG.widget.COMPLETE"

    /** The list open in the app, else the first visible one. */
    fun currentList(context: Context): Pair<String, String>? {
        val store = (context.applicationContext as App).store
        val lists = store.visibleLists()
        val cur = store.cache.value.currentList
        val l = lists.firstOrNull { it.url == cur } ?: lists.firstOrNull() ?: return null
        return l.name to l.url
    }
    fun tasks(context: Context): List<TaskRow> = currentList(context)?.let { (context.applicationContext as App).store.openTasks(it.second) } ?: emptyList()

    fun addIntent(): Intent = Intent(ACTION_ADD).setClassName(PKG, "$PKG.MainActivity")
    fun openApp(): Intent = Intent(Intent.ACTION_MAIN).setClassName(PKG, "$PKG.MainActivity")
    fun completeIntent(context: Context, href: String): Intent = Intent(context, LineWidget::class.java).setAction(ACTION_COMPLETE).setData(Uri.parse("task:$href"))

    fun renderLine(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_line)
        WidgetUi.paint(views, context, intArrayOf(R.id.widget_title, R.id.widget_plus, R.id.widget_box), intArrayOf(R.id.widget_sub))
        val list = currentList(context)
        val open = tasks(context)
        val first = open.firstOrNull()
        when {
            list == null -> { views.setTextViewText(R.id.widget_title, context.getString(R.string.account_needed)); views.setTextViewText(R.id.widget_sub, context.getString(R.string.lists)) }
            first == null -> { views.setTextViewText(R.id.widget_title, context.getString(R.string.no_open_task)); views.setTextViewText(R.id.widget_sub, list.first) }
            else -> {
                views.setViewVisibility(R.id.widget_box, View.VISIBLE)
                views.setOnClickPendingIntent(R.id.widget_box, WidgetUi.broadcast(context, completeIntent(context, first.href), first.href.hashCode()))
                views.setTextViewText(R.id.widget_title, first.summary.ifBlank { "…" })
                views.setTextViewText(R.id.widget_sub, list.first + (if (open.size > 1) "   1/${open.size}" else ""))
            }
        }
        views.setOnClickPendingIntent(R.id.widget_body, WidgetUi.activity(context, openApp(), 1))
        views.setOnClickPendingIntent(R.id.widget_plus, WidgetUi.activity(context, addIntent(), 2))
        mgr.updateAppWidget(id, views)
    }

    fun renderList(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_list)
        WidgetUi.paint(views, context, intArrayOf(R.id.widget_plus), intArrayOf(R.id.widget_caption, R.id.widget_empty))
        val list = currentList(context)
        views.setTextViewText(R.id.widget_caption, list?.first ?: context.getString(R.string.lists))
        views.setTextViewText(R.id.widget_empty, if (list == null) context.getString(R.string.account_needed) else context.getString(R.string.no_open_task))
        val svc = Intent(context, ListService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).apply { data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) }
        views.setRemoteAdapter(R.id.widget_list, svc)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        // the box of an item completes it (a broadcast filled in with the task); the title opens the app
        views.setPendingIntentTemplate(R.id.widget_list, WidgetUi.broadcast(context, Intent(context, ListWidget::class.java).setAction(ACTION_COMPLETE), 3, mutable = true))
        views.setOnClickPendingIntent(R.id.widget_caption, WidgetUi.activity(context, openApp(), 1))
        views.setOnClickPendingIntent(R.id.widget_plus, WidgetUi.activity(context, addIntent(), 2))
        mgr.updateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    fun refresh(context: Context) = WidgetUi.refresh(context, LineWidget::class.java, ListWidget::class.java)

    /** Complete the task named by the intent's data ("task:<href>"), then redraw. */
    fun handleComplete(context: Context, intent: Intent, receiver: AppWidgetProvider) {
        val href = intent.data?.schemeSpecificPart ?: return
        if (intent.data?.scheme == "app") { context.startActivity(openApp().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        val app = context.applicationContext as App
        val list = currentList(context) ?: return
        val task = app.store.openTasks(list.second).firstOrNull { it.href == href } ?: return
        val pending = receiver.goAsync()
        thread {
            runCatching { app.store.completeBlocking(list.second, task, true) }
            refresh(context)
            pending.finish()
        }
    }
}

class LineWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { TasksWidgets.renderLine(context, mgr, it) } }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == TasksWidgets.ACTION_COMPLETE) TasksWidgets.handleComplete(context, intent, this)
    }
}

class ListWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { TasksWidgets.renderList(context, mgr, it) } }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == TasksWidgets.ACTION_COMPLETE) TasksWidgets.handleComplete(context, intent, this)
    }
}

class ListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var items: List<TaskRow> = emptyList()
        override fun onCreate() {}
        override fun onDataSetChanged() { items = TasksWidgets.tasks(applicationContext) }
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getViewAt(i: Int): RemoteViews {
            val t = items[i]
            val v = RemoteViews(packageName, R.layout.widget_item)
            val (_, fg, dim) = WidgetUi.colors(applicationContext)
            v.setViewVisibility(R.id.item_box, View.VISIBLE); v.setTextColor(R.id.item_box, fg)
            v.setTextViewText(R.id.item_title, t.summary.ifBlank { "…" }); v.setTextColor(R.id.item_title, fg)
            val due = t.due?.let { d -> val today = java.time.LocalDate.now(); when { d == today -> getString(R.string.due_today); d == today.plusDays(1) -> getString(R.string.due_tomorrow); d.isBefore(today) -> getString(R.string.due_late, java.time.temporal.ChronoUnit.DAYS.between(d, today).toInt()); else -> d.toString() } } ?: ""
            v.setTextViewText(R.id.item_sub, due); v.setTextColor(R.id.item_sub, dim)
            v.setViewVisibility(R.id.item_sub, if (due.isEmpty()) View.GONE else View.VISIBLE)
            v.setOnClickFillInIntent(R.id.item_box, Intent().setData(Uri.parse("task:${t.href}")))
            v.setOnClickFillInIntent(R.id.item_body, Intent().setData(Uri.parse("app:open")))
            return v
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(i: Int) = items.getOrNull(i)?.href?.hashCode()?.toLong() ?: i.toLong()
        override fun hasStableIds() = true
    }
}
