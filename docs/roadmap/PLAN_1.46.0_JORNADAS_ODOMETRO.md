# Plan 1.46.0 — Uso laboral medido por odómetro de jornada

Estado: **aprobado para implementar** (decisiones del conductor del 2026-11-14). Se trabaja en una rama creada desde `release/1.45.0`, cuando esa entrega tenga su commit y sus referencias.

## Problema

El «uso laboral» es un porcentaje fijo escrito a mano en tres lugares sin conexión:

1. Ajustes (`work_share_pct`): descuenta «Gastos de trabajo» en Ganancias, reportes y el aviso semanal.
2. Ficha de la moto (`vehicle_profiles.workSharePct`): «de referencia», se sugiere al crear un modelo de costos.
3. Cada modelo de costos de operación: su propio porcentaje.

Las lecturas de odómetro (`odometer_readings`) ya existen, pero no indican si son de inicio o de fin de jornada.

## Decisiones del conductor

| # | Pregunta | Decisión |
|---|---|---|
| 1 | ¿Una o varias jornadas por día? | **Varias.** Lo recorrido entre jornadas (por ejemplo, el almuerzo) cuenta como personal. |
| 2 | ¿Aplicar el % medido automáticamente? | **No. Se sugiere y el conductor confirma**, como con los perfiles. |
| 3 | ¿Recordatorios? | **Sí**: al conectarse a Uber sin jornada abierta, y tras una desconexión larga con la jornada abierta. |
| 4 | ¿Y si el período no tiene lecturas? | **Se usa el % manual**, indicando que no está medido. |

## Diseño

### Datos (Room 20, migración aditiva)

- Tabla nueva `work_shifts`: `id`, `vehicle_id`, `started_at`, `start_reading_id`, `ended_at`, `end_reading_id`, `status` (`OPEN`/`CLOSED`), `created_at`, `updated_at`.
- Cada lectura de inicio o de fin es también una fila normal de `odometer_readings`. Así la aprovechan el «lleno a lleno» y los recordatorios de mantenimiento. Las lecturas existentes no se modifican.
- Las correcciones de una lectura de jornada se auditan con el valor anterior, el valor nuevo y la hora.
- Las jornadas entran en el respaldo (normal y cifrado), en la exportación completa JSON, en la restauración (`RestoreRules`) y en el borrado de datos.

### Reglas (dominio, sin Android)

- Hay como máximo una jornada abierta. No se puede cambiar de moto ni terminar la jornada con un viaje abierto.
- Se rechaza un fin menor que el inicio.
- Un inicio menor que el fin anterior obliga a elegir: «reinicio de odómetro», «otra moto» o «corregir».
- Una jornada de más de 600 km pide confirmación.
- La jornada pertenece al día de trabajo de su inicio (corte de las 4:00). Una jornada que cruza la medianoche no se parte.
- Una jornada olvidada se cierra después con la hora real. La hora de fin no puede ser futura ni anterior al inicio.
- Las cuentas son:
  - **Km Uber** = Σ (fin − inicio) de las jornadas cerradas del período.
  - **Km personales** = Σ (inicio de una jornada − fin de la anterior), con la misma moto y sin reinicio entre ambas.
  - **% laboral medido** = km Uber ÷ (km Uber + km personales), redondeado a entero.
- El % medido solo existe si hay al menos 2 jornadas cerradas encadenadas en el período (configurable). Si no, se usa el % manual y se muestra «no medido».

### Sugerencia y confirmación

- En Gastos → Mi moto aparece: «Uso laboral medido este mes: 82 % (de 74 % manual). Basado en 14 jornadas · 1.240 km Uber · 270 km personales». Los botones son **Usar 82 %** y **Ahora no**.
- Al confirmar, el % se guarda en un solo lugar: el % manual de Ajustes y de la ficha de la moto quedan unificados. Para los modelos de costos se propone una versión nueva, y la anterior se conserva con su vigencia.
- «Ahora no» oculta esa sugerencia hasta que el % medido cambie al menos 3 puntos.

### Interfaz

- Tarjeta «Jornada» en Gastos → Mi moto:
  - Sin jornada abierta: **Iniciar jornada**, con el odómetro propuesto desde la última lectura.
  - Con jornada abierta: hora de inicio, odómetro inicial, km GPS como referencia y **Terminar jornada**.
- Historial de jornadas del período con edición y borrado, y la confirmación habitual con opción de deshacer.
- Una comparación informativa: «Odómetro 118 km · GPS 111 km». Una diferencia mayor del 25 % se señala. El odómetro siempre manda.

### Recordatorios

- La app detecta la conexión a Uber y no hay jornada abierta: notificación «Anota el odómetro para iniciar la jornada».
- La jornada está abierta y Uber lleva más de 30 minutos desconectado (configurable): notificación «¿Terminaste la jornada?».
- No se repiten más de una vez por conexión o desconexión. Nunca abren ni cierran una jornada solos.

## Pruebas

- **Unitarias:** km y % con varias jornadas al día, una jornada que cruza la medianoche, odómetro que retrocede, reinicio, cambio de moto, un fin olvidado, un período sin lecturas (respaldo manual), redondeo, umbral de sugerencia, y que los recordatorios no se repitan.
- **Instrumentadas, solo en el emulador:** migración 19 → 20 con los datos conservados, ida y vuelta del respaldo con jornadas, restauración y borrado.
- **En el teléfono:** una semana de jornadas reales; comparar los km de Uber con el historial de la app de Uber y revisar que la sugerencia tenga sentido.

## Fuera de alcance

- Leer el odómetro por foto o de forma automática.
- Partir los gastos por cada viaje. El % sigue siendo uno por período.

## Efecto en la hoja de ruta

Esta entrega toma el número 1.46.0. Las propuestas siguientes de `docs/CONTEXTO_Y_MEJORAS_PROPUESTAS_2026-11-12.md` avanzan un número: «Sectores configurables y zonas» pasa a 1.47.0, y así con las demás.
