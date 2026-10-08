# Decisiones de implementación

Este documento recoge lo acordado para la implementación. El avance se registra en [TASKS.md](TASKS.md) y el diseño técnico se describe en [ARCHITECTURE.md](ARCHITECTURE.md).

## Decisiones de negocio

### Pedidos y reintentos

- Cada `orderId` identifica un pedido de un producto y una cantidad concreta. Reutilizarlo con datos distintos se rechaza sin modificar la reserva original.
- Repetir una reserva activa o confirmada devuelve la reserva original, incluido su vencimiento, sin volver a afectar el inventario.
- Repetir una confirmación termina correctamente sin descontar unidades otra vez.
- Una reserva vencida no se reactiva: se requiere un nuevo pedido. Un rechazo por falta de stock no consume el identificador y permite reintentar.

### Vencimientos y categorías

Una reserva vence cuando la hora actual es igual o posterior a `expiresAt`. Ya no puede confirmarse y sus unidades quedan disponibles. Las unidades confirmadas permanecen vendidas.

- `STANDARD`: 15 minutos para pagar, sin límite por categoría.
- `PRE_ORDER`: 24 horas para pagar, sin límite por categoría.
- `FLASH_SALE`: 5 minutos para pagar, máximo 2 unidades por pedido.

Todas las reservas dependen del stock disponible.

### Productos e identificadores

- Registrar un SKU existente se rechaza con el mensaje “El producto ya existe”. Para agregar unidades se usa `addStock`.
- SKU y `orderId` son obligatorios, distinguen mayúsculas y minúsculas y se conservan sin normalizarlos. Se rechazan valores nulos, vacíos o con solo espacios.
- Registrar un producto sin stock no genera un aviso; la primera evaluación ocurre al cargar stock.

### Avisos a compras

- Con 5 unidades disponibles o menos se genera un aviso, sin repetirlo hasta el siguiente reabastecimiento.
- Cada `addStock` válido habilita un nuevo aviso: si quedan 5 o menos, se genera inmediatamente; si quedan más, se espera a que la disponibilidad baje al umbral.
- Reabastecer cancela los reintentos pendientes del aviso anterior y evalúa el stock actualizado. Una entrega ya iniciada no puede retirarse.
- Un fallo de notificación no revierte la operación de inventario.

---

## Decisiones técnicas

### Arquitectura y contrato

Usaremos DDD ligero con dominio, aplicación e infraestructura, y Spring Boot compatible con Java 21. El dominio será independiente de Spring. Mantendremos intactos `com.store.inventory.api` y la firma de `Inventory.create(Clock, StockAlertListener)`; la fábrica podrá usarse sin arrancar Spring y los tests seguirán ejecutándose con `mvn test`.

Las categorías usarán una tabla de políticas. Agregar categorías nuevas requiere ampliar el enum público, fuera del alcance actual. No incorporaremos microservicios, CQRS ni event sourcing.

### Tiempo y concurrencia

Usaremos el `Clock` recibido y procesaremos vencimientos al consultar disponibilidad o modificar el inventario, sin tarea periódica. La limpieza puede esperar a la siguiente operación, pero una reserva vencida no contará como activa.

Las operaciones serán atómicas por producto mediante transacciones y bloqueos de fila, y la clave primaria protegerá la unicidad de `orderId`. Los servicios que compartan una base usarán la misma coordinación; H2 en memoria sigue siendo local al proceso. Las notificaciones se ejecutarán después de completar la transacción y liberar los bloqueos.

### Almacenamiento y seed

Usaremos H2 en memoria mediante JPA para productos, stock, reservas, registros de pedidos, avisos y DLQ. Las entidades JPA estarán separadas del dominio. Al reiniciar se perderán los datos, incluida la protección contra duplicados.

La seed se implementará posteriormente en la tarea 14 y se cargará explícitamente en H2 mediante la API Java, con las tres categorías, distintas cantidades de stock, reservas activas y pedidos confirmados. Usará un reloj controlado y un listener de demostración. `Inventory.create(...)` seguirá creando un inventario vacío en una base H2 aislada, sin iniciar Spring.

### Entrega de avisos

El aviso se guardará junto con la operación de inventario y el intento inicial se hará inmediatamente después del commit, fuera de los bloqueos. Habrá hasta cinco reintentos en segundo plano: 2, 4, 8, 16 y 32 segundos, con jitter independiente de ±20 %. Sus esperas no bloquearán la respuesta del servicio ni duplicarán un aviso pendiente. Una entrega exitosa cancela los reintentos restantes.

Si se agotan los intentos, el aviso pasa a la DLQ con sus datos, cantidad de intentos y último error. No se considera entregado ni reinicia sus reintentos automáticamente. Antes de reprocesarlo, se comprobará si quedó desactualizado por un reabastecimiento.

### API REST

Los controladores adaptarán el mismo `InventoryService`, sin duplicar reglas.

- `POST /products`: registrar producto.
- `POST /products/{sku}/stock`: agregar unidades.
- `GET /products/{sku}/availability`: consultar disponibilidad.
- `POST /reservations`: reservar unidades.
- `POST /reservations/{orderId}/confirm`: confirmar pedido.

