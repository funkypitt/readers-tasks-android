package com.freedomfighter.readerstasks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readerstasks.App
import com.freedomfighter.readerstasks.R
import com.freedomfighter.readerstasks.data.Align
import com.freedomfighter.readerstasks.data.FontChoice
import com.freedomfighter.readerstasks.data.ListInfo
import com.freedomfighter.readerstasks.data.SyncState
import com.freedomfighter.readerstasks.data.TaskRow
import com.freedomfighter.readerstasks.data.TextSize
import com.freedomfighter.readerstasks.data.ThemeMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

sealed class Screen {
    data object Tasks : Screen()
    data object Lists : Screen()
    data object Settings : Screen()
    data object Account : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Tasks)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
}

@Composable
fun dueLabel(due: LocalDate?): String {
    if (due == null) return ""
    val days = ChronoUnit.DAYS.between(LocalDate.now(), due)
    return when {
        days == 0L -> stringResource(R.string.due_today)
        days == 1L -> stringResource(R.string.due_tomorrow)
        days < 0L -> stringResource(R.string.due_late, -days)
        days < 7L -> due.format(DateTimeFormatter.ofPattern("EEEE")).lowercase()
        else -> due.format(DateTimeFormatter.ofPattern("d MMM")).lowercase()
    }
}

