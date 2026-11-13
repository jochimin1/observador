# P00 · Arquitectura real

Commit auditado: `cdbc059` (rama `temas`). Todo lo de aquí sale del código; cada flujo cita archivo y función.

## 1. Diagrama general

```
Uber Driver (com.ubercab.driver)
  │ AccessibilityEvent (WINDOW_CONTENT_CHANGED | WINDOW_STATE_CHANGED), filtro de paquete
  ▼
UberAccessibilityService ── debounce 300 ms (main) ── readScreen()
  │  ScreenSnapshotFactory.from(root)  ← ÚNICO recorrido del árbol (≤2000 nodos, prof. ≤80)
  │
  ├─► OfferParser.parse(texts) → UberOffer ──► OfferSessionTracker (hash, 1.5 s de ausencia)
  │        │                                    └─ nueva sesión → ProfitEngine.analyze
  │        │                                         ├─ OfferRepository.saveOrSkip → detected_offers
  │        │                                         ├─ OverlayManager.show (si show_overlay)
  │        │                                         ├─ OfferNotifier (modo ASISTIDO y ≠ MALO)
  │        │                                         └─ ShadowScorer.score → shadow_evaluations
  │        └─ OfferPlacesReader (direcciones) · OfferDebug (memoria)
  │
  └─► RideLifecycleMonitor.onSnapshot (object, @Synchronized)
        ├─ RideLifecyclePipeline + UberScreenClassifier.spanish → eventos
        │     └─ TripPersistenceCoordinator ─┐
        ├─ RiderNameReader → submitRiderName ─┤
        ├─ FinancialDetector / UberTodayPillDetector → FinancialPersistenceCoordinator ─┤
        └─ OnlineStateDetector + OnlineSessionRules → OnlineSessionCoordinator ─────────┤
                                                                                      ▼
                                       PersistenceQueue.shared() (Channel UNLIMITED, 1 consumidor, IO)
                                                                                      ▼
                     TripRepository · FinancialRepository · OnlineRepository · DistanceRepository
                                                                                      ▼
                                                             Room observador.db (v11)

ObservadorForegroundService (specialUse|location, START_STICKY)
  ├─ FusedLocationProvider (HIGH_ACCURACY, 5 s, solo mientras DistanceMonitor mide)
  │     └─ DistanceMonitor.onFix → DistanceTracker (memoria) → checkpoint 60 s → PersistenceQueue
  ├─ combina RideLifecycleMonitor.state/online + ticker 30 s → DistanceMonitor.onRide/apply
  └─ notificación persistente (pausa / reanudar)

AppSettings (DataStore «settings», 41 claves) ← leído por servicios, VMs y ProfitEngine

UI Compose (MainActivity → NavHost, 5 pestañas)
  Inicio(DashboardViewModel) · Ganancias(EarningsViewModel) · Gastos(ExpensesViewModel)
  · Análisis(IntelligenceViewModel) · Ajustes(SettingsViewModel + Debug VMs)
```

No hay OCR, ni MediaProjection, ni red propia: no existe dependencia de red salvo Play Services Location.

## 2. Flujos por dominio

### Ofertas (A4)
`UberAccessibilityService.onAccessibilityEvent` → `readScreen` → `ScreenSnapshotFactory.from` → `OfferParser.parse` (si la ventana activa no es Uber o no tiene tarjeta: `offerFromOtherWindows`) → `OfferSessionTracker.onOfferSeen` → `sessionStart` → `ProfitEngine.analyze(offer, settings.engineSettings)` → `QualityThresholds.classify` → `OfferRepository.saveOrSkip` (`OfferDao.insertIfNotRecent`, ventana `grace_window_seconds`) → Room `detected_offers` → `DashboardViewModel` (`observeAll/observeSince`) → `DashboardScreen`.

**Parser** (`OfferPatterns`, `domain/lifecycle/ScreenSignatures.kt:29`):

