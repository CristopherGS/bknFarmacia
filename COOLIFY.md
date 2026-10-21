# Backend en Coolify

Repositorio `CristopherGS/bknFarmacia`, rama `deploy`, estrategia Dockerfile, archivo `/Dockerfile`, directorio base `/`, puerto interno 8080. No definir un comando de arranque que sustituya el de la imagen.

Variables de ejecución obligatorias:

```
DATABASE_URL=jdbc:postgresql://<host-privado-postgresql>:5432/<base>
DATABASE_USERNAME=<usuario>
DATABASE_PASSWORD=<contraseña-del-servidor>
BOOTSTRAP_ADMIN_PASSWORD=<contraseña-inicial-de-al-menos-14-caracteres>
COOKIE_SECURE=true
```

Los valores entre `<...>` se configuran en Coolify, no en Git. No reutilizar datos ni credenciales del desarrollo local. Mantener `SERVER_ADDRESS` sin definir o `0.0.0.0`, y `PORT=8080`; no copiar la vinculación local `127.0.0.1` al contenedor.

Healthcheck: HTTP al puerto 8080, ruta `/actuator/health`, estado 200. El dominio previsto es `https://bknmed.sglabs.lat`, pero frontend puede usar directamente el nombre interno de red en `API_UPSTREAM` y evitar desafíos interactivos de Cloudflare. PostgreSQL y las aplicaciones deben tener conectividad privada.

La raíz `/` no es la interfaz web y puede responder 401: para comprobar disponibilidad usar `/actuator/health`. El usuario `admin` solo se crea si no hay usuarios; cambiar la variable inicial después no reemplaza la contraseña de una cuenta existente.

El Dockerfile ejecuta la compilación y las pruebas como usuario sin privilegios: PostgreSQL temporal de las pruebas no permite ejecutarse como root. No se ha ejecutado una construcción Docker local ni desplegado este cambio en el servidor desde esta conversación.
