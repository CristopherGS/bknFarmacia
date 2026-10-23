# Integración con n8n

El sistema habla con n8n en las dos direcciones:

- **Sistema → n8n (eventos):** cuando pasa algo (venta, compra, stock bajo…) el sistema envía un aviso firmado a un webhook de n8n.
- **n8n → sistema (consultas y acciones):** n8n usa una llave de API con permisos mínimos para leer datos o hacer cambios.

Todo se configura en **Configuración** (solo administradores): URL del webhook, secreto y llaves.

## 1. Recibir eventos en n8n

1. En n8n crea un flujo que empiece con un nodo **Webhook** (método `POST`). En *Options* activa **Raw Body**: la firma se calcula sobre los bytes exactos que envía el sistema, y volver a serializar el JSON puede cambiarlos (por ejemplo `10.00` pasa a `10`).
2. Copia la **Production URL** del nodo. Si n8n y el sistema comparten la red de Coolify, usa la dirección interna (`http://<contenedor-de-n8n>:5678/webhook/...`); así el tráfico no sale a internet.
3. En **Configuración → Conexión con n8n** pega la URL, guarda, pulsa **Generar secreto** (se muestra una sola vez; guárdalo en n8n como variable o credencial) y luego **Enviar prueba**. El evento de prueba es `test.ping`.

Mientras la URL o el secreto estén vacíos el sistema no guarda eventos, para no acumular una cola que nadie lee.

### Formato del aviso

```http
POST /webhook/...
Content-Type: application/json
X-SG-Event: sale.completed
X-SG-Delivery: 1042
X-SG-Signature: t=1792800000,v1=9f2c...e1

{"id":1042,"type":"sale.completed","occurredAt":"2026-10-23T14:05:11Z","business":"Farmacia Sol","data":{...}}
```

`X-SG-Delivery` es el id del evento: úsalo para no procesar dos veces el mismo si n8n responde tarde y el sistema reintenta.

### Verificar la firma (nodo Code de n8n, justo después del Webhook)

```js
const crypto = require('crypto');
const secret = $env.SG_WEBHOOK_SECRET;               // el secreto whsec_...
const item = $input.first();
const raw = Buffer.from(item.binary.data.data, 'base64').toString('utf8');   // cuerpo exacto
const sig = Object.fromEntries(item.json.headers['x-sg-signature'].split(',').map(p => p.split('=')));

const age = Math.abs(Date.now() / 1000 - Number(sig.t));
const expected = crypto.createHmac('sha256', secret).update(`${sig.t}.${raw}`).digest('hex');
const valid = age < 300 && expected.length === sig.v1.length &&
  crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(sig.v1));

if (!valid) throw new Error('Firma inválida o aviso demasiado antiguo');
return [{ json: JSON.parse(raw) }];
```

La firma es `HMAC-SHA256(secreto, "<t>.<cuerpo>")` en hexadecimal. Rechazar avisos de más de 5 minutos evita que alguien reenvíe uno viejo.

Si tu versión de n8n entrega el cuerpo crudo con otro nombre, revisa la pestaña *Binary* del nodo Webhook; lo importante es firmar los bytes recibidos y no el JSON ya interpretado.

### Reintentos

Si n8n no responde con un código 2xx en 8 segundos, el sistema reintenta con espera creciente (10 s, 20 s, 40 s… hasta 1 h) y se rinde tras 8 intentos. Los eventos entregados quedan como `SENT` y los abandonados como `FAILED`; la pantalla de Configuración muestra cuántos hay de cada uno.

## 2. Eventos disponibles

