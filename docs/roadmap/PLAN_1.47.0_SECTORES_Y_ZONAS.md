# Plan 1.47.0 — Sectores configurables y zonas de trabajo

Estado: **entregado en 1.47.0 (2026-11-14); la comprobación física sigue pendiente**. Decisiones del conductor del 2026-11-14.

## Qué existe hoy

- **Sectores de cuidado** (1.34.0–1.37.0):
  - Se guardan como lista JSON en DataStore (`care_sectors`), con un máximo de 50.
  - Cada sector tiene nombre, dónde buscarlo (recogida, destino o ambos) y nivel (aviso, evitar o cerrar).
  - `SectorMatcher` busca el nombre como palabras seguidas dentro de una parte de la dirección. Las partes se separan por comas y la calle se omite.
  - Hay estadísticas por sector (`SectorStats`), una tarjeta semanal en Inteligencia y una columna en el CSV.
- **Cierre automático (la X):** sigue en simulación (`CloseTarget.VALIDATED = false`).
- **GPS** (1.45.0):
  - Cada viaje tomado guarda su recorrido en `trip_route_points`, con la fase de cada punto: yendo a recoger, con pasajero o sin distinguir.
  - Las ofertas que no se toman solo tienen el texto de la dirección y ninguna coordenada.

## Problemas que resuelve

1. Un sector solo coincide si se escribe exactamente como lo pone Uber. No hay alias («Alcarrizos» o «Los Alcarrizos»), excepciones ni horario: un sector que solo preocupa de noche avisa todo el día.
2. No hay forma de saber por qué coincidió una regla, ni de probarla antes de usarla.
3. No se sabe cuánto rinde cada zona: dinero por hora, esperas, recogida y lo que cuesta volver o moverse sin pasajero.

## Propuesta

### A. Reglas de sector más completas (sin migración de base de datos)

- La lista sigue en `care_sectors`, con un formato ampliado que también lee el formato anterior. Los campos nuevos son opcionales:
  - **Alias:** otros nombres que también cuentan («Alcarrizos», «Los Alcarrizos»).
  - **Excepciones:** partes de dirección que anulan la regla. Por ejemplo, «Santo Domingo Oeste» excepto «Herrera».
  - **Horario:** días y franja, que puede cruzar la medianoche (por ejemplo, de 20:00 a 5:00).
  - **Modalidad:** todas o solo una («Moto», «Moto Flash»…), tal como aparece en la oferta.
- Un sector desactivado se conserva en la lista sin avisar.
- **Probador de reglas** en Ajustes → Sectores: pegas o eliges una dirección real reciente, y la app muestra las partes, la que coincidió, con qué nombre o alias y si la anuló una excepción o el horario.
- Los respaldos y las restauraciones aceptan los dos formatos. Una lista inválida se rechaza entera, como hasta ahora.

### B. Zonas medidas con GPS (sin migración)

- La zona es una **cuadrícula fija** de unos 1,5 km. La celda se calcula con la coordenada, así que no hay que dibujar nada y no cambia con el tiempo.
  - Opcionalmente se pueden poner **nombres propios** a algunas celdas o grupos («Zona Megacentro»).
- Para cada viaje con GPS se calcula la zona de recogida (el primer punto «con pasajero», o el último de «yendo a recoger») y la de destino (el último punto).
  - **Cambio al implementar:** no se crea la tabla `trip_zones`. Las zonas se recalculan desde `trip_route_points` cada vez, así que Room sigue en 20 y no hace falta migración.
- Por zona de recogida, se calcula:
  - viajes;
  - dinero confirmado;
  - RD$ por hora de viaje más recogida;
  - minutos de recogida y de espera;
  - km sin pasajero hasta la siguiente oferta aceptada.
- Por zona de destino: cuánto tardó la siguiente oferta y cuántos km sin pasajero hubo hasta ella. Así se ve si una zona deja varado.
- **Muestra mínima:** una zona con menos de 5 viajes en el período muestra «pocos datos» y no se clasifica. Una zona sin viajes es «no observada», nunca «mala».
- El sector por texto y la zona por GPS se muestran por separado. Un viaje puede tener «Sector: Los Alcarrizos (texto de Uber)» y «Zona GPS: celda 18.48, −70.01». No se mezclan.

### C. Pantalla «Zonas» (en Inteligencia)

- Mapa de tus viajes, con los mismos mosaicos y caché de la 1.45.0, coloreado por la métrica elegida. Cada celda muestra su número de viajes y la fecha del último.
- Lista ordenada con el período (7, 30 o 90 días), la muestra y la advertencia de que es tu historial, no una medición de seguridad ni una garantía.
- No se muestran zonas de ofertas no tomadas, porque no tienen coordenadas.

### D. Cierre automático

No cambia: sigue en simulación hasta que haya una captura real que valide la X. El control inmediato de cancelación se mantiene.

## Privacidad

- Las zonas se calculan en el teléfono y no salen de él, salvo en la exportación JSON completa y en los respaldos, igual que los recorridos.
- Una **zona privada** opcional (por ejemplo, la casa) no se muestra en el mapa ni en la lista.

## Pruebas

- **Unitarias:**
  - alias, excepciones, sectores con el mismo nombre en municipios distintos, calles con nombre de sector, destinos incompletos y horarios que cruzan la medianoche;
  - modalidad, lectura del formato anterior y rechazo de listas inválidas;
  - celda en el borde de una zona, recogida sin fase, muestra mínima y «no observada».
- **Instrumentadas, solo en el emulador:** respaldo y restauración con el formato nuevo (sin migración).
- **En el teléfono:** una semana de uso real; revisar en el probador 10 direcciones reales y que el mapa coincida con los lugares donde se trabajó.

## Fuera de alcance

- Convertir el texto de la dirección en coordenadas, ya que requeriría un servicio externo.
- Predecir demanda o recomendar adónde ir. Eso corresponde a 1.48.0 (horarios y mercado), y siempre basado solo en tu historial.
- Activar el cierre real de ofertas.

## Decisiones del conductor

| # | Pregunta | Opciones | Decisión |
|---|---|---|---|
| 1 | ¿Cómo se definen las zonas? | a) Cuadrícula automática · b) Áreas que dibujas en el mapa · c) Cuadrícula + nombres opcionales | **c** |
| 2 | ¿Qué agregar a las reglas de sector? | Alias · Excepciones · Horario · Modalidad (una o varias) | **Todas** |
| 3 | ¿Cuál es la cifra principal de una zona? | a) RD$ por hora · b) RD$ por km · c) Tiempo hasta la siguiente oferta | **a** |
| 4 | ¿Zona privada oculta (casa)? | Sí / No | **Sí** |
