# SG Farma · API

Spring Boot 3.5, Java 21+, PostgreSQL 17 y migraciones Flyway. Repositorio Git independiente; Dockerfile propio.

## Ejecutar

Para trabajar ahora en este equipo, usar `INICIAR-LOCAL.cmd` desde la carpeta contenedora. El iniciador prepara PostgreSQL nativo persistente y las variables necesarias, sin Docker. Ver `farmacia-infra/LOCAL.md`.

Con Java 21+ y Maven 3.9: `mvn verify`. Las pruebas usan PostgreSQL embebido real temporal, con binarios para el sistema operativo; no requieren Docker. `mvn spring-boot:run` necesita `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `BOOTSTRAP_ADMIN_PASSWORD` y, solo para HTTP local, `COOKIE_SECURE=false`.

El usuario `admin` se crea únicamente si no existen usuarios, con contraseña BCrypt. No existe contraseña predeterminada. Para despliegue usar Dockerfile y las instrucciones del repositorio infraestructura.

## API v1

Todos los endpoints salvo health y CSRF requieren sesión. Para POST: obtener `/api/v1/auth/csrf`, conservar cookies y enviar token en `X-XSRF-TOKEN`. Login usa formulario URL encoded.

| Recurso | Métodos | Permisos |
|---|---|---|
| `/auth/csrf`, `/auth/login`, `/auth/logout`, `/auth/me` | GET / POST | Sesión personal |
| `/products`, `/presentations` | GET; POST products | Lectura autenticada; escritura ADMIN/SUPERVISOR |
| `/suppliers` | GET, POST | Escritura ADMIN/SUPERVISOR/INVENTORY |
| `/receipts` | GET, POST | ADMIN/SUPERVISOR/INVENTORY; contador solo consulta |
| `/inventory` | GET | Autenticado, sucursal de usuario |
| `/movements` | GET | ADMIN/SUPERVISOR/INVENTORY/ACCOUNTANT |
| `/sales/checkout` | POST | ADMIN/SUPERVISOR/CASHIER, `Idempotency-Key` obligatorio |
| `/sales`, `/sales/{id}` | GET | ADMIN/SUPERVISOR/CASHIER/ACCOUNTANT |
| `/cash`, `/cash/open`, `/cash/close` | GET / POST | Caja propia, roles de venta; contador consulta |
| `/audit` | GET | ADMIN/SUPERVISOR/ACCOUNTANT |
| `/users`, `/users/password` | GET / POST | ADMIN, misma sucursal |

## Decisiones

- Caja automática habilitada por defecto: una diaria por sucursal de 06:00 a 00:00, hora Guatemala, con recuperación al arrancar y auditoría. No requiere sesión ni navegador. `/cash/schedule` expone horario y modo. Apertura/cierre manual se rechazan en este modo. `CASH_OPENING_AMOUNT` configura el fondo inicial (Q0.00 por defecto); el arqueo físico queda pendiente al cerrar.

- Cada aplicación es un repositorio independiente; no hay Git en la carpeta contenedora.
- Primera versión para control interno, sin cálculo fiscal ni FEL.
- Cantidades enteras en unidad base; dinero decimal; recepción con costo real de lote.
- Checkout y cierre bloquean sucursal dentro de transacción. Es una estrategia conservadora para una farmacia pequeña; evita sobreventa y carreras entre cierre y cobro.
- Reintentos comparan actor y hash de solicitud; clave con distinto contenido devuelve conflicto.
- FEFO excluye vencimientos de hoy y anteriores en America/Guatemala; cuarentena/bloqueo no vendibles.
- El libro de movimientos, auditoría, ventas, asignaciones y recepciones rechaza UPDATE/DELETE mediante triggers PostgreSQL.
- Productos controlados no se pueden vender; fraccionamiento deshabilitado por defecto y habilitado expresamente por producto y presentación.
- Catálogo y proveedores compartidos por la instalación, lotes/caja/ventas/auditoría aislados por sucursal. No es SaaS multiempresa.
- Outbox transaccional sin publicador por ahora. Reiniciar la API cierra sesiones; no hay ventas offline.

## Pendiente antes de completar toda la especificación

Restablecimiento de contraseña, MFA, bloqueo por intentos y revocación central de sesiones; edición de catálogo/precios con historial; órdenes de compra parciales; conteos/mermas; devoluciones y reembolsos; pagos mixtos, retiros/ingresos; informes completos/exportaciones; publicador n8n/Telegram; reposición; cámara/PWA; OpenAPI formal; carga y restore en staging. No considerar esta entrega la implementación completa ni una liberación para operación real.
