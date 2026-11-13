# P00 · Base de datos

`AppDatabase` (`data/db/AppDatabase.kt`), archivo `observador.db`, **versión 11**, `exportSchema = true`, esquemas en `app/schemas/com.ubers.observador.data.db.AppDatabase/` (4.json … 11.json). `11.json` identityHash `eeb038a12de2f8e443976d5c74f2064b` = hash en `AppDatabase_Impl` generado (build de P00): **entidades y esquema exportado coinciden**. El build no modificó ningún JSON. Sin `fallbackToDestructiveMigration` (grep en todo `app/src`: 0).

## 1. Inventario de tablas

| Tabla | PK | FK | UNIQUE | Índices | Propósito | Módulo |
|---|---|---|---|---|---|---|
| detected_offers | id (autoinc) | — | — | offer_hash, detectedAt | ofertas vistas y su score | Offers |
| trips | id (UUID) | — | lifecycle_session_id | status, created_at | foto actual del viaje + snapshot de oferta y ajustes; v11: direcciones y `rider_name` | Trips |
| ride_events | database_id | trip_id → trips CASCADE | event_id; (trip_id, sequence) | — | log de eventos del viaje | Trips |
| financial_observations | database_id | trip_id → trips SET NULL | observation_id | trip_id, observed_at | ledger inmutable (Long minor) con VOIDs | Finance |
| trip_financial_summaries | trip_id | trip_id → trips CASCADE | (PK) | — | resumen derivado y reconstruible | Finance |
| platform_earnings_snapshots | database_id | — | snapshot_id | captured_at | cifras de Uber (referencia, nunca ingreso) | Finance |
| profit_model_versions | id | — | number | — | versiones inmutables del modelo | Intelligence |
| shadow_evaluations | evaluation_id | — | — | offer_hash, candidate_version_id | score activo vs candidato | Intelligence |
| calibration_runs | run_id | — | — | created_at | ejecuciones de calibración | Intelligence |
| online_sessions | id | — | — | started_at, ended_at | sesiones en línea | Online |
| distance_segments | id | — | — | workday, started_at, ended_at | km por tramo (mm), sin posiciones | Distance |
| trip_distances | trip_id | trip_id → trips CASCADE | (PK) | — | km reales por viaje (mm) | Distance |
| fuel_entries | id | — | — | at | cargas de combustible | Expenses |
| expense_entries | id | — | — | at, type | gastos sueltos y mantenimiento | Expenses |
| recurring_costs | id | — | — | — | costos fijos | Expenses |
| odometer_readings | id | — | — | at | lecturas de odómetro | Expenses / Vehículo |

## 2. Dinero y tipos

- **Long minor units**: todas las tablas de Finance y Expenses (`amount_minor`, `*_minor`, `daily/weekly_goal_minor` en DataStore).
- **Double heredado** (no ledger, estimación de oferta): `detected_offers.price`, `fuelCost`, `netProfit`, `rdpPerKm`, `rdpPerHour`; `trips.offered_amount`, `setting_min_rdp_per_*`; `profit_model_versions.min_rdp_*`. Clasificación: LEGACY_ESTIMATE (P2, R-06). No migrar ahora; toda columna monetaria nueva debe ser Long. `ShadowScorer` y `Dataset` convierten con `MoneyAmount.fromLegacyDouble`.

## 3. Cadena de migraciones

| Migración | Registrada | Schema origen/destino | Test | Ejecutado | Resultado | Nota |
|---|---|---|---|---|---|---|
| 1→2 | sí | no / no | no | — | — | añade offer_hash y **borra duplicados** (legacy) |
| 2→3 | sí | no / no | no | — | — | índices |
| 3→4 | sí | no / 4.json (reconstruido) | no | — | — | **borra duplicados** en ventanas de 10 min (legacy) |
| 4→5 | sí | sí / sí | Migration4To5Test (2) | sí (emulador API 36) | PASS | trips, ride_events |
| 5→6 | sí | sí / sí | Migration5To6Test (2) | sí | PASS | tablas financieras |
| 6→7 | sí | sí / sí | Migration6To7Test (2) | sí | PASS | pesos + intelligence |
| 7→8 | sí | sí / sí | Migration7To8Test (2) | sí | PASS | online_sessions |
| 8→9 | sí | sí / sí | Migration8To9Test (2) | sí | PASS | distancia |
| 9→10 | sí | sí / sí | Migration9To10Test (2) | sí | PASS | gastos |
| 10→11 | sí | sí / sí | Migration10To11Test (2) | sí | PASS | direcciones + rider_name |

