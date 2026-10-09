# Arquitectura prevista

Diseño técnico acordado. La base de Spring Boot, el dominio, H2/JPA, el inventario, las notificaciones, la API REST y la correlación de logs están implementados. Las reglas están en [BUSINESS_RULES.md](BUSINESS_RULES.md), las decisiones y limitaciones en [DECISIONS.md](DECISIONS.md), y el avance en [TASKS.md](TASKS.md).

## Organización

Usaremos Java 21, Spring Boot y DDD ligero, con las siguientes responsabilidades:

- **Dominio:** productos, inventario, reservas, estados, políticas por categoría y condiciones para generar avisos. No dependerá de Spring, HTTP ni almacenamiento.
- **Aplicación:** coordinación de registro, reabastecimiento, disponibilidad, reserva y confirmación; acceso al almacenamiento y coordinación de operaciones concurrentes.
- **Infraestructura:** persistencia JPA con H2 en memoria, planificación de reintentos, entrega mediante `StockAlertListener` y DLQ.
- **Adaptador REST:** controladores, validación HTTP, respuestas JSON y manejo global de errores.
- **Configuración:** ensamblaje de dependencias, configuración de Spring y carga explícita de la seed.

Las dependencias apuntarán hacia el dominio. Los controladores no contendrán reglas de negocio y el almacenamiento será reemplazable mediante interfaces.

### Estructura inicial

- `com.store.inventory.InventoryApplication`: entrada de Spring Boot.
- `com.store.inventory.domain`: modelos y reglas del dominio.
- `com.store.inventory.application`: casos de uso y coordinación.
- `com.store.inventory.infrastructure`: almacenamiento y notificaciones.
- `com.store.inventory.web`: adaptador REST.
- `com.store.inventory.configuration`: configuración y ensamblaje.
- `com.store.inventory.api`: contrato existente, sin cambios.

Los paquetes de las capas se documentan con `package-info.java`. Maven utiliza Spring Boot 4.1.1 y compila para Java 21.

### Modelo de dominio

- `Product` y `Category`: identidad del producto y su categoría, independientes del enum público.
- `ReservationPolicy` y `CategoryPolicies`: duración y límite de pedidos en una tabla inmutable.
- `OrderReservation` y `ReservationState`: datos inmutables de la reserva y transiciones entre activa, confirmada y vencida. Las transiciones reciben un `Instant`; el servicio proporcionará el tiempo mediante su `Clock`.
- `OrderLimitViolationException`: error de dominio con el contexto del límite excedido; el adaptador del servicio lo traducirá a la excepción del contrato público.

`ProductInventory` representa un producto y sus unidades aún no vendidas, incluidas las reservadas. Sus operaciones de reabastecimiento y venta devuelven un nuevo estado y rechazan cantidades inválidas, ventas superiores al stock o desbordamientos del entero usado por el contrato. El modelo no mantiene almacenamiento ni realiza notificaciones. Una transición devuelve una nueva reserva; los casos de uso guardan ese resultado y coordinan los cambios de stock.

### Almacenamiento H2/JPA

- `ProductInventoryRepository` y `ReservationRepository` definen el acceso al almacenamiento sin depender de Spring.
- Los adaptadores `JpaProductInventoryRepository` y `JpaReservationRepository` guardan datos mediante entidades JPA separadas del dominio, en las tablas `product_inventory` y `reservations`. Las claves primarias protegen SKU y pedidos duplicados; las reservas referencian un producto y tienen un índice por SKU. Las fechas conservan precisión de nanosegundos.
- Las inserciones y actualizaciones usan transacciones. La inserción no sobrescribe identificadores existentes y la actualización exige que el valor esperado siga vigente.
- `JpaInventoryPersistence` conecta todos los adaptadores y el ejecutor de operaciones a un contexto transaccional compartido. Dentro de un caso de uso, las lecturas y escrituras reutilizan el mismo `EntityManager`; fuera de él, los repositorios conservan sus transacciones independientes.
- Las reservas confirmadas y vencidas se conservan como registros de pedidos; no se duplica esa información en otro almacén. Las consultas por SKU devuelven listas inmutables.
- `Inventory.create(...)` crea una H2 aislada mediante `H2InventoryDatabase`, sin contexto Spring. La implementación del servicio es `AutoCloseable` para liberar la base y el pool; dispone de limpieza de respaldo al ser recolectada. El registro crea productos sin stock y rechaza duplicados; el reabastecimiento actualiza el stock mediante comparación del estado esperado y reintenta si otra operación lo cambió. La disponibilidad considera las reservas activas.
- Spring configura su `DataSource` y `EntityManagerFactory` y conecta los mismos adaptadores mediante `PersistenceConfiguration`. `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` permiten configurar la conexión. El dominio conserva el enum de categorías acordado.