### Respuestas y errores

Todas las respuestas, exitosas o de error, usarán `application/json` con únicamente cuatro campos: `success`, `message`, `data` y `traceId`. `data` contendrá solo el resultado necesario para el cliente y será `null` en errores o cuando no haya datos; no usaremos `204`. El frontend leerá siempre `message`, escrito para clientes.

Esta decisión reemplaza el uso de Problem Details: no agregaremos `type`, `title`, `status`, `detail` ni `instance` al cuerpo. El estado se comunicará mediante HTTP. Un `@RestControllerAdvice` centralizará errores del servicio y de Spring MVC con el mismo formato.

Usaremos `400` para entradas inválidas, `409` para conflictos de estado, como stock insuficiente o reservas vencidas, `404` para rutas inexistentes y `500` para errores internos inesperados. Los errores internos tendrán un mensaje genérico; las excepciones, causas, trazas y demás detalles de diagnóstico irán únicamente a logs, asociados al `traceId`.

El registro exitoso responderá `201`; las otras operaciones, `200`. Reabastecer un producto desconocido responderá `404`, aunque consultar su disponibilidad siga devolviendo cero con `200`. Los métodos o formatos HTTP no admitidos conservarán `405`, `406` o `415` con el mismo JSON.

Las entradas inválidas y el registro duplicado usarán `IllegalArgumentException`; confirmar un pedido desconocido o vencido, `IllegalStateException`. El mapeo HTTP distinguirá la causa, no solo la clase de excepción.

### Pruebas automatizadas

Conservaremos los tests proporcionados y agregaremos pruebas más completas: validaciones, políticas por categoría, disponibilidad, idempotencia, confirmaciones y vencimientos, incluido el instante exacto de expiración. También verificaremos reservas simultáneas y pedidos duplicados para evitar sobreventa.

Probaremos los avisos, su habilitación tras reabastecer, cancelaciones, reintentos con jitter y paso a la DLQ. Usaremos un reloj y un planificador controlables para no depender de esperas reales. Las pruebas REST cubrirán estados HTTP, los cuatro campos del JSON acordado, mensajes aptos para clientes y ausencia de detalles técnicos en las respuestas, incluidos errores de validación, `404` y `500`.

Los tests originales y los nuevos deberán pasar con `mvn test`; el pipeline ejecutará esa verificación antes de construir la imagen.

### Logs y documentación

Registraremos eventos relevantes de inventario y notificaciones con `traceId`, operación, SKU, pedido e intento cuando corresponda. Mantendremos la correlación en tareas en segundo plano.

Usaremos `INFO` para eventos del negocio, `WARN` para fallos recuperables, `ERROR` para fallos inesperados o paso a DLQ y `DEBUG` para diagnóstico. Las trazas técnicas se registrarán sin duplicarlas ni exponer datos sensibles; los rechazos esperados no generarán trazas de error innecesarias.

Durante la implementación crearemos OpenAPI, Swagger UI y una colección de Postman con entorno local, ejemplos, pruebas y guía de uso. Postman generará datos propios y se alineará con la API y su formato de respuestas.

### CI/CD, Docker y Kubernetes

Prepararemos GitHub Actions para ejecutar los tests con Java 21 y Maven y, si pasan, construir la imagen Docker. El flujo quedará listo para publicarla en un registro de contenedores, con etiquetas que identifiquen la versión y el commit. El registro de destino y el despliegue automático se definirán al conocer el entorno.

Incluiremos un Dockerfile con construcción en varias etapas y ejecución como usuario sin privilegios, además de un `.dockerignore`. Prepararemos manifiestos de Kubernetes para el Deployment, Service, configuración, referencias a secretos, recursos y sondas de salud.

Con el almacenamiento actual en memoria, el despliegue de demostración usará una sola réplica y perderá los datos al reiniciar. No habilitaremos múltiples réplicas hasta disponer de almacenamiento compartido y coordinación entre instancias.

Incluiremos un `.env.example` documentado con variables de configuración y valores de ejemplo, sin credenciales reales. Los archivos `.env` locales quedarán fuera de Git; Kubernetes utilizará ConfigMaps y Secrets, y GitHub Actions, sus variables y secretos. Documentaremos cómo cargar las variables localmente, sin asumir que Spring Boot lee un `.env` automáticamente.

Estos archivos y flujos se crearán durante la implementación; por ahora solo queda registrada la decisión.

### Pendientes antes de producción

- Migrar de H2 en memoria a una base persistente, con migraciones de esquema y pruebas en el motor elegido; definir retención de pedidos y reprocesamiento.
- Garantizar atomicidad y unicidad entre instancias y deduplicar entregas de avisos en el receptor.
- Validar la confirmación idempotente con el equipo: el contrato exige una reserva activa y no contempla explícitamente repetir una confirmación exitosa.
- Precisar los estados HTTP restantes y verificar la implementación, OpenAPI, Postman y los tests.
