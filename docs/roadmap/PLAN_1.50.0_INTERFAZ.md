# Plan 1.50.0 — Interfaz para trabajar y revisar

Estado: **implementado en `feature/1.50.0-interfaz`, validado con 843 pruebas JVM, 4 de interfaz y lint sin errores; comprobación física pendiente**. Decisiones del conductor del 2026-11-14. Rama de trabajo `feature/1.50.0-interfaz`, desde `release/1.49.0`.

## Qué existe hoy

- **Ajustes** (`SettingsScreen.kt`, 1016 líneas): una sola pantalla con 15 secciones seguidas (Apariencia, Costos operativos, Umbrales, Categorías, Kilómetros, Ganancias y metas, Gastos y mantenimiento, Jornada, Overlay, Detección, Modo de operación, Sectores, Laboratorio, Respaldo y Novedades). Encontrar un ajuste exige desplazarse mucho.
- **Tarjeta flotante** (`OverlayManager`): vistas Android con textos de tamaño fijo, siempre arriba y al centro. Muestra precio, puntaje y calidad, km y minutos, RD$/km y la franja de sector. No se puede mover ni elegir qué cifras muestra.
- **Navegación:** 5 pestañas (Inicio, Ganancias, Gastos, Análisis y Ajustes). Viajes, Reportes, Salud, Laboratorio y ahora Horarios y Cierre se abren desde botones repartidos en distintas pantallas.
- **Estados:** cargando, vacío, error y «sin dato» se resuelven distinto en cada pantalla, con 3 indicadores de carga y unos 57 textos «Sin dato» o «—» propios.
- **Formularios:** 24 archivos guardan lo escrito con `remember { mutableStateOf }`, que se pierde al girar el teléfono o cuando Android recrea la pantalla. Solo 10 usan `rememberSaveable`.
- **Accesibilidad:** 45 íconos sin descripción (`contentDescription = null`). Algunos son decorativos y otros botones podrían no tener nombre para TalkBack. Además, hay 16 tamaños de letra fijos.

## Propuesta

### A. Ajustes ordenados (decisión 1)

- Seis grupos: **Lectura y avisos** (detección, modo, overlay y sectores), **Rentabilidad** (costos, umbrales, categorías y metas), **Vehículo y gastos** (km, GPS, recorridos, gastos y mantenimiento), **Jornada**, **Privacidad y respaldo**, y **Apariencia**. Laboratorio, Salud y Novedades van al final.
- Un **buscador** filtra los ajustes por nombre y descripción.
- Ningún ajuste cambia de clave ni de valor. Es solo reorganización, así que los respaldos y perfiles siguen igual.

### B. Tarjeta flotante configurable (decisiones 2 y 3)

- Elegir de 2 a 4 cifras y su orden: precio, puntaje, km y minutos, RD$/km, RD$/h, ganancia estimada y recogida.
- Posición (decisión 3) y tamaño de letra (normal, grande o muy grande), con vista previa en Ajustes antes de aplicarla.
- Los colores siempre llevan una etiqueta («Bueno», «Evitar»): nunca solo color.
- El botón ✕ de cerrar tiene al menos 48 dp y descripción para TalkBack.

### C. Navegación (decisión 4)

- Un acceso único a **Viajes**, **Reportes y cierres** y **Horarios** según la decisión, sin multiplicar las pestañas.

### D. Estados claros en todas las pantallas

- Componentes comunes: `Cargando`, `Vacío` (con qué hacer), `Error` («tus datos no se tocaron» y Reintentar) y `Parcial` (con la cobertura).
- Se aplican en Inicio, Ganancias, Gastos, Análisis, Viajes, Reportes, Zonas, Horarios y Cierre.
- «Sin dato» en lugar de 0 cuando el valor no se conoce. Esto ya se cumple en las cifras de dinero; se revisa el resto.

### E. Formularios que no pierden datos

- Los diálogos de gastos, combustible, jornada, sectores, metas y cierre pasan a `rememberSaveable`. Lo escrito sobrevive al giro de pantalla y a la recreación.

### F. Accesibilidad y legibilidad

- Revisión de los 45 íconos: los botones reciben descripción y los decorativos se marcan como tales.
- Letra grande del sistema (200 %): sin textos cortados en las tarjetas principales ni en la tarjeta flotante.
- Contraste revisado en los temas Sol, Noche y Violeta.

## Datos

Sin migración (Room 21). Ajustes nuevos en DataStore: cifras, orden, posición y tamaño de la tarjeta flotante. Se respaldan y restauran con validación estricta.

## Pruebas

- **Unitarias:** codificación y validación de los ajustes nuevos de la tarjeta, buscador de ajustes, orden y límite de cifras, y que cada ajuste existente siga en algún grupo (ninguno se pierde al reorganizar).
- **Instrumentadas, solo en el emulador:** giro de pantalla con un formulario a medio llenar, letra al 200 % y TalkBack en los botones principales.
- **En el teléfono:** una jornada con la tarjeta en la posición elegida.

## Fuera de alcance

- Cambiar cálculos, puntaje o datos.
- Rediseñar los temas.

## Decisiones del conductor

| # | Pregunta | Opciones | Decisión |
|---|---|---|---|
| 1 | ¿Cómo ordenar Ajustes? | a) 6 grupos, cada uno en su pantalla, con buscador · b) Una sola pantalla con secciones plegables, un índice arriba y buscador | **a** |
| 2 | ¿Tarjeta flotante configurable? | a) Eliges 2 a 4 cifras y su orden · b) Las mismas cifras de hoy, solo tamaño y posición | **a** |
| 3 | ¿Posición de la tarjeta? | a) Arriba, centro o abajo (fija) · b) Arrastrarla y que recuerde dónde la dejaste | **a** |
| 4 | ¿Navegación? | a) Mantener 5 pestañas y poner accesos a Viajes, Reportes y Horarios arriba en Inicio · b) Cambiar la pestaña Gastos por **Viajes** y mover Gastos dentro de Ganancias | **b** |
