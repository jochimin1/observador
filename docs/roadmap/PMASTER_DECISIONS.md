# PMASTER · Decisiones transversales

Solo decisiones que afectan a varias fases.

## DEC-001 · Una sola fuente GPS
Estado: VIGENTE (verificado en P00).
`ObservadorForegroundService.setGps` es el único `requestLocationUpdates`. P02 debe extraerlo a un `LocationObservationSource` compartido, nunca crear un segundo tracker.

## DEC-002 · Mercado ≠ resultado; oferta ≠ ingreso
Estado: VIGENTE (verificado en P00).
`detected_offers` y `TripOfferSnapshot` son referencia; el ingreso solo sale del ledger (`FinanceTest.offerIsNeverIncome`).

## DEC-003 · Coordenadas GPS
Estado: VIGENTE hasta P02 — hoy las posiciones **no se guardan** (`LocationFix` solo en memoria; manifest y `Distance.kt` lo prometen).
P02 debe sustituir esta decisión de forma explícita (almacenamiento local, sin logs, export geográfico explícito) y requiere R-01 (backup) resuelto antes.

## DEC-004 · PII de pasajero y direcciones
Estado: **PENDIENTE DE RATIFICACIÓN**.
Hecho: desde 1.17.0 (decisión del usuario del 14/10/2026) se guardan `trips.rider_name`, direcciones de oferta en `detected_offers` y `trips`, y se exportan en CSV/JSON de viajes. PMASTER §39 lo prohíbe «salvo cambio explícito futuro autorizado».
Opciones: (a) ratificar como autorizado, con exclusión de backup y política de retención; (b) revertir en un P00.1 (dejar de capturar; las filas existentes no se borran sin decisión aparte).

## DEC-005 · Money
Estado: VIGENTE. Todo valor monetario nuevo se persiste en Long minor units. Las columnas Double de ofertas (`price`, `offered_amount`…) se mantienen como legado de estimación; no se migran.

## DEC-006 · Datos TEST
Estado: VIGENTE con hallazgo R-03. Prefijo `TEST-`, seeders solo en DEBUG, borrado solo por prefijo. Pendiente: las vistas de usuario deben marcar o excluir TEST.
