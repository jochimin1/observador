# Entregas de Observador

- Cada versión implementada debe terminar en un commit que incluya su `versionName`, `versionCode`, changelog y notas de validación. Añadir su ficha a `docs/releases/changelogs/X.Y.Z.md` y al índice de esa carpeta. No etiquetar trabajo sin commit ni versiones que sean solamente propuestas.
- Conservar una etiqueta `vX.Y.Z` y una rama de checkout `release/X.Y.Z` apuntando al mismo commit. Las referencias de entregas publicadas no se mueven ni se reutilizan para desarrollo posterior.
- Para la siguiente versión, crear una rama de trabajo desde la entrega anterior. Antes de cerrar la entrega, incorporar su fila a `docs/releases/versiones.json` y ejecutar `python scripts/version_refs.py --check`. Publicar referencias nuevas sin `--force` cuando la publicación forme parte de la tarea.
- El procedimiento y las excepciones históricas están en `docs/releases/README.md`.
