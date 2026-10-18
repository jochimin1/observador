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

## [1.29.2] - 2026-10-18

### Corregido
- En el tema Violeta la app se cerraba al mostrar los gráficos de Mejores horas, y el de Ganancias con más de 10 días (por ejemplo «Mes»). La librería de gráficos (Vico) se detiene si una etiqueta del eje queda vacía, y se dejaban vacías para que no se amontonaran. Ahora cada columna tiene su etiqueta y el eje muestra una de cada 2 (horas) o de cada 5 (días). Error encontrado con el registro del teléfono (adb logcat) del 18/10.

## [1.29.1] - 2026-10-18

### Corregido
- Las tarjetas con el recargo «+DOP5.25 incluido» (nuevo en Uber, 18/10) salían con precio RD$5.25 en vez del precio real (DOP92.60, DOP48.31, DOP47.88…). Ese recargo ya va dentro del precio: ahora no cuenta como precio ni corta la tarjeta en dos, y se toma el precio grande. Probado con los textos de las capturas del 18/10, en tarjeta sola y en el Radar.
- Las ofertas guardadas antes con RD$5.25 por este error no se pueden corregir solas: la app no guarda el texto de la pantalla, y no se inventa el precio.

## [1.29.0] - 2026-10-17

### Añadido
- Bienvenida al abrir la app por primera vez: qué hace Observador (con un dibujo propio en los colores del tema) y una página por permiso — accesibilidad para leer las ofertas, ubicación para los kilómetros y avisos (solo Android 13 o más). Cada página explica, abre la pantalla de Android y muestra el estado real («Activado» / «Pendiente»); «Saltar» siempre está.
- Ajustes › Apariencia › Bienvenida › «Ver» la abre otra vez.

### Cambiado
- Quien actualiza con la lectura ya activada no ve la bienvenida. Mientras se muestra, el permiso de avisos se pide desde su página y no al abrir.
- En Violeta, los campos de texto (Gastos, metas, Ajustes, perfiles) son blancos, sin borde y con esquinas redondeadas, como las tarjetas.

## [1.28.1] - 2026-10-17

Más gráficos con Vico en el tema Violeta. Incluye todo lo de la 1.28.0. La base de datos no cambia (sigue en 14).

### Cambiado (solo con el tema Violeta)
- **Mejores horas para salir** como columnas moradas de Vico (pago mediano por hora en cada franja de 2 horas).
  La franja se abre desde la tarjeta de la mejor franja y la lista de debajo; TalkBack lee todas las franjas.

## [1.28.0] - 2026-10-17

Gráficos con Vico, fase 3 del plan de diseño. Incluye todo lo de la 1.27.0. La base de datos no cambia (sigue en 14). Los otros temas no cambian.

### Añadido (solo con el tema Violeta)
- **Ganancias › Por jornada** como gráfico de columnas moradas redondeadas, con RD$ a la izquierda,
  el día abajo y animación al aparecer. Mismas cifras que antes; los días con viajes sin dinero
  confirmado se listan debajo.

### Técnico
- Nueva dependencia **Vico 3.2.3** (`compose` y `compose-m3`, licencia Apache 2.0). Es la versión más
  nueva compilada con Kotlin 2.3: la 3.3.x necesita Kotlin 2.4 y la app usa 2.2.10. La API se tomó
  del código fuente de esa versión.

## [1.27.0] - 2026-10-17

Inicio en el tema Violeta, fase 2 del plan de diseño. Incluye todo lo de la 1.26.0. La base de datos no cambia (sigue en 14). Los otros temas no cambian.

### Añadido (solo con el tema Violeta)
- **Tarjeta morada de la meta** con anillo de porcentaje, lo ganado y lo que falta; toca para ir a Ganancias.
- **«En curso»**: carrusel de tarjetas de color con ofertas y tomadas, tiempo en línea (y % con viaje) y km de hoy (y con pasajero), cada una con su barra.
- **«Grupos»**: Excelentes, Buenas y Malas del periodo con su anillo de porcentaje.
- **Tira de días**: los últimos 7 días de trabajo con sus ofertas; tocar uno muestra solo ese día (pasa a «7 días» si hace falta) y tocarlo otra vez quita el filtro.
- **Botón + central** en la barra inferior: iniciar o detener el recorrido de km, cargar combustible o gasto, y Reportes y exportación.

