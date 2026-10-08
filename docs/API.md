# Contrato HTTP

Iniciar con `mvn spring-boot:run` para un inventario vacío, o con el perfil `demo` para cargar la [seed](SEED.md). La dirección local es `http://localhost:8080`.

## Documentación interactiva

- Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).
- OpenAPI generado: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs).
- Exportación verificada para revisión sin ejecutar la aplicación: [openapi.json](openapi.json).
- Colección y guía de [Postman](../postman/README.md).

Swagger describe las cinco operaciones de negocio; los endpoints de documentación no utilizan el sobre JSON de negocio. La exportación usa un servidor relativo `/`, para importarla en el entorno deseado.

## Respuesta uniforme

Los éxitos y errores contienen únicamente `success`, `message`, `data` y `traceId`. Las respuestas de negocio usan `application/json`.

```json
{
  "success": true,
  "message": "Tu pedido fue confirmado.",
  "data": null,
  "traceId": "frontend-trace-123"
}
```

En errores, `success` es `false`, `data` es `null` y `message` es apto para clientes. El estado se comunica por HTTP; las excepciones, causas y trazas quedan en logs. No se utiliza `204`.

Se puede enviar `X-Trace-Id` con entre 1 y 64 caracteres alfanuméricos, puntos, guiones o guiones bajos. Si falta o es inválido, el servidor genera un UUID. Lo devuelve en el encabezado y en el cuerpo, y lo conserva en las notificaciones y sus reintentos.

## Operaciones

### Registrar producto

`POST /products` responde `201` con `data.sku` y `data.category`.

```json
{"sku":"CAMISA-01","category":"STANDARD"}
```

Las categorías son `STANDARD`, `PRE_ORDER` y `FLASH_SALE`. El registro no carga unidades y repetir un SKU devuelve `409`.

### Reabastecer

`POST /products/{sku}/stock` responde `200` con `data: null`.

```json
{"quantity":10}
```

El producto debe estar registrado. Cada llamada suma unidades: esta operación no es idempotente. Un producto desconocido devuelve `404`.

### Consultar disponibilidad

`GET /products/{sku}/availability` responde `200` con `data.sku` y `data.availableUnits`. Las reservas activas reducen la disponibilidad; las vencidas no la bloquean. Un SKU desconocido devuelve cero, sin error.

### Reservar

`POST /reservations` responde `200` con `data.orderId`, `data.sku`, `data.quantity` y `data.expiresAt`.

```json
{"orderId":"ORDER-01","sku":"CAMISA-01","quantity":3}
```

Repetir un pedido activo o confirmado con los mismos datos devuelve la reserva original, sin renovar el vencimiento ni afectar otra vez el stock. Cambiar los datos del identificador o reintentar un pedido vencido devuelve `409`. Si falló por falta de stock, el mismo identificador puede volver a intentarse después de reabastecer.

### Confirmar

`POST /reservations/{orderId}/confirm` no requiere cuerpo y responde `200` con `data: null`. Una confirmación repetida termina correctamente. Un pedido desconocido o vencido devuelve `409`.

## Validaciones y estados

- `400`: JSON inválido, campos desconocidos, identificadores nulos o con solo espacios, categoría inválida o cantidades no positivas.
- `404`: ruta inexistente o producto desconocido al reabastecer.
- `409`: conflictos de negocio, incluidos falta de stock, límite FLASH_SALE, duplicados, cambios de datos del pedido y reservas vencidas.
- `405`, `406`, `415`: método, formato de respuesta o tipo de contenido no admitidos.
- `500`: fallo inesperado; mensaje genérico y detalle técnico solo en logs.

Los cuerpos con datos usan `Content-Type: application/json`; las cantidades deben ser enteros JSON, sin convertir decimales ni textos. Los identificadores del cuerpo tienen un máximo de 255 caracteres según el esquema actual. Se conservan tal como llegan y distinguen mayúsculas de minúsculas.

## Mantener el contrato alineado

`DocumentationAndDemoTest` verifica Swagger UI y compara operaciones y esquemas del OpenAPI generado con `docs/openapi.json`. Si cambia el contrato, exportar `/v3/api-docs`, normalizar `servers` a `/`, actualizar este archivo y Postman y ejecutar los tests nuevamente. La última generación de diagnóstico se guarda en `target/generated-openapi.json`.

Springdoc 3.1.1 genera la documentación desde los controladores y validaciones. Su [documentación oficial](https://springdoc.org/) describe la compatibilidad con Spring Boot 4.