/** The list of open tasks of the current list — the main screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(nav: Nav, app: App) {
    val cache by app.store.cache.collectAsState()
    val sync by app.store.sync.collectAsState()
    val settings by app.prefs.settings.collectAsState()
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val tick = rememberTick()
    val listUrl = cache.currentList ?: cache.lists.firstOrNull { !it.hidden }?.url
    val list = cache.lists.firstOrNull { it.url == listUrl }
    val open = if (listUrl != null) app.store.openTasks(listUrl) else emptyList()
    val done = if (listUrl != null) app.store.doneTasks(listUrl) else emptyList()
    var showDone by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? com.freedomfighter.readerstasks.MainActivity
    LaunchedEffect(activity?.addRequests) { if (app.pendingAdd) { app.pendingAdd = false; adding = true } }
    var menuFor by remember { mutableStateOf<TaskRow?>(null) }
    var renameFor by remember { mutableStateOf<TaskRow?>(null) }
    var pageMenu by remember { mutableStateOf(false) }

    LaunchedEffect(settings.hasAccount) { if (settings.hasAccount && app.store.isStale()) app.store.syncAll() }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(
                title = (list?.name ?: stringResource(R.string.app_name)) + "  ▾",
                onBack = null,
                trailing = "⋯",
                onTrailing = { pageMenu = true },
                onTitle = { nav.push(Screen.Lists) }
            )
            if (!settings.hasAccount) {
                TextRow(stringResource(R.string.account_needed), size = typo.title) { nav.push(Screen.Account) }
            }
            // Open tasks: tap ☐ to complete, tap the text to rename, long press for the menu,
            // long press then drag to put them in the order you want (saved to the server).
            ReorderableColumn(
                items = open,
                key = { it.href },
                onReorder = { ordered -> if (listUrl != null) app.store.reorder(listUrl, ordered) },
                onLongPress = { t -> menuFor = t },
                row = { t, _ ->
                    TaskLine(t, done = false,
                        onBox = { tick(); app.store.setCompleted(listUrl!!, t, true) },
                        onTap = { renameFor = t })
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
            ) {
                if (settings.hasAccount && open.isEmpty() && listUrl != null) {
                    item { Small(stringResource(R.string.no_open_task), Modifier.padding(horizontal = rowPadH, vertical = rowPadV)) }
                }
                if (done.isNotEmpty()) {
                    item {
                        TextRow(
                            if (showDone) stringResource(R.string.hide_done, done.size) else stringResource(R.string.show_done, done.size),
                            size = typo.small,
                            onClick = { showDone = !showDone }
                        )
                    }
                    if (showDone) items(done, key = { "done" + it.href }) { t ->
                        TaskLine(t, done = true,
                            onBox = { tick(); app.store.setCompleted(listUrl!!, t, false) },
                            onTap = { app.store.setCompleted(listUrl!!, t, false) },
                            onLongPress = { tick(); menuFor = t })
                    }
                }
            }
            Rule()
            if (listUrl != null) TextRow(stringResource(R.string.new_task), size = typo.title, onClick = { adding = true })
            Small(
                when (val s = sync) {
                    SyncState.Running -> stringResource(R.string.syncing)
                    is SyncState.Error -> s.message
                    SyncState.Idle -> if (cache.syncedAt > 0) stringResource(R.string.synced, java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(cache.syncedAt))) else ""
                },
                Modifier.padding(horizontal = rowPadH, vertical = 6.dp), maxLines = 1
            )
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }

        if (adding) TextPrompt(
            title = stringResource(R.string.new_task_prompt),
            confirm = stringResource(R.string.action_add),
            onDone = { title -> adding = false; app.store.add(listUrl!!, title) },
            onCancel = { adding = false }
        )
        renameFor?.let { t ->
            TextPrompt(
                title = stringResource(R.string.rename_prompt), initial = t.summary,
                onDone = { title -> renameFor = null; if (title != t.summary) app.store.rename(listUrl!!, t, title, null, keepDue = true) },
                onCancel = { renameFor = null }
            )
        }
        menuFor?.let { t ->
            TextMenu(
                title = t.summary,
                items = buildList {
                    if (t.completed) add(MenuItem(stringResource(R.string.reopen)) { app.store.setCompleted(listUrl!!, t, false) })
                    else add(MenuItem(stringResource(R.string.complete)) { app.store.setCompleted(listUrl!!, t, true) })
                    add(MenuItem(stringResource(R.string.rename)) { renameFor = t })
                    add(MenuItem(stringResource(R.string.due_tomorrow_set)) { app.store.rename(listUrl!!, t, t.summary, LocalDate.now().plusDays(1), keepDue = false) })
                    add(MenuItem(stringResource(R.string.due_clear)) { app.store.rename(listUrl!!, t, t.summary, null, keepDue = false) })
                    add(MenuItem(stringResource(R.string.delete)) { app.store.delete(listUrl!!, t) })
                },
                onDismiss = { menuFor = null }
            )
        }
        if (pageMenu) {
            val systemDark = isSystemInDarkTheme()
            TextMenu(
                title = null,
                items = buildList {
                    add(MenuItem(stringResource(R.string.lists)) { nav.push(Screen.Lists) })
                    add(MenuItem(stringResource(R.string.sync_now)) { app.store.syncAll() })
                    add(MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(systemDark) })
                    add(MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) })
                },
                onDismiss = { pageMenu = false }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskLine(t: TaskRow, done: Boolean, onBox: () -> Unit, onTap: () -> Unit, onLongPress: (() -> Unit)? = null) {
    val colors = LocalColors.current
    val color = if (done) colors.dim else colors.fg
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onLongPress != null) Modifier.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap, onLongClick = onLongPress)
                else Modifier.noRippleClickable(onClick = onTap)
            )
            .padding(horizontal = rowPadH, vertical = rowPadV * 0.7f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T(if (done) "☑" else "☐", Modifier.noRippleClickable { onBox() }, color = color, align = TextAlign.Start)
        Box(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            T(t.summary.ifBlank { "…" }, color = color, maxLines = 2)
            val due = dueLabel(t.due)
            if (due.isNotEmpty() && !done) Small(due, maxLines = 1)
        }
    }
}

/** Choose the list; long press to hide, move, or reveal hidden ones. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListsScreen(nav: Nav, app: App) {
    val cache by app.store.cache.collectAsState()
    val tick = rememberTick()
    var menuFor by remember { mutableStateOf<ListInfo?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    BackHandler { nav.pop() }
    val visible = cache.lists.filter { !it.hidden }
    val hidden = cache.lists.filter { it.hidden }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.lists), onBack = { nav.pop() })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                items(visible, key = { it.url }) { l ->
                    Box(Modifier.fillMaxWidth().combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = { app.store.setCurrentList(l.url); nav.pop() }, onLongClick = { tick(); menuFor = l })) {
                        TextRow(l.name, inverted = l.url == cache.currentList, secondary = "${app.store.openTasks(l.url).size}")
                    }
                }
                if (hidden.isNotEmpty()) {
                    item { TextRow(if (showHidden) stringResource(R.string.hide_hidden_lists) else stringResource(R.string.show_hidden_lists, hidden.size), size = LocalTypo.current.small) { showHidden = !showHidden } }
                    if (showHidden) items(hidden, key = { "h" + it.url }) { l ->
                        TextRow(l.name, secondary = stringResource(R.string.unhide)) { app.store.setHidden(l.url, false) }
                    }
                }
            }
        }
        menuFor?.let { l ->
            val i = visible.indexOf(l)
            TextMenu(
                title = l.name,
                items = buildList {
                    if (i > 0) add(MenuItem(stringResource(R.string.move_up)) { app.store.move(l.url, -1) })
                    if (i < visible.size - 1) add(MenuItem(stringResource(R.string.move_down)) { app.store.move(l.url, 1) })
                    if (visible.size > 1) add(MenuItem(stringResource(R.string.hide_list)) { app.store.setHidden(l.url, true) })
                },
                onDismiss = { menuFor = null }
            )
        }
    }
}

@Composable
fun SettingsScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.settings), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                TextRow(stringResource(R.string.account), secondary = s.username.ifBlank { stringResource(R.string.account_needed) }, size = typo.title) { nav.push(Screen.Account) }
                Rule(Modifier.padding(vertical = 8.dp))
                val themeName = when (s.theme) { ThemeMode.DARK -> stringResource(R.string.theme_dark); ThemeMode.LIGHT -> stringResource(R.string.theme_light); ThemeMode.SYSTEM -> stringResource(R.string.theme_system) }
                TextRow(stringResource(R.string.setting_theme, themeName), size = typo.title) { app.prefs.setTheme(next(s.theme)) }
                val fontName = when (s.font) { FontChoice.SERIF -> "serif"; FontChoice.SANS -> "sans"; FontChoice.MONO -> "mono" }
                TextRow(stringResource(R.string.setting_font, fontName), size = typo.title) { app.prefs.setFont(next(s.font)) }
                val sizeName = when (s.textSize) { TextSize.SMALL -> stringResource(R.string.size_small); TextSize.MEDIUM -> stringResource(R.string.size_medium); TextSize.LARGE -> stringResource(R.string.size_large) }
                TextRow(stringResource(R.string.setting_text_size, sizeName), size = typo.title) { app.prefs.setTextSize(next(s.textSize)) }
                TextRow(stringResource(R.string.setting_align, if (s.align == Align.LEFT) stringResource(R.string.align_left) else stringResource(R.string.align_center)), size = typo.title) { app.prefs.setAlign(next(s.align)) }
                TextRow(stringResource(R.string.setting_haptics, if (s.haptics) stringResource(R.string.on) else stringResource(R.string.off)), size = typo.title) { app.prefs.setHaptics(!s.haptics) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(R.string.about, com.freedomfighter.readerstasks.BuildConfig.VERSION_NAME), size = typo.title, secondary = stringResource(R.string.about_line)) { }
            }
        }
    }
}

private inline fun <reified E : Enum<E>> next(e: E): E { val all = enumValues<E>(); return all[(e.ordinal + 1) % all.size] }

/** Server, username, app password. */
@Composable
fun AccountScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val sync by app.store.sync.collectAsState()
    val typo = LocalTypo.current
    val colors = LocalColors.current
    var url by remember { mutableStateOf(s.serverUrl) }
    var user by remember { mutableStateOf(s.username) }
    var password by remember { mutableStateOf(s.password) }
    var submitted by remember { mutableStateOf(false) }
    BackHandler { nav.pop() }
    LaunchedEffect(sync, submitted) { if (submitted && sync is SyncState.Idle && app.store.cache.value.lists.isNotEmpty()) nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.account), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                Small(stringResource(R.string.account_help), Modifier.padding(horizontal = rowPadH), maxLines = 8)
                Small(stringResource(R.string.server), Modifier.padding(horizontal = rowPadH).padding(top = 20.dp))
                ReaderTextField(url, { url = it }, Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 6.dp), placeholder = "https://sync.infomaniak.com", imeAction = ImeAction.Next)
                Rule()
                Small(stringResource(R.string.username), Modifier.padding(horizontal = rowPadH).padding(top = 14.dp))
                ReaderTextField(user, { user = it }, Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 6.dp), placeholder = "AB12345", imeAction = ImeAction.Next)
                Rule()
                Small(stringResource(R.string.app_password), Modifier.padding(horizontal = rowPadH).padding(top = 14.dp))
                ReaderTextField(password, { password = it }, Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 6.dp), placeholder = "••••••••", imeAction = ImeAction.Done, password = true)
                Rule()
                TextRow(stringResource(R.string.connect), inverted = url.isNotBlank() && user.isNotBlank() && password.isNotBlank(), size = typo.title) {
                    if (url.isNotBlank() && user.isNotBlank()) { app.prefs.setAccount(url, user, password); submitted = true; app.store.syncAll() }
                }
                when (val st = sync) {
                    SyncState.Running -> Small(stringResource(R.string.connecting), Modifier.padding(rowPadH))
                    is SyncState.Error -> Small(st.message, Modifier.padding(rowPadH), color = colors.fg, maxLines = 4)
                    SyncState.Idle -> Unit
                }
            }
        }
    }
}
