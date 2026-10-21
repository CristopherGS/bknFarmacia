# Backend en Coolify

Repositorio `CristopherGS/bknFarmacia`, rama `main`, estrategia Dockerfile, archivo `/Dockerfile`, directorio base `/`, puerto interno 8080. No definir un comando de arranque que sustituya el de la imagen.

Variables de ejecución obligatorias:

```
DATABASE_URL=jdbc:postgresql://aitoljax7dqzmdbgydq7nlsz:5432/farmacia
DATABASE_USERNAME=<usuario>
DATABASE_PASSWORD=<contraseña-del-servidor>
BOOTSTRAP_ADMIN_PASSWORD=<contraseña-inicial-de-al-menos-14-caracteres>
COOKIE_SECURE=true
```

Los valores entre `<...>` se configuran en Coolify, no en Git. No reutilizar datos ni credenciales del desarrollo local. Mantener `SERVER_ADDRESS` sin definir o `0.0.0.0`, y `PORT=8080`; no copiar la vinculación local `127.0.0.1` al contenedor.

Healthcheck: HTTP al puerto 8080, ruta `/actuator/health/readiness`, estado 200. Incluye estado de arranque y conexión de base de datos. El dominio previsto es `https://bknmed.sglabs.lat`, pero frontend puede usar directamente el nombre interno de red en `API_UPSTREAM` y evitar desafíos interactivos de Cloudflare. PostgreSQL y las aplicaciones deben tener conectividad privada.

La raíz `/` no es la interfaz web y puede responder 401: para comprobar disponibilidad usar `/actuator/health`. El usuario `admin` solo se crea si no hay usuarios; cambiar la variable inicial después no reemplaza la contraseña de una cuenta existente.

El Dockerfile ejecuta la compilación y las pruebas como usuario sin privilegios: PostgreSQL temporal de las pruebas no permite ejecutarse como root. No se ha ejecutado una construcción Docker local ni desplegado este cambio en el servidor desde esta conversación.

## Arranque y diagnóstico

Flyway 11.20.3 se verifica contra PostgreSQL 18.6 temporal real en las pruebas. Se conservan las tres migraciones existentes. El administrador se inicializa de forma transaccional después de Flyway y antes de aceptar tráfico. En una base nueva, la contraseña inicial exige mínimo 14 caracteres y máximo 72 bytes UTF-8; un error lo indica sin imprimir el secreto. Con usuarios existentes, no se exige ni se reemplaza esa contraseña.

Antes, un `ApplicationRunner` podía fallar después de iniciar Tomcat por faltar el secreto inicial. Ese caso se reprodujo localmente, pero los archivos recibidos son logs del frontend: **no contienen la excepción real del backend remoto**. Obtener sus logs de ejecución desde `Application run failed` y todos los `Caused by`; no asumir que el mensaje de Tomcat significa arranque completado.

`DATABASE_URL` y `DATABASE_USERNAME` ya no caen silenciosamente en localhost/usuario de desarrollo. Verificar las tres variables JDBC en ejecución y que el usuario pueda utilizar y crear objetos en el esquema. No imprimir ni publicar sus valores secretos. La raíz del backend puede devolver 401; comprobar salud en las rutas indicadas arriba.

Validación local: `mvn verify` aprobó 22 pruebas, incluidas migraciones en PostgreSQL 18.6, arranque HTTP, login, cookies tras proxy HTTPS, fallo controlado sin secreto y reinicio sin restablecer administrador. Se comprobó además Nginx → backend con autenticación, consultas y cierre de sesión. El JDK local disponible fue 25 con destino 21; CI y Dockerfile usan Java 21. La imagen Docker queda por validar en CI/servidor, sin ejecutarse localmente.

Desde el frontend, ambos contenedores en la red `coolify`, comprobar `wget -qO- http://<alias-real-backend>:8080/actuator/health`. En el propio backend: `wget -qO- http://127.0.0.1:8080/actuator/health/readiness`. Revisar `docker logs --tail 150 <backend>` y `docker inspect --format '{{.State.Status}} reinicios={{.RestartCount}}' <backend>` en CT 104.

Informe conjunto, configuración de frontend, consultas SQL y comandos de Traefik/Tunnel: [DIAGNOSTICO_DESPLIEGUE.md](https://github.com/CristopherGS/frnFarmacia/blob/main/DIAGNOSTICO_DESPLIEGUE.md). En esta revisión, el origen `192.168.0.33` devolvió 502 para ambos dominios y Cloudflare devolvió 403 con desafío. Producción permanece pendiente de comprobación después del despliegue; no se modificó su infraestructura.

En una repetición posterior, el origen cambió a 403 de Angular por host no permitido en frontend y 404 en backend. Se añadió `med.sglabs.lat` a los hosts permitidos de desarrollo en el frontend; el backend necesita revisar también su enrutamiento en Coolify. GitHub Actions no devolvió ejecuciones registradas al consultar ambos repositorios después de subir los cambios: la validación de la imagen Linux sigue pendiente.
