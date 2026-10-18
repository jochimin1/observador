package com.ubers.observador.domain.backup

/** The steps of «Borrar todos los datos» (1.33.0), in order. A failure after the data is set aside rolls back. */
enum class ResetStep(val label: String) {
    PAUSE_READING("Pausando la lectura"),
    DRAIN_WRITES("Guardando lo pendiente"),
    CLOSE_DATABASE("Cerrando la base de datos"),
    DELETE_DATA("Borrando datos"),
    RESET_SETTINGS("Restableciendo ajustes"),
    RESTART("Reiniciando la app");

    companion object { val SHOWN: List<ResetStep> = listOf(PAUSE_READING, DELETE_DATA, RESET_SETTINGS, RESTART) }
}

/** The pure rules of erasing everything, and the texts shown after a restore or an erase. */
object ResetRules {

    const val CONFIRM_WORD = "BORRAR"
    const val ERASED_PREFIX = "Datos borrados"
    const val ROLLED_BACK = "No se borró nada; tus datos están como antes."

    /** Keyboards capitalise or not; spaces around the word do not matter. Anything else does not confirm. */
    fun isConfirmed(typed: String): Boolean = typed.trim().equals(CONFIRM_WORD, ignoreCase = true)

    fun erasedText(rows: Map<String, Int>): String =
        "$ERASED_PREFIX: se eliminaron ${BackupFormat.contentText(rows)}. Observador quedó como recién instalado."

    /** The text of the confirmation, with what is about to disappear. */
    fun warningText(rows: Map<String, Int>): String =
        "Se borrará TODO: ${BackupFormat.contentText(rows)}, el dinero, los kilómetros, los recorridos y tus ajustes. " +
            "No se puede deshacer. El permiso de accesibilidad no se toca."

    // --- the card shown when the app opens again ---

    enum class NoticeKind { RESTORED, ERASED, RESTORE_FAILED, ERASE_FAILED }

    fun noticeKind(text: String): NoticeKind = when {
        text.startsWith("Respaldo restaurado") -> NoticeKind.RESTORED
        text.startsWith(ERASED_PREFIX) -> NoticeKind.ERASED
        text.startsWith("No se borró") -> NoticeKind.ERASE_FAILED
        else -> NoticeKind.RESTORE_FAILED
    }

    fun noticeTitle(text: String): String = when (noticeKind(text)) {
        NoticeKind.RESTORED -> "Respaldo restaurado"
        NoticeKind.ERASED -> "Datos borrados"
        NoticeKind.RESTORE_FAILED -> "No se restauró"
        NoticeKind.ERASE_FAILED -> "No se borró"
    }

    fun noticeBody(text: String): String = when (noticeKind(text)) {
        NoticeKind.RESTORED -> "Ahora tienes: " + text.removePrefix("Respaldo restaurado: ")
        NoticeKind.ERASED -> text.removePrefix("$ERASED_PREFIX: ").replaceFirstChar { it.uppercase() }
        else -> text
    }
}