### Técnico
- `Rings`, `DayStrip` y `QualityGroups`: porcentajes siempre entre 0 y 100, tira según el día de trabajo. 3 pruebas JVM nuevas.

## [1.26.0] - 2026-10-17

Tema «Violeta», primera fase del plan de diseño. Incluye todo lo de la 1.25.0. La base de datos no cambia (sigue en 14).

### Añadido
- **Tema «Violeta»** en Ajustes › Tema, junto a Sistema, Claro, Oscuro, Noche y Sol: morado #5F33E1,
  fondo pastel difuminado (verde, amarillo, lila y azul), letra **Lexend**, tarjetas blancas y
  esquinas más redondeadas. Las barras de arriba quedan transparentes sobre el fondo.
- Los colores de MALO, BUENO y EXCELENTE no cambian, y todos los textos del tema pasan la prueba de
  contraste (mínimo 4.5 a 1). «Noche automática» sigue mandando de noche.

### Técnico
- Letra Lexend incluida en la app (licencia SIL OFL, en `docs/licencias`): funciona sin internet.

## [1.25.0] - 2026-10-17

Análisis con tus viajes reales. Incluye todo lo de la 1.24.0. La base de datos no cambia (sigue en 14).

### Cambiado
- **Análisis cuenta también los viajes «sin confirmar»** (los que se cierran cuando Uber vuelve a
  «Buscando»), igual que Ganancias desde la 1.19.0. Antes solo usaba los viajes con la pantalla de
  resumen de Uber, que aún no está validada, y por eso salía casi vacío. Su hora de fin es el último
  punto del recorrido o, sin recorrido, 45 s antes del cierre (Uber ya estaba buscando). Un nuevo
  filtro «Incluir sin confirmar» los quita para ver solo los confirmados.
- **Espera entre viajes** cuenta también la espera después de un viaje «sin confirmar»: antes se
  perdía casi toda.
- «Calidad de datos» separa los viajes confirmados por Uber de los «sin confirmar» y dice cuántos
  tienen km reales del GPS.
- Cuando no hay dinero real por viaje, «Análisis» lo explica (de dónde sale ese dinero) y muestra
  igual «Predicción vs realidad» con tiempos y km, que no necesitan dinero.

### Añadido
- **Km reales contra los de la oferta:** con «Medir km con GPS» y el viaje medido completo, compara
  la distancia estimada (recogida + viaje) con la real del GPS, con su sesgo y su error típico.
- **RD$/km real**: lo cobrado entre los km del GPS, al lado del RD$/km estimado, en los viajes con
  dinero real.
- Nuevo hallazgo: «Los viajes son más largos (o más cortos) de lo que dice la oferta», con 10
  viajes medidos o más y una diferencia de al menos 10 %.

### Técnico
- `TripOutcome.realEndAt`: el mejor fin conocido de un viaje (resumen, cancelación, último punto
  del recorrido o 45 s antes del cierre por «Buscando»), nunca antes del último hito visto.
- 5 pruebas JVM nuevas (`IntelligenceTest`, `TripsViewTest`).

## [1.24.0] - 2026-10-17

Plan 8K: el recorrido de los viajes tomados. Incluye todo lo de la 1.23.1. La base de datos pasa de
13 a 14 solo con dos tablas nuevas: tus datos actuales no se tocan.

