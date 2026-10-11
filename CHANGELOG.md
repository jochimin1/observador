# Changelog

Todos los cambios importantes de Observador se registran aquí.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones siguen
[Versionado Semántico](https://semver.org/lang/es/):

- **MAJOR** (2.0.0): cambios que rompen algo, por ejemplo datos que dejan de ser compatibles.
- **MINOR** (1.1.0): funciones nuevas. Cada fase del plan del dashboard sube este número.
- **PATCH** (1.1.1): correcciones sin funciones nuevas.

Cada versión sube también `versionCode` en `app/build.gradle.kts` y lleva una etiqueta de git
(`v1.1.0`). La versión instalada se ve al final de Ajustes.

## [Sin publicar]

## [1.2.0] - 2026-10-10

Fase 2 del plan del dashboard: jerarquía de la pantalla.

### Añadido
- Franja de estado fija arriba del dashboard: verde "Leyendo ofertas" con la hora de la última
  oferta real (sin contar las de prueba), o roja "Inactivo" con un botón que abre Accesibilidad.
- Resumen en cuadrícula de 2 x 2: ofertas vistas, RD$/km y RD$/h medianos con una flecha que
  dice si están por encima o por debajo de tus mínimos, y la mejor oferta. Con letra del sistema
  muy grande pasa a una sola columna.
- Barra de reparto MALO / BUENO / EXCELENTE con cantidad y porcentaje (los porcentajes siempre
  suman 100). TalkBack la lee como una frase.
- "Probar la tarjeta" en Ajustes → Overlay.

### Cambiado
- La barra superior usa un ícono para Ajustes y lleva "Exportar ofertas" al menú ⋮.
- La barra de categorías de Ajustes y la del dashboard son el mismo componente.

### Eliminado
- La tarjeta "Servicio de Accesibilidad" y el botón "Probar overlay" del dashboard.

## [1.1.0] - 2026-10-10

Fase 1 del plan del dashboard: datos correctos.

### Añadido
- Selector de periodo en el resumen: Hoy, 7 días, 30 días y Todo.
- Ajuste "Inicio de la jornada" (por defecto 4:00 am). Una oferta de la 1:30 am cuenta en la
  jornada que empezó la tarde anterior.
- Reparto de ofertas MALO / BUENO / EXCELENTE del periodo y mejor oferta del periodo.
- Mediana de RD$/km y de RD$/h en el resumen y en cada jornada.
- Versión de la app visible al final de Ajustes.
- Este CHANGELOG.

### Cambiado
- El dashboard recalcula score, combustible y ganancia con los ajustes actuales, así todo el
  historial se juzga con el mismo criterio. Los datos guardados y la exportación conservan los
  valores del momento en que se detectó cada oferta.
- Las ofertas se agrupan por jornada en lugar de por día calendario.
- "Hoy" se renueva solo al empezar la jornada, sin cerrar la app.

### Corregido
- La lista se cortaba en las 200 ofertas más recientes sin avisar; ahora carga el periodo completo.
- Los promedios mezclaban todo el historial; ahora corresponden al periodo elegido.

### Eliminado
- Consultas de promedios de todo el historial, el resumen por día calendario (`DaySummary`) y un
  contador que no se usaba.

### Pendiente
- La exportación "Hoy" todavía usa el día calendario, no la jornada.

## [1.0.0] - 2026-10-10

Primera versión registrada en GitHub (commit `74bdd64`).

### Añadido
- Lectura de las ofertas de Uber Driver con un servicio de accesibilidad y cálculo de RD$/km,
  RD$/h, costo de combustible, ganancia neta y score de 0 a 100.
- Tres categorías de oferta, MALO / BUENO / EXCELENTE, con cortes configurables.
- Tarjeta flotante sobre Uber con precio, km, minutos, RD$/km y score; se cierra con un toque.
- Modo Asistido: notificación para ofertas BUENAS o EXCELENTES; se cierra con un toque.
- Dashboard con estadísticas y ofertas agrupadas por día; exportación a CSV y JSON.
- Ajustes con descripción en cada campo, validación de valores y oferta de ejemplo en vivo.
- Ofertas de prueba para comprobar el dashboard (solo en la versión de desarrollo).

### Corregido
- Ofertas duplicadas: seguimiento por sesión de la tarjeta, ventana anti-duplicados y limpieza
  de duplicados antiguos al migrar la base de datos (versión 4).

[Sin publicar]: https://github.com/jochimin1/observador/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/jochimin1/observador/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/jochimin1/observador/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/jochimin1/observador/releases/tag/v1.0.0
