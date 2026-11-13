# Versiones y checkout de Observador

Cada entrega implementada tiene una etiqueta `vX.Y.Z` y una rama `release/X.Y.Z` en el mismo commit. La etiqueta identifica la entrega; la rama permite volver a su código con `git switch`. Son puntos conservados de publicación: las nuevas funciones se desarrollan en otra rama.

El catálogo [versiones.json](versiones.json) cubre las 48 versiones del changelog, desde 1.0.0 / 1 hasta 1.37.2 / 48. No se crean referencias para las versiones futuras del roadmap.

## Consultar y cambiar de versión

```powershell
git fetch origin --tags
python scripts/version_refs.py --check --remote
git switch release/1.37.1
git switch release/1.37.2
```

Si la rama todavía no está creada en otro clon:

```powershell
git switch --track origin/release/1.37.2
```

Para revisar directamente una etiqueta sin una rama activa:

```powershell
git checkout --detach v1.37.1
git switch release/1.37.2
```

Git protege los cambios locales que se solapen al cambiar de versión. Guárdalos en un commit o un stash antes de cambiar. Estos comandos cambian el código del proyecto; no instalan ni degradan la base de datos del teléfono.

El verificador compara versión y código de la app, cobertura del changelog, etiquetas, ramas y, con `--remote`, sus commits en `origin`. No cambia archivos ni hace checkout. El script pertenece a 1.37.2: ejecútalo desde esa versión o una posterior.

## Registro histórico

- Las 37 etiquetas preexistentes conservan sus objetos y commits originales, incluidas las etiquetas ligeras. Las 11 etiquetas faltantes se crean anotadas.
- Para cada versión histórica sin etiqueta se toma el último commit de su tramo en la historia de la app. En 1.30.0 se conserva `44a0dcc`, que añade la corrección de compilación al cambio funcional `30f7e6c`.
- La primera app declaró `versionName = "1.0"`. Se conserva ese código original y su etiqueta histórica `v1.0.0`; el verificador reconoce esa equivalencia.
- La 1.37.1 se registra desde la instantánea conservada `ba7ab5314f0ba2e72fad61737ff0c35faa4478c4`. Se comprueba igualdad del árbol `app` y del changelog con esa instantánea. Su commit de publicación tiene como padre 1.37.0; no se reconstruye código a partir del APK.
- La 1.37.2 se registra como hija de 1.37.1 con el código verificado del workspace, sus notas y las evidencias visuales. El SHA-256 del APK de prueba está en [las notas de 1.37.2](1.37.2.md).
- Los commits de publicación de 1.37.1 y 1.37.2 se crean al ordenar la trazabilidad. Las fechas del changelog describen las entregas; no se falsifican fechas de autor ni de commit.
- Las ramas temáticas, `main` y las etiquetas existentes se conservan. Crear referencias históricas no certifica que hoy compilen todas las versiones con el SDK actual.

## Próximas entregas

Desde la última entrega, crear una rama de trabajo, implementar y verificar la nueva versión. Actualizar el catálogo, changelog y notas, y confirmar todo en un commit. Por ejemplo, una vez lista la 1.38.0:

```powershell
git tag -a v1.38.0 -m "Observador 1.38.0"
git branch release/1.38.0 v1.38.0
python scripts/version_refs.py --check
git push --atomic origin refs/tags/v1.38.0 refs/heads/release/1.38.0
python scripts/version_refs.py --check --remote
git switch release/1.38.0
```

No ejecutar ese ejemplo antes de implementar y confirmar la versión. No mover una referencia publicada para introducir correcciones: la corrección recibe otra versión.
