package com.ubers.observador.domain.sectors

import java.io.File
import java.io.FileOutputStream

/** Durable, append-only budget: reserve before touching, even if a result cannot later be logged. */
object DailyCloseBudget {
    @Synchronized
    fun used(file: File, legacyClosed: Int): Int = try {
        maxOf(legacyClosed, if (file.exists()) file.readLines().count { it.isNotBlank() } else 0)
    } catch (e: Exception) { CloseRules.DAILY_CAP }

    @Synchronized
    fun reserve(file: File, legacyClosed: Int): Boolean = try {
        val used = used(file, legacyClosed)
        if (used >= CloseRules.DAILY_CAP) false
        else {
            val recorded = if (file.exists()) file.readLines().count { it.isNotBlank() } else 0
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { out ->
                repeat(used - recorded + 1) { out.write("reservado\n".toByteArray(Charsets.UTF_8)) }
                out.fd.sync()
            }
            true
        }
    } catch (e: Exception) { false }
}
