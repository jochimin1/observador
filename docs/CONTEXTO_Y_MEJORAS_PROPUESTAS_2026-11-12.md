# Observador: contexto actual y propuesta de mejoras

Fecha de revisión: 12 de noviembre de 2026. Base: rama `sectores-cerrar`, commit `ca1c7c3`, app **1.37.0**, `versionCode` **46**, Room **14**.

Este informe documenta una propuesta. Las versiones futuras no están implementadas, publicadas ni comprometidas a una fecha. El código de la app no se modifica con este informe. Los documentos PMASTER/P00 anteriores se conservan como antecedentes.

**Seguimiento posterior a la auditoría:** están implementadas **1.37.1 / 47** y **1.37.2 / 48**, con Room 14 y sin nueva migración de estructura. La 1.37.2 protege respaldo, restauración y borrado mediante exclusividad de escrituras, verificación de copias y recuperación al arrancar. Verificación: **680 pruebas JVM y 81 pruebas instrumentadas aprobadas**; lint sin errores bloqueantes. Detalles: [informe de la 1.37.1](releases/1.37.1.md) e [informe de la 1.37.2](releases/1.37.2.md). La validación con Uber real sigue pendiente y los cierres automáticos continúan en simulación. La siguiente entrega general propuesta es **1.38.0**; las posteriores continúan como propuesta. El contexto y los resultados de la auditoría siguientes describen su baseline 1.37.0.

## 1. Qué es la app y qué intenta resolver

Observador es una aplicación Android personal para un conductor de Uber Moto, con interfaz y reglas orientadas a República Dominicana: pesos dominicanos, combustible por galón y nombres de sectores locales. Lee la interfaz de `com.ubercab.driver` mediante accesibilidad. Su objetivo combina cuatro necesidades:

1. **Decidir sobre una oferta:** precio, recogida, distancia, tiempo y puntaje comprensible antes de aceptarla.
2. **Reconstruir el trabajo realizado:** ofertas tomadas, estados del viaje, tiempos, kilómetros y recorrido.
3. **Controlar el dinero:** ingresos observados, diferencias con lo ofertado, gastos, combustible, mantenimiento y metas.
4. **Aprender del historial:** horarios, esperas, errores de estimación y comparación de configuraciones del puntaje.

Funciona principalmente con datos locales. Las calles del mapa se solicitan a OpenStreetMap y los nombres de lugares al servicio Geocoder de Android. Por tanto, «datos locales» no equivale a «sin ninguna comunicación externa».

La estructura actual tiene cinco pestañas: **Inicio, Ganancias, Gastos, Análisis y Ajustes**. Reportes se abre como pantalla secundaria. Incluye temas Sistema, Claro, Oscuro, Noche, Sol y Violeta, bienvenida, perfiles y pausa/apagado.

## 2. Contexto técnico que conviene conservar

| Aspecto | Estado comprobado en el repositorio |
|---|---|
| Plataforma | Android nativo; mínimo API 30, objetivo API 36 y compilación 36.1 |
| Implementación | Kotlin, Jetpack Compose y Material 3; un módulo `:app` |
| Persistencia | Room/SQLite para datos; DataStore para preferencias |
| Concurrencia | Coroutines/Flow y una cola compartida para viajes, dinero, sesiones, km y rutas; existen escrituras adicionales fuera de esa cola |
| Motor de ofertas | `ProfitEngine`, identificado como `PROFIT_ENGINE_V1` |
| Dinero confirmado | `MoneyAmount` en unidades menores enteras; varias estimaciones de ofertas conservan `Double` |
| GPS | Una solicitud de posiciones en `ObservadorForegroundService`, compartida por distancia y rutas |
| Tamaño aproximado | 202 archivos Kotlin de producción; 31 674 líneas no vacías según `Measure-Object -Line`; 43 archivos de pruebas JVM y 23 instrumentados |
| Distribución | Configuración release sin firma definida en el módulo y con minificación desactivada |
| Documentación anterior | Auditoría P00 en 1.18.0/DB 11; el código actual está en 1.37.0/DB 14 |

### Flujo principal

`Uber → accesibilidad → ScreenSnapshot → parser/clasificador → motor y seguimiento de viaje → repositorios/Room → pantallas y exportaciones`.

Los datos de ingresos pertenecen al registro financiero; el precio de una oferta es una referencia. Conviene mantener esa separación en cualquier mejora. Las rutas y la distancia total son productos relacionados, pero la simplificación del mapa no debe sustituir el contador de kilómetros.

### Cómo se obtiene el puntaje actual

El motor combina cuatro factores: RD$/km **35 %**, RD$/hora **30 %**, distancia de recogida **20 %** y minutos de recogida **15 %**. Los pesos son configurables. Los dos primeros cocientes usan el importe ofrecido y las estimaciones de Uber. El costo de combustible se resta para mostrar una ganancia estimada, pero **no entra en la fórmula del puntaje**.

Esto implica que, con los demás parámetros iguales, aumentar el costo por kilómetro cambia la ganancia estimada y mantiene el mismo puntaje. Es una oportunidad para un modelo económico futuro, conservando el significado y el historial de V1.

## 3. Qué ya existe y qué necesita evolucionar