| Señal | Regla | Clasificación |
|---|---|---|
| Botón | texto contiene `aceptar`/`accept`/`aceitar` (requisito único de entrada) | FRAGILE: si Uber cambia el texto, no se lee nada |
| Precio | `(?:DOP|RD\$)\s*([\d,]+\.?\d*)` sobre todos los textos unidos; primer match | ROBUST para DOP; FRAGILE si aparece otro importe antes (p. ej. tarifa dinámica/promoción) |
| Recogida | `(?:A|En)\s+(\d+)\s*min\s*[\(·•]\s*([\d.]+)\s*km` | ROBUST en formato observado; no admite metros (`800 m`) → UNVERIFIED |
| Viaje | primer `N min (X km)` distinto de la recogida, o `X km · N min` | FRAGILE si recogida y viaje coinciden exactamente (se descarta el match) |
| Direcciones | `OfferPlacesReader`: textos tras la recogida y el viaje | CANDIDATE (CHANGELOG 1.17.0) |
| Pasajero | `RiderNameReader`: «llegar por» VALIDADO 14/10; resto candidatos | PARTIAL |

**ProfitEngine** (`domain/ProfitEngine.kt`): única fórmula (`ALGORITHM_VERSION = PROFIT_ENGINE_V1`). sub-scores: km = min(rdp/km ÷ min × 50, 100); hora = idem; recogida km = max(0, (1 − km/max) × 100); recogida min = idem; total = Σ peso×sub/100 redondeado y acotado 0..100; pesos por defecto 35/30/20/15. `fuelCost = costPerKm × totalKm`, `netProfit = price − fuelCost` (estimación). No existen ProfitEngine2 ni fórmulas paralelas: `ShadowScoring`, `Calibration` y `Dataset` llaman a `ProfitEngine.evaluate`. **OfferQuality real: BAD «MALO», GOOD «BUENO», EXCELLENT «EXCELENTE»** (3 categorías, sin REGULAR); cortes por defecto 60/80.

### Lifecycle / Trips (A5)
Tabla SCREEN → EVIDENCE → EVENT → STATE → TRIP STATUS (`ScreenSignatures.SPANISH`, `TripAggregator.eventTypeOf`, `TripStatus.of`):

| Pantalla | Evidencia (frases) | Evento lifecycle | Estado | TripStatus | Validación |
|---|---|---|---|---|---|
| OFFER | aceptar + precio + recogida | OFFER_DETECTED | OFFER_VISIBLE | — (no crea viaje) | VALIDATED_REAL |
| PICKUP_NAVIGATION | «recoger a», «recogida en», «ir a recoger» | ACCEPTANCE_CONFIRMED / PICKUP_NAVIGATION_CONFIRMED | TO_PICKUP | TO_PICKUP (crea Trip) | CANDIDATE (¡«llegar por», validado para el nombre, no está en PICKUP_HEADER!) |
| ARRIVED_OR_WAITING | «llegué», «esperando a» | ARRIVAL_CONFIRMED | ARRIVED_OR_WAITING | ARRIVED_OR_WAITING | CANDIDATE |
| IN_TRIP | «iniciar/finalizar viaje», «dejar a» | TRIP_STARTED | IN_TRIP | IN_TRIP | CANDIDATE |
| TRIP_SUMMARY | «viaje completado», «califica a» | TRIP_COMPLETED | COMPLETED | COMPLETED | CANDIDATE |
| CANCELLED | «viaje cancelado», «entendido» | TRIP_CANCELLED | CANCELLED | CANCELLED | CANDIDATE |
| SEARCHING | «buscando viajes», «estás conectado» | SEARCHING_CONFIRMED | SEARCHING | INTERRUPTED (si había viaje abierto) | PARTIAL (etiqueta online validada 12/10) |
| reinicio / reconexión | — | LIFECYCLE_LOST | UNKNOWN | UNKNOWN (abierto) | SYNTHETIC_ONLY |

