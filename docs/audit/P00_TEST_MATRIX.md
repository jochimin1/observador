# P00 · Matriz de tests y validación

JVM: `.\gradlew test` → 30 suites, 489 tests, 0 fallos (2026-10-15).
Instrumentados: `connectedDebugAndroidTest` en emulator-5580 (Android 16, API 36) → 55 tests, 49 PASS, 6 FAIL de entorno (Espresso 3.5.1).
DEVICE = emulador. **UBER_REAL = nunca afirmado por tests**; viene de capturas reales documentadas en código/CHANGELOG.

## 1. Inventario

| Test | Dominio | Tipo | Datos | Estado |
|---|---|---|---|---|
| BestHoursStatsTest (66) | Mercado / horas | Unit | sintético | PASS |
| CsvSecurityTest (2) | Exportes | Unit | sintético | PASS |
| DashboardStatsTest (13) | Dashboard | Unit | sintético | PASS |
| DistanceTest (20) | Distancia | Unit | sintético | PASS |
| EarningsTest (19) | Ganancias / metas | Unit | sintético | PASS |
| ExpensesTest (19) | Gastos | Unit | sintético | PASS |
| FieldValidationTest (9) | Ajustes | Unit | sintético | PASS |
| FinanceTest (45) | Dinero / ledger | Unit | sintético + replay | PASS |
| IntelligenceTest (47) | Intelligence / sombra | Unit | sintético | PASS |
| LifecycleScreenTest (22) | Clasificador | Unit | fixtures (`LifecycleFixtures`) | PASS |
| MoneyTest (11) | Money | Unit | sintético | PASS |
| OfferDetailsTest (4), OfferListOptionsTest (8) | Dashboard | Unit | sintético | PASS |
| OfferQualityTest (4), ProfitEngineTest (4), ScoreBreakdownTest (4) | ProfitEngine | Unit | sintético | PASS |
| OfferReadingTest (8) | Parser direcciones / pasajero | Unit | textos tipo captura | PASS |
| OfferSessionTrackerTest (7) | Ofertas | Unit | sintético | PASS |
| OnlineTest (40) | Online | Unit | etiquetas reales 12/10 | PASS |
| ReportsTest (12) | Reportes | Unit | sintético | PASS |
| RideLifecyclePipelineTest (27), RideLifecycleStateMachineTest (23) | Lifecycle | Unit | replay sintético | PASS |
| SettingsProfilesTest (10) | Perfiles | Unit | sintético | PASS |
| TestOfferSeederTest (8) | Datos TEST | Unit | sintético | PASS |
| ThemeTest (6), TimeAgoTest (3), WorkdayClockTest (7), TripsViewTest (7) | UI / utilidades | Unit | sintético | PASS |
| TripRulesTest (33) | Trips | Unit | sintético | PASS |
| ExampleUnitTest (1) | — | Unit | — | PASS |
| Migration4To5 … Migration10To11 (7×2) | Migraciones | Instrumented | sintético | PASS |
| TripRepositoryTest (9) | Trips / identidad | Instrumented | sintético | PASS (tras arreglo P00; antes nunca ejecutado) |
| FinancialRepositoryTest (5) | Finanzas | Instrumented | sintético | PASS |
| ManualAssignmentRepositoryTest (2) | Asignación manual | Instrumented | sintético | PASS |
| IntelligenceRepositoryTest (5) | Intelligence | Instrumented | sintético | PASS |
| OnlineRepositoryTest (5) | Online | Instrumented | sintético | PASS |
| DistanceRepositoryTest (3) | Distancia | Instrumented | sintético | PASS |
| ExpenseRepositoryTest (3) | Gastos | Instrumented | sintético | PASS |
| ReportExportTest (2) | Exportes | Instrumented | sintético | PASS |
| BestHoursSectionTest (6) | UI Compose | Instrumented | sintético | FAIL entorno (R-04) |
| ExampleInstrumentedTest (1) | — | Instrumented | — | PASS |

## 2. AUTOMATED / DEVICE / UBER_REAL