### Añadido
- **Recorrido de cada viaje tomado.** Desde «Llegar por …» hasta el cierre del viaje se guarda por
  dónde pasaste, con las mismas posiciones de cada 5 segundos que ya usa «Medir km con GPS» (no gasta
  más batería) y los mismos filtros: precisión peor que 25 m, saltos de más de 120 km/h y el temblor
  del GPS parado no cuentan. Cada punto lleva su fase: yendo a recoger o con pasajero (si «Iniciar
  viaje» no se distingue, queda «sin distinguir»). Se escribe cada 30 segundos, se simplifica al
  cerrar (fuera los puntos a menos de 5 m de la línea) y se guarda siempre, hasta que lo borres.
  Tope de 3 horas por viaje. Un viaje cerrado por «Buscando» pierde sus últimos 45 s, que ya eran
  búsqueda.
- **Ficha del viaje** en el detalle (Inicio, al tocar un tomado, y Ganancias): pasajero, aceptado,
  llegada al punto, inicio y fin con hora y lugar, duración, y **ofertado contra real** («Uber dijo
  3.9 km y 10 min; hiciste 4.6 km y 17 min»).
- **Mapa con calles** (OpenStreetMap) con el trazo encima: recogida en naranja, viaje en azul,
  inicio verde y fin rojo. Se mueve con el dedo, acerca con dos dedos o con los botones, y «Ver
  todo» lo vuelve a encuadrar. Sin internet se ve solo el trazo.
- **Nombres de los lugares** de inicio y fin a partir del GPS, con internet (servicio de mapas del
  teléfono). Mientras no hay nombre se muestran las direcciones de la tarjeta de Uber.
- **Exportar el recorrido** en GPX (abre en Google Maps «Mis mapas», Google Earth y casi cualquier
  app de mapas) o GeoJSON, desde el detalle del viaje y desde «Reportes y exportación»
  («Recorridos», con los filtros del periodo). Llevan las ubicaciones y el nombre del pasajero.
- **«Borrar recorrido»** en el detalle y **«Borrar todos los recorridos»** en Ajustes: se van los
  puntos; las horas, los lugares, los km y el dinero se quedan.
- **Ajustes › Recorrido de los viajes**: interruptor (encendido) y cuántos recorridos y puntos hay.
- En la lista de **Tomados**, una línea por viaje: «Stefany · 6:42 → 7:01 · Los Ríos → Los Prados».
- Herramientas de prueba: «Cargar prueba» de viajes crea también recorridos inventados en Santo
  Domingo para ver la ficha y el mapa sin manejar.

### Cambiado
- El archivo de **viajes** suma `hora_inicio`, `lugar_inicio` y `lugar_fin` (texto). Las
  coordenadas solo van en los archivos de recorrido; las demás tablas y exportaciones siguen sin
  ninguna (la prueba de privacidad de la 8E se dividió para revisarlo).
- La app pide permiso de **internet**, solo para las calles del mapa y los nombres de lugares.

### Técnico
- Base de datos 14: `trip_route_points` (un punto por fila, grados × 10 000 000 en enteros) y
  `trip_routes` (resumen por viaje). Se borran con su viaje. Migración 13 → 14 con prueba
  instrumentada (`Migration13To14Test`) y la cadena desde la 4.
- 26 pruebas JVM nuevas (`RouteTest`): grabador, filtros, fases, 30 s, 3 horas, 45 s de «Buscando»,
  simplificación a 5 m, GPX y GeoJSON válidos, ficha, mapa y privacidad.

## [1.23.1] - 2026-10-17

Corrección del botón de cerrar. Incluye todo lo de la 1.23.0. La base de datos no cambia (sigue en 13).

### Corregido
- **«Cerrar» no hacía nada.** El cierre se lanzaba dentro del diálogo de confirmación, y al
  desaparecer el diálogo Android cancelaba ese trabajo antes de guardar la pausa. Ahora corre aparte
  y termina siempre: pausa, apaga el GPS, quita la notificación y cierra la app.

### Cambiado
- **Ícono de apagar en Inicio**, en la barra de estado, justo debajo de «Pausar» / «Reanudar», en
  rojo suave. Sale del menú ⋮.
- Se llama ahora «Apagar Observador» en el diálogo, en Ajustes y en el botón de la notificación.

## [1.23.0] - 2026-10-17