| Área | Ya existe | Siguiente necesidad |
|---|---|---|
| Ofertas | Precio, km/min, Radar con varias tarjetas, modalidad, exclusivo, botón y desglose del puntaje | Identidad estable por aparición, calidad de lectura y detección de cambios de Uber |
| Sectores | Coincidencia textual por recogida/destino; Aviso, Evitar y Cerrar; estadísticas y exportación | Reglas por contexto y explicación de coincidencias; zonas geográficas como módulo distinto |
| Automatización | Simulación, cuenta atrás, cancelación al tocar la tarjeta y registro diario | Revalidación completa al ejecutar, pruebas de integración y validación real de la X |
| Viajes | Estados, eventos, recuperación, nombre/direcciones y detalle repartido entre Inicio/Ganancias/depuración | Centro unificado con todos los estados y correcciones auditables |
| Dinero | Registro de observaciones, totales/componentes, pendientes, asignación manual y conciliación | Validación real de etiquetas, corrección manual completa y conciliación con periodos claros |
| Distancia/ruta | Km por jornada/viaje, puntos GPS, fases, mapa, exportación GPX/GeoJSON y borrado | Cobertura más visible, batería medida y tratamiento de pérdidas de señal |
| Gastos | Combustible, mantenimiento, gastos sueltos, recurrentes y odómetro | Costo operativo imputado por viaje/hora/km y ficha de vehículo |
| Combustible | Método lleno a lleno, mediana de tramos y sugerencias de ajustes | Validar cobertura del GPS usado como alternativa al odómetro |
| Análisis | Calidad de datos, predicción frente a realidad, km reales, esperas, calibración y modelo en sombra | Separar resultados confirmados/inferidos y evaluar por contexto con incertidumbre |
| Reportes | CSV, JSON, ZIP, PDF, filtros, totales, resúmenes y exportación de recorridos | Un criterio de periodo explícito, cierre versionado y privacidad por archivo |
| Respaldo | ZIP completo, SHA-256, verificación, restauración con copia de seguridad y borrado total | Exclusiones del backup de Android y recuperación comprobada frente a fallos |
| Experiencia | Temas, contraste, gráficos, acciones rápidas y bienvenida | Salud operativa visible y ajustes más fáciles de recorrer |

**Funciones que no deben proponerse como si faltaran:** mapas de recorridos, GPS por viaje, respaldo manual, restauración, borrado total, modalidad de oferta, sectores, reportes PDF/CSV/JSON y calibración en sombra.

## 4. Hallazgos que justifican las primeras versiones

Los hallazgos siguientes provienen de lectura del código. Los escenarios de riesgo no equivalen a incidentes reproducidos en un teléfono.

### H01. Una huella numérica se está usando como identidad de oferta

`UberOffer.offerHash()` concatena precio, distancias y minutos con redondeo. Omite direcciones, modalidad y la identidad temporal de la aparición. Además, el formato depende del locale predeterminado. `OfferLedger.of()` asocia viajes y ofertas por esa huella y conserva el viaje más reciente para cada huella.

**Consecuencia posible:** dos ofertas diferentes con las mismas cifras pueden fusionarse durante la deduplicación o aparecer asociadas al mismo viaje. Dos apariciones en días distintos pueden heredar el estado de la más reciente dentro del conjunto consultado. Esto puede afectar Tomados, porcentajes y reportes.

**Solución:** distinguir huella de contenido, sesión de visualización e ID persistente de aparición; relacionar el viaje con la aparición concreta. Conservar vínculos heredados como aproximados si no se pueden reconstruir.

### H02. El cierre automático tiene condiciones pendientes de revalidación

`CloseTarget.VALIDATED = false`: actualmente no se realiza el toque real. Es correcto mantenerlo así hasta verificarlo. La decisión sobre modo, sector y límite diario se toma al programar; `SectorCloser.fire()` busca otra vez el botón, pero no vuelve a consultar todas esas condiciones. Pasar a Asistido actualiza DataStore y no cancela directamente los cierres pendientes en ese objeto. La identidad final se busca por precio, insuficiente ante una sustitución de tarjeta por otra del mismo precio.

**Consecuencia posible al habilitar los toques:** una operación ya programada podría sobrevivir a un cambio de modo/regla o consumir un límite calculado antes de otras operaciones pendientes.

**Solución:** cancelar al cambiar de modo/regla; comprobar observación activa, ID de aparición, contenido completo, ventana, límite y configuración inmediatamente antes de actuar; verificar también el resultado posterior. El límite debe actualizarse de forma serializada. No habilitar el toque cambiando solo una constante.

### H03. La privacidad del backup del sistema sigue sin estar definida

El manifiesto permite backup y los dos XML conservan las reglas de plantilla. Android puede incluir la base de datos y archivos privados en los mecanismos habilitados de copia/transferencia. Ahora la base ya contiene nombres, direcciones, dinero y coordenadas; también hay capturas y registros privados en archivos.

**Solución:** definir explícitamente exclusiones o inclusiones para nube y transferencia entre dispositivos, manteniendo el respaldo manual como decisión visible del conductor. La disponibilidad efectiva de las copias depende de la configuración de Android y del transporte; el código por sí solo no prueba que ya se haya subido una copia.

### H04. Restauración y borrado necesitan una barrera de escritura verificable

`BackupWriter.flushPendingWrites()` y `BackupRestorer.drainWrites()` esperan hasta cinco segundos con `withTimeoutOrNull`, pero no comprueban si venció la espera. La cola no incorpora estado de salud ni confirmación de rechazo al enviar. Hay escritores que no pasan por esa cola. El rollback de restauración emplea `runCatching` sin verificar integralmente el resultado de cada restauración. La comprobación de la copia valida SQLite y versión, pero no ejecuta toda la apertura/migración Room sobre una copia antes del reemplazo definitivo.