| Dominio | Automated | Device (emulador) | Uber real |
|---|---|---|---|
| Offers (parser precio/recogida/aceptar) | YES | NO (no hay test de servicio) | PARTIAL_REAL (tarjetas reales desde 1.0.0; firmas `validated`) |
| Direcciones de oferta | YES | NO | NO (CANDIDATE) |
| Nombre del pasajero | YES | NO | PARTIAL («llegar por» capturado 14/10) |
| ProfitEngine | YES | n/a | n/a |
| Ride Lifecycle | YES (replay) | NO | NO (firmas de viaje CANDIDATE) |
| Trips (persistencia) | YES | YES | NO |
| Financial detection | YES | YES (repo) | NO (firmas provisionales) |
| Ledger / anti doble conteo | YES | YES | n/a |
| Manual assignment | YES | YES | NO |
| Online state | YES | YES (repo) | PARTIAL_REAL (etiquetas de capturas 12/10) |
| Distance | YES | YES (repo) | NO (sin prueba en carretera documentada) |
| Expenses | YES | YES | n/a (manual) |
| Intelligence / Shadow | YES | YES (repo) | NO (datos reales probablemente insuficientes) |
| Reports | YES | YES | NO |
| Migraciones 4→11 | — | YES | n/a |
| UI (BestHours) | — | NO (entorno) | NO |
| Telemetry | n/a (no existe) | n/a | n/a |

## 3. REAL WORLD VALIDATION STATUS

| Área | Estado |
|---|---|
| Offers | PARTIAL_REAL |
| Ride Lifecycle | SYNTHETIC_ONLY |
| Financial Detection | SYNTHETIC_ONLY |
| Online State | PARTIAL_REAL |
| Distance | NOT_TESTED (en carretera) |
| Expenses | n/a (entrada manual, sin detección) |
| Intelligence | SYNTHETIC_ONLY |
| Telemetry | NOT_TESTED (no implementada) |

## 4. Huecos de test

| Prioridad | Hueco |
|---|---|
| P0 (resuelto en P00) | Identidad de viaje en Room nunca ejecutada (TripRepositoryTest inválido) |
| P1 | Sin test de que Ganancias/Metas/Reportes excluyan o marquen datos TEST (R-03) |
| P1 | Sin test de `setRiderName` / evento asociado (R-05) |
| P1 | Sin test del servicio de accesibilidad extremo a extremo (snapshot → oferta guardada → overlay); solo piezas puras |
| P1 | UI Compose no ejecutable en API ≥ 34 con Espresso 3.5.1 (R-04) |
| P2 | Migraciones 1→4 sin test ni esquema |
| P2 | `PersistenceQueue` ante un `Error` |
| P2 | Ventana de export de viajes (created_at) vs Ganancias (completed_at) |
| P2 | Notificaciones (canales, guardas) sin tests |
| — | Doble distancia: una sola suscripción GPS por diseño; `DistanceTest` cubre jitter/saltos/huecos |
| — | Data leakage: garantizado por la firma de `ProfitEngine.evaluate`; `IntelligenceTest` |

## 5. Datos TEST (A18)

| Seeder | Prefijo | Idempotente | Borra solo TEST |
|---|---|---|---|
| TestOfferSeeder | `TEST-` (offer_hash) | sí (`replaceByHashPrefix`, transacción) | sí (`deleteByHashPrefix`) |
| TripTestSeeder | `TEST-` (trip id) | sí (`replaceTestTrips`, `require` prefijo) | sí (eventos por FK) |
| FinancialTestSeeder | ids `TEST-…` | sí (misma ruta de escritura que lo real) | sí |
| IntelligenceTestSeeder | `TEST-` | sí | sí (`clearTest`) |
| OnlineTestSeeder | `TEST-online-` | sí | sí |
| DistanceTestSeeder | `TEST-km-` | sí | sí |
| ExpenseTestSeeder | `TEST-gasto-` | sí | sí |

Todos solo accesibles en builds DEBUG (`SettingsScreen.kt:440`, `IntelligenceScreen.kt:180`). Contaminación: **ninguna consulta de Ganancias, Metas, Reportes ni Dashboard excluye `TEST-`** (único filtro: `OfferDao.observeLatestDetectedAtExcluding`). Intelligence detecta viajes TEST (`usesTestData`) y bloquea promoción de candidatos `TEST-`. En release no se pueden crear datos TEST → sin P0; en debug los datos TEST se suman a los reales sin marca (R-03, P1). Dado que no hay firma de release, es probable que el teléfono use un APK debug (ver checklist).
