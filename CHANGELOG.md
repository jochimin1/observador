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

## [1.7.0] - 2026-10-12

Fase 7B: registro persistente de viajes. Los eventos del ciclo de la 7A se guardan y forman
viajes que sobreviven a reinicios. No calcula ganancias. Validación con un viaje real de Uber:
pendiente (depende también de validar la 7A).

### Añadido
- Base de datos versión 5 con migración explícita 4 → 5: tablas nuevas `trips` (estado actual
  de cada viaje) y `ride_events` (historial de eventos que nunca se modifica). La tabla de
  ofertas no cambia.
- Un viaje se crea al confirmarse la aceptación o al recuperarse a mitad de camino; una oferta
  vista nunca crea viaje. Guarda la oferta original (precio, recogida, viaje, score de ese
  momento) y los ajustes con los que se juzgó.
- Reglas: una sesión = un viaje; un evento repetido no se duplica; la primera hora observada
  manda; COMPLETED y CANCELLED se excluyen; nuevo estado INTERRUPTED para finales no vistos;
  anomalías con códigos fijos (orden imposible, dos viajes activos, conflicto final...).
- Un único escritor guarda cada evento y el cambio de su viaje en la misma transacción, con
  reintentos seguros.
- Al reiniciar, el viaje abierto vuelve a la 7A y continúa como el mismo viaje; si Uber ya está
  buscando ofertas, queda INTERRUPTED.
- Ajustes → Herramientas de prueba (solo desarrollo): "Viajes detectados" con lista, línea de
  tiempo, contadores, comprobación de integridad y viajes de prueba que se borran sin tocar los
  reales.
- Esquemas de Room exportados en `app/schemas` y prueba de migración con MigrationTestHelper.

## [1.6.0] - 2026-10-10

Fase 7A: detectar el ciclo de vida del viaje. Solo observa: no guarda viajes, no cambia la base
de datos y nunca toca nada en Uber. Las reglas de la oferta están comprobadas; las de recogida,
llegada, viaje en curso, fin, cancelación y búsqueda son candidatas hasta validarlas con un
viaje real.

### Añadido
- Capa de interpretación del viaje: captura de pantalla sin tipos de Android, clasificador de
  pantallas con confianza y evidencia explicable, eventos tipados y máquina de estados
  (IDLE, OFFER_VISIBLE, TO_PICKUP, ARRIVED_OR_WAITING, IN_TRIP, COMPLETED, CANCELLED, UNKNOWN).
- Sin estados inventados: una oferta que desaparece no es un rechazo, y completar o cancelar
  exige una pantalla que lo muestre. Si Observador arranca a mitad de viaje, recupera el estado
  sin inventar horas anteriores.
- Evita eventos repetidos y cambios por parpadeo: 50 lecturas iguales dan un solo evento, y las
  transiciones importantes se confirman con una segunda lectura.
- Diagnóstico en Ajustes, solo en la versión de desarrollo: estado, pantalla, confianza,
  evidencia, salud del detector, contadores, últimos 50 eventos, "Reiniciar detector" y "Copiar
  lectura" (lectura de la pantalla con nombres y direcciones ocultos, para validar las reglas).

### Cambiado
- El árbol de accesibilidad se lee una sola vez por pantalla; la lectura de ofertas usa esa
  copia con las mismas reglas de siempre.
- Los textos de la pantalla ya no se escriben en el registro de la versión final, solo en la de
  desarrollo.

## [1.5.0] - 2026-10-10

Fase 5 del plan del dashboard: mejores horas para salir. Mide el mercado que viste (las ofertas
que te llegaron), no lo que ganaste.

### Añadido
- Sección "Mejores horas para salir" entre el resumen y la lista. Divide el día en 12 franjas
  fijas de 2 horas, según la hora real del teléfono, y dibuja el pago mediano por hora de cada
  una. Periodo de 7 o 30 días (30 por defecto).
- Tarjeta "Mejor franja observada": RD$/h y RD$/km medianos, porcentaje de buenas o excelentes,
  cantidad de ofertas, confianza e índice de oportunidad (0 a 100).
- Top 3 de franjas, ordenado por índice de oportunidad.
- Detalle al tocar una barra, la tarjeta o una fila del Top 3: mediana y rango típico (P25 a P75)
  del RD$/h, RD$/km, score mediano, buenas + excelentes, excelentes, recogida típica, frecuencia,
  días con ofertas, confianza e índice.
- Las franjas con menos de 5 ofertas se dibujan como barra hueca ("Pocos datos") y nunca
  compiten por mejor franja.
- Línea discontinua con tu mínimo de RD$/h en la gráfica.
- TalkBack enfoca cada barra por separado, la lee como una frase y anuncia la seleccionada.
- Ofertas de prueba: 100 más con un patrón de mercado conocido (160 en total). Con ellas
  18–20 sale como mejor franja, 20–22 paga más por hora pero no gana y 00–02 sale con pocos
  datos.

### Cambiado
- La mediana del dashboard usa la misma función de percentiles que la nueva sección (el
  resultado no cambia).
- En el panel de detalle de una oferta, con letra muy grande la etiqueta se parte en dos líneas
  en lugar de empujar el valor fuera de la pantalla.

## [1.4.0] - 2026-10-10

Fase 4 del plan del dashboard: filtros, orden y borrado.

### Añadido
- Filtro por categoría en la lista: Todas, Excelentes, Buenas y Malas, cada una con su cantidad.
  Con un filtro activo, la cabecera de cada jornada dice cuántas se muestran ("3 de 12 ofertas").
- Orden dentro de cada jornada: por hora, score, precio o RD$/km.
- Borrar una oferta deslizándola hacia la izquierda, desde su panel de detalle o con TalkBack
  ("Borrar oferta"). Aparece "Deshacer" durante unos segundos y la oferta vuelve tal como estaba.
- Borrar una jornada entera desde su menú ⋮, con confirmación y también con "Deshacer".

## [1.3.0] - 2026-10-10

Fase 3 del plan del dashboard: fila de oferta y detalle.

### Añadido
- Panel de detalle al tocar una oferta: recogida y viaje por separado, pago por km y por hora,
  combustible, ganancia neta y los cuatro sub-scores con su barra. Se cierra deslizando hacia
  abajo o con Atrás.

### Cambiado
- Cada oferta es una fila plana con una franja de color, el precio y el RD$/km en grande y solo
  la hora (la fecha ya la da la cabecera de la jornada). Caben más ofertas por pantalla.

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

[Sin publicar]: https://github.com/jochimin1/observador/compare/v1.4.0...HEAD
[1.4.0]: https://github.com/jochimin1/observador/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/jochimin1/observador/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/jochimin1/observador/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/jochimin1/observador/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/jochimin1/observador/releases/tag/v1.0.0