**Solución:** modo exclusivo de mantenimiento, barrera para todos los escritores, fallo explícito si no se vacía la cola, prevalidación de migraciones y rollback comprobado. El ZIP actual detecta corrupción con hashes; esos hashes no cifran el contenido ni prueban quién creó el archivo.

### H05. Faltan esquemas de migración que las pruebas esperan

Room declara versión 14, pero en `app/schemas/...` solo se encuentran 4–12. Los tests 12→13 y 13→14 requieren los JSON correspondientes. El changelog indica que 13 se generaría al compilar, pero eso no sustituye disponer del archivo en el repositorio y en los assets del test.

**Solución:** regenerar 13/14 desde sus entidades y commits correctos, contrastarlos con la migración y conservarlos. No escribir hashes arbitrarios para hacer pasar la prueba. Comprobar en un checkout limpio toda la cadena de migraciones mantenida.

### H06. Las validaciones reales son parciales

Las firmas de oferta están validadas y «llegar por» está registrada como frase real. Otras firmas de llegada, inicio, fin y cancelación siguen siendo candidatas. `MONEY_LABELS_VALIDATED = false` y el detector financiero identifica sus firmas como provisionales. Hay recorridos y cierres inferidos cuando Uber vuelve a buscar; ese final no equivale a una confirmación de viaje completado.

**Solución:** corpus de capturas saneadas y pruebas de secuencias reales, con versión de Uber y del detector; separar confianza de lectura, estado de viaje y cobertura monetaria.

### H07. «Mejores horas» necesita distinguir oferta aceptada e ingreso final

El ViewModel alimenta la sección con ofertas tomadas. La estadística calcula cocientes con sus precios/minutos estimados; la interfaz dice «Cuándo pagaron mejor los viajes que hiciste». Esa frase puede entenderse como dinero efectivamente cobrado. Además, el volumen se divide entre franjas de dos horas de días con actividad, no entre el tiempo real observado en línea en cada franja.

**Solución:** etiquetar el cálculo existente como condiciones ofrecidas de los viajes tomados; construir vistas separadas de mercado observado y rendimiento real; normalizar frecuencia por tiempo de observación válido. No afirmar demanda baja durante horas sin captura.

### H08. Los datos de prueba y las ventanas no se tratan igual en todas las vistas

La nueva pantalla de Reportes excluye `TEST-` por defecto. Otras entradas de reporte tienen `includeTest = true` por defecto; Ganancias construye su informe sin un filtro general equivalente. Los viajes exportados se seleccionan por creación y Ganancias por finalización, lo cual puede diferir en un viaje que cruza la hora de jornada.

**Solución:** una política común de origen de datos y periodos, con opción explícita para datos sintéticos y explicación del criterio temporal.

### H09. Parte de la base técnica creció sin una limpieza equivalente

La cola es ilimitada y su consumidor ejecuta cada trabajo sin contención central de fallos; el arranque usa `runBlocking`; varios modelos de dominio importan entidades Room. Ajustes, Inicio y Ganancias rondan 983, 895 y 854 líneas no vacías. Las consultas de gastos/distancias cargan historiales completos en varios flujos.

**Solución:** medir y hacer cambios acotados: salud de escritura, trabajo asíncrono de arranque, modelos de lectura, consultas por ventana y componentes por responsabilidad. No es necesario reescribir la app.

### H10. El análisis estático actual no pasa

La ejecución de `lintDebug` del 12/11/2026 finalizó con **46 errores, 42 advertencias y 1 sugerencia**. Los 46 errores se agrupan en dos identificadores: **44 notificaciones de `InvalidFragmentVersionForActivityResult`**, repetidas en `MainActivity.kt:45`, y **2 `ByteOrderMark`**, en los exportadores CSV. No son 46 defectos funcionales independientes ni demuestran 46 cierres de la app.

**Solución:** comprobar el grafo resuelto y la compatibilidad de Activity/Fragment y del detector antes de decidir la corrección; revisar la representación del BOM en fuente sin romper el BOM intencional de los CSV. Revisar el cálculo de insets y distinguir advertencias reales de falsos positivos: por ejemplo, la advertencia de contexto estático apunta a `PlaceNamer`, que conserva `applicationContext`, y no demuestra una fuga de Activity. No ocultar todo con una baseline para presentar la entrega como limpia.

## 5. Propuesta de versiones

P1 = corrección importante o base necesaria para confiar; P2 = evolución de alto valor; P3 = ampliación opcional. El esfuerzo es relativo: pequeño, mediano o grande. La numeración permite dividir una entrega si resulta demasiado grande; todos los parches deben mantener compatibilidad y toda migración debe conservar datos.

### 1.37.1 — Identidad de ofertas y control de acciones pendientes

**Prioridad P1 · esfuerzo mediano/grande · propósito:** que cada viaje corresponda a la oferta correcta y que una acción pendiente respete la última decisión del conductor.

