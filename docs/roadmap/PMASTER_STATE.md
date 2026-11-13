# PMASTER STATE

Current phase:
P00

Previous phase:
—

Status:
COMPLETED_WITH_LIMITATIONS

Completed:
- P00 (COMPLETED_WITH_LIMITATIONS)

Blocked:
None (roadmap detenido por instrucción del usuario: «solo P00 y parar»)

Branch:
temas

Start commit (P00):
cdbc059

Last successful commit:
commit `p00: establish project baseline` (sobre cdbc059)

App version:
1.18.0 (versionCode 19)

Database version:
11

Last test:
JVM PASS (489/489) · instrumentados 49/55 PASS (6 fallos de entorno, Espresso en API 36)

Last build:
PASS (assembleDebug)

Blockers:
None P0. P1 abiertos: R-01, R-02, R-03, R-04, R-05, R-07, R-08 (docs/audit/P00_RISK_REGISTER.md)

Warnings:
- Instrumentados ejecutados en emulador API 36: Gradle desinstala la app del emulador al terminar (sin efecto en teléfonos).
- `docs/p00–p10.md` y `docs/PMASTER.md` siguen sin seguimiento en git (decisión del usuario).
- `cdbc059` contiene cambios de comportamiento sin versión/CHANGELOG (R-11).

Gate result:
P00 COMPLETED_WITH_LIMITATIONS (docs/roadmap/results/P00_RESULT.md)

Next:
Decisión del usuario sobre R-01/R-02 → P00.1 (opcional, recomendado) → P01.

## Prompt hashes (SHA-256)

| Archivo | SHA-256 |
|---|---|
| PMASTER.md | 6c06e323483e587dbbd78000ba9976380a1eaaf26f9325c3c9b4b7d5d5383aa2 |
| p00.md | 7e6e607b8832267e6a88fc5f866f87300da4397d22a8add054fdc790a162b9c4 |
| p01.md | 398b38776193f3e66d0207da94b05147d8260f1b507adc74f21d3573fc845d24 |
| p02.md | 627be5e5412cfd3266cb11816a68ac0ede1e157320a84c116273517039970f15 |
| p03.md | c923aa040aea64b060583303208dd8cab105fa2711ef7c9ca9efb876a739c30b |
| p04.md | 3128452dd87df0c541d892fc64d20d5f88a45f85dfecde0b8f303d6e2fbfa94d |
| p05.md | 335d351cdc2c87b0cf2987dabd5561627219967605af77cb635085c31fd0a453 |
| p06.md | fb9f598b977ce65741dd28934eea4a79f1aeb00a9e5e99db42ee2d897219ceb0 |
| p07.md | 82148ae66bc3d44367f7c4f4e5fcc15ee3eb0205db28de455f71d5edc98c5036 |
| p08.md | 2c29070ab324820a1c7e64fed5b88f7b233cea642b10d8f712aa0745fd27ff8b |
| p09.md | a4656d5d14ad0fb5f4bfeedd1076034cf4c0c182cac6d80db3c6928a8e0f1399 |
| p10.md | 780fa4d3ff30ee4b2b249678b8f1b8c5e89adb999f639262e28c9376938ab3ff |

## Roadmap execution table

| PHASE | STATUS | COMMIT | TESTS | BUILD | DEVICE | REAL UBER |
|---|---|---|---|---|---|---|
| P00 | COMPLETED_WITH_LIMITATIONS | p00: establish project baseline | JVM 489/489; inst. 49/55 | PASS | emulador API 36 | no |
| P01 | NOT_STARTED | — | — | — | — | — |
| P02 | NOT_STARTED | — | — | — | — | — |
| P03 | NOT_STARTED | — | — | — | — | — |
| P04 | NOT_STARTED | — | — | — | — | — |
| P05 | NOT_STARTED | — | — | — | — | — |
| P06 | NOT_STARTED | — | — | — | — | — |
| P07 | NOT_STARTED | — | — | — | — | — |
| P08 | NOT_STARTED | — | — | — | — | — |
| P09 | NOT_STARTED | — | — | — | — | — |
| P10 | NOT_STARTED | — | — | — | — | — |

## Database version ledger

| Fase | DB |
|---|---|
| P00 (baseline) | v11 |

## Architecture ledger (componentes canónicos)

| Componente | Implementación |
|---|---|
| ProfitEngine | `domain/ProfitEngine.kt` (`ALGORITHM_VERSION = PROFIT_ENGINE_V1`) |
| Lectura de pantalla | `service/ScreenSnapshotFactory.kt` (único recorrido del árbol) |
| Parser de ofertas | `service/OfferParser.kt` + `domain/lifecycle/ScreenSignatures.kt` (`OfferPatterns`) |
| Lifecycle | `service/RideLifecycleMonitor.kt` + `domain/lifecycle/RideLifecyclePipeline.kt` |
| Trip writer | `service/TripPersistenceCoordinator.kt` → `data/TripRepository.kt` (`TripAggregator`) |
| Cola de escritura | `service/PersistenceQueue.kt` (`shared()`) |
| Financial ledger | `data/FinancialRepository.kt` + `domain/finance/FinancialLedger.kt` (`FinancialWriteRules`, `TripFinancialAggregator`) |
| Money | `domain/money/Money.kt` (`MoneyAmount`, `MoneyFormat`) |
| Online sessions | `service/OnlineSessionCoordinator.kt` + `domain/online/OnlineSessionRules.kt` |
| Location source | `service/ObservadorForegroundService.kt#setGps` → `service/DistanceMonitor.kt` (candidato a `LocationObservationSource`) |
| Distance | `domain/distance/DistanceTracker.kt` |
| Expenses | `data/ExpenseRepository.kt` + `domain/expenses/ExpenseRules.kt` |
| Earnings model | `domain/earnings/Earnings.kt` (`EarningsReports`) |
| Reports | `data/export/ReportData.kt`, `domain/reports/Weekly.kt`, `Exports.kt`, `data/export/SummaryPdf.kt` |
| Intelligence | `data/IntelligenceRepository.kt`, `domain/intelligence/*` (`ModelVersionRules`) |
| Settings | `data/AppSettings.kt` (DataStore) |

## Source-of-truth registry

| Concepto | Owner | Implementación |
|---|---|---|
| Offer score | ProfitEngine | `ProfitEngine.evaluate/analyze` |
| Score histórico | detected_offers.score / trips.score_at_detection | inmutable; copiado al crear el viaje |
| Trip lifecycle | RideLifecyclePipeline | `RideLifecycleMonitor` |
| Trip state | TripAggregator | `TripRepository.persist` |
| Final money | Financial ledger | `financial_observations` → `trip_financial_summaries` |
| Offer amount (referencia) | detected_offers / TripOfferSnapshot | nunca ingreso |
| Online time | OnlineSessionRules | `online_sessions` |
| GPS distance | DistanceTracker | `distance_segments`, `trip_distances` |
| Vehicle odometer | usuario | `odometer_readings` |
| Cash expense | usuario | `fuel_entries`, `expense_entries`, `recurring_costs` |
| Allocated operating cost | — (no existe) | P03 |
| Operating profit | — (no existe) | P03 |
| Market analytics | BestHoursStats | `domain/BestHoursStats.kt` |