Reglas de Trip (`TripAggregator`, `TripRestorePolicy`, `TripRepository.persist` en una transacción Room):
- Crea: aceptación o unirse a un viaje en curso; una oferta nunca crea viaje.
- Actualiza: cada evento, hitos first-write-wins.
- Completa / cancela: mutuamente excluyentes; contradicción tardía = anomalía.
- Interrumpe: volver a buscar con viaje abierto; un segundo viaje abierto interrumpe al primero (anomalía).
- Reinicio: `restoreOpenTrip` deja como máximo un viaje abierto (LIFECYCLE_LOST) y la pipeline no interpreta pantallas hasta restaurarlo (`RideLifecycleMonitor.restored`).
- Idempotencia: `ride_events.event_id` UNIQUE, `(trip_id, sequence)` UNIQUE, `trips.lifecycle_session_id` UNIQUE; reintento con mismo eventId → DuplicateIgnored. Probado en emulador (`TripRepositoryTest` 9/9, p. ej. `theSameEventFiftyTimesIsOneRow`, `aSessionCanNeverHoldTwoTrips`).
- Excepción a la atomicidad: `TripDao.setRiderName` actualiza `trips.rider_name` sin evento (R-05).

### Finanzas (A6)
Pantalla Uber → `FinancialDetector.detect(snapshot, screenType, lifecycleContext)` → `FinancialObservation` (Long minor units, id determinista) → `FinancialCorrelator.link` (HIGH solo para un viaje recién terminado; firma provisional → LOW desde `cdbc059`) → `FinancialPersistenceCoordinator` (cola compartida, reintento con mismo id) → `FinancialRepository.persistObservation` (`FinancialWriteRules.resolve` + `TripFinancialAggregator` reconstruye `trip_financial_summaries`) → `EarningsReports.build` → `EarningsScreen`.
Anti doble conteo: `FinanceTest.newAbsoluteAbsorbsEarlierDeltaNever140` (100 ABS + 20 TIP + 120 ABS = 120) **existe y pasa**. Además `orderOfArrivalDoesNotChangeTheResult`, `rebuildEqualsIncrementalSummary`, `voidCancelsAnObservationWithoutDeletingIt`.
ManualAssignment (`domain/earnings/ManualAssignment.kt`): nunca edita; VOID del original + copia MANUAL con evidencia `MANUAL_ASSIGNMENT`/`ASSIGNED_FROM:<id>`; deshacer y «no es mío» también son VOID. Auditable y reversible. Solo viajes COMPLETED, misma moneda, con vista previa y confirmación.

### Online (A7)
Evidencia (`OnlineStateDetector`, `OnlineSignatures`): texto completo normalizado «estas conectado» / «desconectarse» (ONLINE), «estas desconectado» / «conectarse» (OFFLINE), o pantalla de viaje 7A con confianza media/alta. Lecturas mixtas no cuentan. Etiquetas tomadas de capturas reales del 12/10 (PARTIAL_REAL). Sesión: `OnlineSessionRules` (confirmación de segunda lectura, keep-alive de 5 min desde el servicio, cierre WENT_OFFLINE/PAUSED/OBSERVATION_LOST/SUPERSEDED, restauración de la sesión abierta con `RECOVERED`). Una sola sesión abierta (`SUPERSEDED`).

### Distancia (A8)
Una sola fuente GPS: `ObservadorForegroundService.setGps` (`FusedLocationProviderClient.requestLocationUpdates`, HIGH_ACCURACY, 5 s, min 2.5 s) → `DistanceMonitor.onFix` → `DistanceTracker` (descarta precisión >25 m, saltos >120 km/h, jitter < precisión, huecos >60 s estimados, >10 min no estimados; reparte en pasajero/recogida/vacío/fuera de línea según contexto de viaje). Solo totales en mm: `distance_segments` (checkpoint cada 60 s) y `trip_distances`. `LocationFix.toString()` oculta coordenadas. Mide según `DistanceGate` (interruptor, permiso, pausa, modo AUTO_ONLINE o MANUAL).

### Gastos (A9)
Tipos reales (`ExpenseType`): OIL, TIRES, CHAIN_KIT, BRAKES, MAINTENANCE_OTHER (grupo mantenimiento); WASH, PARKING_TOLL, FINE, OTHER (sueltos). Combustible aparte (`fuel_entries`: galones en milésimas, odómetro, tanque lleno). Recurrentes: BIKE_PAYMENT, DATA_PLAN, INSURANCE, OTHER con frecuencia WEEKLY/BIWEEKLY/MONTHLY. Odómetro: `odometer_readings`. Asignación de costos: **solo prorrateo diario de recurrentes y `work_share_pct`** (`ExpenseRules.summarize`); **no existe asignación por viaje ni por km** → oportunidad P03.