`InventoryOperationExecutor`, implementado por `JpaInventoryOperationExecutor`, abre una transacción y obtiene un bloqueo `PESSIMISTIC_WRITE` sobre el producto antes de ejecutar reserva, confirmación, reabastecimiento o disponibilidad. Todos sus accesos a repositorios comparten esa transacción. Esto protege también los servicios con contextos independientes que utilicen la misma base, sin bloquear productos diferentes.

La confirmación usa `ReservationSettlementRepository`, implementado por `JpaReservationSettlementRepository`, para guardar el estado confirmado y descontar el stock juntos. Los fallos técnicos revierten la operación completa. Los conflictos de inserción y liquidación reintentan toda la transacción hasta tres intentos, con estado fresco. Los rechazos esperados del negocio conservan la limpieza de vencimientos, sin crear una reserva ni una venta.

Los vencimientos se procesan al consultar disponibilidad, reabastecer, reservar o confirmar. Se guarda el estado vencido sin descontar unidades, conservando el registro del pedido para reconocer reintentos. Las reservas confirmadas no vencen.

La reserva valida datos y política, comprueba disponibilidad y guarda el pedido en H2 sin descontar unidades vendidas. Repetir un pedido activo o confirmado devuelve su respuesta original; cambiar sus datos o reenviar uno vencido se rechaza. Los rechazos por stock insuficiente no crean registros. El adaptador traduce las infracciones de límites a `OrderLimitExceededException` y la falta de stock a `InsufficientStockException`.

La clave primaria de H2 protege pedidos que compiten entre SKU distintos; ante una colisión, se revierte la transacción y se vuelve a consultar el pedido ganador. Las lecturas de disponibilidad usan el mismo bloqueo y transacción que las escrituras. El cierre del servicio espera a que terminen sus operaciones en curso antes de liberar recursos.

H2 mantiene sus datos mientras la base está activa y los pierde al cerrar o reiniciar. Para esta etapa usamos creación y eliminación automática del esquema; una base persistente requerirá migraciones y pruebas con su dialecto, no solo cambiar la URL.

### Verificación de la base

Requiere JDK 21 y Maven 3.6.3 o posterior; `JAVA_HOME` debe apuntar al JDK.

- `mvn -Dtest=InventoryApplicationTest test`: comprueba el arranque del servidor en un puerto aleatorio.
- `mvn "-Dtest=com.store.inventory.domain.*Test" test`: ejecuta las pruebas del dominio sin arrancar Spring ni esperar tiempo real.
- `mvn "-Dtest=com.store.inventory.infrastructure.jpa.*Test,InventoryApplicationServiceTest,InventoryFactoryTest" test`: verifica los adaptadores con H2 real, la disponibilidad y la fábrica.
- `mvn spring-boot:run`: inicia la API en `http://localhost:8080`, con H2 vacía.
- `mvn spring-boot:run "-Dspring-boot.run.profiles=demo"`: carga la seed y utiliza un reloj controlado; ver [SEED.md](docs/SEED.md).
- `mvn test`: ejecuta todos los tests, incluidos los tres originales, que ya pasan.

La prueba de arranque requiere conexiones locales habilitadas en el entorno de ejecución.

## Contrato y construcción del servicio

- Mantendremos intacto `com.store.inventory.api` y la firma de `Inventory.create(Clock, StockAlertListener)`.
- La fábrica creará un servicio vacío y utilizable sin iniciar Spring. Spring configurará la misma implementación para HTTP.
- El record público `Reservation` será una respuesta del contrato; los estados internos se representarán en el dominio.
- Las reglas de categoría se concentrarán en una tabla de políticas. El enum público limita las categorías aceptadas.

## Estado y consistencia

