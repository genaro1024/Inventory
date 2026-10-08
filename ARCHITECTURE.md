# Arquitectura prevista

Diseño técnico acordado. La base de Spring Boot, el modelo de dominio y el almacenamiento H2/JPA están preparados; los casos de uso de escritura siguen pendientes. Las reglas están en [BUSINESS_RULES.md](BUSINESS_RULES.md), las decisiones y limitaciones en [DECISIONS.md](DECISIONS.md), y el avance en [TASKS.md](TASKS.md).

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

`ProductInventory` representa un producto y sus unidades aún no vendidas, incluidas las reservadas. El modelo no mantiene almacenamiento ni realiza notificaciones. Una transición devuelve una nueva reserva; los casos de uso deberán guardar ese resultado y coordinar los cambios de stock.

### Almacenamiento H2/JPA

- `ProductInventoryRepository` y `ReservationRepository` definen el acceso al almacenamiento sin depender de Spring.
- Los adaptadores `JpaProductInventoryRepository` y `JpaReservationRepository` guardan datos mediante entidades JPA separadas del dominio, en las tablas `product_inventory` y `reservations`. Las claves primarias protegen SKU y pedidos duplicados; las reservas referencian un producto y tienen un índice por SKU. Las fechas conservan precisión de nanosegundos.
- Las inserciones y actualizaciones usan transacciones. La inserción no sobrescribe identificadores existentes y la actualización exige que el valor esperado siga vigente. Cada operación crea y cierra su propio `EntityManager`.
- Las reservas confirmadas y vencidas se conservan como registros de pedidos; no se duplica esa información en otro almacén. Las consultas por SKU devuelven listas inmutables.
- `Inventory.create(...)` crea una H2 aislada mediante `H2InventoryDatabase`, sin contexto Spring. La implementación del servicio es `AutoCloseable` para liberar la base y el pool; dispone de limpieza de respaldo al ser recolectada. La consulta de disponibilidad considera las reservas activas; las operaciones de escritura siguen pendientes de las tareas 4 a 6.
- Spring configura su `DataSource` y `EntityManagerFactory` y conecta los mismos adaptadores mediante `PersistenceConfiguration`. `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` permiten configurar la conexión. El dominio conserva el enum de categorías acordado.

Las transacciones actuales son por operación de repositorio; no garantizan atomicidad entre stock y reservas ni una instantánea conjunta de disponibilidad. La coordinación completa y los límites transaccionales de los casos de uso corresponden a la tarea 7.

H2 mantiene sus datos mientras la base está activa y los pierde al cerrar o reiniciar. Para esta etapa usamos creación y eliminación automática del esquema; una base persistente requerirá migraciones y pruebas con su dialecto, no solo cambiar la URL.

### Verificación de la base

Requiere JDK 21 y Maven 3.6.3 o posterior; `JAVA_HOME` debe apuntar al JDK.

- `mvn -Dtest=InventoryApplicationTest test`: comprueba el arranque del servidor en un puerto aleatorio.
- `mvn "-Dtest=com.store.inventory.domain.*Test" test`: ejecuta las pruebas del dominio sin arrancar Spring ni esperar tiempo real.
- `mvn "-Dtest=com.store.inventory.infrastructure.jpa.*Test,InventoryApplicationServiceTest,InventoryFactoryTest" test`: verifica los adaptadores con H2 real, la disponibilidad y la fábrica.
- `mvn spring-boot:run`: inicia la aplicación base; todavía no expone los endpoints de inventario.
- `mvn test`: ejecuta todos los tests. Los tres originales aún fallan porque el registro de productos está pendiente de la tarea 4; sus flujos completos requieren también las tareas 5 y 6.

La prueba de arranque requiere conexiones locales habilitadas en el entorno de ejecución.

## Contrato y construcción del servicio

- Mantendremos intacto `com.store.inventory.api` y la firma de `Inventory.create(Clock, StockAlertListener)`.
- La fábrica creará un servicio vacío y utilizable sin iniciar Spring. Spring configurará la misma implementación para HTTP.
- El record público `Reservation` será una respuesta del contrato; los estados internos se representarán en el dominio.
- Las reglas de categoría se concentrarán en una tabla de políticas. El enum público limita las categorías aceptadas.

## Estado y consistencia

- Productos, stock, reservas y registros de pedidos se guardan en H2 en memoria; avisos y DLQ usarán la misma base al implementarse. Reiniciar perderá los datos y el historial de idempotencia.
- El estado de cada producto se coordinará para que comprobar disponibilidad y modificar unidades sea una operación atómica.
- El registro de pedidos también protegerá la unicidad de `orderId`, incluso entre solicitudes de productos distintos.
- Las reservas tendrán estados activa, confirmada y vencida. Se conservará la información necesaria para reconocer reintentos.
- Los vencimientos se evaluarán con el `Clock` recibido al consultar disponibilidad o modificar inventario. No habrá una tarea periódica de expiración.

Esta coordinación será local a una instancia. Antes de usar varias réplicas se necesitarán almacenamiento compartido, atomicidad y unicidad de pedidos entre instancias.

## Notificaciones

Las operaciones de inventario determinarán cuándo corresponde un aviso. Su entrega ocurrirá fuera de los bloqueos mediante el listener proporcionado; no integraremos directamente un proveedor de correo.

- Después del intento inicial, hasta cinco reintentos en segundo plano: 2, 4, 8, 16 y 32 segundos, con jitter de ±20 %.
- Un aviso pendiente no generará entregas adicionales por operaciones posteriores. Una entrega exitosa cancelará los reintentos restantes.
- Reabastecer invalidará los reintentos pendientes del aviso anterior y podrá generar uno actualizado según las reglas de negocio.
- Agotar los intentos enviará el aviso a la DLQ, sin revertir el inventario. Se conservarán sus datos, intentos y último error.

La DLQ se guardará en H2 en memoria. Los avisos deberán comprobarse antes de reprocesarlos para evitar entregar información desactualizada. Una llamada ya iniciada no puede retirarse y los reintentos no garantizan una única entrega externa.

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