### Earnings / Goals (A10)
`EarningsViewModel` combina FinancialRepository (`earningsInputs`), OnlineRepository (resumen y utilización), DistanceRepository (km del periodo) y ExpenseRepository (neto). `Goals`: metas diaria/semanal en minor units sobre dinero confirmado; `GoalNotifier` con guardas `goal_notified_day/week`. Duplicación con Dashboard: el Dashboard muestra métricas de **ofertas** (medianas RD$/km, RD$/h, BestHours) y una píldora «hoy»; Ganancias muestra **dinero confirmado**. No hay fórmula duplicada de ingresos.

### Intelligence (A11)
Datos históricos (`IntelligenceRepository.loadDataset`: viajes COMPLETED + resumen financiero) → `IntelligenceDataset` (`IntelligenceSample`: `features` = solo datos de la oferta; resultados como etiquetas) → Analyzers / `ProfitModelBacktester` / `Calibration` (split temporal) → `IntelligenceReport` → candidato (`recordCandidate`, modelo activo intacto) → sombra (`ShadowScorer` en cada oferta nueva) → promoción solo desde la UI con confirmación (`ModelVersionRules.promote(userConfirmed = true)`), candidato `TEST-` rechazado, rollback crea una versión nueva con los valores anteriores.
Data leakage: **no**. `ProfitEngine.evaluate(features: OfferDecisionFeatures, settings)` solo acepta importe ofertado, km/min estimados y hora de detección; no recibe ganancias reales, distancia real, propina ni tiempos reales.

### Reportes (A12)
`ReportData` reutiliza `EarningsReports.build`, `ExpenseRules.summarize`, `OnlineRepository.summarize`, `DistanceTotals.kmBetween` → `WeeklyReports.build`, `TripExport`/`ExpenseExport`/`DailyExport` (CSV/JSON), `SummaryPdf`. `OfferExporter` exporta ofertas (CSV/JSON). Sin fórmulas duplicadas de ingresos. Inconsistencia menor: el export de viajes filtra por `created_at` y Ganancias por `completed_at` (R-16).

## 3. Mapa de navegación (A13)

`MainActivity` → `ObservadorNavHost`, barra inferior con `Tab`: **HOME (Inicio)** → `DashboardScreen` (con `onOpenEarnings`); **EARNINGS (Ganancias)** → `EarningsScreen` (lista de viajes completados con detalle: línea de tiempo, dinero, pasajero, direcciones; asignación manual; semanal; metas; exportes); **EXPENSES (Gastos)**; **ANALYSIS (Análisis)** → `IntelligenceScreen`; **SETTINGS (Ajustes)**. Notificación semanal abre Ganancias semana pasada (`AppNavigation.OPEN_LAST_WEEK`).
Ocultos en DEBUG (`SettingsScreen.kt:440`): Herramientas de prueba, Lifecycle/Online/Distance/Expense/Trips/Finance debug cards. **`OfferDebugCard` está fuera del bloque DEBUG** (visible en release, con «Copiar oferta/recogida» = texto completo de pantalla al portapapeles).

### Settings UX (§34)
`SettingsScreen.kt` 912 líneas, 11 secciones de usuario (Apariencia, Costos operativos, Umbrales, Categorías de score, Kilómetros, Ganancias y metas, Gastos y mantenimiento, Jornada, Overlay, Detección, Modo de operación) + 1 tarjeta de diagnóstico + bloque DEBUG con 7 tarjetas. Recomendación (no refactorizar ahora): separar «Ajustes» de un «Laboratorio/Diagnóstico» (OfferDebugCard + debug cards + lectura de pantalla), candidato natural para P09.

## 4. Notificaciones (A14)