| Evento | Cuándo | Datos principales |
|---|---|---|
| `sale.completed` | Se confirma una venta | `saleId`, `total`, `method`, `tendered`, `change`, `actor`, `lines[]` (producto, presentación, cantidad, precio) |
| `receipt.created` | Se registra una compra | `receiptId`, `document`, `supplierId`, `actor`, `lines[]` (producto, lote, vence, unidades) |
| `stock.low` | Una venta deja un producto en su mínimo o por debajo (una sola vez por cruce) | `productId`, `product`, `sku`, `stock`, `minimum` |
| `lot.expiring` | Cada día a las 07:00 (hora de Guatemala): lotes que entraron en la ventana de 90 o de 30 días | `thresholdDays`, `lots[]` (producto, lote, `expiresOn`, `daysLeft`, `units`) |
| `cash.closed` | Se cierra una caja, manual o automática | `sessionId`, `mode`, `opening`, `expected`, y en cierre manual `counted`, `difference` |
| `user.access_changed` | Se crea un usuario, se cambian sus permisos o rol, o se activa o desactiva | `username`, `actor`, `change`, `role`, `added[]`, `removed[]`, `active` |
| `invoice.uploaded` | Alguien sube la foto o el PDF de una factura | `draftId`, `filename`, `contentType`, `sizeBytes`, `uploadedBy`, `filePath`, `extractionPath` |
| `invoice.ready` | n8n devolvió la lectura de una factura | `draftId`, `lines`, `matched`, `supplierMatched` |
| `invoice.failed` | n8n informó que no pudo leer la factura | `draftId`, `error` |
| `test.ping` | Botón **Enviar prueba** | `message` |

Cada lote por vencer se avisa una vez por umbral, no todos los días.

## 3. Consultar y actuar desde n8n (llaves de API)

1. En **Configuración → Llaves de API** pon un nombre y marca **solo** los permisos que ese flujo necesita (por ejemplo, un reporte diario solo necesita *Ver ventas*). Pulsa **Crear llave** y copia la llave (`sgk_...`); no se vuelve a mostrar.
2. En n8n usa el nodo **HTTP Request** con el encabezado `Authorization: Bearer sgk_...` hacia `https://<tu-dominio>/api/v1/...`. No necesita sesión ni token CSRF.
3. Si una llave se filtra, **Revocar** la deja sin efecto al instante.

Una llave solo puede lo que se le dio: sin el permiso, el sistema responde `403`. No puede administrar usuarios, configuración ni otras llaves.

Consultas útiles: `GET /api/v1/sales` (últimas 200 ventas), `GET /api/v1/products` (incluye `stock`), `GET /api/v1/inventory`, `GET /api/v1/cash`, `GET /api/v1/receipts`.

## 4. Leer facturas de proveedores

La persona sube la foto o el PDF en **Compras → Subir factura**; n8n la lee; el sistema cruza lo leído con tu catálogo y deja un **borrador** que una persona revisa en «Recibir compra» y confirma. **Nada entra al inventario hasta esa confirmación.**

El motor de lectura vive en n8n: puedes usar un modelo con visión, un servicio de OCR o el que prefieras, con tus propias credenciales. El sistema no guarda llaves de ningún motor.

### Preparación (una vez)

1. Conecta n8n como en la sección 1. Sin webhook configurado, **Subir factura** responde que falta conectar n8n.
2. Crea una **llave de API** con **solo** el permiso *Cargar facturas para leerlas automáticamente*. Esa llave puede descargar facturas y devolver lecturas, pero **no** puede registrar compras ni ver inventario.
3. Da el permiso *Cargar facturas* a quienes suben facturas (el rol Inventario y Supervisor ya lo tienen). Confirmar sigue requiriendo *Registrar compras*.

### El flujo en n8n

1. **Webhook** (Raw Body) → verifica la firma como en la sección 1 → continúa solo si `type` es `invoice.uploaded`.
2. **HTTP Request** `GET https://<tu-dominio>` + `data.filePath`, con `Authorization: Bearer sgk_...`, y respuesta como **archivo**. Devuelve la imagen o el PDF (JPG, PNG, WEBP o PDF, hasta 8 MB).
3. **Nodo de lectura** (modelo con visión u OCR): envía el archivo con las instrucciones de abajo y pide la respuesta como JSON.
4. **HTTP Request** `PUT https://<tu-dominio>` + `data.extractionPath`, con la misma llave y el cuerpo JSON de abajo.
5. Si la lectura falla, envía `{"error": "motivo corto"}` en lugar de las líneas: la factura queda marcada como «No se pudo leer» y se puede volver a enviar.
6. Opcional: con `invoice.ready` puedes avisar por WhatsApp o Telegram que la factura está lista para revisar.

### Cuerpo de la lectura (`PUT .../extraction`)

