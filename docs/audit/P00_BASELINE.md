# P00 · Baseline técnico oficial

Fecha: 2026-10-15 · Prompt: `docs/p00.md` (SHA-256 `7e6e607b8832267e6a88fc5f866f87300da4397d22a8add054fdc790a162b9c4`)
Auditoría read-only. Única modificación de código: arreglo de una firma de test (ver §6).

## 1. Identidad

| Campo | Valor (evidencia) |
|---|---|
| Proyecto | Observador, un solo módulo `:app` |
| applicationId / namespace | `com.ubers.observador` (`app/build.gradle.kts`) |
| App observada | `com.ubercab.driver` (`RideLifecycleMonitor.UBER_DRIVER_PACKAGE`, `accessibility_service_config.xml`) |
| versionName / versionCode | **1.18.0 / 19** |
| DB Room | **v11** (`AppDatabase.kt` `version = 11`; `11.json` identityHash `eeb038a12de2f8e443976d5c74f2064b` = `AppDatabase_Impl` generado) |
| Código main | 149 archivos `.kt`, 25 581 líneas |

## 2. Git baseline (A0)

| Campo | Valor |
|---|---|
| branch | `temas` |
| commit de auditoría | `cdbc059` «wip: cambios previos a PMASTER P00» (trabajo previo del usuario, commiteado por PMASTER a petición del usuario, sin cambios de contenido) |
| commit anterior | `fd24c17` Temas (v1.18.0) |
| remote | `origin` https://github.com/jochimin1/observador.git |
| divergencia | `origin/temas...HEAD` = 0 detrás / 1 adelante (`cdbc059` no publicado) |
| dirty | No, salvo `docs/p00–p10.md` y `docs/PMASTER.md` (sin seguimiento, decisión del usuario) |
| ramas locales | main, temas, direcciones, fase-7a…7d, ganancias, gastos, km-gps, perfiles-y-pausa… (main = v1.5.0; todo el trabajo posterior vive en ramas de fase) |

> Aviso: `cdbc059` contiene cambios de comportamiento sin versión ni CHANGELOG (`FinancialDetector`: firma provisional → LinkConfidence LOW; `TripRules`: hito terminal en recuperación; `Analyzers`; `Csv.spreadsheetText`; logs de `OfferParser`). Ver R-11.

## 3. Versiones (A1)

| Componente | Versión | Fuente |
|---|---|---|
| AGP | 9.4.1 | libs.versions.toml |
| Gradle | 9.8.0 | gradle-wrapper.properties |
| Kotlin | 2.2.10 | libs.versions.toml |
| KSP | 2.2.10-2.0.2 | libs.versions.toml |
| Compose BOM | 2026.02.01 | libs.versions.toml |
| Room | 2.7.0 | libs.versions.toml |
| Navigation Compose | 2.7.7 | libs.versions.toml |
| Coroutines | 1.8.1 | libs.versions.toml |
| DataStore | 1.1.0 | libs.versions.toml |
| Play Services Location | 21.3.0 | libs.versions.toml |
| Fragment (constraint) | 1.3.6 | añadido en `cdbc059` para ActivityResult |
| Espresso / androidx.junit | 3.5.1 / 1.1.5 | **incompatible con API 36** (ver R-04) |
| minSdk / targetSdk / compileSdk | 30 / 36 / 36.1 | app/build.gradle.kts |
| JVM target | 11 | app/build.gradle.kts |
| Release | `isMinifyEnabled = false`, sin `signingConfig` | app/build.gradle.kts |

Drift: `docs/ai-audit/16-VALIDATION-RESULTS.md` (14/10) dice «489 tests, 1 fallo» e «instrumentadas no ejecutadas»; hoy: 489/0 y 55 instrumentadas ejecutadas.

## 4. Build (A2)

