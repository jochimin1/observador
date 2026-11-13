# P00 · Registro de riesgos

Severidades: P0 bloqueador (datos/dinero/migración/privacidad) · P1 importante · P2 mejora · P3 nice to have.

| ID | Sev. | Dominio | Problema | Evidencia | Impacto | Recomendación | Fase |
|---|---|---|---|---|---|---|---|
| R-00 | P0 → **RESUELTO** | Trips / tests | `TripRepositoryTest` inválido: un `@Test` devolvía no-Unit y JUnit descartaba la clase; identidad de viaje nunca probada en Room | `TripRepositoryTest.kt:171`; error «Method … should be void»; desde `9b27a60` | 9 tests de identidad/atomicidad nunca corrieron | Corregido en P00 (`runBlocking<Unit>`); 9/9 PASS en emulador | P00 |
| R-01 | P1 (P0 antes de P02) | Privacidad / backup | `allowBackup=true` con reglas plantilla vacías: DB y DataStore van a la nube de Google y a transferencia entre dispositivos | `AndroidManifest.xml`; `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Nombres de pasajeros, direcciones y dinero salen del dispositivo sin decisión explícita; con P02 saldrían rutas GPS | Excluir `observador.db` (y decidir DataStore) de cloud-backup y device-transfer, o `allowBackup=false`; decisión del usuario | P00.1 / antes de P02 |
| R-02 | P1 | Privacidad / política | PMASTER §39 prohíbe guardar `passengerName` y direcciones privadas «salvo cambio explícito autorizado»; el usuario lo autorizó el 14/10 (Fase 8F) y se guardan y exportan | `trips.rider_name`, `offer_*_address`, `detected_offers.*_address` (MIGRATION_10_11); `TripExport` CSV/JSON; CHANGELOG 1.17.0 | Conflicto documental: el roadmap asume «no PII» | Ratificar como decisión transversal (DEC-004) con retención/exclusión de backup; si no se ratifica, P00.1 para dejar de guardarlo | Decisión usuario |
| R-03 | P1 | Calidad de datos | Datos `TEST-` se suman a Ganancias, Metas, Reportes y Dashboard sin filtro ni marca (solo en builds DEBUG) | Sin `NOT LIKE 'TEST-%'` salvo `OfferDao.observeLatestDetectedAtExcluding`; seeders tras `BuildConfig.DEBUG` | En el APK debug del teléfono, cargar datos de prueba falsea ingresos y metas hasta borrarlos | Marcar o excluir TEST en las vistas de usuario; test que lo garantice | P01 / P09 |
| R-04 | P1 | Tests | Espresso 3.5.1 / androidx.test viejos fallan en API 36 (`InputManager.getInstance`) | 6 fallos de `BestHoursSectionTest` | No hay tests de UI ejecutables en emuladores/teléfonos actuales; P01 añadirá UI | Subir espresso/androidx.test a versiones compatibles (cambio de dependencia de test) | P00.1 / P01 |
| R-05 | P1 | Trips / integridad | `setRiderName` actualiza `trips` sin evento en `ride_events` | `TripDao.setRiderName`; `TripPersistenceCoordinator.submitRiderName` | Rompe la promesa «un viaje nunca cambia sin su evento» (`TripRepository` KDoc); el cambio no es auditable | Registrar un evento (p. ej. `RIDER_SEEN`) en la misma transacción, o documentar la excepción | P01 |
| R-06 | P2 | Dinero | Importes de oferta persistidos como `Double` | `detected_offers.price/fuelCost/netProfit`, `trips.offered_amount` | Contradice «Money siempre Long» en la capa de estimación; el ledger sí es Long | No migrar; columnas nuevas en Long; convertir en el borde (`fromLegacyDouble`) | Deuda |
| R-07 | P1 | Lifecycle | Las firmas de viaje (recogida, llegada, inicio, fin, cancelación) son CANDIDATE; «llegar por», validado para el nombre, no está en `PICKUP_HEADER` | `ScreenSignatures.SPANISH.validated` = solo oferta | En Uber real puede que no se cree ningún Trip → Ganancias sin viajes, dinero sin vincular, km por viaje vacíos | Validación con capturas reales (checklist) antes de construir P01 sobre Trips; añadir «llegar por» como candidata | P01 entrada |
| R-08 | P1 | Finanzas | Firmas de pantalla de ganancias provisionales; desde `cdbc059` un enlace con firma provisional baja a LOW (queda pendiente) | `FinancialDetector`, `FinancialEvidence.PROVISIONAL_SIGNATURE` | Ingresos reales pueden quedar «pendientes» hasta asignación manual | Captura real de la pantalla de resumen de viaje | P03 entrada |
| R-09 | P2 | Concurrencia | `PersistenceQueue`: un consumidor sin aislamiento de `Throwable`, canal ilimitado | `PersistenceQueue.kt` | Un `Error` detiene todas las escrituras (trips, dinero, online, km) en silencio | try/catch `Throwable` por trabajo y métrica de cola | P09 |
| R-10 | P2 | Documentación | Comentarios añadidos en `cdbc059` en `data/db` describen valores inexistentes (OCR, `SCREEN_OCR`, GPS/ODOMETER, MANUAL/AUTOMATIC, LOW/MEDIUM/HIGH, GROSS/NET/DELTA, estados ACCEPTED/STARTED) | `FinancialEntities.kt`, `DistanceEntities.kt`, `OnlineEntities.kt`, `TripEntities.kt` | Induce a error en fases futuras (p. ej. creer que hay OCR) | Corregir comentarios a los enums reales | P00.1 (doc) |
| R-11 | P2 | Versionado | `cdbc059` cambia comportamiento (FinancialDetector, TripRules, Analyzers, CSV) sin subir versión ni CHANGELOG | `git diff fd24c17 cdbc059`; CHANGELOG `[Sin publicar]` vacío | Trazabilidad | Anotar en CHANGELOG `[Sin publicar]` | P00.1 / P01 |
| R-12 | P2 | Arquitectura | Domain importa `OfferEntity`; `IntelligenceViewModel` usa DAOs | ver P00_ARCHITECTURE §5 | Acoplamiento | Mover a repositorio / modelo de dominio cuando se toque | P06 / P10 |
| R-13 | P2 | Privacidad | `OfferDebugCard` visible en release copia la lectura completa de pantalla (nombres, direcciones) al portapapeles | `SettingsScreen.kt:419`; `OfferDebug.fullOffer/fullRider` | Texto bruto de Uber accesible a cualquier app que lea el portapapeles | Mover a DEBUG o al futuro «Laboratorio» con aviso | P09 |
| R-14 | P3 | Migraciones | 1→4 sin esquemas ni tests; 1→2 y 3→4 borran duplicados | `AppDatabase.kt` | Riesgo bajo (instalaciones muy antiguas) | Aceptar como legacy | — |
| R-15 | P2 | Build | Lint no ejecutado en P00; el informe previo tenía fatal `InvalidFragmentVersionForActivityResult` | `docs/ai-audit/16-VALIDATION-RESULTS.md` | Desconocido si sigue | Ejecutar `.\gradlew lint` en P01 o P10 | P10 |
| R-16 | P2 | Reportes | Export de viajes filtra por `created_at`; Ganancias por `completed_at` | `ReportData.build` vs `getTripsCompletedBetween` | Un viaje que cruza el corte de jornada sale en un periodo distinto | Alinear a `completed_at` o documentar | P08 |
| R-17 | P2 | Integración | Intelligence y RD$/km no usan `trip_distances` reales (comentario «no real distance is measured yet» desactualizado) | `Dataset.kt` | Métricas por km siguen siendo estimadas | Integrar km reales | P03 / P05 |
| R-18 | P2 | Batería | GPS HIGH_ACCURACY cada 5 s mientras se mide; sin medición de consumo | `DistancePolicy.fixIntervalMs`, `setGps` | Consumo desconocido | Medir en teléfono; considerar BALANCED en vacío | P02 |
| R-19 | P3 | Release | `isMinifyEnabled=false`, sin firma de release; `Log.d` sin guarda (no sensibles) | `app/build.gradle.kts`, `UberAccessibilityService` | El teléfono probablemente usa APK debug (con herramientas TEST) | Definir build de uso diario | P10 |
| R-20 | P3 | UI | `runBlocking` en main para el tema inicial | `MainActivity.kt:59` | Pequeño bloqueo al arrancar | Splash/estado inicial | P10 |
| R-21 | P3 | Performance | Reportes cargan todos los gastos y tramos (`expenses.getAll()`, `distances.getAll()`) | `ReportData` | Crece lineal con el historial | Consultas por ventana | P08 |

## Resumen

- **P0 abiertos: 0.** (R-00 resuelto en P00.)
- P1: R-01, R-02, R-03, R-04, R-05, R-07, R-08.
- P2: R-06, R-09, R-10, R-11, R-12, R-13, R-15, R-16, R-17, R-18.
- P3: R-14, R-19, R-20, R-21.

## Critical truths (§99)

| Afirmación | Veredicto | Evidencia |
|---|---|---|
| ProfitEngine es única fórmula | **CONFIRMADO** | `domain/ProfitEngine.kt` (`computeBreakdown` único); `ShadowScoring`, `Calibration.score`, `IntelligenceDatasetBuilder` llaman a `ProfitEngine.evaluate` |
| Money siempre Long | **PARCIAL** | Ledger y gastos Long (`FinancialObservationEntity.amountMinor`); ofertas y snapshot en Double (`OfferEntity.price`, `TripEntity.offeredAmount`) — R-06 |
| Trip events son inmutables | **CONFIRMADO** | Solo `insertEvent` (ABORT), sin update/delete salvo prefijo TEST y CASCADE; `TripRepositoryTest` |
| Financial observations son auditables | **CONFIRMADO** | Inmutables; correcciones por `voids_observation_id`; `FinanceTest.voidCancelsAnObservationWithoutDeletingIt`; `ManualAssignment` |
| TEST nunca contamina real | **REFUTADO (debug)** | Sin filtros en Ganancias/Metas/Reportes; seeders solo DEBUG — R-03 |
| No destructive migration | **CONFIRMADO** | 0 usos de `fallbackToDestructiveMigration`; 10 migraciones registradas; 7 tests PASS (1→2 y 3→4 borran duplicados por diseño) |
| No PII persistida | **REFUTADO (autorizado)** | `trips.rider_name`, direcciones (decisión 14/10, CHANGELOG 1.17.0) — R-02 |
| Distance tiene una sola fuente GPS | **CONFIRMADO** | Único `requestLocationUpdates` en `ObservadorForegroundService.setGps` → `DistanceMonitor.onFix` |
| Intelligence no tiene data leakage | **CONFIRMADO** | `ProfitEngine.evaluate(OfferDecisionFeatures, …)`; `IntelligenceSample` separa features de resultados |
| Shadow no cambia active model | **CONFIRMADO** | `ShadowScorer` solo `saveShadow`; `recordCandidate` no toca ajustes |
| Usuario controla promotion | **CONFIRMADO** | `IntelligenceViewModel.promote` tras diálogo, `userConfirmed = true`, rechaza `TEST-`; `rollback` |
| Reports usan los mismos datos que UI | **CONFIRMADO (con R-16)** | `ReportData` → `EarningsReports.build`, `ExpenseRules.summarize` |
