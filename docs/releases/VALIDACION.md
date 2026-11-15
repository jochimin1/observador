# Validar una entrega de Observador

## Comprobación local reproducible

Con Python 3.10 o posterior, JDK 21 y el SDK Android del proyecto instalados:

```powershell
Set-Location D:\ubercalculador
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
python scripts/validate_release.py --previous-apk build/releases/1.51.0/Observador-1.51.0-62-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'No entregar: revisar los logs' }
```

El comando comprueba fuente, notas, catálogo, esquemas históricos, pruebas Python/JVM, compilación debug/release, compilación de pruebas, lint debug/release, identidad y firma del APK debug. Produce logs e informe en `build/release-validation/local`. La compilación release sin claves produce un APK **sin firmar**, que no se presenta como instalable. El informe distingue la instrumentación no ejecutada; no la da por aprobada.

Para incluir pruebas Android, abre un emulador de QA y añade `--serial emulator-N`, usando su número real de `adb devices`. El script rechaza seriales físicos y comprueba `ro.kernel.qemu` antes de instalar. No selecciona el primer dispositivo disponible. Solo en el emulador se actualiza la app, se escribe la evidencia de QA y se ejecuta instrumentación. No usar `connectedDebugAndroidTest` con el teléfono del conductor conectado.

La prueba de actualización comprueba un registro con nombre, dirección, ruta y búsqueda y un tema real de DataStore, separados por una instalación `-r`. No demuestra por sí sola que todas las filas de todos los teléfonos estén conservadas; se complementa con copias/conteos antes y después, migraciones y respaldo/exportación. La restauración se comprueba preparando la copia, no reiniciando el teléfono del conductor.

## Compilación desde fuente limpia

El workflow `.github/workflows/android.yml` parte del checkout de Git, sin claves ni `local.properties`. Instala SDK/JDK, ejecuta los controles, conserva los informes y compila la versión anterior desde su etiqueta con `prepare_previous.py`. Ambos APK debug usan la misma clave efímera del runner. En trabajos separados prueba actualización y regresiones en API 30 y API 36. Esa clave de CI **no** se ofrece como actualización de la instalación local del conductor.

Los trabajos de etiquetas verifican además todas las referencias de entrega. Los trabajos de ramas permiten validar una entrega en desarrollo antes de crear sus referencias. Un workflow escrito no equivale a una ejecución remota aprobada. No se publica automáticamente en tiendas, releases de GitHub ni teléfonos.

## Firma externa de distribución

Se configura con cuatro variables de entorno: `OBSERVADOR_KEYSTORE` (ruta absoluta a un almacén externo al repositorio), `OBSERVADOR_STORE_PASSWORD`, `OBSERVADOR_KEY_ALIAS` y `OBSERVADOR_KEY_PASSWORD`. Configurarlas en el entorno local o en el gestor de secretos del sistema de compilación; no escribir credenciales en comandos compartidos, archivos del repositorio ni notas. Una configuración parcial o un almacén dentro del repositorio se rechazan.

Una vez configurado el entorno, sin imprimirlo:

```powershell
.\gradlew.bat assembleRelease --max-workers=2 --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación firmada' }
python scripts/check_release.py --apk app/build/outputs/apk/release/app-release.apk --require-release --previous-apk build/releases/1.51.0/Observador-1.51.0-62-debug.apk --output build/release-validation/signed-release.json
if ($LASTEXITCODE -ne 0) { throw 'No actualizar: revisar identidad y certificado' }
```

El verificador exige firma válida, paquete, nombre/código actuales, código superior y los mismos certificados que el APK anterior. Un certificado diferente falla: no propone desinstalar ni borrar datos. La comprobación es conservadora y no interpreta linajes de rotación de claves. Para una nueva clave de producción hace falta planificar y probar la transición; esta entrega no inventa credenciales ni cambia la identidad existente.

## Evaluar la optimización

`assembleOptimized` es una variante experimental con R8 y reducción de recursos. No sustituye debug ni release. Se puede firmar con la configuración externa. Conservar `app/build/outputs/mapping/optimized/` junto con su APK e informe antes de distribuir una variante optimizada. Comparar tamaño con la misma firma y comprobar las rutas de ejecución (accesibilidad, servicios, alertas, Room, mapas, exportación y respaldo). Compilar o abrir Inicio no certifica toda esa regresión. Mientras falte esa evidencia, la entrega normal conserva minificación desactivada.

## Matriz y comprobación de una jornada

Registrar por separado lo configurado, lo ejecutado y lo pendiente:

| Entorno | Cobertura prevista | Evidencia requerida |
|---|---|---|
| Emulador API 30 | Android mínimo, migraciones, actualización, respaldo y cálculos | results.json e instrumentación |
| Emulador API 36 | Android objetivo, mismas regresiones | results.json e instrumentación |
| Redmi Note 8 Pro API 30 | Actualización y trabajo real | Copias antes/después y revisión del conductor |

Lista manual: terminar viaje y copiar base antes de actualizar; comprobar versión/novedades/tema; revisar viajes, costos y cierres en los mismos periodos; revisar tarjeta y lectura de Uber; comprobar permisos y servicio tras reiniciar; comprobar rutas/mapas y búsquedas; exportar datos completos; preparar y verificar un respaldo en QA; registrar arranques y consumo durante una jornada. Ninguna casilla se marca automáticamente por existir en esta lista.

## Cierre

Actualizar ficha, índice, changelog y catálogo; revisar resultados; confirmar código/documentación y conservar `vX.Y.Z` y `release/X.Y.Z` en el mismo commit. Ejecutar `python scripts/version_refs.py --check`. Si la tarea incluye publicar, enviar las referencias nuevas sin force y comprobar el remoto; de lo contrario declarar la entrega local.

Referencias: [firma Android](https://developer.android.com/studio/publish/app-signing), [apksigner](https://developer.android.com/tools/apksigner), [optimización R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization), [Android Emulator Runner](https://github.com/ReactiveCircus/android-emulator-runner).