```json
{
  "supplierName": "DISTRIBUIDORA CENTRAL, S.A.",
  "supplierNit": "1234567-8",
  "document": "FAC-A-2041",
  "lines": [
    { "description": "ACETAMINOFEN 500MG TAB CAJA X100", "barcode": "7400000000011", "sku": "",
      "quantity": 3, "lot": "ACE-2210", "expiresOn": "2029-03-31", "totalCost": 135.50 }
  ]
}
```

Solo `description` es obligatoria en cada línea. Lo que no se lea se deja fuera o en blanco: la persona lo completa al revisar. Las fechas van como `AAAA-MM-DD` (una fecha en otro formato se ignora) y los números con punto decimal, sin símbolo de moneda ni separador de miles.

### Instrucciones sugeridas para el modelo de lectura

> Eres un lector de facturas de proveedores de una farmacia. Devuelve **solo** un objeto JSON con las claves `supplierName`, `supplierNit`, `document` y `lines`. Cada línea de producto lleva `description` (el texto tal como aparece en la factura), `quantity` (número de unidades o cajas facturadas, como número), `totalCost` (importe total de esa línea, como número con punto decimal), y, solo si aparecen impresos, `barcode`, `sku`, `lot` y `expiresOn` (AAAA-MM-DD). No inventes datos: si algo no se ve, omítelo. No incluyas líneas que no sean productos (fletes, descuentos, impuestos, totales). Si la imagen no es una factura legible, responde `{"error": "motivo corto"}`.

### Cómo se cruza con tu catálogo

Para cada línea, en este orden: **código de barras** exacto → **SKU** → **parecido del nombre** (ignora tildes y mayúsculas, y separa «500MG» en «500 mg»). Si dos productos puntúan casi igual, o ninguno se parece lo suficiente, la línea queda **sin cruzar** y la persona elige el producto: es preferible a ingresar existencias en el equivocado. El proveedor se cruza por NIT o por un nombre muy parecido.

En la pantalla de revisión las líneas dudosas se marcan en ámbar y cada una muestra el texto original de la factura junto a la imagen. La cantidad se interpreta como **presentaciones** (cajas), no unidades sueltas: conviene verificarlo en la primera factura de cada proveedor.

### Privacidad y límites

Las imágenes se guardan en la base del sistema y se borran a los 30 días de que la factura se confirma o se descarta (el borrador y su historial se conservan). Se admiten hasta 50 facturas sin revisar por sucursal. La lectura solo sale de tu servidor si tu flujo de n8n la envía a un servicio externo: esa decisión es tuya.

## 5. Ideas de flujos

- **Stock bajo → Telegram o WhatsApp.** Webhook (`stock.low`) → verificar firma → mensaje: «⚠️ {{product}}: quedan {{stock}} (mínimo {{minimum}})».
- **Vencimientos → correo al encargado.** Webhook (`lot.expiring`) → agrupar `lots` en una tabla → enviar por correo.
- **Cierre del día → resumen al dueño.** Webhook (`cash.closed`) → HTTP Request a `/api/v1/sales` con una llave de solo lectura → sumar el día → enviar por Telegram y guardar en una hoja de cálculo.
- **Ventas → hoja de cálculo o contabilidad.** Webhook (`sale.completed`) → agregar una fila por venta.
- **Alertas de seguridad.** Webhook (`user.access_changed`) → avisar al dueño de cada cambio de permisos o desactivación.
- **Reposición asistida.** `stock.low` → buscar al proveedor → armar un borrador de pedido → que una persona lo apruebe antes de enviarlo.
- **Alta de un cliente nuevo.** n8n crea un stack por cliente en Coolify con `BUSINESS_NAME` y `BUSINESS_TYPE` (ver abajo), el dominio y el correo de bienvenida.

## 6. Un negocio nuevo ya configurado

Al crear la base por primera vez se leen dos variables de entorno (después se cambian desde Configuración):

| Variable | Ejemplo | Efecto |
|---|---|---|
| `BUSINESS_NAME` | `Tienda Luna` | Nombre que ven todos |
| `BUSINESS_TYPE` | `FARMACIA`, `TIENDA` u `OTRO` | Aplica la plantilla de módulos de ese tipo |

Variables opcionales de la entrega de eventos: `OUTBOX_INTERVAL_MS` (cada cuánto revisa la cola, 5000 por defecto) y `OUTBOX_BACKOFF_SECONDS` (primera espera tras un fallo, 10 por defecto).
