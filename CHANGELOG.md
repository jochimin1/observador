# Changelog

Todos los cambios importantes de Observador se registran aquí.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones siguen
[Versionado Semántico](https://semver.org/lang/es/):

- **MAJOR** (2.0.0): cambios que rompen algo, por ejemplo datos que dejan de ser compatibles.
- **MINOR** (1.1.0): funciones nuevas. Cada fase del plan del dashboard sube este número.
- **PATCH** (1.1.1): correcciones sin funciones nuevas.

Cada versión sube también `versionCode` en `app/build.gradle.kts` y lleva una etiqueta de git
(`v1.1.0`). La versión instalada se ve al final de Ajustes.

## [Sin publicar]

## [1.19.0] - 2026-10-16

Registro de viajes y ofertas. Incluye todo lo de la 1.18.0. La base de datos no cambia (sigue en 11).

### Corregido
- **Los viajes tomados no se registraban** y «Viajes realizados» marcaba 0. Causa: la pantalla de ir
  a recoger se buscaba con frases adivinadas («recoger a…») y Uber muestra «Llegar por <nombre>»,
  así que el viaje nunca pasaba de oferta a viaje. Ahora «Llegar por» es una frase confirmada con tu
  captura y basta por sí sola (confianza media, confirmada medio segundo después).
- Si Uber vuelve a «Buscando viajes» y se queda 45 segundos sin ninguna pantalla del viaje ni oferta
  nueva, Observador da el viaje por terminado. Se guarda como **terminó sin confirmar** (INTERRUPTED),
  nunca como completado: puede que se haya cancelado y Observador no lo sabe. Un solo vistazo a
  «buscando» no cierra nada.

### Añadido
- **Inicio** muestra todas las ofertas con tres filtros: **Todo**, **Ofertados** (los que viste y no
  tomaste) y **Tomados** (los que aceptaste), cada uno con su cantidad. Las ofertas tomadas llevan
  su estado: yendo a recoger, en viaje, terminado, cancelado o terminó sin confirmar. El resumen, las
  tarjetas por categoría y «Mejores horas» siguen el filtro elegido; «Mejores horas» usa los viajes tomados.
- **Ganancias** cuenta los viajes tomados que Uber cerró al volver a buscando («N sin cierre
  confirmado») y los marca así en la lista. No suman dinero por sí solos; el dinero de cada uno se
  puede asignar a mano como antes.
- **Meta de hoy** usa la cifra **HOY** que Uber muestra sobre el mapa cuando es mayor que el dinero
  confirmado viaje por viaje, y lo dice («según la cifra HOY de Uber»). Esa cifra no tiene ritmo
  porque el ritmo necesita viajes con dinero confirmado.

### Pruebas
- 14 pruebas JVM nuevas (`TripRegistrationTest`): «Llegar por» como pantalla de recogida, cierre al
  quedarse en «buscando», un vistazo no cierra, una oferta reinicia la cuenta, qué viajes cuentan
  como hechos, asignación manual a un viaje sin confirmar, Meta de hoy con la cifra de Uber y los
  tres filtros de Inicio.

## [1.18.0] - 2026-10-14

Fase 8G: temas. Incluye todo lo de la 1.17.0. La base de datos no cambia (sigue en 11).

### Añadido
- Ajustes → «Apariencia» con cinco temas y vista previa de cada uno:
  - **Sistema**: claro u oscuro como el teléfono (el de siempre).
  - **Claro** y **Oscuro**: fijos, sin depender del teléfono.
  - **Noche**: negro puro con grises para separar tarjetas, texto blanco al 96 %; menos brillo y
    menos batería en pantallas AMOLED (guía de Google para pantallas de manejo).
  - **Sol**: alto contraste (nivel 1.0 de Material) con texto casi negro y letras un paso más
    firmes; la tarjeta flotante sobre Uber sale un 15 % más grande.
- «Noche automática»: pasa a Noche entre las horas que elijas (19 a 6 por defecto) aunque el
  teléfono esté en modo claro, y vuelve a tu tema después.
- Los iconos de la barra de estado siguen al tema que se ve, no al del teléfono.

### Cambiado
- Los colores viven en un solo lugar (`ThemePalettes`), sin colores del fondo de pantalla. Los de
  MALO, BUENO y EXCELENTE no cambian con el tema.
- Dos textos que usaban el color de los bordes («Sin dinero visto» y las cantidades anuladas)
  usan ahora el color de texto secundario, que se lee mejor.

### Pruebas
- 6 pruebas JVM nuevas (`ThemeTest`): contraste de 4.5:1 o más en cada par de texto y fondo de
  los cuatro temas y de los tres veredictos, Sol con más contraste que Claro, Noche en negro puro,
  y la Noche automática cruzando la medianoche.

## [1.17.0] - 2026-10-14

Fase 8F: origen, destino y pasajero, y diagnóstico de las tarjetas de ofertas. Incluye todo lo de
la 1.16.0. Base de datos 10 → 11 (solo añade columnas).

### Cambiado (decisión del 14/10)
- Por decisión tuya, Observador ahora **guarda** la dirección de recogida y la de destino que Uber
  muestra en la tarjeta de la oferta, y el nombre del pasajero que aparece al aceptar («Llegar por
  …»). Se guardan con la oferta y con el viaje, y salen en el archivo de viajes (CSV y JSON). Nunca
  se leen ni se guardan coordenadas.

### Añadido
- La tarjeta flotante y la notificación de oferta rentable muestran ▲ recogida y ▼ destino.
- Origen, destino y pasajero en el detalle del viaje (Inicio y Ganancias), en la lista de viajes
  de Ganancias y al asignar una cantidad a un viaje.
- Si la tarjeta de la oferta no está en la ventana activa (Uber la puede mostrar como ventana
  aparte, o hay otra app delante), Observador la busca en las demás ventanas de Uber.
- Ajustes → «Ofertas (diagnóstico)»: lecturas con «Aceptar», ofertas leídas, tarjetas mostradas,
  notificaciones, en qué paso falló la lectura, recogida, destino y pasajero leídos, y botones
  «Copiar oferta» y «Copiar recogida» con la lectura completa para revisarla.
- «Probar notificación» junto a «Probar tarjeta»: envía el aviso de oferta rentable de ejemplo y
  dice si Android tiene las notificaciones apagadas o si estás en un modo que no avisa (solo el
  modo Asistido avisa con ofertas reales).

### Pendiente de validar
- La lectura de las direcciones es candidata hasta comprobarla con una oferta real (la regla:
  la dirección va después de su tiempo y distancia). «Llegar por …» ya está confirmado con tu
  captura.

### Pruebas
- 8 pruebas JVM nuevas (`OfferReadingTest`) y la de exportación ajustada (nunca coordenadas;
  pasajero y direcciones en el archivo de viajes, con comillas cuando llevan coma).
- `Migration10To11Test` y las pruebas de migración anteriores abren la cadena hasta 11.

## [1.16.0] - 2026-10-13

Fase 8D: reporte semanal y exportación. Incluye todo lo de la 1.15.0. La base de datos no
cambia (sigue en 10).

### Añadido
- «Resumen de la semana» en Ganancias (vista Semana): comparación con la semana anterior solo si
  las dos tienen al menos 80 % de viajes con dinero confirmado (si no, «No comparable: falta
  dinero de algunos viajes»), tiempo en línea y ocupación, ganancia por hora, gastos, neto y meta
  semanal cumplida o no. Usa las mismas cifras de Ganancias; no tiene fórmulas propias.
- Aviso opcional «Tu resumen de la semana está listo» al empezar la semana nueva (15 minutos
  después de tu hora de inicio), con una alarma de Android que se vuelve a programar al reiniciar
  el teléfono, al actualizar la app o al cambiar la hora. Apagado hasta que lo actives en Ajustes.
  Al tocarlo abre Ganancias en la semana que terminó.
- Exportar desde Ganancias (el periodo que estás viendo), desde Gastos (el mes) y desde el menú ⋮
  del inicio (lleva a Ganancias): viajes y ganancias (CSV y JSON), gastos (CSV y JSON), resumen por
  jornada (CSV) y resumen del periodo (PDF de una página). Se guarda con el selector de archivos o
  se comparte.
- Importes exactos en decimal a partir de los centavos («1978.38»), siempre con columna de
  moneda; CSV con BOM para Excel. Sin datos personales: ids internos, fechas, importes y códigos.
- El PDF se dibuja con la herramienta de PDF de Android (sin librerías nuevas) y siempre lleva la
  nota «Resumen generado por Observador con lo que registró de la app de Uber. No es un documento
  oficial de Uber.»

### Pruebas
- 12 pruebas JVM nuevas (`ReportsTest`): decimales exactos, CSV con BOM y comillas, JSON válido,
  real vacío cuando no hay dinero confirmado (nunca el ofertado), sin datos personales, filas por
  jornada que suman el total, comparación con 80 %, alarma del lunes y líneas del PDF con la nota.
- Prueba instrumentada `ReportExportTest`: CSV de gastos desde Room y PDF real de Android.

## [1.15.0] - 2026-10-13

Fase 8C: ganancias, metas y asignación manual. Incluye todo lo de la 1.14.0. La base de datos no
cambia (sigue en 10).

### Añadido
- Pestaña «Ganancias» en la barra de abajo (ahora son cinco: Inicio, Ganancias, Gastos, Análisis
  y Ajustes), con Hoy, Semana y Mes y flechas para ver periodos anteriores. La semana empieza el
  lunes a tu hora de inicio de jornada; en Ajustes se puede cambiar a domingo.
- Ingreso bruto real del periodo con su cobertura («12 de 14 viajes con dinero confirmado»); con
  cobertura menor de 100 % dice «al menos». Desglose de propinas, promociones, ajustes y otros
  ingresos. Las ofertas nunca cuentan.
- Tiempo en línea, ocupación y ganancia por hora (8A); km por GPS y ganancia por km (8E); gastos
  de trabajo (8B) y, en semana y mes, la ganancia neta. El día no muestra neto (decisión 4).
- «Uber dice»: la referencia de Uber del periodo y la conciliación (coincide, diferencia,
  incompleto o periodo distinto). Nunca se ajusta para que cuadre.
- En semana y mes, barras por jornada con el mejor día y un punto en los días con viajes sin
  dinero confirmado.
- Lista de viajes realizados: hora, ofertado frente a real, diferencia y estado del dinero. Al
  tocar uno se ve su línea de tiempo y el dinero que Observador vio.
- Aviso arriba mientras las etiquetas de dinero de Uber no estén confirmadas con un viaje real.
- Metas diaria y semanal en RD$ (en Ganancias con «Editar» y en Ajustes; un perfil no las cambia):
  barra de progreso con el ingreso real confirmado y «Te faltan RD$X». «A tu ritmo de hoy, unas X
  en línea» solo con 30 minutos en línea y 2 viajes con dinero confirmado hoy. La meta semanal
  dice cuánto sería por jornada en los días que quedan (una división, no un pronóstico).
- Franja pequeña con la meta en Inicio; al tocarla abre Ganancias.
- Aviso opcional «meta alcanzada», una vez por día o semana. Apagado hasta que lo actives.
- «Pendientes de vincular»: cada cantidad vista sin viaje tiene «Asignar a un viaje» (viajes
  terminados de esa jornada y la anterior, el más cercano primero, con diálogo de confirmación que
  muestra cómo queda el viaje) y «No es mío». Nada se edita: se guarda una anulación y una copia
  vinculada (fuente MANUAL) en una sola transacción, y el resumen del viaje se reconstruye. Si el
  viaje ya tiene otro total, el diálogo lo advierte. «Asignadas a mano» permite deshacer.

### Cambiado
- El ingreso de un periodo tiene en cuenta las anulaciones escritas días después de la cantidad
  anulada.

### Pruebas
- 19 pruebas JVM nuevas (`EarningsTest`): periodos con inicio a las 4:00 y semana de lunes o
  domingo, suma de barras igual al total, «al menos», lo pendiente nunca sumado, ritmo solo con
  datos mínimos y cálculo exacto, reparto semanal, asignación con propina tras total (una sola
  vez), total sobre otro total, viaje no terminado rechazado, deshacer y «no es mío».
- Prueba instrumentada `ManualAssignmentRepositoryTest` en Room: asignar, doble toque, deshacer y
  «no es mío», sin desviaciones del resumen.

## [1.14.0] - 2026-10-13

Fase 8B: gastos reales. Incluye todo lo de la 1.13.0. Base de datos 9 → 10 (solo añade cuatro
tablas).

### Añadido
- Pestaña «Gastos» en la barra de abajo, con botón «+» y cuatro formularios:
  - Tanqueo: galones (o precio por galón), total, odómetro opcional y «tanque lleno».
  - Gasto: mantenimiento (aceite, gomas, cadena o kit, frenos, otro) con odómetro opcional, o
    gasto suelto (lavado, parqueo o peaje, multa, otro).
  - Gasto fijo: cuota del motor, plan de datos, seguro u otro; semanal, quincenal o mensual,
    con fecha de inicio y de fin opcional. Se reparte por día.
  - Odómetro: para actualizarlo sin tanquear; «cambié de odómetro o de motor» empieza una cuenta
    nueva.
- Límites de los campos: galones de 0.1 a 20, importe de RD$1 a RD$100,000, odómetro de 0 a
  9,999,999 km. Si el odómetro baja, se avisa y solo se guarda si confirmas el cambio.
- Resumen del mes (este mes o el pasado): combustible, mantenimiento, fijos y sueltos, el total,
  la parte de trabajo y la ganancia neta = ingreso real confirmado − gastos de trabajo, con su
  cobertura. Sin ingreso real no hay neto.
- Rendimiento real por el método de tanque lleno: km/galón y costo de combustible por km (último
  tramo y mediana de los últimos 5). Sin odómetro usa los km del GPS (8E), marcados «aprox.».
  Nunca a través de un cambio de odómetro.
- Sugerencia para Ajustes con 3 tramos o más, con botón «Usar en Ajustes» y confirmación. Solo
  cambia el costo de combustible de cada oferta; el score no cambia.
- Mantenimiento: aviso cuando falta el 10 % del intervalo o menos, y en rojo si ya pasó. El
  odómetro actual suma los km del GPS desde la última lectura (estimado). Notificación (encendida
  por defecto) al abrir la app y al anotar algo, una sola vez por cambio y nivel.
- Lista de registros con filtro, tocar para editar y deslizar para borrar con «Deshacer».
- Ajustes → «Gastos y mantenimiento»: parte de trabajo (100 % por defecto), intervalos de aceite,
  cadena, gomas y frenos (ninguno viene puesto), y el aviso.
- Panel de prueba «Gastos (prueba)» con registros TEST-gasto-.

## [1.13.0] - 2026-10-13

El inicio pasa a mostrar los viajes que hiciste, y Análisis gana la espera entre viajes.
Incluye todo lo de la 1.12.0. Sin cambios en la base de datos. La 8B pasa a ser la 1.14.0.

### Cambiado
- Inicio: «Viajes realizados» en lugar de «Resumen de ofertas». El resumen, la lista «Viajes por
  jornada» y «Mejores horas para salir» usan solo viajes completados, con lo que prometía su
  oferta (guardada en el viaje al aceptarlo) y tus ajustes de hoy. Una oferta vista puede haber
  sido de otro conductor o la dejaste pasar, así que ya no cuenta aquí.
- Las ofertas se siguen guardando: alimentan «ofertas por hora en línea», Análisis y la
  exportación. Desde el inicio ya no se borran (no hay deslizar ni «Borrar jornada»).
- Quedan fuera los viajes cancelados o interrumpidos y los que Observador encontró ya empezados
  sin ver su oferta.

### Añadido
- Análisis: tarjeta «Espera entre viajes» (de terminar un viaje a aceptar el siguiente) con
  mediana, promedio, la más larga, reparto en menos de 5, 5 a 15, 15 a 30 y más de 30 minutos, y
  las franjas de dos horas donde esperas menos y más (con 3 esperas o más). Cuenta una espera
  cuando ambos extremos caen en la misma sesión en línea; sin sesión, solo si fue de 30 minutos o
  menos (una pausa más larga puede ser un descanso).

## [1.12.0] - 2026-10-12

Fase 8E: kilómetros reales con GPS. Incluye todo lo de la 1.11.0. Base de datos 8 → 9 (solo
añade dos tablas). Va antes de la 8B, como dice la decisión 11 del plan.

### Añadido
- «Medir km con GPS» en Ajustes (apagado hasta que lo actives y des el permiso de ubicación
  precisa), con tres modos: al conectarte a Uber y con el botón, solo al conectarte o solo con
  el botón.
- Mide solo mientras hace falta: al conectarte a Uber (sesiones de la 8A) o con «Iniciar
  recorrido». Se detiene y guarda el tramo al desconectarte, con «Detener recorrido» o al pausar
  Observador. El GPS está apagado el resto del tiempo.
- Cómo cuenta: distancia entre posiciones seguidas (Haversine) en milímetros enteros; descarta
  posiciones con precisión peor que 25 m, saltos de más de 120 km/h y el temblor del GPS
  parado. Más de 60 s sin señal se guarda aparte como «aprox. sin señal»; más de 10 min, nada.
- El total del día es la suma de sus tramos; un tramo que cruza el inicio de la jornada se corta
  ahí y la jornada nueva empieza en 0. El tramo abierto se guarda cada minuto.
- Reparto de los km: con pasajero, a recoger, sin pasajero (en línea) y fuera de línea.
- Km por viaje (recogida y viaje) con su cobertura, frente a lo que estimó la oferta, en el
  detalle del viaje del panel de prueba.
- Tarjeta «Km de hoy» en el inicio: total, reparto, tramos del día y botón Iniciar / Detener
  recorrido. La notificación dice «Midiendo km · 23.4 km hoy» mientras mide.
- Panel de prueba «Kilómetros»: contadores de calidad y tramos TEST-km-.

### Privacidad
- Las posiciones se usan en memoria para sumar metros y se descartan al instante. Nunca se
  guardan coordenadas, rutas ni mapas: ni en la base de datos, ni en exportaciones, ni en
  registros. Una prueba revisa que ninguna tabla tenga dónde guardarlas.

### Técnico
- Permisos nuevos: ubicación precisa y servicio en primer plano de tipo «ubicación». Librería
  nueva: ubicación combinada de Google (play-services-location 21.3.0).
- Si Android reinicia el servicio con la app cerrada, puede negarle el tipo «ubicación»; la
  tarjeta lo avisa y basta con abrir Observador una vez.
- Corregido el aviso de compilación de `MainActivity.kt:71`.

## [1.11.0] - 2026-10-12

Fase 8A: tiempo en línea, y una cara nueva para toda la app. Incluye todo lo de la 1.10.0.
Base de datos 7 → 8 (solo añade una tabla; no cambia nada de lo guardado).

### Añadido
- Tiempo en línea: Observador sabe cuándo estás conectado a Uber.
  - Lee cuatro señales con el texto completo, sin acentos ni mayúsculas: «Estás conectado» y
    «DESCONECTARSE» (en línea), «Estás desconectado» y «Conectarse» (fuera de línea). Las
    pantallas de oferta, recogida, espera y viaje también cuentan como «en línea».
  - Un cambio se confirma con dos lecturas a 500 ms o más; una pantalla de viaje con confianza
    alta lo confirma al momento. Una lectura con señales de los dos lados no cuenta.
  - Cada sesión guarda dos cifras: tiempo confirmado y tiempo posible. Un rato de hasta 30 min
    sin ver Uber cuenta como en línea; uno más largo solo cuenta en «hasta …».
  - El inicio es exacto si antes se vio «fuera de línea»; si no, es un mínimo («al menos»).
  - Pausar Observador cierra la sesión. Tras reiniciar el teléfono o la app, la sesión sigue si
    Uber vuelve a verse antes de 30 min; si no, se cierra en la última señal.
  - «Última vez visto en línea» se guarda como máximo una vez por minuto, por batería.
  - Mientras hay una sesión abierta, si Uber no avisa de cambios en 5 min, Observador vuelve a
    mirar la pantalla una vez (un mapa quieto con «Estás conectado» sigue contando).
- Tarjeta «Tiempo en línea» en el inicio, con el mismo periodo que el resumen: tiempo
  confirmado (y «hasta …» si hubo huecos), ocupado frente a esperando, % de ocupación, ofertas
  por hora en línea, minutos hasta la primera oferta, espera entre viajes y ganancia por hora en
  línea, siempre con su cobertura («Parcial: 3 de 5 viajes con dinero»).
- Barra de estado: segunda línea «En línea en Uber · 1 h 25 min», «Uber sin ver desde hace …» o
  «Desconectado de Uber». No se confunde con «En pausa».
- La cifra «DOP … · HOY» de arriba del mapa se guarda como referencia de Uber del día, solo si la
  etiqueta «HOY» está junto al importe. Nunca se suma como ingreso; como no se sabe dónde empieza
  y termina el «hoy» de Uber, la conciliación dice «periodo distinto».
- Panel de prueba «Sesiones en línea» (solo desarrollo): estado, sesión abierta, huecos, últimos
  cambios, sesiones TEST- y «Copiar lectura».

### Cambiado
- Navegación con barra abajo: Inicio, Análisis y Ajustes.
- Tema propio de Observador (claro y oscuro) en lugar de los colores del fondo de pantalla.
- Iconos en toda la app (Material Symbols Rounded, licencia Apache 2.0): tarjetas con icono en
  el inicio, Análisis y Ajustes; resumen con mosaicos; barra de estado con icono y botones
  Pausar/Reanudar con icono.

### Pendiente de validar en el teléfono
- Las cuatro señales salen de capturas de imagen; falta confirmar con «Copiar lectura» que el
  árbol de accesibilidad dice lo mismo.
- La pastilla «HOY»: si el importe y la etiqueta van en el mismo elemento o separados.

## [1.10.0] - 2026-10-12

Pausa y perfiles de ajustes. Incluye todo lo de la 1.9.0.

### Añadido
- Interruptor "Observador activo / en pausa":
  - está en la barra de estado del inicio (botón "Pausar" / "Reanudar"), arriba en Ajustes y en
    la notificación;
  - en pausa no se lee nada de Uber: ni ofertas, ni tarjeta, ni avisos, ni viajes, ni dinero;
  - si se pausa durante un viaje, ese viaje queda como interrumpido;
  - por defecto está activo, así que actualizar la app no cambia nada.
- Perfiles en Ajustes: guarda los ajustes actuales con un nombre ("Día", "Noche", "Lluvia"...) y
  aplícalos de un toque.
  - Hasta 10 perfiles. Se pueden actualizar, renombrar y borrar.
  - Ajustes muestra qué perfil está en uso y si lo cambiaste después.
  - Un perfil guarda costos, mínimos, pesos y cortes del score, y cómo se muestran las ofertas.
    No guarda el inicio de la jornada.
  - Aplicar un perfil cambia todos los valores de una vez. Si tus valores actuales no están en
    ningún perfil, pide confirmación antes de reemplazarlos.

## [1.9.0] - 2026-10-12

Fase 7D: análisis del modelo. Mide si un score más alto trae de verdad mejores viajes, con tus
viajes reales. Solo mide, explica y recomienda: nunca cambia el modelo sin tu confirmación.
Sin viajes reales todavía, la pantalla dice "datos insuficientes".

### Añadido
- Pantalla "Análisis" (icono de barras en el inicio):
  - calidad de datos y cobertura por métrica;
  - estado del modelo con sus razones, y un hallazgo principal;
  - "¿El score funciona?" (Spearman y tramos de score);
  - predicción frente a realidad: tiempo, RD$/hora y dinero ofertado frente a real;
  - categorías y matriz frente a tu objetivo de RD$/hora;
  - mejores horas reales, por hora de aceptación y separadas de las de ofertas vistas;
  - factores, decisiones, recomendación, modo sombra e historial de modelos.
- Calibración con una cuadrícula pequeña de pesos (84 combinaciones):
  - se ajusta con los viajes antiguos y se confirma con los recientes;
  - rechaza el sobreajuste;
  - no recomienda nada con menos de 30 viajes.
- Modo sombra: el candidato puntúa en silencio las ofertas nuevas, sin cambiar lo que ves.
  "Aplicar" solo aparece con 30 viajes con resultado y una mejora confirmada, y pide confirmación.
  "Restaurar" vuelve exactamente al modelo anterior.
- Versiones de modelo que nunca se editan: inicial, editado en Ajustes, aplicado tras sombra,
  restaurado.
- Base de datos versión 7 con migración explícita 6 → 7:
  - tablas `profit_model_versions`, `shadow_evaluations` y `calibration_runs`;
  - 4 columnas de pesos en `trips` (vacías = los pesos de siempre).
- Datos de prueba de desarrollo con patrones conocidos: modelo que funciona, modelo que falla,
  "la recogida pesa más" y sobreajuste.

### Cambiado
- Los pesos del score (35/30/20/15) pasan a ser ajustes del modelo con esos mismos valores por
  defecto, así que **ningún score cambia** hasta que apliques un candidato. Ajustes y el
  detalle de cada oferta muestran los pesos en uso.
- Las pruebas de migración de Room abren la base con todas las migraciones posteriores.

## [1.8.0] - 2026-10-12

Fase 7C: dinero real. Observador guarda lo que Uber muestra que ganaste después de un viaje,
aparte del precio ofertado. Las etiquetas de las pantallas de dinero de Uber son provisionales:
validación con capturas reales pendiente. La pantalla principal no cambia todavía.

### Añadido
- Dinero en enteros (`Long`, centavos: RD$1,978.38 = 197838); nunca `Double` ni `Float`. El
  texto y `BigDecimal` solo aparecen al leer la pantalla y al mostrar.
- Lector de importes dominicano (`RD$`, `DOP`, `US$`): nunca devuelve 0 por error; separadores
  dudosos ("1.234,56") o dos importes juntos quedan como ambiguos, no se adivinan.
- Base de datos versión 6 con migración explícita 5 → 6: `financial_observations` (cada importe
  visto, nunca se edita), `trip_financial_summaries` (resumen reconstruible por viaje) y
  `platform_earnings_snapshots` (lo que Uber dice del día o la semana).
- Total efectivo = último total absoluto + sumas posteriores. Un total nuevo reemplaza la base y
  absorbe la propina ya vista (100, +20, total 120 = 120, nunca 140). Los componentes solo
  forman el desglose. Sin total visto, la ganancia queda desconocida aunque se sepa el precio
  ofertado.
- Una cantidad se vincula a un viaje solo si aparece al terminar ese mismo viaje (10 minutos);
  si no, queda "pendiente de vincular" y no se suma a ningún viaje.
- Ajustes → Herramientas de prueba (solo desarrollo): "Dinero detectado" con contadores,
  últimas cantidades, ingreso real de la jornada con cobertura, dato de Uber y conciliación,
  datos de prueba y reconstrucción de resúmenes. La línea de tiempo de cada viaje muestra
  ofertado frente a real.

### Cambiado
- Viajes y dinero se escriben en una sola cola, en orden: el dinero de un viaje nunca llega
  antes que el viaje.

## [1.7.0] - 2026-10-12

Fase 7B: registro persistente de viajes. Los eventos del ciclo de la 7A se guardan y forman
viajes que sobreviven a reinicios. No calcula ganancias. Validación con un viaje real de Uber:
pendiente (depende también de validar la 7A).

### Añadido
- Base de datos versión 5 con migración explícita 4 → 5: tablas nuevas `trips` (estado actual
  de cada viaje) y `ride_events` (historial de eventos que nunca se modifica). La tabla de
  ofertas no cambia.
- Un viaje se crea al confirmarse la aceptación o al recuperarse a mitad de camino; una oferta
  vista nunca crea viaje. Guarda la oferta original (precio, recogida, viaje, score de ese
  momento) y los ajustes con los que se juzgó.
- Reglas: una sesión = un viaje; un evento repetido no se duplica; la primera hora observada
  manda; COMPLETED y CANCELLED se excluyen; nuevo estado INTERRUPTED para finales no vistos;
  anomalías con códigos fijos (orden imposible, dos viajes activos, conflicto final...).
- Un único escritor guarda cada evento y el cambio de su viaje en la misma transacción, con
  reintentos seguros.
- Al reiniciar, el viaje abierto vuelve a la 7A y continúa como el mismo viaje; si Uber ya está
  buscando ofertas, queda INTERRUPTED.
- Ajustes → Herramientas de prueba (solo desarrollo): "Viajes detectados" con lista, línea de
  tiempo, contadores, comprobación de integridad y viajes de prueba que se borran sin tocar los
  reales.
- Esquemas de Room exportados en `app/schemas` y prueba de migración con MigrationTestHelper.

## [1.6.0] - 2026-10-10

Fase 7A: detectar el ciclo de vida del viaje. Solo observa: no guarda viajes, no cambia la base
de datos y nunca toca nada en Uber. Las reglas de la oferta están comprobadas; las de recogida,
llegada, viaje en curso, fin, cancelación y búsqueda son candidatas hasta validarlas con un
viaje real.

### Añadido
- Capa de interpretación del viaje: captura de pantalla sin tipos de Android, clasificador de
  pantallas con confianza y evidencia explicable, eventos tipados y máquina de estados
  (IDLE, OFFER_VISIBLE, TO_PICKUP, ARRIVED_OR_WAITING, IN_TRIP, COMPLETED, CANCELLED, UNKNOWN).
- Sin estados inventados: una oferta que desaparece no es un rechazo, y completar o cancelar
  exige una pantalla que lo muestre. Si Observador arranca a mitad de viaje, recupera el estado
  sin inventar horas anteriores.
- Evita eventos repetidos y cambios por parpadeo: 50 lecturas iguales dan un solo evento, y las
  transiciones importantes se confirman con una segunda lectura.
- Diagnóstico en Ajustes, solo en la versión de desarrollo: estado, pantalla, confianza,
  evidencia, salud del detector, contadores, últimos 50 eventos, "Reiniciar detector" y "Copiar
  lectura" (lectura de la pantalla con nombres y direcciones ocultos, para validar las reglas).

### Cambiado
- El árbol de accesibilidad se lee una sola vez por pantalla; la lectura de ofertas usa esa
  copia con las mismas reglas de siempre.
- Los textos de la pantalla ya no se escriben en el registro de la versión final, solo en la de
  desarrollo.

## [1.5.0] - 2026-10-10

Fase 5 del plan del dashboard: mejores horas para salir. Mide el mercado que viste (las ofertas
que te llegaron), no lo que ganaste.

### Añadido
- Sección "Mejores horas para salir" entre el resumen y la lista. Divide el día en 12 franjas
  fijas de 2 horas, según la hora real del teléfono, y dibuja el pago mediano por hora de cada
  una. Periodo de 7 o 30 días (30 por defecto).
- Tarjeta "Mejor franja observada": RD$/h y RD$/km medianos, porcentaje de buenas o excelentes,
  cantidad de ofertas, confianza e índice de oportunidad (0 a 100).
- Top 3 de franjas, ordenado por índice de oportunidad.
- Detalle al tocar una barra, la tarjeta o una fila del Top 3: mediana y rango típico (P25 a P75)
  del RD$/h, RD$/km, score mediano, buenas + excelentes, excelentes, recogida típica, frecuencia,
  días con ofertas, confianza e índice.
- Las franjas con menos de 5 ofertas se dibujan como barra hueca ("Pocos datos") y nunca
  compiten por mejor franja.
- Línea discontinua con tu mínimo de RD$/h en la gráfica.
- TalkBack enfoca cada barra por separado, la lee como una frase y anuncia la seleccionada.
- Ofertas de prueba: 100 más con un patrón de mercado conocido (160 en total). Con ellas
  18–20 sale como mejor franja, 20–22 paga más por hora pero no gana y 00–02 sale con pocos
  datos.

### Cambiado
- La mediana del dashboard usa la misma función de percentiles que la nueva sección (el
  resultado no cambia).
- En el panel de detalle de una oferta, con letra muy grande la etiqueta se parte en dos líneas
  en lugar de empujar el valor fuera de la pantalla.

## [1.4.0] - 2026-10-10

Fase 4 del plan del dashboard: filtros, orden y borrado.

### Añadido
- Filtro por categoría en la lista: Todas, Excelentes, Buenas y Malas, cada una con su cantidad.
  Con un filtro activo, la cabecera de cada jornada dice cuántas se muestran ("3 de 12 ofertas").
- Orden dentro de cada jornada: por hora, score, precio o RD$/km.
- Borrar una oferta deslizándola hacia la izquierda, desde su panel de detalle o con TalkBack
  ("Borrar oferta"). Aparece "Deshacer" durante unos segundos y la oferta vuelve tal como estaba.
- Borrar una jornada entera desde su menú ⋮, con confirmación y también con "Deshacer".

## [1.3.0] - 2026-10-10

Fase 3 del plan del dashboard: fila de oferta y detalle.

### Añadido
- Panel de detalle al tocar una oferta: recogida y viaje por separado, pago por km y por hora,
  combustible, ganancia neta y los cuatro sub-scores con su barra. Se cierra deslizando hacia
  abajo o con Atrás.

### Cambiado
- Cada oferta es una fila plana con una franja de color, el precio y el RD$/km en grande y solo
  la hora (la fecha ya la da la cabecera de la jornada). Caben más ofertas por pantalla.

## [1.2.0] - 2026-10-10

Fase 2 del plan del dashboard: jerarquía de la pantalla.

### Añadido
- Franja de estado fija arriba del dashboard: verde "Leyendo ofertas" con la hora de la última
  oferta real (sin contar las de prueba), o roja "Inactivo" con un botón que abre Accesibilidad.
- Resumen en cuadrícula de 2 x 2: ofertas vistas, RD$/km y RD$/h medianos con una flecha que
  dice si están por encima o por debajo de tus mínimos, y la mejor oferta. Con letra del sistema
  muy grande pasa a una sola columna.
- Barra de reparto MALO / BUENO / EXCELENTE con cantidad y porcentaje (los porcentajes siempre
  suman 100). TalkBack la lee como una frase.
- "Probar la tarjeta" en Ajustes → Overlay.

### Cambiado
- La barra superior usa un ícono para Ajustes y lleva "Exportar ofertas" al menú ⋮.
- La barra de categorías de Ajustes y la del dashboard son el mismo componente.

### Eliminado
- La tarjeta "Servicio de Accesibilidad" y el botón "Probar overlay" del dashboard.

## [1.1.0] - 2026-10-10

Fase 1 del plan del dashboard: datos correctos.

### Añadido
- Selector de periodo en el resumen: Hoy, 7 días, 30 días y Todo.
- Ajuste "Inicio de la jornada" (por defecto 4:00 am). Una oferta de la 1:30 am cuenta en la
  jornada que empezó la tarde anterior.
- Reparto de ofertas MALO / BUENO / EXCELENTE del periodo y mejor oferta del periodo.
- Mediana de RD$/km y de RD$/h en el resumen y en cada jornada.
- Versión de la app visible al final de Ajustes.
- Este CHANGELOG.

### Cambiado
- El dashboard recalcula score, combustible y ganancia con los ajustes actuales, así todo el
  historial se juzga con el mismo criterio. Los datos guardados y la exportación conservan los
  valores del momento en que se detectó cada oferta.
- Las ofertas se agrupan por jornada en lugar de por día calendario.
- "Hoy" se renueva solo al empezar la jornada, sin cerrar la app.

### Corregido
- La lista se cortaba en las 200 ofertas más recientes sin avisar; ahora carga el periodo completo.
- Los promedios mezclaban todo el historial; ahora corresponden al periodo elegido.

### Eliminado
- Consultas de promedios de todo el historial, el resumen por día calendario (`DaySummary`) y un
  contador que no se usaba.

### Pendiente
- La exportación "Hoy" todavía usa el día calendario, no la jornada.

## [1.0.0] - 2026-10-10

Primera versión registrada en GitHub (commit `74bdd64`).

### Añadido
- Lectura de las ofertas de Uber Driver con un servicio de accesibilidad y cálculo de RD$/km,
  RD$/h, costo de combustible, ganancia neta y score de 0 a 100.
- Tres categorías de oferta, MALO / BUENO / EXCELENTE, con cortes configurables.
- Tarjeta flotante sobre Uber con precio, km, minutos, RD$/km y score; se cierra con un toque.
- Modo Asistido: notificación para ofertas BUENAS o EXCELENTES; se cierra con un toque.
- Dashboard con estadísticas y ofertas agrupadas por día; exportación a CSV y JSON.
- Ajustes con descripción en cada campo, validación de valores y oferta de ejemplo en vivo.
- Ofertas de prueba para comprobar el dashboard (solo en la versión de desarrollo).

### Corregido
- Ofertas duplicadas: seguimiento por sesión de la tarjeta, ventana anti-duplicados y limpieza
  de duplicados antiguos al migrar la base de datos (versión 4).

[Sin publicar]: https://github.com/jochimin1/observador/compare/v1.4.0...HEAD
[1.4.0]: https://github.com/jochimin1/observador/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/jochimin1/observador/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/jochimin1/observador/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/jochimin1/observador/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/jochimin1/observador/releases/tag/v1.0.0