- Separar el ID de aparición de la huella numérica; utilizar formato estable independiente del idioma del teléfono.
- Asociar viajes a una aparición concreta. Para datos antiguos, usar hora/snapshot y marcar la confianza del enlace sin inventar asociaciones.
- Cancelar cierres pendientes al cambiar a Asistido, desactivar sectores, cambiar una regla, pausar o perder la pantalla.
- Revalidar tarjeta completa, modo y límite justo antes de ejecutar; serializar la reserva del límite diario y verificar el resultado de un toque futuro.
- Mantener simulación mientras falte evidencia real. Corregir además la liberación de todas las raíces de accesibilidad adquiridas cuando una búsqueda termina antes de recorrerlas.

**Ejemplo:** una oferta de RD$120 con las mismas distancias que la de ayer conserva su propia identidad; cambiar a Asistido durante la cuenta atrás anula la operación.

**Validación de salida:** secuencias con dos tarjetas iguales, reordenamiento del Radar, cambio de modo a mitad de cuenta atrás, pausa, cierre del servicio y varias acciones con el contador en 29/30. Ninguna debe aceptar viajes ni tocar una tarjeta sustituta.

### 1.37.2 — Respaldo, restauración y migraciones confiables

**Prioridad P1 · esfuerzo grande · propósito:** proteger el historial y poder recuperarlo con un resultado verificable.

- Definir backup de Android para base, preferencias, capturas, cierres y copia de seguridad local; documentar la elección y conservar el respaldo manual visible.
- Unificar el mantenimiento exclusivo durante restauración/borrado; esperar confirmación de todos los escritores y detenerse ante timeout.
- Comprobar rollback, archivos auxiliares de SQLite y espacio libre; validar la apertura/migración de la copia antes de dar por exitosa la restauración.
- Incorporar los esquemas 13 y 14 auténticos y probar cadenas desde versiones mantenidas.
- Aplicar límites de tamaño al respaldo y a la extracción de la base; el límite actual de textos no cubre toda la base comprimida.
- Corregir el bloqueo de lint de Activity/Fragment y la representación de BOM; separar defectos, duplicados del detector y advertencias para que la calidad inicial quede verificable.

**Ejemplo:** si el almacenamiento está lleno o la cola no termina, la app informa que no puede restaurar; no afirma que los datos quedaron intactos sin comprobarlo.

**Validación de salida:** respaldo de 1.37.0, respaldo antiguo, archivo dañado, interrupción, falta de espacio, escritor activo y fallo en cada paso de reemplazo. Aislar estas pruebas de cualquier teléfono con datos reales.

### 1.38.0 — Lectura de Uber validada y trazable

**Prioridad P1 · esfuerzo grande · depende de capturas representativas · propósito:** que un cambio de Uber no produzca cifras creíbles pero incorrectas.

- Convertir capturas saneadas de oferta, recogida, llegada, inicio, fin, cancelación y dinero en casos de regresión de la secuencia completa.
- Guardar versión del parser/clasificador, versión de Uber observada y razones de baja confianza junto al resultado necesario para auditoría.
- Detectar precios múltiples, tasas por km, recargos incluidos, formatos distintos y lecturas incompletas; exponer «no pude leer» en lugar de una calificación engañosa.
- Permitir marcar una lectura como dudosa y excluirla de análisis sin eliminarla. Las ofertas dañadas por los errores antiguos no se pueden reparar sin evidencia adicional del importe real.
- Recuperar pruebas de UI con dependencias compatibles verificadas; elegir versiones por compatibilidad, sin actualizar todo indiscriminadamente.

**Validación de salida:** corpus real versionado y saneado; métricas separadas de errores y cobertura por tipo de pantalla, en los dispositivos realmente utilizados. El número suficiente de muestras se define al preparar el corpus y se publica con el resultado.

### 1.39.0 — Salud de Observador y calidad de datos

**Prioridad P1 · esfuerzo mediano · propósito:** saber si la app está observando y guardando correctamente antes de confiar en sus números.

- Mostrar accesibilidad conectada, lectura pausada/activa, última lectura válida, última oferta, última posición GPS y edad de cada señal.
- Diferenciar «no llegaron ofertas» de «la lectura dejó de funcionar» y «GPS autorizado» de «GPS entregando posiciones».
- Añadir estado de la cola, trabajos pendientes, última escritura y fallos; contener fallos recuperables respetando cancelación y sin ocultar errores fatales.
- Historial local breve de incidentes y exportación saneada para diagnóstico. Explicar la consecuencia y la acción útil: por ejemplo, volver a abrir la app para recuperar ubicación.
- Unificar exclusión de TEST en cifras reales y mover herramientas técnicas a un Laboratorio explícito.

**Validación de salida:** cada fallo simulado cambia el estado visible y no contamina ganancias ni dispara avisos repetidos. Ningún indicador afirma funcionamiento basándose solo en un permiso habilitado.

### 1.40.0 — Centro de Viajes

**Prioridad P2 · esfuerzo mediano/grande · depende de 1.38/1.39 · propósito:** revisar todo lo que pasó con cada viaje en un solo lugar.

- Listar en curso, completados, cancelados, interrumpidos y recuperados, incluso cuando no se capturó la oferta inicial.
- Unificar ficha, mapa, horas, oferta original, dinero, distancia real, espera, eventos y anomalías reutilizando las implementaciones existentes.
- Filtros por periodo, estado, modalidad, sector y dinero pendiente; búsqueda local con controles de privacidad.
- Mostrar por separado el puntaje al detectar y el recalculado hoy, junto a sus configuraciones.
- Registrar cualquier corrección de metadatos o estado con evento, autor/origen y fecha; incluir el nombre de pasajero en esa política de auditoría.

