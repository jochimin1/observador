# MotorCare y su posible integración en Observador

Revisión del 12 de noviembre de 2026. Documento de análisis y propuesta funcional; no implementa el módulo ni cambia la versión de Observador.

## 1. Resultado principal

El archivo recibido es **MotorCare 1.4.1 / código 26**, paquete `com.chaoyue.motorcare`. Su función es vigilar **presión, temperatura y batería de sensores TPMS para las ruedas delantera y trasera de una motocicleta**, mediante Bluetooth Low Energy. No es un gestor general de mantenimiento, combustible ni reparaciones.

Para Observador recomiendo un apartado **«Mi moto»**, con un subapartado **«Neumáticos y sensores»**. Los gastos, combustible y recordatorios existentes deben alimentar ese mismo apartado. La novedad aportada por MotorCare sería la telemetría de las ruedas, su asociación a sensores y las alertas. El teléfono necesita sensores compatibles para obtener medidas automáticas.

La revisión distingue lo observado en pantallas, lo encontrado en el APK y lo que requiere hardware real. Una función localizada en código no equivale a una medición o alarma verificada físicamente.

## 2. Archivo y alcance reproducible

| Dato | Resultado |
|---|---|
| Archivo entregado | `motorcare.apk`, 15 848 524 bytes |
| SHA-256 | `3CF91554D611D45F916714CEBEEA9264BF5132FBDDDD3EC332D9613680CBEE3A` |
| Identificador | `com.chaoyue.motorcare` |
| Versión del APK analizado | **1.4.1**, código **26** |
| Android mínimo / objetivo | API 19 / API 33 |
| Compilación declarada | API 33 |
| Tecnología | Android nativo, Java, bibliotecas Android Support, ZXing y EventBus |
| Estructura | Un `classes.dex`; sin bibliotecas nativas `.so` en el archivo |
| Actividades declaradas | Principal, selección de asociación, asociación, búsqueda automática, ayuda, HUD y lector QR: 7 |
| Servicio / receptor | `MainService` / `BootCompletedReceiver` |
| Persistencia observada | Preferencias locales: identificadores, configuración y últimas tramas recibidas |
| Observador revisado para la integración | Workspace **1.37.1 / 47**, Room **14**; no confundir con versiones futuras propuestas |

Se analizaron manifiesto, recursos, textos, navegación y clases propias del APK. JADX produjo tres métodos incompletos en su salida inicial: inicialización de ajustes, actualización de pantalla y procesamiento principal de lecturas. Se recuperó una segunda representación del procesamiento principal con `--show-bad-code`; sus advertencias obligan a no tratar el Java reconstruido como código original exacto. Las excepciones «Method not decompiled» de esos archivos son artefactos del analizador, **no errores demostrados de MotorCare**.

