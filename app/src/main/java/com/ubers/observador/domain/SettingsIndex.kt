package com.ubers.observador.domain

import com.ubers.observador.domain.lifecycle.TextNormalizer

/**
 * 1.50.0: Ajustes in six groups, each on its own screen (the driver's choice, 2026-11-14). Only
 * where a setting is shown changes: no key, value or default moves, so backups and profiles are
 * the same as before.
 */
enum class SettingsGroup(val label: String, val description: String) {
    READING("Lectura y avisos", "Tarjeta sobre Uber, detección, modo de operación, avisos de ofertas y sectores de cuidado"),
    PROFIT("Rentabilidad", "Combustible y costo por km, umbrales del puntaje, categorías y metas de ingreso"),
    VEHICLE("Vehículo y gastos", "Kilómetros, GPS, recorridos, parte de trabajo y mantenimiento"),
    WORKDAY("Jornada", "Hora de inicio de la jornada"),
    PRIVACY("Privacidad y respaldo", "Respaldo, restauración, exportación completa y borrado de datos"),
    APPEARANCE("Apariencia", "Tema, noche automática y bienvenida")
}

/** One setting the search can find: what the screen calls it, other words for it, and its group. */
data class SettingEntry(val title: String, val group: SettingsGroup, val keywords: String = "")

object SettingsIndex {

    val ENTRIES: List<SettingEntry> = listOf(
        SettingEntry("Tema", SettingsGroup.APPEARANCE, "sol noche violeta claro oscuro colores"),
        SettingEntry("Noche automática", SettingsGroup.APPEARANCE, "horario oscuro"),
        SettingEntry("Bienvenida", SettingsGroup.APPEARANCE, "presentación permisos accesibilidad ubicación"),
        SettingEntry("Precio del combustible", SettingsGroup.PROFIT, "gasolina galón"),
        SettingEntry("Rendimiento de la moto", SettingsGroup.PROFIT, "km por galón consumo"),
        SettingEntry("Costo por km", SettingsGroup.PROFIT, "gasto kilómetro"),
        SettingEntry("RD$/km mínimo", SettingsGroup.PROFIT, "umbral puntaje score kilómetro"),
        SettingEntry("RD$/hora mínimo", SettingsGroup.PROFIT, "umbral puntaje score hora"),
        SettingEntry("Recogida máx. km", SettingsGroup.PROFIT, "pickup distancia pasajero"),
        SettingEntry("Recogida máx. min", SettingsGroup.PROFIT, "pickup tiempo pasajero"),
        SettingEntry("BUENO desde", SettingsGroup.PROFIT, "categoría score bueno amarillo"),
        SettingEntry("EXCELENTE desde", SettingsGroup.PROFIT, "categoría score excelente verde"),
        SettingEntry("Meta diaria", SettingsGroup.PROFIT, "objetivo ingreso día"),
        SettingEntry("Meta semanal", SettingsGroup.PROFIT, "objetivo ingreso semana"),
        SettingEntry("La semana empieza el", SettingsGroup.PROFIT, "lunes domingo"),
        SettingEntry("Aviso de meta alcanzada", SettingsGroup.PROFIT, "notificación"),
        SettingEntry("Aviso del resumen semanal", SettingsGroup.PROFIT, "notificación reporte"),
        SettingEntry("Medir km con GPS", SettingsGroup.VEHICLE, "distancia kilómetros"),
        SettingEntry("GPS, batería y datos", SettingsGroup.VEHICLE, "ubicación permisos señal"),
        SettingEntry("Recorrido de los viajes", SettingsGroup.VEHICLE, "ruta mapa gps zonas"),
        SettingEntry("Parte de trabajo", SettingsGroup.VEHICLE, "uso laboral porcentaje personal"),
        SettingEntry("Cambio de aceite", SettingsGroup.VEHICLE, "mantenimiento intervalo km"),
        SettingEntry("Aviso de mantenimiento", SettingsGroup.VEHICLE, "notificación aceite cadena llantas"),
        SettingEntry("Inicio de la jornada", SettingsGroup.WORKDAY, "hora día de trabajo corte"),
        SettingEntry("Mostrar tarjeta sobre Uber", SettingsGroup.READING, "overlay flotante"),
        SettingEntry("Duración en pantalla", SettingsGroup.READING, "overlay segundos tarjeta"),
        SettingEntry("Cifras de la tarjeta", SettingsGroup.READING, "overlay campos orden precio puntaje"),
        SettingEntry("Posición de la tarjeta", SettingsGroup.READING, "overlay arriba centro abajo"),
        SettingEntry("Tamaño de letra de la tarjeta", SettingsGroup.READING, "overlay texto grande legible"),
        SettingEntry("Probar tarjeta", SettingsGroup.READING, "overlay prueba vista previa notificación"),
        SettingEntry("Ventana anti-duplicados", SettingsGroup.READING, "detección repetidas"),
        SettingEntry("Modo de operación", SettingsGroup.READING, "observador asistido automático"),
        SettingEntry("Avisos de ofertas", SettingsGroup.READING, "notificación sonido vibración"),
        SettingEntry("Sectores de cuidado", SettingsGroup.READING, "zonas evitar aviso cerrar probar reglas"),
        SettingEntry("Respaldo", SettingsGroup.PRIVACY, "copia backup cifrado contraseña"),
        SettingEntry("Restaurar", SettingsGroup.PRIVACY, "recuperar backup"),
        SettingEntry("Exportar todos los datos", SettingsGroup.PRIVACY, "json completo"),
        SettingEntry("Borrar datos", SettingsGroup.PRIVACY, "eliminar reiniciar")
    )

    private fun words(text: String): List<String> = TextNormalizer.normalize(text).split(Regex("[^a-z0-9$]+")).filter { it.isNotEmpty() }

    /**
     * Settings whose title or keywords contain every word typed (accents and case ignored, each word
     * as the start of a word), titles first. Empty for an empty query.
     */
    fun search(query: String, entries: List<SettingEntry> = ENTRIES): List<SettingEntry> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        fun hits(text: String): Boolean {
            val w = words(text)
            return q.all { part -> w.any { it.startsWith(part) } }
        }
        val inTitle = entries.filter { hits(it.title) }
        val inKeywords = entries.filter { it !in inTitle && hits(it.title + " " + it.keywords + " " + it.group.label) }
        return inTitle + inKeywords
    }
}
