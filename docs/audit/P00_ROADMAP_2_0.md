# P00 · Roadmap 2.0 y readiness

Esfuerzo en clases (SMALL / MEDIUM / LARGE / VERY_LARGE), sin estimaciones de tiempo.

## 1. Readiness por fase

### P01 · Centro de Viajes — READY (reorganización, sin DB nueva)
- Datos de Trip existentes: estado, hitos (aceptado, navegación, llegada, inicio, fin, cancelación, interrupción), recuperado, anomalías, snapshot de oferta (importe, km/min estimados, score original, ajustes y pesos), direcciones, pasajero, eventos con evidencia, dinero (resumen + observaciones), km reales (`trip_distances`).
- Repositorios: `TripRepository` (+ `observeTripWithEvents`), `FinancialRepository`, `DistanceRepository.observeTripDistance`. No hacen falta nuevos.
- Ya en Ganancias: lista de viajes **completados** con detalle (línea de tiempo, dinero visto, pasajero, direcciones), asignación manual (`EarningsScreen.TripRow`, `TripDetailContent`).
- Ya en Debug: todos los estados (cancelados/interrumpidos), eventos, km por viaje, score original, integridad (`TripsDebugCard`).
- **Conclusión**: el Centro de Viajes es sobre todo **reorganizar** lo que ya está repartido entre Ganancias y TripsDebugCard en una sección propia, y añadir lo que falta en la UI de usuario (cancelados/interrumpidos, km reales, score original, esperas). DB nueva: no.
- Condiciones de entrada: R-07 (firmas de viaje sin validar: el centro puede quedar vacío en uso real), R-03 (marcar TEST), R-04 (tests de UI), R-05 (rider name sin evento).

### P02 · Telemetría GPS — NEEDS_REFACTOR (pequeño) + decisiones previas
- Stream: un único `FusedLocationProviderClient` en `ObservadorForegroundService.setGps` (HIGH_ACCURACY, 5 s, solo mientras se mide) → `DistanceMonitor.onFix` → `DistanceTracker`.
- Compartible: sí. Refactor mínimo: extraer `LocationObservationSource` (dueño del request y del callback) con dos consumidores, `DistanceMonitor` y telemetría; el encendido seguiría `DistanceGate`. `DistanceTracker` ya calcula el delta filtrado (jitter, saltos, huecos) y puede aportarlo a la telemetría.
- Schema nuevo: tabla(s) de puntos por viaje (FK a `trips`, CASCADE) en DB v12, con migración, `12.json` y test.
- Permisos: FINE/COARSE y FGS location ya existen; no hace falta `ACCESS_BACKGROUND_LOCATION` mientras el servicio en primer plano tenga el tipo location (hoy se pierde si el servicio arranca en segundo plano: `backgroundLimited`).
- Batería: HIGH_ACCURACY 5 s sin medir (R-18).
- Privacidad que debe cambiar: `Distance.kt`, `DistanceEntities.kt`, manifest y CHANGELOG prometen «positions are never stored»; hay que reescribir esa regla (DEC-003) y **excluir del backup antes** (R-01).

### P03 · Rentabilidad — PARTIAL READY
- Ingresos reales: ledger con completitud (CONFIRMED/PARTIAL/UNKNOWN), observaciones sin asignar; validación real pendiente (R-08).
- Tipos de gasto: 9 sueltos/mantenimiento + combustible + 4 recurrentes (ver P00_ARCHITECTURE).
- Asignación de costos: solo prorrateo diario de recurrentes + `work_share_pct`; **no** por viaje ni por km.
- Distancia real: sí (día y viaje). Tiempo en línea real: sí (`online_sessions`, utilización).
- Falta para beneficio por viaje/hora/km: modelo de asignación (combustible por km real, mantenimiento por km, recurrentes por hora en línea), separar caja vs costo operativo, y conectar `trip_distances`.

### P04 · Vehículo — NOT READY (base mínima existe)
- No hay entidad Vehicle ni Maintenance. `MaintenanceNotifier` usa intervalos por tipo en DataStore (`maintenance_interval_km_<tipo>`) y compara con el odómetro.
- Odómetro: `odometer_readings` (+ `odometer_km` en gastos y combustible). Historial de mantenimiento = `expense_entries` del grupo MAINTENANCE con odómetro.

### P05 · Eficiencia — READY en backend
- `OnlineMetrics` (confirmado, utilización), `TripWaits`, buckets de km (pasajero/recogida/vacío/fuera de línea). Se puede calcular searching, pickup, waiting, inTrip, offline y utilización; falta reconciliar tiempo en línea con km operacionales.

