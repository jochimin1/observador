package com.ubers.observador.data

import android.content.Context
import android.util.Log
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.earnings.MoneyCheck
import com.ubers.observador.domain.earnings.MoneyChecks
import java.io.File

/** 1.60.0: the driver's answers on Uber's money (MoneyChecks), one line each, only added (in backups and «Exportar todo»). */
object MoneyCheckStore {
    private fun file(context: Context) = File(File(context.applicationContext.filesDir, MoneyChecks.DIR), MoneyChecks.FILE)

    fun all(context: Context): List<MoneyCheck> =
        try { file(context).takeIf { it.isFile }?.readText()?.let(MoneyChecks::parse).orEmpty() } catch (e: Exception) { emptyList() }

    @Synchronized
    fun add(context: Context, check: MoneyCheck): Boolean = MaintenanceGate.shared.tryAccess {
        try {
            file(context).parentFile?.mkdirs()
            file(context).appendText(MoneyChecks.encode(check) + "\n")
            true
        } catch (e: Exception) {
            Log.w("MoneyCheckStore", "Could not save the money check", e)
            false
        }
    } ?: false
}