| Notificación | Disparador | Canal | Prioridad | Guarda de duplicado | Control usuario |
|---|---|---|---|---|---|
| Oferta buena | `sessionStart`, oferta guardada (no duplicada), modo ASISTIDO, ≠ MALO | `observador_offers` | HIGH | `saved` + `tracker.activeHash` | modo de operación; permiso |
| Meta alcanzada | tras guardar dinero (`FinancialPersistenceCoordinator.onStored`), arranque de app, ajuste | `metas` | DEFAULT | `goal_notified_day` / `_week` | `goal_notification` |
| Mantenimiento | arranque de app, guardar gasto, ajustes | `mantenimiento` | DEFAULT | `maintenance_notified_<tipo>` | `maintenance_notification`, intervalos por tipo |
| Resumen semanal | alarma (`WeeklyReportReceiver`, reprograma en boot/update/hora) | `resumen_semanal` | DEFAULT | `weekly_report_notified` | `weekly_report_notification` |
| Servicio | foreground | `observador_service` | LOW | única (id fijo) | pausa/reanudar en la notificación |

Sin canales redundantes. El mantenimiento solo se evalúa al abrir la app o guardar un gasto (no por km recorridos en segundo plano).

## 5. Violaciones de capas (§11)

| Violación | Evidencia | Severidad |
|---|---|---|
| Domain → entidad Room | `BestHoursStats`, `CompletedTrips`, `DashboardStats`, `OfferDetails`, `OfferListOptions` importan `data.db.OfferEntity` | P2 ARCHITECTURE_VIOLATION |
| ViewModel → DAO | `IntelligenceViewModel.kt:79` `db.financialDao()`, `:113` `db.tripDao()` | P2 |
| Service → Repository directo (no DAO) | `UberAccessibilityService` → `OfferRepository` fuera de la cola compartida | aceptable, ver writers |
| Domain → android / compose | ninguna | — |

## 6. Single writer (A23)

| Dominio | Writer lógico | Storage | Observación |
|---|---|---|---|
| Ofertas | `UberAccessibilityService` (`OfferRepository.saveOrSkip`, scope IO propio); Dashboard borra/restaura; seeder DEBUG | `detected_offers` | Varios writers, sin conflicto lógico (inserciones independientes). P3 |
| Trips | `TripPersistenceCoordinator` vía `PersistenceQueue` (+ seeder DEBUG) | `trips`, `ride_events` | Único. `setRiderName` por la misma cola |
| Finanzas | `FinancialPersistenceCoordinator` vía cola; `ManualAssignment` desde `EarningsViewModel` (transacción) ; seeder DEBUG | `financial_observations`, summaries, snapshots | Dos entradas (automática + manual), ambas transaccionales |
| Online | `OnlineSessionCoordinator` vía cola | `online_sessions` | Único |
| Distancia | `DistanceMonitor` vía cola | `distance_segments`, `trip_distances` | Único |
| Gastos | `ExpensesViewModel` / `ExpenseRepository` | 4 tablas de gastos | Único (usuario) |
| Intelligence | `IntelligenceViewModel` (candidatos, promoción) + `ShadowScorer` (`ensureCurrent`, sombra) | 3 tablas | Dos writers; `ensureCurrent` en transacción. P3 |

Concurrencia: `RideLifecycleMonitor` y `DistanceMonitor` son `object` con `@Synchronized`; el servicio trabaja en main. `PersistenceQueue`: canal ilimitado, un consumidor sin aislamiento de `Throwable` (los writers capturan `Exception`; un `Error` mataría la cola en silencio) → P2 (R-09).

## 7. Matriz de integración (A26)

| Integración | Estado | Evidencia |
|---|---|---|
| Trips ↔ Finance | CONNECTED | correlador por sesión; FK `financial_observations.trip_id` SET NULL; summaries CASCADE |
| Trips ↔ Distance | CONNECTED | `DistanceMonitor.onRide` con `tripSessionId`; `trip_distances` (solo visible en DEBUG) |
| Trips ↔ Online | PARTIAL | `OnlineRepository` usa `getTripsTouching` para métricas; sesiones no referencian viajes |
| Trips ↔ Expenses | NOT_CONNECTED | sin asignación por viaje |
| Trips ↔ Intelligence | CONNECTED | dataset de viajes COMPLETED; no usa `trip_distances` («no real distance is measured yet» en `Dataset.kt`, desactualizado) |
| Finance ↔ Earnings | CONNECTED | `EarningsReports.build` |
| Finance ↔ Reports | CONNECTED | `ReportData` reutiliza el mismo modelo |
| Distance ↔ Expenses | PARTIAL | km del periodo en PDF/Ganancias; rendimiento de combustible no cruza con km GPS |
| Online ↔ Earnings | CONNECTED | RD$/h en línea, utilización |
| Online ↔ Intelligence | PARTIAL | `TripWaits` en Análisis; sesiones no entran al dataset |

