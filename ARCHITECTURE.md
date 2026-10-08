# Arquitectura prevista

Diseño técnico acordado. La base de Spring Boot y los paquetes de las capas están preparados; las funcionalidades restantes siguen pendientes. Las reglas están en [BUSINESS_RULES.md](BUSINESS_RULES.md), las decisiones y limitaciones en [DECISIONS.md](DECISIONS.md), y el avance en [TASKS.md](TASKS.md).

## Organización

Usaremos Java 21, Spring Boot y DDD ligero, con las siguientes responsabilidades:

- **Dominio:** productos, inventario, reservas, estados, políticas por categoría y condiciones para generar avisos. No dependerá de Spring, HTTP ni almacenamiento.
- **Aplicación:** coordinación de registro, reabastecimiento, disponibilidad, reserva y confirmación; acceso al almacenamiento y coordinación de operaciones concurrentes.
- **Infraestructura:** almacenamiento en memoria, planificación de reintentos, entrega mediante `StockAlertListener` y DLQ.
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

Los paquetes de las capas se documentan con `package-info.java`; sus clases se agregarán en las siguientes tareas. Maven utiliza Spring Boot 4.1.1 y compila para Java 21.

### Verificación de la base

Requiere JDK 21 y Maven 3.6.3 o posterior; `JAVA_HOME` debe apuntar al JDK.

- `mvn -Dtest=InventoryApplicationTest test`: comprueba el arranque del servidor en un puerto aleatorio.
- `mvn spring-boot:run`: inicia la aplicación base; todavía no expone los endpoints de inventario.
- `mvn test`: ejecuta todos los tests. Los tres originales aún fallan porque `Inventory.create(...)` conserva su `TODO`; se resolverán al implementar el servicio.

La prueba de arranque requiere conexiones locales habilitadas en el entorno de ejecución.

## Contrato y construcción del servicio

- Mantendremos intacto `com.store.inventory.api` y la firma de `Inventory.create(Clock, StockAlertListener)`.
- La fábrica creará un servicio vacío y utilizable sin iniciar Spring. Spring configurará la misma implementación para HTTP.
- El record público `Reservation` será una respuesta del contrato; los estados internos se representarán en el dominio.
- Las reglas de categoría se concentrarán en una tabla de políticas. El enum público limita las categorías aceptadas.

## Estado y consistencia

- Productos, stock, reservas, registros de pedidos y DLQ se almacenarán en memoria. Reiniciar perderá los datos y el historial de idempotencia.
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

La DLQ será en memoria. Los avisos deberán comprobarse antes de reprocesarlos para evitar entregar información desactualizada. Una llamada ya iniciada no puede retirarse y los reintentos no garantizan una única entrega externa.

## HTTP y observabilidad

El adaptador REST expondrá las cinco operaciones documentadas en `DECISIONS.md`. Tanto los éxitos como los errores usarán `application/json` con únicamente `success`, `message`, `data` y `traceId`; los estados se comunicarán mediante HTTP y no se usará `204`.

Un `@RestControllerAdvice` centralizará la traducción de excepciones, incluyendo errores de Spring MVC y rutas inexistentes. La API devolverá mensajes aptos para clientes y conservará los detalles técnicos en logs.

El `traceId` relacionará respuestas, logs y notificaciones en segundo plano. Los niveles serán `INFO` para eventos relevantes, `WARN` para fallos recuperables, `ERROR` para fallos inesperados o paso a DLQ y `DEBUG` para diagnóstico.

## Pruebas y demostración

- Conservar los tests originales y ampliar cobertura de dominio, aplicación, concurrencia, notificaciones y REST.
- Usar reloj y planificador controlables para comprobar vencimientos y reintentos sin esperas reales.
- Cargar la seed explícitamente mediante la API Java, con las tres categorías y pedidos en distintos estados, usando un reloj controlado.
- Crear OpenAPI, Swagger UI y Postman durante la implementación; verificar que coincidan con las respuestas reales.

## Empaquetado y ejecución

- Docker con construcción en varias etapas, usuario sin privilegios y `.dockerignore`.
- `.env.example` documentado; configuración mediante variables, sin guardar credenciales reales ni archivos `.env` locales en Git.
- Kubernetes con una réplica, Service, ConfigMaps, referencias a Secrets, recursos y sondas de salud.
- GitHub Actions ejecutará `mvn test` antes de construir la imagen y quedará preparado para publicarla con etiquetas de versión y commit.

El registro de imágenes y el despliegue automático quedan pendientes de definir el entorno. La persistencia, retención de pedidos y entrega durable de avisos son cambios previos a producción.
