# Backend en Coolify

Repositorio `CristopherGS/bknFarmacia`, rama `main`, estrategia Dockerfile, archivo `/Dockerfile`, directorio base `/`, puerto interno 8080. Los comandos de inicio, build e install se dejan vacíos: no definir un comando de arranque que sustituya el de la imagen.

| Campo | Valor |
|---|---|
| Build pack | **Dockerfile** (no Railpack) |
| Ports Exposes | `8080` |
| Dominio | `http://bknmed.sglabs.lat` (con `http://`: Cloudflare pone el TLS) |
| Custom network alias | `bkn-api` |
| Healthcheck | HTTP GET, puerto 8080, ruta `/actuator/health/readiness`, estado 200, periodo de arranque 60 s |

Variables de ejecución obligatorias (marcar solo **Runtime**, no Build):

```
DATABASE_URL=jdbc:postgresql://aitoljax7dqzmdbgydq7nlsz:5432/farmacia
DATABASE_USERNAME=<usuario>
DATABASE_PASSWORD=<contraseña-del-servidor>
BOOTSTRAP_ADMIN_PASSWORD=<contraseña-inicial-de-al-menos-14-caracteres>
PORT=8080
COOKIE_SECURE=true
```

Los valores entre `<...>` se configuran en Coolify, no en Git. No reutilizar datos ni credenciales del desarrollo local. Mantener `SERVER_ADDRESS` sin definir o `0.0.0.0`; no copiar la vinculación local `127.0.0.1` al contenedor.

Healthcheck: incluye estado de arranque y conexión de base de datos. El dominio es `http://bknmed.sglabs.lat`, pero el frontend usa el alias de red `bkn-api` en `API_UPSTREAM=http://bkn-api:8080` y así evita los desafíos interactivos de Cloudflare. PostgreSQL y las aplicaciones deben tener conectividad privada (red `coolify`).

## Infraestructura SG Labs

- Servidor: CT104 `sglabs-coolify`, IP interna `192.168.0.33` (panel de Coolify normalmente en `:8000`). Recursos: 4 cores, 6 GB de RAM y 2 GB de swap; con 2 cores / 4 GB las compilaciones de Angular y Maven eran muy lentas.
- Ruta de tráfico: Cloudflare Tunnel (CT102, `*.sglabs.lat` → `http://192.168.0.33:80`) → Traefik (`coolify-proxy`) → contenedor.
- Frontend: [frnFarmacia](https://github.com/CristopherGS/frnFarmacia/blob/main/COOLIFY.md), `http://med.sglabs.lat`, puerto 80.

Comprobación directa contra el origen (salta Cloudflare):

```sh
curl -i -H 'Host: bknmed.sglabs.lat' http://192.168.0.33/actuator/health
curl -i -H 'Host: med.sglabs.lat'    http://192.168.0.33/api/v1/auth/csrf   # JSON, no HTML
```

Si por HTTPS público aparece `403` con "Just a moment...", es una regla de Cloudflare (Security Events), no del servidor.

La raíz `/` no es la interfaz web y puede responder 401: para comprobar disponibilidad usar `/actuator/health`. El usuario `admin` solo se crea si no hay usuarios; cambiar la variable inicial después no reemplaza la contraseña de una cuenta existente.

El Dockerfile compila sin ejecutar las pruebas: ya corren en GitHub Actions (`mvn -B verify`) y repetirlas en cada despliegue era la mayor parte del tiempo de build. Para ejecutarlas también en Coolify, definir la variable de **build** `RUN_TESTS=true`; corren como usuario sin privilegios porque PostgreSQL temporal no se ejecuta como root. Si GitHub Actions no está habilitado en el repositorio, conviene activar `RUN_TESTS=true` hasta habilitarlo.

Las dependencias de Maven quedan en una caché de BuildKit (`--mount=type=cache`) que sobrevive entre despliegues aunque cambie `pom.xml`. No activar *Disable build cache* en Coolify, o cada build volverá a descargar unos 200 MB.

## Arranque y diagnóstico

Flyway 11.20.3 se verifica contra PostgreSQL 18.6 temporal real en las pruebas. Se conservan las tres migraciones existentes. El administrador se inicializa de forma transaccional después de Flyway y antes de aceptar tráfico. En una base nueva, la contraseña inicial exige mínimo 14 caracteres y máximo 72 bytes UTF-8; un error lo indica sin imprimir el secreto. Con usuarios existentes, no se exige ni se reemplaza esa contraseña.

Antes, un `ApplicationRunner` podía fallar después de iniciar Tomcat por faltar el secreto inicial. Ese caso se reprodujo localmente, pero los archivos recibidos son logs del frontend: **no contienen la excepción real del backend remoto**. Obtener sus logs de ejecución desde `Application run failed` y todos los `Caused by`; no asumir que el mensaje de Tomcat significa arranque completado.

`DATABASE_URL` y `DATABASE_USERNAME` ya no caen silenciosamente en localhost/usuario de desarrollo. Verificar las tres variables JDBC en ejecución y que el usuario pueda utilizar y crear objetos en el esquema. No imprimir ni publicar sus valores secretos. La raíz del backend puede devolver 401; comprobar salud en las rutas indicadas arriba.

Validación local: `mvn verify` aprobó 22 pruebas, incluidas migraciones en PostgreSQL 18.6, arranque HTTP, login, cookies tras proxy HTTPS, fallo controlado sin secreto y reinicio sin restablecer administrador. Se comprobó además Nginx → backend con autenticación, consultas y cierre de sesión. El JDK local disponible fue 25 con destino 21; CI y Dockerfile usan Java 21.

Validación de imágenes (21 de octubre de 2026, Docker 29 con BuildKit, 4 núcleos): ambas imágenes se construyeron y se probaron juntas con PostgreSQL 18 en una red privada, backend con alias `bkn-api` y frontend con `API_UPSTREAM=http://bkn-api:8080`. Readiness `UP` en 8 s, `/healthz`, CSRF en JSON, login y alta de producto a través de Nginx, sin reinicios. Tiempos de build del backend, antes y después de dejar las pruebas fuera de la imagen: en frío de 80 s a 30 s; con un cambio de código de 27 s a 6 s. En CT104 la diferencia debería ser mayor, porque las pruebas dependen de la CPU.

Desde el frontend, ambos contenedores en la red `coolify`, comprobar `wget -qO- http://<alias-real-backend>:8080/actuator/health`. En el propio backend: `wget -qO- http://127.0.0.1:8080/actuator/health/readiness`. Revisar `docker logs --tail 150 <backend>` y `docker inspect --format '{{.State.Status}} reinicios={{.RestartCount}}' <backend>` en CT 104.

Informe conjunto, configuración de frontend, consultas SQL y comandos de Traefik/Tunnel: [DIAGNOSTICO_DESPLIEGUE.md](https://github.com/CristopherGS/frnFarmacia/blob/main/DIAGNOSTICO_DESPLIEGUE.md). En esta revisión, el origen `192.168.0.33` devolvió 502 para ambos dominios y Cloudflare devolvió 403 con desafío. Producción permanece pendiente de comprobación después del despliegue; no se modificó su infraestructura.

En una repetición posterior, el origen cambió a 403 de Angular por host no permitido en frontend y 404 en backend. Se añadió `med.sglabs.lat` a los hosts permitidos de desarrollo en el frontend; el backend necesita revisar también su enrutamiento en Coolify. GitHub Actions no devolvió ejecuciones registradas al consultar ambos repositorios después de subir los cambios: la validación de la imagen Linux sigue pendiente.