La ficha oficial describe el mismo producto TPMS y muestra una actualización del 9 de diciembre de 2025. Esta auditoría corresponde al archivo **1.4.1 entregado**, no certifica que sea la última edición distribuida ni evalúa otro APK. [Google Play](https://play.google.com/store/apps/details?id=com.chaoyue.motorcare&hl=en).

## 3. Inventario de funcionalidades

Claves de evidencia: **UI** = pantalla o interacción comprobada en el emulador; **APK** = recursos/código/manifiesto; **HW** = comprobación física pendiente. El registro de recorrido al final precisa qué partes de cada flujo se ejecutaron.

### 3.1 Panel principal

| ID | Función | Propósito y comportamiento | Evidencia y límite |
|---|---|---|---|
| MC01 | Dos ruedas diferenciadas | Representa rueda delantera y trasera; mantiene IDs y lecturas independientes | APK; no es un sistema de cuatro ruedas aunque existan constantes heredadas para 3 y 4 |
| MC02 | Presión por rueda | Muestra la presión recibida y su unidad | APK; exactitud, latencia y calibración HW pendientes |
| MC03 | Temperatura por rueda | Muestra temperatura del sensor, con conversión de unidad | APK; no equivale a temperatura del motor ni a temperatura ambiente |
| MC04 | Estado de presión | Indica normal, demasiado alta o demasiado baja; cambia colores de valor/unidad/estado | APK; requiere lecturas para comprobar la reacción completa |
| MC05 | Estado térmico | Indica normal o temperatura excesiva | APK + HW pendiente |
| MC06 | Indicación de fuga | Presenta señal de pérdida de aire cuando la trama lo indica | APK; no hay prueba de detección real de pinchazo |
| MC07 | Batería del sensor | Porcentaje y representación gráfica por rueda, con aviso de nivel bajo | APK; porcentaje dependiente del protocolo del fabricante |
| MC08 | Consulta de voltaje | Pulsar la zona de una rueda calcula y muestra un voltaje aproximado a partir del nivel de batería | APK; valor derivado, no campo de voltaje directo demostrado |
| MC09 | Estado Bluetooth | Informa si Bluetooth está encendido y solicita activarlo si corresponde | APK; encendido no demuestra recepción de ambos sensores |
| MC10 | Silencio rápido | Icono para activar o desactivar avisos de voz desde Inicio | APK; es un ajuste global de voz |
| MC11 | Restauración de última lectura | Recupera últimas tramas guardadas al volver a la pantalla | APK; no se encontró una edad de lectura visible vinculada a esas tramas |

### 3.2 Asociación de sensores

«Asociar» significa guardar qué identificador pertenece a cada rueda. En la ruta analizada se reciben anuncios BLE; no se encontró una llamada de enlace Bluetooth clásico ni una conexión GATT de lectura.

| ID | Método | Flujo y propósito | Límite |
|---|---|---|---|
| MC12 | Manual | Elegir rueda, escribir código de 6 caracteres y confirmar | En esa ruta se valida longitud; no se encontró validación hexadecimal completa, unicidad entre ruedas ni confirmación con lectura física |
| MC13 | Código individual por cámara | Elegir rueda y escanear código del sensor; el lector incluye códigos de barras 1D y QR | La asociación acepta resultado de 6 caracteres; necesita cámara. El manual del kit del usuario incluye códigos Code 128 |
| MC14 | QR conjunto | «One key sweep code»: leer una cadena de 13 caracteres y extraer los dos códigos, posiciones 0–5 y 7–12 | La separación se ignora al extraer; no demuestra soporte para QR arbitrarios, enlaces web o cualquier fabricante |
| MC15 | Automática | Elegir delantera o trasera y buscar durante unos 60 segundos | Acepta ID cuando concuerdan nombre y datos del anuncio del protocolo esperado; hallazgo HW pendiente |
| MC16 | Persistencia de asociación | Guarda y vuelve a mostrar IDs de cada rueda; permite reemplazarlos | No se encontró una función dedicada de intercambio entre ruedas o desvinculación completa en los controladores revisados |
| MC17 | Permiso de cámara | Lo solicita al abrir el lector; permite volver desde la cámara | No hace falta cámara para asociación manual o automática |

En búsqueda automática, el APK distingue nombres como `TPMS1` / `TPMS_1` y `TPMS2` / `TPMS_2`, exige coincidencia del ID de seis caracteres y comprueba los marcadores de rueda de su formato. Esto **no demuestra compatibilidad universal** con todo producto vendido como TPMS.

### 3.3 Ajustes y alarmas

| ID | Función | Detalle y propósito |
|---|---|---|
| MC18 | Unidad de presión | kPa, PSI o bar; cambia presentación y umbrales mostrados |
| MC19 | Unidad de temperatura | °C o °F |
| MC20 | Presión mínima delantera | Umbral independiente para advertir baja presión |
| MC21 | Presión máxima delantera | Umbral independiente para advertir alta presión |
| MC22 | Temperatura máxima delantera | Umbral independiente para calentamiento |
| MC23 | Presión mínima trasera | Independiente de la rueda delantera |
| MC24 | Presión máxima trasera | Independiente de la rueda delantera |
| MC25 | Temperatura máxima trasera | Independiente de la rueda delantera |
| MC26 | Batería mínima | Umbral global de nivel de batería de los sensores |
| MC27 | Avisos de voz | Activación/desactivación global y sonidos grabados para rueda y tipo de problema |
| MC28 | Vibración | Activación/desactivación independiente de la voz |
| MC29 | Alarmas combinadas | Sonidos para fuga junto con presión baja; no son funciones de diagnóstico adicionales |
| MC30 | Idioma | Inglés o chino; cambio de textos y banco de voz |
| MC31 | Restablecer ajustes | Confirmación antes de recuperar valores predeterminados; no equivale a borrar toda la aplicación |
| MC32 | Persistencia | Guarda ajustes entre aperturas |

Rangos del deslizador encontrados en esta versión, expresados en unidades base:

| Parámetro | Rango de la interfaz | Predeterminado localizado |
|---|---|---|
| Presión mínima, cada rueda | 10–250 kPa | 200 kPa |
| Presión máxima, cada rueda | 280–640 kPa | 300 kPa |
| Temperatura máxima, cada rueda | 60–80 °C | 65 °C |
| Batería mínima | 0–100 % | 20 % |
| Voz / vibración | Activada o desactivada | Voz activada / vibración desactivada |
| Unidades | kPa, PSI, bar / °C, °F | kPa / °C |

Son valores del software analizado, **no presiones ni límites recomendados para tu moto**. En Observador los parámetros deben proceder del vehículo/neumático y de una configuración explícita del conductor. El rango térmico de la interfaz y los límites de presión de MotorCare son restricciones de su diseño, no una regla para copiar.

Los avisos localizados distinguen delantera/trasera y presión alta, presión baja, temperatura alta, fuga, fuga con presión baja y batería baja: 12 combinaciones de voz por idioma. La cola recorre alertas con un intervalo de aproximadamente 3 segundos; la vibración tiene llamadas de 1 segundo. No se verificó sonido, prioridad, audibilidad con casco, llamadas entrantes ni comportamiento con sensores reales.

### 3.4 Ayuda y operación en segundo plano

| ID | Función | Alcance real |
|---|---|---|
| MC33 | Ayuda de permisos | Explica Bluetooth, ubicación, batería y operación continua |
| MC34 | Atajos a ajustes Android | Permisos de la app, optimización de batería y ajustes generales |
| MC35 | Guías según fabricante | Textos para Huawei, Vivo, OPPO, Xiaomi, Meizu, Samsung, Lenovo, Nubia y alternativa general; no se probaron esos fabricantes |
| MC36 | Recepción continua | Servicio y tareas periódicas para mantener búsqueda; no basta para garantizar continuidad con pantalla apagada |
| MC37 | Reacción al Bluetooth | Intenta recuperar el flujo al encenderse Bluetooth y pide activación si se apaga |
| MC38 | Reacción a ubicación | Comprueba el interruptor de ubicación y orienta al usuario cuando no está disponible |
| MC39 | Arranque del teléfono | Receptor declarado que intenta abrir la actividad al recibir `BOOT_COMPLETED`; ejecución efectiva no verificada |
| MC40 | Mantener CPU activa | `WakeLock` mientras vive la actividad; implica una decisión de consumo que debe medirse |
| MC41 | Acuerdos y privacidad | Enlaces a páginas del proveedor y diálogo específico de la ruta china |

**HUD y ayuda antiguos:** el APK conserva una actividad `HudActivity` con reloj digital, presión y temperatura, y otra `HelpActicty`. No se encontró una ruta de navegación activa hacia ellas en los controladores de la versión principal. Se documentan como componentes presentes, **no como funciones accesibles confirmadas**. Tampoco debe anunciarse una vista flotante/HUD sobre Uber: este APK no declara el permiso de superposición.

**Idiomas:** muchas carpetas `values-*` proceden de bibliotecas Android. Los textos propios revisados y el selector de la app son inglés/chino; la presencia de `values-es` no demuestra una traducción española de MotorCare.

## 4. Funciones que no encontré en esta versión

La ausencia se refiere a navegación, manifiesto, recursos y clases propias revisadas, no a una afirmación sobre toda versión o producto del proveedor.

- Cuenta, inicio de sesión, suscripción, compras integradas o anuncios en el flujo propio revisado.
- API pública, exportación o proveedor de contenido para que Observador consulte sus datos.
- Historial navegable de presión/temperatura, gráficos temporales o registro durable de incidentes. Las claves con «history» no bastan: las rutas revisadas conservan última lectura y banderas, no una serie consultable.
- CSV, PDF, respaldo/restauración manual de mediciones o sincronización entre equipos.
- Múltiples motos con perfiles independientes, ficha de marca/modelo/placa o inventario completo de componentes.
- Control de aceite, cadena, frenos, revisiones, taller, odómetro, combustible, ingresos o costo por kilómetro.
- Diagnóstico OBD, códigos de falla del motor, GPS de viajes o navegación.
- Control físico del inflado o desinflado, calibración de los sensores o petición comprobada de una medición instantánea al sensor.
- Estado verificable de «lectura reciente / antigua / sin señal» con marca de tiempo visible.

El manifiesto no declara `INTERNET`. Los enlaces legales se abren mediante una aplicación externa. Esto apoya una arquitectura local para el flujo propio de este APK, pero no prueba ausencia de comunicaciones del sistema, backups Android o navegador. `allowBackup=true` está declarado. La ficha de Google Play declara no recopilar ni compartir datos; esa declaración pertenece al desarrollador y no sustituye una auditoría de tráfico. [Ficha oficial](https://play.google.com/store/apps/details?id=com.chaoyue.motorcare&hl=en).

## 5. Lectura técnica útil para Observador

### Recepción y protocolo

El servicio usa anuncios BLE, compara nombre y un patrón del fabricante, extrae el ID del sensor y guarda la última trama correspondiente a la rueda asociada. No se encontró en las clases propias una sesión `connectGatt`, `createBond` ni una API de consulta remota.

El formato revisado utiliza posiciones fijas en una representación hexadecimal de la trama: patrón `0001` en posiciones 18–21, ID en 28–33, presión en 34–41, temperatura en 42–49, batería en 50–51 y una bandera posterior relacionada con fuga. Presión reconstruida como entero little endian y dividida por 1000; temperatura como entero con tratamiento de negativos y escala 100. Son **observaciones de esta familia y este APK**, no una especificación universal ni garantía del modelo físico del usuario.

La implementación futura debe interpretar las estructuras AD de Bluetooth, validar longitudes/tipos/versión y rechazar tramas incompletas. No conviene trasladar offsets de la cadena completa sin pruebas con anuncios reales: el orden y la composición de datos publicitarios pueden variar. El nombre comercial del sensor o que aparezca «TPMS» no basta para asociarlo.

### Riesgos y limitaciones encontrados

| Hallazgo | Evidencia | Consecuencia para el diseño de Observador |
|---|---|---|
| «Normal» junto a valores `--` sin sensores | Pantalla inicial comprobada en el emulador | Estado inicial «Sin datos»; no inferir seguridad por ausencia de recepción |
| Última trama sin edad visible identificada | Preferencias y reconstrucción de pantalla | Mostrar `recibido hace…`; conservar diferencia entre recepción y medición del sensor |
| Escaneo recurrente amplio | Reinicios de escaneo y modo de baja latencia en la ruta moderna | Un único gestor BLE; medir consumo, evitar búsquedas duplicadas y hacerlas cancelables |
| Procesamiento por substrings | Lectura de posiciones fijas sin guardas completas visibles en varias rutas | Parser puro con validación de longitud y valores; pruebas con paquetes cortos, extraños y negativos |
| IDs manuales poco validados | Comprobación de longitud 6 en el controlador | Validar formato admitido, duplicados y asignación delantera/trasera |
| Búsqueda automática por primer resultado admitido | Callback de asociación con estado `isExist` | Presentar candidato y confirmar rueda; no asociar silenciosamente un sensor vecino |
| Límite de umbrales estrecho | Deslizadores y funciones de guardado | Perfiles y rangos adecuados al hardware; no imponer los valores genéricos de MotorCare |
| Servicio antiguo | `startService`, temporizadores y dependencias de actividad; foreground real no demostrado en el servicio principal | Diseñar ciclo de vida y permisos para el target actual de Observador |
| Receptor de reinicio abre actividad | Manifiesto y receptor | No copiar apertura forzada; recuperación compatible con restricciones Android |
| Voz repetida | Cola y repetición de alarmas | Deduplicación, prioridad, silencio temporal y accesibilidad; probar convivencia con navegación y llamadas |
| Lecturas de batería/voltaje dependientes del fabricante | Conversión propia desde nivel | Marcar voltaje estimado o eliminarlo si no se valida; nunca presentarlo como batería de la moto |

En Android moderno, la integración BLE necesita declarar y solicitar los permisos correspondientes a las operaciones realizadas. `neverForLocation` puede filtrar ciertos anuncios, por lo que requiere verificación con los sensores elegidos. No se debe exigir ubicación continua como condición universal del TPMS. [Permisos oficiales](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

Para operación prolongada deberá evaluarse el mecanismo apropiado de presencia/escaneo y, si corresponde, un servicio de tipo `connectedDevice` con sus requisitos. Que Observador ya tenga un servicio de ubicación no habilita automáticamente el uso BLE continuo. [Tipos de servicio oficiales](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).

## 6. Qué reutilizar de Observador

| Necesidad | Estado actual | Cambio recomendado |
|---|---|---|
| Gastos de neumáticos | Existe `ExpenseType.TIRES` | Vincular cambio/reparación de una rueda al gasto existente mediante ID; guardar una sola operación monetaria |
| Aceite, cadena y frenos | Existen categorías y recordatorios por km | Integrarlos en la vista del vehículo; ampliar contexto y fecha cuando se implemente el módulo |
| Odómetro | Lecturas, reinicios y línea temporal existentes | Reutilizarla; no crear un contador paralelo de km para el TPMS |
| Combustible | Tanqueos, lleno a lleno y mediana de rendimiento/costo | Mostrar desde el repositorio actual; no atribuir cambios de consumo a presión sin evidencia suficiente |
| GPS y jornadas | Fuente de localización compartida existente | BLE debe funcionar como fuente independiente, sin iniciar otro GPS |
| Alertas | `MaintenanceNotifier` con deduplicación por cambio/nivel | Canal separado de sensores; mantenimiento y alarma de rueda tienen urgencias distintas |
| Respaldo y reportes | Infraestructura existente | Extender versión del formato, esquema y opciones de privacidad para sensores/historial |
| Interfaz | Compose, cinco pestañas, temas y ajustes | Acceso «Mi moto» desde Inicio y Gastos; decidir una nueva pestaña solo tras evaluar navegación |

Archivos de referencia propios: `domain/expenses/Expenses.kt`, `ExpenseRules.kt`, `data/ExpenseRepository.kt`, `service/MaintenanceNotifier.kt`, `ui/expenses/ExpensesScreen.kt` y las entidades de gastos. Los recordatorios actuales se comprueban al abrir y al guardar gastos; no deben describirse como un monitor de sensores continuo.

## 7. Propuesta detallada del apartado «Mi moto»

### A. Resumen del vehículo

**Propósito:** ver si la moto está lista para trabajar y reunir la información dispersa.

Ficha de moto activa; odómetro y origen de la cifra; próximo mantenimiento; consumo medido; gasto del período y acceso al estado de ambas ruedas. Diferenciar «falta revisar» de «problema detectado». Ausencia de datos no es estado correcto.

Primera versión para una moto activa. El modelo debe prever `vehicleId` y conservar historial cuando se cambie de vehículo. No asignar automáticamente todos los gastos históricos a una moto nueva sin una decisión de migración visible.

### B. Neumáticos y revisión manual

**Propósito:** aportar utilidad aunque no haya sensores y registrar el cuidado de cada rueda.

Dos fichas con posición, marca/modelo opcionales, medida, fecha y odómetro de instalación, reparaciones, cambios, presión objetivo configurada y última revisión. Registrar presión manual con fecha, unidad y contexto frío/caliente si el usuario lo conoce. La lectura manual se etiqueta como tal y nunca se renueva automáticamente.

Una reparación puede tener costo y enlazar con Gastos; una medición gratuita no debe exigir crear un gasto. El cambio de neumático inicia un nuevo componente y conserva el anterior. Un reinicio de odómetro no debe producir vida útil negativa.

### C. Sensores Bluetooth opcionales

**Propósito:** recibir datos reales sin obligar a abrir MotorCare.

Asistente con detección de capacidad BLE, explicación de permisos, búsqueda cancelable, candidatos compatibles, selección de rueda y confirmación por ID. Entrada manual, código de barras Code 128 individual y QR individual; QR doble solo con formato documentado y validado. Para el kit fotografiado, la cadena conjunta es `ID_DELANTERO&ID_TRASERO`. Impedir el mismo sensor en ambas ruedas. Añadir reemplazo, desvinculación y cambio de posición con confirmación.

Mostrar ID abreviado, familia/protocolo, última recepción y estado. No llamar «conectado» a un emisor de anuncios simplemente porque Bluetooth está encendido. Un estado correcto sería «recibiendo», «esperando emisión», «lectura antigua», «permiso faltante» o «Bluetooth apagado».

No se necesita leer otra app por accesibilidad: lo preferible, sujeto a compatibilidad física, es recibir los anuncios del sensor. No se ha encontrado una API de MotorCare que deba consumirse.

### D. Panel de telemetría

**Propósito:** hacer comprensible la situación de cada rueda con mínima distracción.

Presión y temperatura, batería del sensor y hora de última recepción en la unidad elegida. Diseño legible de día/noche y texto además de color. Detalle con rango configurado, procedencia y calidad de lectura. El modo de conducción utiliza números grandes y pocas acciones; los ajustes se hacen con el vehículo detenido.

La frescura se calcula desde `receivedAt` y un plazo definido por protocolo y pruebas. Si el sensor no transmite la hora de medición, la interfaz debe decir «recibido», no inventar «medido ahora». Una trama idéntica recibida de nuevo actualiza recepción, pero no debe fingir un cambio físico de presión. La pantalla apagada o el cambio de app no autorizan a declarar que se mantienen lecturas sin verificarlo.

### E. Alertas explicables

**Propósito:** avisar de cambios relevantes con una causa clara.

Configuración por rueda para baja/alta presión y temperatura; batería baja; pérdida de recepción; fuga solo si el protocolo la soporta de manera validada. Histéresis y confirmación temporal configuradas por el diseño y las pruebas para no alternar estados por ruido. Separar fuga informada por sensor de caída de presión inferida por Observador.

Registrar inicio, última confirmación, finalización, rueda, valor, límites y motivo. Notificación, voz en español opcional y vibración; prioridad para eventos simultáneos, repetición limitada y silencio temporal visible. Con permiso de notificación denegado, mostrar explícitamente que los avisos externos no están disponibles. La app no debe afirmar que previene pinchazos ni garantizar detección de todos los fallos.

### F. Historial y reportes

**Propósito:** revisar eventos, diagnosticar pérdidas de recepción y llevar un registro del cuidado.

Gráficos de presión/temperatura por rueda y período; tramos sin datos visibles; lista de alertas y cambios de sensor; filtros por vehículo, componente y jornada. Mantener fuente manual/BLE separada. Retención configurable y agregación para evitar almacenar todo anuncio indefinidamente.

Exportación CSV/JSON de lecturas y eventos; resumen PDF dentro del sistema existente. Incluir unidad canónica, unidad de presentación, zona horaria, origen y calidad. Excluir IDs completos y ubicación por defecto cuando no hagan falta; vinculación con recorridos opcional. Extender respaldo, restauración y borrado del historial.

### G. Relación con rentabilidad

**Propósito:** conocer el costo del vehículo sin confundir medidas con dinero confirmado.

Enlazar cambios de gomas y reparaciones con gastos reales. Mostrar costo de mantenimiento/km con cobertura y período explícitos cuando exista base suficiente. Una alarma o una presión baja **no crea un gasto**, no cambia ingresos y no modifica automáticamente el puntaje de las ofertas. Cualquier futura relación entre presión y consumo sería un análisis exploratorio, no causalidad demostrada.

## 8. Versiones propuestas y propósito

No se asigna `versionCode` ni número de esquema Room antes de implementar: el proyecto sigue en 1.37.1/47, Room 14. Las siguientes versiones son una extensión propuesta del roadmap que llega a 1.52.0, no publicaciones ni fechas prometidas. El trabajo de vehículo ya previsto en **1.43.0** debe reutilizarse.

| Versión | Entrega concreta | Propósito | Condición de salida |
|---|---|---|---|
| **1.43.0**, propuesta existente | Ficha de vehículo, componentes, combustible y mantenimiento | Establecer identidad del vehículo e historial común | Odómetro, cobertura GPS, gastos y cambio de moto coherentes |
| **1.53.0 — Mi moto y neumáticos** | Entrada al apartado, dos ruedas, revisiones manuales, instalación/reparación/cambio, enlace con gastos y unidades | Dar utilidad sin hardware y preparar datos correctos | Persistencia, edición, borrado, migración y restauración; ninguna operación monetaria duplicada |
| **1.54.0 — TPMS compatible** | Gestor BLE, parser de una familia validada, asociación manual/QR/automática, panel con frescura, umbrales y alertas básicas | Sustituir la consulta del sensor en otra app para hardware comprobado | Dos sensores físicos identificados, pruebas de comparación, permisos, pérdida de señal, bloqueo de pantalla y consumo; compatibilidad publicada por modelo/protocolo |
| **1.55.0 — Vehículo integrado** | Historial, incidentes, exportación, respaldo, voz/vibración afinadas, accesibilidad y validación de uso prolongado | Entregar el apartado completo y mantenible | Pruebas de uso real y restauración completas; límites de compatibilidad y cobertura visibles |

**Versión objetivo final de esta propuesta: Observador 1.55.0.** Si se decide adelantar la integración respecto del roadmap, hay que reordenar versiones antes de desarrollar; no saltar la app directamente de 1.37.1 a 1.55.0 ni presentar como hechas las fases intermedias.

Requisito previo transversal: cerrar los pendientes de estabilidad, migraciones y respaldo documentados para 1.37.2 y posteriores. La parte manual no depende de conseguir sensores; la salida de 1.54.0 sí depende de pruebas físicas. Si el hardware no corresponde al protocolo estudiado, su adaptador es un alcance nuevo y no una compatibilidad asumida.

## 9. Modelo de datos y reglas de integración

Entidades conceptuales, sujetas al diseño y migración definitivos:

- `Vehicle`: identidad, vigencia y parámetros del vehículo.
- `TireInstallation`: rueda, neumático/componente, fechas, odómetro y gasto enlazado opcional.
- `SensorBinding`: vehículo, rueda, familia, identificador, vigencia; no usar ID BLE como clave primaria del vehículo.
- `TireReading`: fuente manual/BLE, sensor/componente, presión en unidad canónica entera, temperatura en centésimas de °C si el protocolo lo permite, batería opcional, `receivedAt`, `measuredAt` opcional, calidad y versión del parser.
- `TireThresholdProfile`: límites por rueda, unidad base, fecha y revisión de configuración.
- `TireAlertEvent`: inicio/fin, tipo, rueda, evidencia y límites aplicados; sin importe financiero.

Reglas: campos ausentes son nulos, no cero; valores fuera del formato soportado se rechazan o se marcan inválidos. Las conversiones no alteran el valor original ni disparan otra alarma. No confundir presión absoluta con manométrica: comprobar qué entrega el hardware antes de convertir. Un cambio de sensor no cambia retroactivamente la procedencia de lecturas previas. Conservar intervalos de asociación para que un sensor reemplazado no actualice la rueda antigua.

Un coordinador BLE debe publicar estados y lecturas al repositorio, sin depender de que una pantalla esté abierta. Compartir la infraestructura de servicio solo si el ciclo de vida y los tipos de servicio quedan correctos; no iniciar un GPS adicional. Las lecturas se agregan con una política de retención; los incidentes relevantes deben conservarse aunque se agreguen las muestras.

Las nuevas tablas deben generar esquemas auténticos y pruebas de migración. El backup de configuración puede conservar asociaciones, pero al restaurar en otro teléfono debe quedar «pendiente de recepción», nunca «sensor activo» por una bandera guardada. No copiar código, sonidos, iconos ni marcas de MotorCare dentro del producto: esta propuesta usa sus comportamientos observables como referencia.

## 10. Pruebas necesarias para considerar terminada la integración

1. **Identidad:** dos sensores cercanos, rueda incorrecta, sensor vecino, ID repetido, sustitución, desvinculación y reinstalación.
2. **Parser:** anuncios cortos, malformados, AD reordenados, protocolo desconocido, presión cero válida según especificación, valores imposibles, temperatura negativa y batería ausente.
3. **Medición física:** comparar con instrumento de referencia y documentar tolerancia, escala, presión absoluta/manométrica y comportamiento del hardware. No inferir precisión desde números en pantalla.
4. **Frescura:** emisión repetida idéntica, sensor inmóvil/dormido, fuera de alcance, Bluetooth apagado, proceso finalizado, hora del sistema cambiada y reinicio. El dato antiguo nunca debe parecer actual.
5. **Alertas:** ambos sensores a la vez, oscilación en umbral, histéresis, recuperación, batería baja, fuga reportada frente a inferida y silencio temporal.
6. **Android y energía:** permisos concedidos/denegados/revocados, notificaciones apagadas, pantalla bloqueada, ahorro de batería, otra app en primer plano y dispositivo Android mínimo/objetivo soportados por Observador. Medir duración y consumo con condiciones repetibles, sin inventar porcentajes ahora.
7. **Convivencia:** servicio de accesibilidad, oferta Uber, overlay, GPS, voz de navegación y llamada. El TPMS no debe romper captura ni cerrar ofertas por su cuenta.
8. **Datos:** migración desde bases mantenidas, edición de revisión, cambio de odómetro, gasto enlazado una sola vez, retención, exportación, backup/restauración y borrado.
9. **Interfaz:** español, PSI/bar/kPa y °C/°F, temas claro/oscuro/sol, letra grande, lector de pantalla y orientación que se decida soportar.

Sin sensores físicos solo pueden verificarse interfaz, persistencia y pruebas de protocolo con datos de prueba. Eso no certifica recepción, exactitud, fugas ni alertas en carretera.

## 11. Evidencias y recorrido

El manifiesto identificado se conserva en [manifest.xml](manifest.xml). Los archivos de trabajo del análisis están en `build/motorcare-review/` y quedan fuera del código de producción. Las evidencias seleccionadas se conservan en `evidencias/`: 19 capturas con sus 19 jerarquías XML, el resultado de lectura de códigos y el registro de excepción. No se utilizaron tramas simuladas como si fueran sensores reales.

### Recorrido efectivamente realizado

Emulador Pixel_10, API 37, sesión de solo lectura del AVD, sin ventanas, con todos los comandos dirigidos explícitamente a `emulator-5580`. Se amplió la memoria de 2 a 4 GB y se redujo la pantalla a 720×1280 para completar el recorrido. Es un único entorno, no una matriz de compatibilidad de teléfonos.

| Área | Comprobación real | Evidencia |
|---|---|---|
| Inicio | Dos ruedas, temperatura/presión, guiones sin medición y etiquetas «Normal» | [Captura](evidencias/11-home.png), [jerarquía](evidencias/11-home.xml) |
| Asociación | Menú de cuatro métodos | [Captura](evidencias/12-bind-menu.png) |
| Manual | Abrir diálogo delantero, guardar ID sintético `a1b2c3`, volver a verlo | [Diálogo](evidencias/15-manual-input.png), [guardado](evidencias/16-manual-saved.png) |
| Automática | Elegir rueda trasera, observar cuenta «scanning.. 57S», cancelar y volver | [Búsqueda](evidencias/19-auto-search.png); no apareció un sensor real ni se verificó un emparejamiento físico |
| Escáner individual | Abrir desde la rueda, conceder permiso de cámara, ver escáner y salir | [Escáner](evidencias/25-scanner.png) |
| Escáner conjunto | Abrir desde «One key sweep code» y ver el escáner | [Escáner conjunto](evidencias/45-double-scanner.png); no se presentó un código a la cámara virtual |
| Ajustes | Revisar sección general, límites delanteros, traseros y versión V1.4.1 | [General](evidencias/26-settings.png), [inferior](evidencias/29-settings-bottom.png) |
| Conversiones | Pasar a PSI y °F: 300→43.5, 200→29.0, 65→149; después a bar: 3.00 y 2.00 | [PSI/°F](evidencias/27-units-psi-f.png), [bar](evidencias/39-bar.png) |
| Independencia de umbrales | Cambiar límite superior delantero hasta 86.4 PSI; trasero permanece en 43.5 PSI | [Delantero](evidencias/28-front-slider.png), [trasero](evidencias/29-settings-bottom.png) |
| Restablecimiento | Ver confirmación y aceptar; valores vuelven a kPa, 300/200 y 65 °C | [Confirmación](evidencias/30-reset-dialog.png), [resultado](evidencias/31-reset-done.png) |
| Idioma | Inglés→chino→inglés | [Chino](evidencias/35-language-chinese.png); voces de alarma no reproducidas |
| Persistencia | Forzar detención y reabrir; unidad bar y ID sintético conservados; restablecer ajustes no eliminó el ID | [Unidad conservada](evidencias/41-persisted-home.png), [ID conservado](evidencias/43-manual-persistence.png) |
| Ayuda | Secciones de ubicación, batería y funcionamiento en segundo plano | [Superior](evidencias/32-help.png), [inferior](evidencias/33-help-bottom.png) |
| Fotos reales | Leer dos Code 128, QR de asociación y QR Android de descarga | [Resultado de decodificación](evidencias/photo-codes.json) |

La selección de voz/vibración se localizó en interfaz y código; no se desencadenaron alarmas físicas ni se midió vibración/sonido. No se comprobó todavía QR leído por la cámara de MotorCare, emisión BLE, precisión, voltaje físico, fugas, pantalla apagada prolongada, arranque del teléfono ni soporte de otros fabricantes Android. La decodificación de fotos se hizo con un lector local independiente y se identifica como tal.

### Incidencias del entorno y de la app

Hubo avisos de **System UI no responde** durante los arranques del emulador y finalizaciones de procesos; no se atribuyen automáticamente a MotorCare. Se registró además una excepción propia de MotorCare durante una recreación de actividad asociada al cambio de configuración de pantalla: `Unable to destroy activity`, causada por `IllegalArgumentException: Receiver not registered` en `MainChangeActivity.onDestroy`. [Registro](evidencias/crashes.txt).

La excepción tiene una causa concreta de ciclo de vida y una condición observada; no demuestra que MotorCare falle siempre al abrirse ni que todos los teléfonos tengan el mismo problema. El cambio posterior inglés/chino sí pudo completarse. El emulador se apagó al finalizar; no se instaló nada en el teléfono físico conectado.

### Trazabilidad del análisis del APK

| Área | Clase o recurso inspeccionado |
|---|---|
| Navegación y alarmas | `activity/MainChangeActivity`, `fragment/ShouYeFragment`, `res/layout/bottom.xml` |
| Asociaciones | `fragment/BindFragment`, `activity/BindActivity`, `BindStyleActivity`, `AutoBindActivity` |
| Escáner y formatos | `zxing/activity/CaptureActivity`, `zxing/decoding/DecodeThread`, `DecodeFormatManager`, `DecodeHandler` |
| Valores y conversión | `fragment/SetFragment`, `utils/SetValueTool`, `utils/SPUtils`, `app/MyApplication` |
| Recepción BLE | `service/MainService` y funciones `sendbleMessage`, `getTireName`, `searchBluetoothMore` |
| Operación/permisos | `activity/Permission`, `fragment/HelpFragment`, `utils/BootCompletedReceiver`, manifiesto |
| Componentes antiguos | `activity/HudActivity`, `HelpActicty` y referencias de navegación |

El manifiesto incluye **19 declaraciones de permiso**: Bluetooth antiguo y moderno, ubicación aproximada/precisa/en segundo plano, cámara/linterna, vibración, CPU activa, reinicio, excepciones de batería, operación de dispositivo acompañante, servicios y permisos específicos de fabricantes/legados. La mera declaración no prueba una función accesible: por ejemplo, no se encontró un botón de linterna activo ni una función de registro GPS de viajes. Las pantallas y rutas concretas del inventario tienen mayor valor que una lista de permisos.

Fuentes primarias: APK entregado por el usuario; repositorio local de Observador; [ficha de MotorCare](https://play.google.com/store/apps/details?id=com.chaoyue.motorcare&hl=en); [permisos Bluetooth](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions); [servicio para dispositivos conectados](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).

## 12. Kit físico identificado mediante las fotos del usuario

Las ocho fotos aportadas después del APK permiten acotar el equipo. El folleto muestra **LEEPEE**, sensores externos para válvula con sistema antirrobo, instrucciones de MotorCare y Bluetooth 4.0 o superior. No aparece en las caras visibles un número de modelo o firmware que permita asegurar una variante de radio exacta. La referencia al hardware es por tanto **kit LEEPEE para MotorCare de dos ruedas**, no una certificación de todos los LEEPEE.

Se decodificó directamente la foto `IMG_20261112_194709.jpg`:

| Elemento del folleto | Formato reconocido | Contenido exacto |
|---|---|---|
| Rueda delantera | Code 128 | `13AE32` |
| Rueda trasera | Code 128 | `23A434` |
| Asociación conjunta | QR | `13AE32&23A434` |

El QR conjunto tiene exactamente 13 caracteres y el separador `&` en la posición esperada por el APK. Se confirma la coherencia entre **etiquetas físicas y formato de asociación**, no la recepción BLE ni la precisión del equipo. La app normaliza los IDs a minúsculas al guardarlos; mayúsculas en la etiqueta no deben impedir la futura asociación.

La foto de descarga `IMG_20261112_194715.jpg` contiene un QR Android con `http://www.elm327.com/uploads/soft/Android/motorcare.apk`. Se registró el texto decodificado; no se descargó ese archivo ni se sustituyó el APK entregado. El QR iOS no produjo un resultado en la lectura realizada: no se reconstruyó ni se adivinó su destino.

El manual añade cuatro hechos relevantes para el diseño:

1. Cada sensor tiene un ID único y debe instalarse en la rueda indicada. Observador debe conservar esa asignación y confirmarla en el asistente.
2. La asociación puede hacerse leyendo la tarjeta del kit. El escáner de Observador debe aceptar **Code 128 individual** además del QR doble, no limitarse a QR por su nombre en la interfaz.
3. El folleto contempla demoras de transmisión dependientes del teléfono y del hardware. Escanear o actualizar la pantalla no garantiza una nueva medición inmediata.
4. El equipo informa presión/temperatura y no evita por sí mismo un accidente. Su presencia no autoriza a mostrar una rueda como normal sin una lectura válida y reciente.

En la primera versión BLE se debe validar **este par de sensores** como caso concreto. Los IDs del usuario son evidencia de asociación, no claves secretas ni contraseñas; conviene abreviarlos en exportaciones compartidas. No se conectó ni modificó el teléfono físico del usuario para esta revisión.