**Ejemplo:** un viaje cancelado deja de estar escondido en depuración y muestra lo medido, el tiempo perdido y una eventual tarifa de cancelación confirmada.

**Validación de salida:** todos los estados aparecen, la pantalla funciona con datos faltantes y los importes coinciden con Ganancias. La lista carga resúmenes; el detalle completo se solicita al abrirlo.

### 1.41.0 — Dinero completo y correcciones auditables

**Prioridad P1/P2 · esfuerzo grande · depende de lectura validada y Centro de Viajes · propósito:** poder cerrar cuentas aunque Uber no muestre toda la información en una misma pantalla.

- Añadir entrada/corrección manual de total, propina, ajuste, comisión o cargo cuando corresponda, con fuente y posibilidad de anular mediante otra observación.
- Mostrar «pendiente», «parcial» y «confirmado» con el importe y la razón; separar cierre del viaje de confirmación monetaria.
- Revisar totales absolutos frente a componentes y pagos tardíos para impedir sumar dos veces una propina o un ajuste ya incluido.
- Definir conciliación por intervalos explícitos de Uber; no asumir que su día/semana coincide con la jornada personalizada del conductor.
- En caso de registrar efectivo y pagos digitales, separarlos de ingreso económico y saldo a transferir. Un cobro en efectivo no constituye un segundo ingreso.

**Ejemplo:** una propina de ayer recibida hoy tiene fecha de observación y viaje de origen; la interfaz explica qué periodo cambia y por qué.

**Validación de salida:** la suma de componentes no duplica un total; corregir y deshacer deja historial; el precio ofertado nunca rellena un ingreso desconocido.

### 1.42.0 — Rentabilidad operativa real

**Prioridad P2, mayor valor económico · esfuerzo grande · depende de 1.41 y km/tiempos con cobertura · propósito:** conocer lo que deja trabajar y comparar viajes sin que el día de tanqueo distorsione todo.

- Mantener una vista de pagos/gastos del periodo y añadir otra de consumo/costo operativo imputado. Los recurrentes ya se prorratean actualmente: el contrato debe explicar cada base.
- Asignar combustible consumido por km, desgaste/mantenimiento por km, costos fijos por jornada u horas y depreciación opcional según parámetros del conductor.
- Calcular beneficio por viaje, por hora comprometida, por hora en línea y por km operativo, incluyendo recogida y desplazamientos vacíos con un método explícito.
- Versionar el modelo de costos y su fecha de vigencia; mostrar medido, introducido por el usuario y estimado. No duplicar el gasto de combustible con el consumo imputado.
- Mantener márgenes/rangos cuando dinero o GPS estén incompletos; «no calculable» si faltan datos esenciales.

**Ejemplo ilustrativo:** RD$200 de ingreso confirmado − RD$30 de combustible consumido − RD$15 de desgaste − RD$10 de costos fijos imputados = RD$145 de beneficio operativo estimado. No es una cifra obtenida del historial del usuario ni una obligación fiscal.

**Validación de salida:** el costo total se reconcilia con su método de asignación, no hay doble conteo y cambiar un parámetro no reescribe silenciosamente el histórico.

### 1.43.0 — Vehículo, combustible y mantenimiento

**Prioridad P2 · esfuerzo mediano/grande · propósito:** anticipar gastos y mejorar el costo por km con datos propios.

- Ficha de moto activa con odómetro, rendimiento, componentes y parámetros de uso personal/laboral; varias motos solo si hay una necesidad real.
- Historial por componente: cambio, costo, odómetro, vida útil esperada y recordatorios por fecha o kilómetros.
- Exigir cobertura suficiente cuando el cálculo lleno a lleno use GPS como alternativa al odómetro; una medición parcial no debe presentarse como rendimiento de todo el tanque.
- Sugerir parámetros derivados con muestra y confianza; aplicarlos como una decisión visible.
- Evaluar alertas al superar umbrales de km durante el uso, además de al abrir la app/guardar gastos, con deduplicación y trabajo moderado.

**Validación de salida:** tanqueos parciales, reinicio de odómetro, mezcla de uso personal y laboral, pérdida de GPS y cambio de moto conservan resultados explicables.

### 1.44.0 — Puntaje económico V2 y perfiles por contexto

**Prioridad P2 · esfuerzo grande · depende de costos y evidencia suficiente · propósito:** evaluar ofertas con relación a lo que cuesta atenderlas.

- Mantener V1 reproducible y proponer `PROFIT_ENGINE_V2` como algoritmo explícito, con costo por km y beneficio estimado por tiempo comprometido.
- Incorporar espera esperada y retorno/reposicionamiento solo cuando exista historial suficiente; presentar un intervalo, no una precisión inventada.
- Explicar en lenguaje directo los factores: «recogida larga», «paga por debajo de tu objetivo», «costo elevado».
- Perfiles por vehículo, modalidad o turno, manteniendo la confirmación del conductor y mostrando cuál está activo.
- Comparar V2 en sombra con V1 antes de promoverlo. Si faltan datos, usar el modelo conocido con una advertencia de cobertura.

**Validación de salida:** entradas conocidas producen resultados deterministas; los costos altos se reflejan en la evaluación; los puntajes históricos conservan su algoritmo y ajustes.

### 1.45.0 — GPS, mapas y controles de privacidad

**Prioridad P2 · esfuerzo grande · propósito:** mejorar rutas y batería dando al conductor control sobre el historial sensible.