- Productos, stock, reservas, registros de pedidos, avisos y DLQ se guardan en H2 en memoria. Reiniciar perderá los datos y el historial de idempotencia.
- El estado de cada producto se coordinará para que comprobar disponibilidad y modificar unidades sea una operación atómica.
- El registro de pedidos también protegerá la unicidad de `orderId`, incluso entre solicitudes de productos distintos.
- Las reservas tendrán estados activa, confirmada y vencida. Se conservará la información necesaria para reconocer reintentos.
- Los vencimientos se evaluarán con el `Clock` recibido al consultar disponibilidad o modificar inventario. No habrá una tarea periódica de expiración.

La coordinación se aplica a quienes compartan una base y ejecuten los casos de uso mediante el servicio. H2 en memoria no comparte datos entre procesos; antes de usar varias réplicas se necesitará una base persistente compartida y pruebas en el motor elegido.

## Notificaciones

`LowStockPolicy` determina cuándo corresponde un aviso. `StockAlertNotifications` guarda el aviso en la tabla `stock_alerts` dentro de la transacción de inventario y lo entrega después del commit mediante `StockAlertDelivery`. El listener se invoca sin bloqueos de producto, de acceso a la base ni del ciclo de vida del servicio. No integramos directamente un proveedor de correo.

Cada producto mantiene en H2 un ciclo de reabastecimiento y una marca de aviso creado. Esto permite generar un aviso por ciclo incluso con varios servicios, mientras el estado de entrega registra si realmente llegó al listener.

- El intento inicial es inmediato y puede ejecutar el listener en el hilo que llamó al servicio. Los cinco reintentos usan `RetryScheduler` en segundo plano: 2, 4, 8, 16 y 32 segundos, con jitter independiente de ±20 %.
- Los estados son pendiente, en entrega, esperando reintento, entregado, cancelado y DLQ. El número esperado de intento protege contra ejecuciones duplicadas de distintos trabajadores.
- Reabastecer cancela los avisos pendientes anteriores en H2 y sus tareas locales. Las tareas de otros servicios también consultan el estado guardado antes de llamar al listener.
- Una entrega exitosa detiene los reintentos. Seis fallos, contando el intento inicial, dejan el aviso en DLQ con sus datos, cantidad de intentos y último error, sin revertir el inventario.

La DLQ se consulta mediante `StockAlertRepository.deadLetters()`. `StockAlertDispatcher.reprocess(id)` solo permite reprocesar avisos del ciclo vigente; un fallo de un ciclo anterior permanece como historial. El reprocesamiento manual abre una nueva ronda de intentos. `resumePending()` permite retomar avisos pendientes o en espera guardados en H2.

El cierre cancela las tareas locales y evita nuevos accesos del dispatcher a la base. Una llamada al listener ya iniciada puede terminar, pero no podrá reiniciar reintentos cancelados. Antes de producción se necesita recuperación de entregas interrumpidas en estado "en entrega" y deduplicación en el receptor; los reintentos no garantizan una única entrega externa.

Las pruebas usan reloj y planificador manuales para comprobar tiempos, cancelaciones, DLQ y concurrencia sin esperas reales largas. Los fallos de entrega se registran en `WARN` y el paso a DLQ en `ERROR`, con diagnóstico técnico.

## HTTP y observabilidad

`ProductController` y `ReservationController` delegan las cinco operaciones al mismo `InventoryService`, conectado por Spring a la base configurada. Tanto éxitos como errores usan `application/json` con únicamente `success`, `message`, `data` y `traceId`; el estado se comunica mediante HTTP y no se usa `204`.

- `POST /products`: recibe `sku` y `category`; responde `201` con el producto registrado.
- `POST /products/{sku}/stock`: recibe `quantity`; responde `200` con `data: null`.
- `GET /products/{sku}/availability`: responde `200` con `sku` y `availableUnits`. Un SKU desconocido tiene disponibilidad cero.
- `POST /reservations`: recibe `orderId`, `sku` y `quantity`; responde `200` tanto al crear como al reconocer un reintento, con los datos de la reserva original.
- `POST /reservations/{orderId}/confirm`: responde `200` con `data: null`, también en confirmaciones repetidas.

Los cuerpos se validan antes de llamar al servicio: identificadores obligatorios de hasta 255 caracteres, de acuerdo con el esquema actual, categoría válida y cantidades enteras positivas. JSON inválido, campos desconocidos, cantidades decimales o números enviados como texto se rechazan con `400`.