### P06 · Mercado — PARTIAL (no duplicar BestHoursStats)
- `BestHoursStats` ya hace mercado por franja (mediana RD$/h y RD$/km, ofertas/h, índice, poca muestra). Falta en `OfferEntity`: tipo de producto, tarifa dinámica, calificación, resultado (aceptada se infiere vía `trips.related_offer_hash`). Duplicación a evitar: segundo agregador de ofertas por hora.

### P07 · Zonas — NOT READY
- Sin infraestructura espacial: no se guardan coordenadas; solo direcciones de texto (CANDIDATE). Depende de P02.

### P08 · Cierres / reportes — READY
- `EarningsReports`, `WeeklyReports`, `PeriodSummaryText`, `SummaryPdf`, `ReportData`, exportes CSV/JSON. Reutilizables tal cual.

### P09 · Health / calidad — PARTIAL
- Señales existentes: servicio de accesibilidad activo (`UberAccessibilityService.isEnabled`), última oferta real (`observeLatestRealOfferAt`), `OfferDebugState`, `LifecycleDiagnostics`, `OnlineDiagnostics`, `DistanceDiagnostics`, `FinancialMetrics`, `TripPersistenceMetrics`, integridad de viajes (DEBUG), cobertura financiera (completitud). Todas en memoria o DEBUG; falta una pantalla de salud y persistencia mínima.

## 2. DAG recomendado (dependencias reales)

```
P00 ──► P00.1 (recomendado: backup, Espresso, TEST visible, comentarios)
          │
          ▼
         P01 (reorganización de viajes; requiere validación real de firmas R-07)
          │
          ├──► P03 ◄── (usa km reales ya existentes) ──► P04 ──► P05
          │
          └──► P02 (requiere R-01 cerrado y DEC-003) ──► P07
                         P06 (independiente de P02; solo ofertas)
                         P08 (después de P03/P05)
                         P09 (transversal; antes de P10)
                         P10
```

Cambio sugerido respecto al orden lineal: **P03 no depende de P02** (los km reales ya existen desde Fase 8E), así que puede ir antes o en paralelo lógico; P06 tampoco depende de P02. PMASTER exige orden estricto: se mantiene P01→P10, pero P02 debe entrar con R-01 resuelto.

## 3. Tabla de fases

| Fase | Objetivo | Depende de | Reutiliza | Schema | Riesgo | Esfuerzo | Entry gate | Exit gate |
|---|---|---|---|---|---|---|---|---|
| P01 | Centro de Viajes | P00 | TripRepository, Earnings TripRow/Detail, TripsDebugCard, DistanceRepository | ninguno | R-07 | MEDIUM | firmas de viaje validadas o limitación aceptada | todos los estados de viaje en UI de usuario; tests UI ejecutables |
| P02 | Telemetría GPS | P01, R-01 | DistanceMonitor/Tracker, FGS | v12 puntos por viaje | privacidad, batería | LARGE | backup excluido; DEC-003 | una fuente GPS; migración + test; sin coords en logs |
| P03 | Rentabilidad | P01 | ledger, ExpenseRules, trip_distances, online | posible (asignaciones) o derivado | R-08 | LARGE | dinero real validado o limitado | caja ≠ costo operativo; beneficio por viaje/h/km |
| P04 | Vehículo | P03 | odometer, gastos MAINTENANCE, MaintenanceNotifier | posible (vehículo) | duplicar Expenses | MEDIUM | modelo de costos P03 | sin duplicar gastos |
| P05 | Eficiencia | P03/P04 | OnlineMetrics, TripWaits, buckets km | ninguno | solapes de tiempo | MEDIUM | — | sum(categorías) ≤ online |
| P06 | Mercado | P05 | BestHoursStats, ofertas | posible (campos de oferta) | duplicar BestHours | MEDIUM | — | mercado ≠ resultado |
| P07 | Zonas | P02 | telemetría | celdas | promesas falsas | LARGE | telemetría validada | análisis histórico, N visible |
| P08 | Cierres | P03–P06 | EarningsReports, Weekly, PDF | ninguno | recalcular | MEDIUM | — | reportes no recalculan |
| P09 | Health | todas | diagnósticos existentes, integrity check | mínimo | — | MEDIUM | — | «¿podemos confiar?» respondido |
| P10 | Integración | P09 | — | — | — | LARGE | — | READY / WITH_LIMITATIONS / NOT_READY |

## 4. Quick wins top 10