- Medir consumo con Uber y Observador juntos, en recorridos equivalentes; ajustar precisión, frecuencia y entrega según fase/movimiento a partir de esos resultados.
- Mostrar huecos de señal, cobertura por fase y motivo de descarte; mantener separados km del tracker y geometría simplificada del mapa.
- Manejar fallos asíncronos de solicitud GPS y servicio limitado en segundo plano, no solo la llamada inicial; guiar la recuperación desde la app.
- Preferencias para guardar nombres, direcciones, rutas y consultas de nombres de lugares; retención elegible y vista del espacio ocupado. No borrar histórico sin una acción explícita.
- Exportaciones saneadas por defecto en los flujos donde sea adecuado y opción explícita de incluir identidad/coordenadas; respaldo manual cifrado opcional con recuperación bien explicada.
- Mejorar caché de mapas: evitar descargas simultáneas duplicadas, revisar su límite durante sesiones largas y soportar cabeceras de caché/revalidación cuando resulte necesario. El código ya cachea 30 días y limita descargas a dos: eso es una base existente.

**Validación de salida:** ruta con pantalla apagada, permiso aproximado, pérdida de señal, reinicio y red caída; batería medida y documentada. Respetar la política del proveedor de mapas; no añadir descarga masiva sobre el servidor público de teselas.

### 1.46.0 — Sectores configurables y zonas de trabajo

**Prioridad P2 · esfuerzo mediano/grande · depende de datos de ubicación y privacidad · propósito:** adaptar avisos al contexto y conocer el rendimiento histórico de las zonas.

- Alias de sectores, excepciones, horarios y modalidad; una pantalla de prueba explica qué fragmento de la dirección hizo coincidir una regla.
- Separar sector identificado por texto de zona medida con GPS. La dirección textual de una oferta no proporciona una coordenada exacta por sí sola.
- Mapa histórico de viajes propios con dinero, esperas, recogida y costo de reposicionamiento; mostrar tamaño de muestra y fecha.
- Ocultar resultados insuficientes y distinguir «no observado» de «mal rendimiento»; evitar presentar zonas de cuidado como una medición objetiva de seguridad.
- Habilitar cierres reales solo después de superar 1.37.1 y validar la X con evidencia; conservar simulación y control inmediato de cancelación.

**Validación de salida:** sectores homónimos, calles con nombre de sector, destinos incompletos, límites de zona y cambios de regla no producen acciones ambiguas. Las recomendaciones geográficas reflejan únicamente el historial observado.

### 1.47.0 — Horarios, mercado y eficiencia

**Prioridad P2 · esfuerzo mediano/grande · propósito:** decidir cuándo trabajar y dónde se pierde tiempo con denominadores correctos.

- Vista de condiciones ofrecidas de todas las ofertas observadas, vista de ofertas tomadas y vista de resultados monetarios confirmados.
- Ofertas por hora de observación válida en cada franja; separar tiempo desconectado, lectura perdida y cero ofertas.
- Distribución de tiempo: buscando, recogida, espera de pasajero, viaje y pausas; distribución de km con pasajero, recogida y vacío.
- Comparaciones por día de semana, modalidad y turno con muestra mínima y periodos equivalentes.
- Proponer cambios concretos ligados a una pérdida observada, sin prometer que una franja o una zona seguirá rindiendo igual.

**Validación de salida:** las categorías temporales no se solapan; su suma cuadra con el periodo observado. El informe nunca denomina ganancia real al cociente de un precio ofertado.

### 1.48.0 — Calibración con evidencia y seguimiento del modelo

**Prioridad P2/P3 · esfuerzo grande · depende de calidad financiera y temporal · propósito:** mejorar el puntaje demostrando que funciona fuera de los datos con que se eligió.

- Extender el backtest y el modelo en sombra existentes; no crear otra fórmula paralela.
- Validación temporal en varias ventanas, separación por modalidad/turno y cuantificación de incertidumbre.
- Evaluación principal con finales confirmados; finales inferidos en un análisis de sensibilidad separado. Publicar cómo cambia la conclusión al incluirlos.
- Detectar degradación tras cambios de Uber, combustible o patrón de trabajo, conservando reversión al modelo anterior.
- Explicar el sesgo de selección: solo existen resultados de viajes tomados. No calcular supuestas ganancias perdidas de ofertas rechazadas como si se hubieran realizado.

**Validación de salida:** el candidato mejora un criterio acordado en datos posteriores, con muestra publicada; los resultados insuficientes bloquean su promoción. Ningún candidato se activa por sí solo.

### 1.49.0 — Cierre de jornada y reportes coherentes

**Prioridad P2 · esfuerzo mediano/grande · depende de Centro de Viajes, finanzas y costos · propósito:** terminar el día sabiendo qué pasó y qué falta revisar.

- Cierre con viajes, ingresos confirmados, pendientes, gasto registrado, costo operativo, horas, km y cobertura.
- Lista de incidencias pendientes: viaje sin cierre, dinero sin vincular, GPS parcial o lectura sospechosa.
- Criterio de periodo visible: inicio, fin, observación de pago o jornada; misma regla en pantalla y archivo. Tratar explícitamente los viajes que cruzan el corte.
- Un solo modelo de reporte para CSV/JSON/PDF y vistas; política TEST uniforme en todos los accesos.
- Reportes con fecha de cálculo, versión de parser/modelo/costos y nueva revisión cuando llega un ajuste tardío.
- Metas opcionales de beneficio operativo y tiempo máximo, diferenciadas de las metas de ingreso actuales.

