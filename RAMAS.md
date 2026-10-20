# Ramas de trabajo

- `main`: punto de partida estable y referencia del proyecto.
- `develop`: integración del desarrollo diario. La copia local queda en esta rama.
- `deploy`: versión destinada a despliegue. Configurar el futuro servicio de despliegue para seguir solo esta rama.

Para una modificación, crear una rama `feature/nombre` desde `develop`, completar los cambios y ejecutar `mvn verify`. Integrarla a `develop` mediante pull request. Cuando esté validada, abrir otro pull request de `develop` hacia `deploy`. No desarrollar directamente en `deploy` ni usar force push.

Antes de trabajar, actualizar con `git pull --ff-only`. Las ramas separan desarrollo y publicación, pero no eliminan por sí mismas los conflictos: deben resolverse en la rama de trabajo antes de integrar. Los workflows ejecutan pruebas y compilación; no publican aplicaciones ni alteran servidores.

Esta subida no configura reglas de protección de GitHub. Para impedir cambios directos a `deploy`, configurar en GitHub un ruleset que exija pull request y el check `verify` del workflow `API checks`.