`ApiExceptionHandler` usa `@RestControllerAdvice` para manejar errores del servicio y de Spring MVC; `ApiErrorController` cubre el fallback de errores del servlet sin páginas HTML ni atributos técnicos. Excepciones internas específicas permiten distinguir conflictos del negocio de fallos inesperados, sin analizar sus mensajes.

- `400`: solicitudes inválidas.
- `404`: rutas inexistentes o reabastecimiento de un producto no registrado.
- `409`: falta de stock, límite por pedido, producto duplicado, datos de pedido incompatibles, reservas vencidas o inexistentes al confirmar, y capacidad de stock excedida.
- `500`: fallos inesperados, con mensaje genérico y diagnóstico completo únicamente en logs.
- `405`, `406` y `415`: método, formato de respuesta o tipo de contenido no admitidos, también con el JSON uniforme.

En errores, `data` es `null`. Nunca se copia el mensaje de una excepción al mensaje del cliente.

`TraceIdFilter` genera un identificador por solicitud o acepta `X-Trace-Id` con hasta 64 caracteres alfanuméricos, puntos, guiones o guiones bajos. Lo devuelve en el encabezado y en el cuerpo, y lo mantiene en MDC durante los despachos normales y de error. Al terminar, restaura el contexto anterior.

El identificador se guarda en cada aviso de H2. `StockAlertDelivery` lo recupera para la entrega inicial y los reintentos, incluso en otros hilos, restaurando después el contexto del trabajador.

Usamos `INFO` para registro, reabastecimiento, reservas, confirmaciones y entrega de avisos; `WARN` para fallos recuperables; `ERROR` para fallos inesperados o paso a DLQ; y `DEBUG` para reintentos idempotentes, vencimientos preparados y diagnóstico HTTP. Los identificadores se escapan en los logs para evitar saltos de línea introducidos por entradas externas. No se registran cuerpos HTTP completos ni credenciales; el manejador global registra una sola traza por error inesperado.

La aplicación local proporciona `LoggingStockAlertListener`: registra los avisos en logs como demostración. Una integración de correo puede reemplazar ese bean sin cambiar el contrato. OpenAPI y Swagger UI están disponibles en `/v3/api-docs` y `/swagger-ui.html`; la colección de Postman y su [guía](postman/README.md) verifican las mismas operaciones.

## Pruebas y demostración

- Conservar los tests originales y ampliar cobertura de dominio, aplicación, concurrencia, notificaciones y REST.
- Usar reloj y planificador controlables para comprobar vencimientos y reintentos sin esperas reales.
- `DemoInventorySeed` carga productos y pedidos de las tres categorías mediante la API Java. `DemoConfiguration` la activa únicamente con el perfil `demo` y usa `DemoClock` y el listener de logs. La fábrica sigue vacía.
- `demo.advance-by` avanza el reloj después de cargar los pedidos para reproducir vencimientos. No se agregaron rutas para modificar el tiempo.
- `DocumentationAndDemoTest` compara el OpenAPI generado con [docs/openapi.json](docs/openapi.json). `PostmanContractTest` verifica rutas y variables, y la colección fue ejecutada contra el JAR local mediante Newman.
- [TESTING.md](docs/TESTING.md) describe cobertura, comandos y límites de la verificación.

## Empaquetado y ejecución

- Docker con construcción en varias etapas, usuario sin privilegios y `.dockerignore`.
- `.env.example` documentado; configuración mediante variables, sin guardar credenciales reales ni archivos `.env` locales en Git.
- Kubernetes con una réplica, Service, ConfigMaps, referencias a Secrets, recursos y sondas de salud.
- GitHub Actions ejecutará `mvn test` antes de construir la imagen y quedará preparado para publicarla con etiquetas de versión y commit.

El flujo de [GitHub Actions](.github/workflows/container.yml) construye y prueba la imagen con Postman y la publica en GHCR desde la rama predeterminada o etiquetas de versión. Las sondas `/health/liveness` y `/health/readiness` usan el JSON uniforme; solo readiness comprueba la base. Kubernetes usa una réplica con estrategia `Recreate`. La [guía de despliegue](docs/DEPLOYMENT.md) documenta configuración y operación. El despliegue al clúster queda únicamente indicado, con los secretos previstos y la preparación de una base persistente en [PRODUCTION.md](docs/PRODUCTION.md). La persistencia, retención de pedidos y entrega durable de avisos son cambios previos a producción.