**Validación de salida:** totales reconstruibles desde el detalle; jornada nocturna y semana personalizada coherentes; un ajuste posterior no cambia un informe cerrado sin indicar su revisión.

### 1.50.0 — Interfaz para trabajar y revisar

**Prioridad P2 · esfuerzo mediano · propósito:** consultar lo esencial rápidamente y revisar después con menos esfuerzo.

- Reorganizar Ajustes en lectura, avisos, rentabilidad, vehículo, privacidad/respaldo y Laboratorio.
- Presentar carga, vacío, error y parcial de forma distinta; mostrar «sin dato» en vez de cero cuando corresponda.
- Consolidar acceso a Viajes y Reportes sin multiplicar innecesariamente las pestañas.
- Tarjeta sobre Uber configurable para pocas cifras, posición estable y texto legible; colores acompañados de etiquetas y acciones de pausa/cancelación accesibles.
- Revisar tamaño de letra grande, TalkBack, contraste, temas Sol/Noche/Violeta y consistencia de acciones rápidas.

**Validación de salida:** las tareas frecuentes se prueban con usuarios y en pantallas reales; formularios no pierden datos con rotación/recreación y los mensajes de estado se comprenden sin detalles técnicos.

### 1.51.0 — Rendimiento y arquitectura mantenible

**Prioridad P2 · esfuerzo mediano/grande · propósito:** que el historial crezca manteniendo lectura rápida y consumo razonable.

- Medir latencia de lectura/tarjeta, arranque, consultas y memoria; definir presupuestos con el teléfono objetivo.
- Consultas por ventana, agregados SQL y paginación donde el volumen lo requiera; evitar cargar todas las distancias y gastos para cada actualización.
- Retirar `runBlocking` del arranque con un estado inicial controlado.
- Separar modelos de dominio y entidades Room; mover DAOs fuera de ViewModels y dividir pantallas grandes por responsabilidad.
- Cola de persistencia con métricas y recuperación; decidir límites sin descartar silenciosamente eventos financieros o de viaje. Mantener una sola fuente de GPS.

**Validación de salida:** benchmark con volumen de historial explícito, comparación antes/después y mismas cifras; probar cancelación de flujos y ausencia de crecimiento indefinido de colas/cachés.

### 1.52.0 — Entrega estable y mantenimiento continuo

**Prioridad P2 · esfuerzo mediano · propósito:** que cada actualización sea reproducible, verificable e instalable sin perder datos.

- Integración continua en checkout limpio: JVM, análisis estático, compilación, esquemas/migraciones y pruebas instrumentadas aisladas.
- APK release firmado, claves administradas fuera del repo y pruebas de actualización sobre la instalación existente. Si cambia la clave de firma respecto al APK actual, resolver antes la transición; no asumir actualización directa.
- Evaluar reducción de tamaño/minificación con pruebas de regresión y mapa de símbolos conservado.
- Matriz de los Android/dispositivos realmente usados y checklist de jornada real.
- Estado del roadmap actualizado a versiones y evidencias actuales, historial de decisiones y notas de versión.

**Validación de salida:** instalación y actualización comprobadas, respaldo/restauración verificados en entorno aislado y ninguna función provisional presentada como validada.

## 6. Relación con P00–P10

| Roadmap anterior | Situación actual | Correspondencia propuesta |
|---|---|---|
| P00 Auditoría | Documento útil, desactualizado respecto a app/DB | Este informe + 1.37.1/1.37.2 |
| P01 Centro de Viajes | Fichas repartidas; centro unificado pendiente | 1.40.0 |
| P02 Telemetría | Ruta/GPS ya existe desde 1.24.0; telemetría avanzada pendiente | 1.45.0; extender lo existente |
| P03 Rentabilidad | Ganancias y gastos presentes; imputación por viaje pendiente | 1.41.0–1.42.0 |
| P04 Vehículo | Odómetro/combustible/mantenimiento básicos presentes | 1.43.0 |
| P05 Eficiencia | Sesiones, distancias y esperas presentes | 1.47.0 |
| P06 Mercado | Estadísticas horarias existentes con alcance por aclarar | 1.47.0 |
| P07 Zonas | Sectores textuales presentes; análisis espacial pendiente | 1.46.0 |
| P08 Cierres/reportes | Exportaciones amplias presentes; cierre versionado pendiente | 1.49.0 |
| P09 Salud | Diagnósticos parciales y de laboratorio | 1.39.0; conviene adelantarlo |
| P10 Integración | Necesidad transversal | 1.50.0–1.52.0 |

La correspondencia es una propuesta nueva; no modifica la instrucción histórica de detenerse tras P00 ni afirma que se hayan ejecutado formalmente P01–P10. Las funciones añadidas posteriormente se evalúan por su código actual.

## 7. Orden recomendado y alcance futuro

**Primer objetivo:** 1.37.1 → 1.37.2 → 1.38.0 → 1.39.0. Resuelve asociación, recuperación, lectura y confianza operativa.

**Segundo objetivo:** Centro de Viajes → dinero completo → rentabilidad → vehículo → puntaje V2. Es el tramo que más ayuda a saber cuánto deja trabajar.

**Tercer objetivo:** GPS/privacidad → zonas → horarios/eficiencia → calibración → cierre/reportes; UX y rendimiento se mejoran también cuando se toca cada módulo.

