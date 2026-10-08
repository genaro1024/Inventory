# Arquitectura prevista

Diseño técnico acordado. La base de Spring Boot, el dominio, H2/JPA, las operaciones de inventario y las notificaciones con reintentos y DLQ están implementados. La API REST sigue pendiente. Las reglas están en [BUSINESS_RULES.md](BUSINESS_RULES.md), las decisiones y limitaciones en [DECISIONS.md](DECISIONS.md), y el avance en [TASKS.md](TASKS.md).

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
- `mvn spring-boot:run`: inicia la aplicación base; todavía no expone los endpoints de inventario.
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

El adaptador REST expondrá las cinco operaciones documentadas en `DECISIONS.md`. Tanto los éxitos como los errores usarán `application/json` con únicamente `success`, `message`, `data` y `traceId`; los estados se comunicarán mediante HTTP y no se usará `204`.

Un `@RestControllerAdvice` centralizará la traducción de excepciones, incluyendo errores de Spring MVC y rutas inexistentes. La API devolverá mensajes aptos para clientes y conservará los detalles técnicos en logs.

El `traceId` relacionará respuestas, logs y notificaciones en segundo plano. Los niveles serán `INFO` para eventos relevantes, `WARN` para fallos recuperables, `ERROR` para fallos inesperados o paso a DLQ y `DEBUG` para diagnóstico.

## Pruebas y demostración

- Conservar los tests originales y ampliar cobertura de dominio, aplicación, concurrencia, notificaciones y REST.
- Usar reloj y planificador controlables para comprobar vencimientos y reintentos sin esperas reales.
- Implementar la seed posteriormente, en la tarea 14, y cargarla explícitamente en H2 mediante la API Java, con las tres categorías y pedidos en distintos estados, usando un reloj controlado. La fábrica no cargará semillas automáticamente.
- Crear OpenAPI, Swagger UI y Postman durante la implementación; verificar que coincidan con las respuestas reales.

## Empaquetado y ejecución

- Docker con construcción en varias etapas, usuario sin privilegios y `.dockerignore`.
- `.env.example` documentado; configuración mediante variables, sin guardar credenciales reales ni archivos `.env` locales en Git.
- Kubernetes con una réplica, Service, ConfigMaps, referencias a Secrets, recursos y sondas de salud.
- GitHub Actions ejecutará `mvn test` antes de construir la imagen y quedará preparado para publicarla con etiquetas de versión y commit.

El registro de imágenes y el despliegue automático quedan pendientes de definir el entorno. La persistencia, retención de pedidos y entrega durable de avisos son cambios previos a producción.