## 8. BACKEND_READY_UI_MISSING (§63)

| Capacidad | Backend | UI hoy |
|---|---|---|
| km reales por viaje (`trip_distances`, cobertura FULL/PARTIAL) | `DistanceRepository.observeTripDistance` | solo TripsDebugCard |
| Viajes cancelados/interrumpidos | `trips` + eventos | solo TripsDebugCard (Ganancias lista solo COMPLETED) |
| Score original de la oferta del viaje (`score_at_detection`) | `TripOfferSnapshot` | solo TripsDebugCard |
| Línea de eventos con anomalías | `ride_events` | TripsDebugCard (Ganancias muestra hitos) |
| Esperas entre viajes por viaje | `TripWaits.of` | solo agregado en Análisis |
| Integridad de viajes | `TripsDebugViewModel` integrity check | solo DEBUG |
| Diagnósticos de detectores (lifecycle/online/finance/distance) | `StateFlow` en memoria | solo DEBUG |

## 9. Código muerto y duplicación (A24)

- No se encontraron clases sin referencias. `LifecycleReplay`/`ReplayStep` solo se usan desde tests (KEEP, arnés de pruebas en main; REVIEW mover a test fixtures). `OfferRepository.save` sin llamadas (SAFE TO REMOVE). Sin TODO/FIXME.
- Duplicación de formato: la UI de ofertas formatea `RD$%.2f` a mano sobre `Double` (`DashboardScreen`, `OfferDetailSheet`, `BestHoursSection`) mientras el ledger usa `MoneyFormat` (P2). Formatos por dominio separados (`EarningsFormat`, `ExpenseFormat`, `OnlineFormat`, `DistanceFormat`) sin solapamiento evidente.
- Costo por km: dos modelos coexisten — `cost_per_km`/`fuel_price_per_gallon`/`km_per_gallon` (estimación en ProfitEngine) y `fuel_entries` reales (Gastos). No se reconcilian (oportunidad P03/P04).
- Archivos grandes: `SettingsScreen` 912, `EarningsScreen` 846, `DashboardScreen` 765, `Analyzers` 701. Ganancias y Ajustes mezclan muchas responsabilidades (lista de viajes, asignación, metas, exportes) — relevante para P01.

## 10. Estado de módulos (§60–61)

| Módulo | Estado |
|---|---|
| Offers | IMPLEMENTED (parser validado con tarjetas reales; direcciones CANDIDATE) |
| ProfitEngine | PRODUCTION_READY (fuente única, 4 tests + 4 de breakdown) |
| Dashboard | PRODUCTION_READY |
| BestHours | PRODUCTION_READY (UI tests no ejecutables en API 36) |
| RideLifecycle | IMPLEMENTED_UNVALIDATED (firmas de viaje CANDIDATE) |
| Trips | IMPLEMENTED_UNVALIDATED (integridad probada en emulador) |
| Money | PRODUCTION_READY |
| Financial | IMPLEMENTED_UNVALIDATED (firmas de pantalla de ganancias provisionales) |
| Earnings | IMPLEMENTED_UNVALIDATED |
| Online | IMPLEMENTED_UNVALIDATED (etiquetas reales, flujo sin validar) |
| Distance | IMPLEMENTED_UNVALIDATED |
| Expenses | IMPLEMENTED (manual) |
| Goals | IMPLEMENTED |
| Intelligence | EXPERIMENTAL |
| Shadow | EXPERIMENTAL |
| Reports | IMPLEMENTED |
| Notifications | IMPLEMENTED |
| Settings | IMPLEMENTED (validación con `FieldValidation`/`SettingsLimits`, 9 tests) |
| Profiles | IMPLEMENTED (10 tests) |
| Pause | IMPLEMENTED (`observer_enabled`, notificación) |
| Debug tools | DEBUG_ONLY (OfferDebugCard en release) |