| Comando | Resultado | Duración | Detalle |
|---|---|---|---|
| `.\gradlew test` | **PASS** | 259 s (BUILD 4m16s) | 30 suites, **489 tests, 0 fallos, 0 errores, 0 omitidos** (solo `testDebugUnitTest`) |
| `.\gradlew assembleDebug` | **PASS** | 21.6 s | `app-debug.apk` 39.6 MB; sin warnings `w:` de Kotlin |
| `.\gradlew connectedDebugAndroidTest` (emulator-5580, AVD «ObservadorAuditD», Android 16 / API 36) | **FAIL (entorno)** → ver §6 | 147 s | 47 ejecutados, 40 PASS, 7 FAIL |
| reejecución `TripRepositoryTest` tras arreglo | **PASS** | 51 s | 9/9 |

Warnings relevantes: `android.disallowKotlinSourceSets=false` experimental; funciones Gradle deprecadas (incompatibles con Gradle 10). Lint no ejecutado en P00 (no exigido; el informe previo reportaba fatal `InvalidFragmentVersionForActivityResult`, presuntamente resuelto por la constraint de Fragment → sin verificar, R-15).

## 5. Instrumentados: resultado consolidado

| Clase | Tests | Resultado | Clasificación |
|---|---|---|---|
| Migration4To5 … Migration10To11 (7 clases) | 14 | PASS | — |
| TripRepositoryTest | 9 | PASS (tras arreglo) | antes: CODE_ERROR en test, nunca se había ejecutado |
| Financial/Intelligence/Online/Distance/Expense/ManualAssignment RepositoryTest | 23 | PASS | — |
| ReportExportTest | 2 | PASS | — |
| ExampleInstrumentedTest | 1 | PASS | — |
| BestHoursSectionTest (Compose UI) | 6 | **FAIL** | ENVIRONMENT/CONFIGURATION: `NoSuchMethodException InputManager.getInstance` (Espresso 3.5.1 en API 36) |
| **Total** | **55** | **49 PASS / 6 FAIL entorno** | |

Validación de emulador = DEVICE (emulador) para Room/migraciones/repos. **Nada de esto es validación con Uber real.**

## 6. Modificación de código durante P00

`app/src/androidTest/java/com/ubers/observador/TripRepositoryTest.kt:171`:
`fun aTripSurvivesARestartAndIsRestoredAsTheSameTrip() = runBlocking {` → `runBlocking<Unit> {`.
Causa raíz: el último valor del bloque no era `Unit`; JUnit invalida **toda la clase** («Method … should be void»), así que los 9 tests de identidad de viaje nunca habían corrido desde Fase 7B (`9b27a60`). Justificación p00 §83.2 («test infrastructure no puede ejecutarse»): mínimo, solo test, sin cambio funcional.

## 7. Estado de módulos (resumen; detalle en P00_ARCHITECTURE.md)

| Módulo | Estado |
|---|---|
| Offers / ProfitEngine / Dashboard / BestHours | PRODUCTION_READY en código; parser con validación real parcial |
| RideLifecycle / Trips | IMPLEMENTED_UNVALIDATED (firmas de oferta validadas; resto candidatas) |
| Money / Financial / Earnings / ManualAssignment | IMPLEMENTED_UNVALIDATED (sin captura real confirmada de pantalla de ganancias) |
| Online | IMPLEMENTED_UNVALIDATED (etiquetas de capturas reales 12/10) |
| Distance | IMPLEMENTED_UNVALIDATED (sin prueba en carretera documentada) |
| Expenses / Goals / Reports | IMPLEMENTED (entrada manual; automatizados) |
| Intelligence / Shadow | EXPERIMENTAL (REAL_DATA_INSUFFICIENT probable) |
| Debug tools | DEBUG_ONLY (salvo OfferDebugCard, visible en release) |

## 8. Bloqueadores conocidos

- **P0 abiertos: ninguno.** P0 resuelto en P00: R-00 (TripRepositoryTest nunca ejecutado).
- P1 que condicionan el roadmap: R-01 backup, R-02 conflicto de privacidad, R-03 TEST sin filtrar, R-04 Espresso, R-05 rider name sin evento. Ver `P00_RISK_REGISTER.md`.