**Criterio de versión 2.0:** no hace falta usar 2.0 solo por terminar este plan. Se reserva para un cambio de contrato/compatibilidad que realmente lo justifique. Varias motos con historiales independientes, otras plataformas/países o sincronización entre dispositivos son posibles líneas futuras, pero requieren definir necesidad, identidad, privacidad, conflictos de datos y mantenimiento. No las pondría antes de consolidar el uso actual.

## 8. Verificación realizada y límites

- Se revisaron configuración, manifiesto, historial, inventario de fuentes/pruebas, auditoría y estado de roadmap anteriores, además de los módulos principales de ofertas, puntaje, seguimiento, dinero, gastos, GPS/rutas, modelos, reportes, respaldo y UI.
- Comando solicitado: `.\\gradlew.bat testDebugUnitTest lintDebug --console=plain`. La tarea JVM quedó `UP-TO-DATE`; los XML vigentes contienen **635 pruebas, 0 fallos, 0 errores y 0 omitidas**. No equivale a reejecutar hoy las 635 pruebas.
- `lintDebug` **FAIL**, ejecutado en esta revisión: 46 errores, 42 advertencias y 1 sugerencia; duración total del comando combinado 5 min 40 s. Errores: 44 notificaciones repetidas de `InvalidFragmentVersionForActivityResult` y 2 `ByteOrderMark`. El reporte actual sustituyó al XML antiguo del 14/10/2026. No se modificó código para corregirlos durante esta revisión.
- No se ejecutaron pruebas conectadas ni se operó un teléfono/emulador en esta revisión; no se valida aquí el comportamiento de Uber real, el consumo de batería, un cierre automático ni una restauración física completa.
- No se afirma que los módulos pendientes hayan fallado en el teléfono: se documentan las limitaciones declaradas y los riesgos visibles en código.

## 9. Archivos de referencia para continuar

Prefijo común de código: `app/src/main/java/com/ubers/observador/`.

| Contexto | Referencias |
|---|---|
| Identidad/versiones | `app/build.gradle.kts`, `gradle/libs.versions.toml`, `CHANGELOG.md` |
| Entrada y navegación | `MainActivity.kt` |
| Lectura | `service/UberAccessibilityService.kt`, `service/OfferParser.kt`, `domain/lifecycle/ScreenSignatures.kt` |
| Identidad de oferta | `domain/models/UberOffer.kt`, `domain/OfferListOptions.kt`, `data/db/OfferDao.kt` |
| Puntaje | `domain/ProfitEngine.kt`, `domain/EngineSettings.kt`, `domain/ProfitModel.kt` |
| Cierres | `domain/sectors/SectorClose.kt`, `service/SectorCloser.kt`, `service/ObservadorForegroundService.kt` |
| Viajes | `data/TripRepository.kt`, `domain/trips/TripRules.kt`, `service/TripPersistenceCoordinator.kt` |
| Dinero | `domain/finance/FinancialDetector.kt`, `domain/finance/FinancialLedger.kt`, `domain/earnings/Earnings.kt`, `ui/earnings/EarningsViewModel.kt` |
| Gastos/costos | `domain/expenses/ExpenseRules.kt`, `domain/expenses/ExpenseOverview.kt`, `service/MaintenanceNotifier.kt` |
| GPS y mapa | `service/ObservadorForegroundService.kt`, `service/RouteMonitor.kt`, `domain/route/RouteRecorder.kt`, `ui/route/TileStore.kt`, `service/PlaceNamer.kt` |
| Análisis/modelos | `data/IntelligenceRepository.kt`, `domain/intelligence/Dataset.kt`, `Calibration.kt`, `Shadow.kt` |
| Horarios | `domain/BestHoursStats.kt`, `ui/dashboard/DashboardViewModel.kt`, `ui/dashboard/BestHoursSection.kt` |
| Datos/exportación | `data/export/ReportData.kt`, `ui/reports/ReportsViewModel.kt` |
| Respaldo | `data/backup/BackupWriter.kt`, `BackupRestorer.kt`, `BackupVerifier.kt`; XML en `app/src/main/res/xml/` |
| Esquemas/pruebas | `data/db/AppDatabase.kt`, `app/schemas/`, `app/src/androidTest/` |
| Escritura | `service/PersistenceQueue.kt` |
| Antecedentes | `docs/audit/`, `docs/roadmap/`, `docs/p00.md`–`docs/p10.md` |

## 10. Documentación externa contrastada

- [Android Auto Backup](https://developer.android.com/identity/data/autobackup): datos incluidos por defecto y reglas de copia/transferencia. Sustenta H03; no prueba que el dispositivo concreto haya efectuado una copia.
- [Tipos de servicio en primer plano](https://developer.android.com/develop/background-work/services/fgs/service-types): requisitos de ubicación y restricciones. Sustenta las pruebas de recuperación GPS propuestas.
- [Ubicación y batería](https://developer.android.com/develop/sensors-and-location/location/battery): precisión, frecuencia y latencia influyen en el consumo. Las mejoras de batería necesitan medición propia.
- [Política de teselas de OpenStreetMap](https://operations.osmfoundation.org/policies/tiles/): identificación, atribución, caché y ausencia de descargas masivas. La caché actual de 30 días ya cubre el mínimo alternativo de siete días indicado por la política cuando no se interpretan cabeceras; soportarlas sería una evolución, no prueba automática de incumplimiento.