1. Mostrar en Ganancias/Centro de Viajes los km reales por viaje (`observeTripDistance`, ya existe).
2. Listar cancelados e interrumpidos (datos ya en `trips`).
3. Mostrar el score original de la oferta en el detalle del viaje (`TripOfferSnapshot.scoreAtDetection`).
4. Excluir del backup `observador.db` (dos XML).
5. Marcar visualmente filas `TEST-` en Ganancias/Reportes.
6. Subir Espresso/androidx.test para recuperar los 6 tests de UI.
7. Añadir «llegar por» como frase candidata de PICKUP_HEADER (ya validada por el lector de nombre).
8. Mover `OfferDebugCard` dentro de DEBUG o tras un aviso.
9. Corregir los comentarios erróneos de `data/db`.
10. Usar `trip_distances` en el dataset de Intelligence (km reales frente a estimados).

## 5. Deuda técnica top 10

1. Firmas de lifecycle sin validar (R-07): todo Trips depende de ellas.
2. `SettingsScreen` (912 líneas) mezcla ajustes de usuario y diagnóstico.
3. `EarningsScreen` (846) concentra lista de viajes, asignación, metas, semanal y exportes.
4. `DashboardScreen` (765) / `DashboardViewModel` (428).
5. Domain acoplado a `OfferEntity` (5 archivos).
6. Importes de oferta en `Double` y formateo ad-hoc `RD$%.2f`.
7. `PersistenceQueue` sin aislamiento de errores.
8. Reglas de privacidad repartidas en comentarios (contradicen el estado actual tras 8F).
9. Estado de detectores solo en memoria (sin historial de salud).
10. Build de uso diario = debug (sin firma ni minify).

## 6. Quality score (cualitativo)

| Área | Nota | Evidencia |
|---|---|---|
| Architecture | B | reglas puras testeables, writer único por dominio, una fuente GPS; violaciones menores (R-12) |
| Data Integrity | A− | ledger inmutable, FKs, uniques, transacciones probadas en emulador; excepción R-05 |
| Testing | B | 489 JVM + 55 instrumentados; UI no ejecutable en API 36; un test inválido durante meses (R-00) |
| Privacy | C | sin red ni logs sensibles, pero PII persistida/exportada y backup abierto (R-01, R-02, R-13) |
| Performance | B | cálculo en `Dispatchers.Default`/IO, árbol recorrido una vez, checkpoint GPS 60 s; cargas completas en reportes |
| Maintainability | B− | pantallas muy grandes; comentarios engañosos en `data/db` |
| Observability | C+ | diagnósticos ricos pero en memoria y solo DEBUG |
| UX | B | 5 pestañas claras; Ajustes sobrecargado |
| Real-world validation | D | solo ofertas y etiquetas online con capturas reales; lifecycle y dinero sintéticos |

## 7. ADRs candidatos

- ADR-001 Money en minor units (Long) para todo lo nuevo; Double de oferta como legado.
- ADR-002 ProfitEngine fuente única; candidatos solo cambian ajustes.
- ADR-003 Local-first: sin red propia; exportes solo por acción del usuario.
- ADR-004 Log de eventos de viaje inmutable; un viaje no cambia sin evento (resolver R-05).
- ADR-005 Coordenadas GPS: hoy nunca guardadas; P02 debe sustituir esta decisión explícitamente.
- ADR-006 PII de pasajero/direcciones: autorizada 14/10 con exportación; definir backup y retención.

## 8. Checklist manual post-P00 (teléfono + Uber real)

[ ] ¿Qué APK usa el teléfono a diario (debug o release)? ¿Aparece «Herramientas de prueba» en Ajustes?
[ ] Oferta real: overlay y fila en Inicio con precio/recogida/viaje correctos (y direcciones: CANDIDATE).
[ ] Aceptar una oferta real: ¿se crea un viaje TO_PICKUP? (TripsDebugCard / Ganancias). Copiar lectura saneada de la pantalla de recogida.
[ ] Llegada, inicio y fin reales: ¿el viaje llega a COMPLETED con sus hitos? Copiar lecturas saneadas de cada pantalla.
[ ] Cancelación real (si ocurre): ¿CANCELLED?
[ ] Pantalla de ganancias del viaje: ¿dinero vinculado (CONFIRMED) o queda pendiente?
[ ] Conectarse/desconectarse en Uber: ¿sesión en línea abre y cierra con la hora correcta?
[ ] Medición de km con pantalla apagada 15 min en ruta: ¿tramos y km por viaje coherentes con el odómetro?
[ ] Reinicio del teléfono a mitad de viaje: ¿un solo viaje, marcado recuperado?
[ ] Ajustes del sistema → Copia de seguridad: ¿está activa la copia de Observador?

## 9. Próxima acción recomendada

1. El usuario decide R-02 (ratificar PII) y R-01 (backup).
2. **P00.1** acotado (opcional pero recomendado antes de P01): backup rules, Espresso, marca de TEST, comentarios `data/db`, CHANGELOG `[Sin publicar]`.
3. Hacer la checklist de §8 para validar firmas de viaje (R-07) antes o durante P01.
4. Luego P01.
