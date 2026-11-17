package com.ubers.observador

import android.app.Application
import com.ubers.observador.data.backup.DataRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ObservadorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The restart helper is intentionally independent of the process replacing its data.
        if (getProcessName().endsWith(":restart")) return
        runBlocking(Dispatchers.IO) { DataRecovery.recover(this@ObservadorApplication) }
        // 1.61.0: «Revisión de la semana» (a daily check; notifies only on the chosen day)
        com.ubers.observador.service.WeeklyReview.ensureScheduled(this)
    }
}
