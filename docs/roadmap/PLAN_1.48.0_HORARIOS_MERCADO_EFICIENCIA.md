# Plan 1.48.0 — Horarios, mercado y eficiencia

Estado: **implementado en `feature/1.48.0-horarios` (desde `release/1.47.0`), pendiente de validación**. Decisiones del conductor del 2026-11-14.

## Qué existe hoy

- **«Mejores horas para salir»** (Inicio, `BestHours`): las ofertas vistas en 12 franjas de 2 horas, con mediana de RD$/h y RD$/km ofertados, puntaje, calidad y «ofertas por hora en los días con actividad». Solo mide ofertas vistas: no sabe cuáles se tomaron ni cuánto se cobró.
- **Tiempo conectado** (`OnlineMetrics`):
  - Por sesión hay tiempo confirmado y tiempo posible.
  - Los huecos sin ver Uber se separan del tiempo confirmado.
  - Se calculan el tiempo ocupado (de aceptación a fin), el tiempo esperando, la utilización, las ofertas por hora conectada, el tiempo hasta la primera oferta, la espera entre viajes y el dinero confirmado por hora conectada.
- **Esperas** (`TripWaits`): la espera del pasajero por franja.
- **Distancias** (`DistanceSegment`): km medidos con pasajero, de recogida y el resto (sin pasajero), y los huecos estimados.
- **Viajes y dinero** (Centro de Viajes, Ganancias): hora de aceptación, llegada, inicio y final de cada viaje, y el dinero confirmado.

Hoy cada cifra está en una pantalla distinta y con denominadores distintos. Por ejemplo, «ofertas por hora» en Mejores horas cuenta las horas de los días con actividad, no las horas que estuviste conectado.

## Problemas que resuelve

1. No se puede saber si una franja es mala o si no había nadie mirando. Una franja sin ofertas puede ser falta de mercado, que estabas desconectado o que la lectura falló.
2. No se ve en qué se va el tiempo de una jornada: buscando, yendo a recoger, esperando, con pasajero o en pausa.
3. El precio ofertado, lo que tomaste y lo que realmente cobraste se mezclan.

## Propuesta

### A. Tres vistas por franja, cada una con su denominador

| Vista | Qué cuenta | Denominador |
|---|---|---|
| **Mercado** | todas las ofertas vistas: cantidad, RD$/h y RD$/km ofertados, recogida | horas **conectado y leyendo** en la franja |
| **Tomadas** | ofertas aceptadas y su condición ofertada | ofertas vistas en la franja (tasa de aceptación) |
| **Resultado** | dinero **confirmado** de los viajes terminados | horas conectado en la franja (RD$ por hora conectada) |

- El precio ofertado nunca se llama «ganancia». El resultado usa solo el dinero confirmado, y se indica cuántos viajes faltan por confirmar.
- Cada celda indica sus horas observadas y su muestra. Por debajo del mínimo dice «pocos datos».

### B. Estado del tiempo en cada franja

Para cada hora del período se separa:

- **Conectado y leyendo:** Uber visto y lectura activa.
- **Conectado sin lectura:** los huecos de la sesión en que Uber no se vio (lectura perdida o pantalla apagada).
- **Desconectado:** fuera de toda sesión.

Así, «0 ofertas» solo aparece en horas conectado y leyendo. En las demás se dice «no observado».

### C. En qué se fue el tiempo y los km

- **Tiempo conectado y leyendo:**
  - buscando, sin viaje;
  - yendo a recoger, de aceptación a llegada;
  - esperando al pasajero, de llegada a inicio;
  - con pasajero, de inicio a final;
  - ~~en pausa~~: por la decisión 4, las pausas no se separan y cuentan como «buscando».
- Las categorías **no se solapan y su suma cuadra exactamente** con el tiempo conectado y leyendo. Las pruebas lo comprueban con jornadas reales anonimizadas y sintéticas.
- **Km:** con pasajero, de recogida y sin pasajero, desde los tramos medidos (`DistanceSegment`). Los huecos estimados se muestran aparte y no se reparten.

### D. Comparaciones con muestra mínima

- Por día de la semana, por turno (decisión 2) y por modalidad.
- Solo se comparan períodos equivalentes: las mismas semanas y los mismos días de la semana. Si un lado no tiene muestra suficiente, no se compara.

### E. Sugerencias ligadas a una pérdida observada

- Ejemplos:
  - «Los martes de 14:00 a 16:00 pasaste el 48 % del tiempo buscando (tu promedio es 30 %).»
  - «Después de dejar en Zona X tardaste 22 min en la siguiente oferta.»
- Cada sugerencia indica la muestra y el período, y aclara que es lo observado, no una promesa.
- Como máximo 3, ordenadas por tiempo o dinero perdido. Nunca cambian ajustes solas.

### Dónde se ve

Una pantalla nueva «Horarios y eficiencia» dentro de Análisis, con pestañas Mercado, Tomadas, Resultado y Tiempo (decisión 1). «Mejores horas» en Inicio se queda como resumen y enlaza a la pantalla nueva.

## Datos

No hace falta migración (Room sigue en 20). Todo sale de:

- `online_sessions`, con sus huecos;
- `detected_offers`;
- `trips`, con sus horas;
- `trip_financial_summaries`;
- `distance_segments` y `trip_distances`.

Las reglas son puras (dominio, sin Android) y se calculan al abrir la pantalla.

## Pruebas

- **Unitarias:**
  - las categorías de tiempo suman exactamente el tiempo conectado y leyendo, con un viaje que cruza la franja y la medianoche, sesiones con huecos, un viaje recuperado sin aceptación y una pausa;
  - una franja sin conexión sale «no observada», no «0 ofertas»;
  - el denominador de cada vista;
  - la muestra mínima y la comparación de períodos equivalentes;
  - las sugerencias: solo con muestra suficiente y nunca más de 3;
  - el precio ofertado nunca aparece como resultado.
- **En el teléfono:** comparar una jornada real con el historial de Uber (horas conectado y viajes) y revisar que la suma del tiempo cuadre.

## Fuera de alcance

- Predecir la demanda o recomendar a qué hora salir como si fuera seguro.
- Estimar las ofertas que no se vieron.

## Decisiones del conductor

| # | Pregunta | Opciones | Decisión |
|---|---|---|---|
| 1 | ¿Dónde va? | a) Pantalla nueva en Análisis (Mejores horas queda como resumen) · b) Reemplazar Mejores horas en Inicio | **a** |
| 2 | ¿Qué turnos? | a) Fijos: madrugada 0–6, mañana 6–12, tarde 12–18, noche 18–24 · b) Los defines tú | **a** |
| 3 | ¿Tamaño de franja? | a) 1 hora · b) 2 horas (como hoy) | **b** |
| 4 | ¿Cuándo es «pausa»? | a) 10 min conectado sin moverte · b) 15 min · c) No separar pausas (todo cuenta como buscando) | **c** |