Las 10 migraciones están en `.addMigrations(...)`. Las de 1→4 no tienen esquema ni test (P3, R-14): usuarios en v<4 son improbables, y borran filas duplicadas por diseño histórico.

## 4. Integridad

- FKs: eventos y resúmenes caen en cascada con su viaje (borrar un viaje real solo ocurre desde utilidades TEST por prefijo); las observaciones financieras quedan con `trip_id = NULL` (el dinero nunca se pierde).
- Ids únicos: `event_id`, `observation_id`, `snapshot_id`, `lifecycle_session_id`, `number` de versión.
- Secuencia: `(trip_id, sequence)` UNIQUE; `nextSequence` dentro de la transacción del writer único.
- Inserciones: `TripDao.insertTrip/insertEvent` con `ABORT` (un duplicado revienta la transacción → rollback, probado en `aFailedTransactionLeavesNothingHalfWritten`). `OfferDao.insert` con `REPLACE` sobre id autogenerado (solo afecta restauraciones con id explícito).
- Excepción: `setRiderName` modifica `trips` sin `ride_events` (R-05).
- Integrity checker existente: `TripsDebugViewModel` (botón DEBUG «comprobar integridad», códigos `IntegrityCode`) — reutilizable en P09.
- Filas huérfanas: imposibles por FK para eventos/resúmenes/distancias; observaciones sin viaje son estado válido («pendiente»).

## 5. DataStore (A16)

Archivo de preferencias `settings`, 41 claves (`data/AppSettings.kt`):

| Grupo | Claves |
|---|---|
| ProfitEngine | min_rdp_per_km, min_rdp_per_hour, max_pickup_km, max_pickup_min, weight_*_pct (4), cost_per_km, fuel_price_per_gallon, km_per_gallon, min_score_threshold, excellent_score_threshold |
| Intelligence | shadow_model_version_id |
| UI / tema | app_theme, auto_night, night_from_hour, night_to_hour |
| Overlay | show_overlay, overlay_duration_seconds |
| Detección | grace_window_seconds, operating_mode, observer_enabled |
| Jornada | workday_start_hour, week_start_day |
| Perfiles | settings_profiles, active_profile_id |
| Distancia | distance_enabled, distance_mode, distance_manual |
| Ganancias / metas | daily_goal_minor, weekly_goal_minor, goal_notification, goal_notified_day, goal_notified_week |
| Gastos / mantenimiento | work_share_pct, maintenance_notification, maintenance_interval_km_<tipo>, maintenance_notified_<tipo> |
| Reportes | weekly_report_notification, weekly_report_notified |
| Telemetry-ready | ninguna |

Sin claves muertas detectadas (todas con lectura y escritura). `cost_per_km` solo se usa si combustible/rendimiento no están definidos (`AppSettings.kt:88-91`): dos fuentes del costo por km, coherentes por diseño. `min_score_threshold` = `goodFrom` (nombre histórico). Validación: `FieldValidation`/`SettingsLimits` (9 tests) y `QualityThresholds.of` separa cortes cruzados.

## 6. Backup (A20)

`AndroidManifest.xml`: `allowBackup="true"`, `dataExtractionRules=@xml/data_extraction_rules`, `fullBackupContent=@xml/backup_rules`; **ambos XML son la plantilla vacía** → la copia en la nube y la transferencia entre dispositivos incluyen `observador.db` (ofertas con direcciones, viajes con nombre del pasajero, dinero) y DataStore. Clasificación: **RISK** (R-01). Bloquea P02 (rutas GPS persistidas entrarían al backup).