Botón «Cerrar Observador». Incluye todo lo de la 1.22.0. La base de datos no cambia (sigue en 13).

### Añadido
- **«Cerrar Observador»** en Inicio (menú ⋮), en Ajustes (debajo del interruptor de pausa) y como
  botón «Cerrar» en la notificación fija. Deja de leer Uber, apaga el GPS, quita la notificación del
  servicio y cierra la app.
- Antes de cerrar pide confirmación. Si hay un viaje en curso lo avisa en rojo: Observador no verá
  cómo termina y quedará sin cierre confirmado. Si se estaban midiendo km, dice que lo medido se guarda.
- Al abrir la app de nuevo, vuelve a leer sola. Una pausa elegida con el interruptor de Ajustes, en
  cambio, se respeta al abrir.

### Pruebas
- 3 pruebas JVM nuevas (`AppCloseTest`): cerrar pausa y marca, abrir reanuda solo si se cerró con el
  botón, y el aviso según haya viaje en curso o medición.

## [1.22.0] - 2026-10-17

Reportes y exportación. Incluye todo lo de la 1.21.0. Base de datos 12 → 13 (solo añade columnas).

### Añadido
- **Pantalla «Reportes y exportación»**: Ajustes → «Reportes y exportación». También se abre desde
  Inicio (⋮ → «Reportes y exportación…»), Ganancias y Gastos (botón de descarga) con el periodo que
  estabas viendo. No es una pestaña nueva.
  - **Periodo**: Hoy, Esta semana, Este mes, Semana pasada, Mes pasado o Rango personalizado
    (calendario). Usa tu hora de inicio de jornada y tu día de inicio de semana.
  - **Filtros con su conteo**: Origen (Todo / Ofertados / Tomados), Categoría (Excelentes / Buenas /
    Malas, con tus ajustes de hoy), Botón (Me interesa / Aceptar) y Modalidad (Moto, Flash…).
  - **Totales en pantalla antes de exportar**, y «Ver todos los totales».
  - **Qué exportar**, con las filas de cada archivo: Ofertas y viajes (detalle), Totales del periodo,
    Viajes y ganancias, Gastos, Resumen por jornada (CSV) y Resumen del periodo (PDF). CSV o JSON.
    Un archivo sin datos sale deshabilitado con «Sin datos en este periodo».
  - **Guardar en el teléfono** o **Compartir**; con varios archivos van juntos en un .zip.
  - **Últimas exportaciones**: las 5 más recientes (solo el registro, no los archivos).
- **Archivo «Ofertas y viajes»**: una fila por oferta con fecha, hora, jornada, origen, estado del
  viaje, modalidad, exclusivo, botón, categoría, score de hoy y al detectar, ofertado (referencia),
  km y minutos, RD$/km, RD$/hora, combustible y ganancia estimados, recogida, destino, pasajero, real
  confirmado, propina, diferencia con lo ofertado y cobertura del dinero.
- **Archivo «Totales del periodo»** (y segunda página del PDF): por origen y % de tomados; tomados
  por estado; por categoría; por botón; por modalidad (cantidad, precio promedio, RD$/km promedio y
  mediana); sumas (ofertado como referencia, real confirmado con su cobertura, propinas, km y minutos
  de lo tomado, gastos y neto en semana y mes) y por jornada. Cada total es la suma del detalle.
- **Modalidad de la tarjeta**: cada oferta guarda la etiqueta que Uber pone sobre el precio («Moto»,
  y otras que aparezcan, tal cual) y si es «Exclusivo». Se ve en Inicio y en el detalle. Los viajes
  copian modalidad, exclusivo y botón de su oferta. Las ofertas anteriores quedan como «Sin dato».

### Cambiado
- Los datos de prueba (TEST-) no se exportan desde la pantalla nueva; en compilaciones de depuración
  hay un interruptor para incluirlos.

