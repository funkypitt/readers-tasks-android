package com.freedomfighter.readerstasks

import android.app.Application
import com.freedomfighter.readerstasks.data.Prefs
import com.freedomfighter.readerstasks.data.Store
import com.freedomfighter.readerstasks.provider.TasksProvider

class App : Application() {
    /** Set by the widget's + before the activity shows: the tasks screen opens its prompt. */
    @Volatile var pendingAdd = false
    lateinit var prefs: Prefs
    lateinit var store: Store

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        com.freedomfighter.readerstasks.ui.CredentialsShare.clear(this)
        store = Store(this, prefs)
        store.onChanged = { contentResolver.notifyChange(TasksProvider.BASE, null) }
    }
}
