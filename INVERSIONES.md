# Portafolio personal de ETFs («Inversiones»)

Apartado **solo para el dueño** dentro de la app. Costo cero: usa fuentes públicas gratuitas y se guarda en tu propia base de datos (nada se publica en el repositorio).

> Herramienta educativa personal, no es asesoría financiera. No se conecta a ningún broker ni ejecuta operaciones.

## Cómo se activa

1. En Coolify, en el backend, define **`INVEST_OWNER`** con tu usuario administrador (por ejemplo `admin`). Sin esta variable el apartado no existe: todos los `/api/v1/invest/**` responden 403 y el menú no aparece.
2. Entra con ese usuario: aparece **Inversiones** en el menú.
3. En **Ajustes** registra tus compras (ETF, acciones, precio promedio, fecha), tus aportes y revisa tu plan. Pulsa **Actualizar**.

El trabajo diario corre solo de lunes a viernes a las 22:30 UTC (después del cierre de EE. UU.). Cambia la hora con `INVEST_CRON` (cron de Spring, 6 campos, UTC).

## Variables de entorno (todas opcionales salvo `INVEST_OWNER`)

| Variable | Para qué | Dónde sacarla (gratis) |
|---|---|---|
| `INVEST_OWNER` | Usuario dueño del apartado | tu usuario admin |
| `FRED_API_KEY` | Series macro de FRED: curva 10-2, spread de alto riesgo, tasa de la Fed, inflación | https://fred.stlouisfed.org/docs/api/api_key.html |
| `FINNHUB_TOKEN` | Noticias filtradas (60 llamadas/min en el plan gratis) | https://finnhub.io/register |
| `TWELVEDATA_KEY` | Respaldo de precios de ETFs si Yahoo falla (800/día gratis) | https://twelvedata.com/pricing |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | Avisos al celular | crea un bot con @BotFather y escribe a tu bot para obtener el chat id |
| `INVEST_CRON` | Hora del trabajo diario | — |

**Sin ninguna llave igual funciona**: precios por Yahoo (respaldo Stooq), VIX y bono a 10 años por Yahoo, tipo de cambio del Banguat. Lo que queda sin dato sin llaves: curva 10-2, spread de alto riesgo, tasa de la Fed, inflación y noticias; la pantalla los muestra como «sin dato» y dice qué fuente falló.

> El CSV público de FRED (sin llave) a veces rechaza o cuelga conexiones que no parecen un navegador o `curl`; por eso se recomienda `FRED_API_KEY` (la API oficial no tiene ese problema).

## Qué hace el trabajo diario

1. Precios: Yahoo → Twelve Data → Stooq (en ese orden, solo se piden los días que faltan). Un respaldo nunca pisa el historial de la fuente principal y se ajusta con la relación dividendos/precio de la última fila.
2. Macro (FRED, en paralelo), tipo de cambio (Banguat) y noticias (Finnhub).
3. Calcula el resumen: rendimiento sin aportes, caída desde el máximo, volatilidad, Sharpe, beta, rebalanceo (solo compras), prueba de estrés, meta con fecha estimada, semáforo de riesgo, plan para caídas y calendario de compras. Se guarda en `invest_snapshots`.
4. Avisos (Telegram, y en pantalla): el semáforo pasa a rojo, un ETF sale de su banda, o el S&P 500 cruza −10 / −20 / −30 %. Solo avisa cuando algo **cambia**.

Si una fuente falla, se muestra el último dato válido con su fecha y la lista de lo que quedó atrasado.

## Notas

- La migración `V7__invest.sql` va después de `V6` (borradores de facturas): fusiona `factura-borradores` antes que esta rama.
- Las caídas de la prueba de estrés (2008/2020/2022) son aproximadas por tipo de activo y se pueden editar en el plan (API: `PUT /api/v1/invest/config`).
- Las métricas de riesgo aparecen con 30 o más días de historial; con menos se explica por qué no hay cifra.
- Fuentes y límites: Yahoo Finance (no oficial, puede bloquear IPs de centros de datos → por eso hay respaldos), FRED, Banguat (referencia diaria), Finnhub.