### Pruebas
- 12 pruebas JVM nuevas (`ReportsExportTest`): modalidad y «Exclusivo» en tarjetas sueltas y en el
  Radar, filtros combinados, filas de detalle, totales que cuadran con el detalle, ofertado nunca como
  ingreso, archivos CSV/JSON, .zip, historial y periodos con jornada a las 4:00 y semana desde lunes o
  domingo.
- Instrumentadas: `Migration12To13Test` (y las cadenas de migración hasta 13) y `ReportsScreenDataTest`
  (un periodo con ambos botones, dos modalidades, viajes en varios estados y un tanqueo).
- `schemas/12.json` se escribió a mano a partir de la 11 con la columna `card_action` (la 1.21.0 no
  lo generó en tu PC); `13.json` lo genera Room al compilar.

## [1.21.0] - 2026-10-17

Ofertas «Me interesa». Incluye todo lo de la 1.20.0. Base de datos 11 → 12 (solo añade una columna).

### Añadido
- Cada oferta guarda el **botón de su tarjeta**: «Me interesa» (Radar de solicitud de viaje) o
  «Aceptar» (tarjeta directa, también las «Exclusivo»). Las ofertas guardadas antes de esta versión
  no lo tienen.
- Las de «Me interesa» se registran **como ofertas**: tocar «Me interesa» solo le dice a Uber que
  quieres el viaje, y Uber puede dárselo a otro. Pasan a «Tomados» únicamente si después aparece
  «Llegar por …».
- Inicio: debajo de Todo / Ofertados / Tomados, cuántas tarjetas del periodo fueron «Me interesa» y
  cuántas «Aceptar»; cada fila dice «Tarjeta: Me interesa» o «Tarjeta: Aceptar», y el detalle lo explica.
- La exportación de ofertas (CSV y JSON) lleva la columna `boton` / `cardAction`.

### Pruebas
- 3 pruebas JVM nuevas en `OfferRadarTest` (botón de cada tarjeta del Radar, clave fija, conteo y
  que «Me interesa» sin viaje queda en «Ofertados»).
- `Migration11To12Test` (instrumentada): las ofertas pasan intactas con el botón vacío, validado
  con `schemas/12.json`, y la cadena 4 → 12 abre. Las demás pruebas de migración encadenan la 11 → 12.

## [1.20.0] - 2026-10-17

Radar de ofertas y avisos. Incluye todo lo de la 1.19.0. La base de datos no cambia (sigue en 11).

### Corregido
- **Las ofertas con «Me interesa» no se guardaban.** Observador solo reconocía tarjetas con el
  botón «Aceptar»; las del «Radar de solicitud de viaje» (y muchas otras de Uber Moto) dicen «Me
  interesa». Por eso faltaban ofertas por día en Inicio, no salía la tarjeta flotante ni el aviso.
  Confirmado con tus capturas del 17/10.
- **El Radar muestra varias tarjetas a la vez** y solo se leía la primera. Ahora se lee cada una
  (de arriba abajo), con su precio, recogida, destino y direcciones. La tarjeta flotante y el ciclo
  del viaje siguen con la primera; las demás se guardan y avisan por su cuenta. Una tarjeta cortada
  arriba (sin precio a la vista) no se lee hasta que se vea completa.

### Añadido
- Ajustes → «Avisos de ofertas»: **Todas** (por defecto), **Buenas y excelentes** o **Ninguna**.
  Antes solo avisaba el modo Asistido, y el modo por defecto (Observador) nunca avisaba. Cada
  oferta nueva avisa una sola vez, con veredicto, score, recogida y destino; en el Radar cada
  tarjeta tiene su propio aviso. El modo Asistido sigue avisando siempre de BUENAS y EXCELENTES.
- El canal de notificaciones se llama ahora «Ofertas de Uber».

### Pruebas
- 7 pruebas JVM nuevas (`OfferRadarTest`) con los textos de tus capturas: tarjeta con «Me
  interesa», Radar con dos tarjetas en orden y sin mezclar direcciones, tarjeta cortada arriba,
  tarjeta exclusiva con «Aceptar», un precio suelto que no es oferta, y qué ofertas avisan.

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
