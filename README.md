# Reservas de inventario

Microservicio de inventario con Java 21, Spring Boot y DDD ligero. Reserva unidades mientras el cliente paga, confirma ventas y libera reservas vencidas. Productos, pedidos, avisos y DLQ se guardan en H2 en memoria.

## Ejecutar

Requiere JDK 21 y Maven 3.6.3 o posterior. `mvn -version` debe mostrar Java 21; configurar `JAVA_HOME` según el JDK instalado.

```powershell
mvn test
mvn spring-boot:run
```

La API inicia en `http://localhost:8080` con el inventario vacío. Al reiniciar se pierden los datos. Los avisos locales se reciben mediante un listener que escribe en logs; puede reemplazarse por una integración de correo u otro canal.

## Demostración con datos

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Carga cinco productos, tres reservas activas y tres pedidos confirmados. Usa un reloj fijo y un listener de demostración. La [guía de la seed](docs/SEED.md) explica los datos y cómo simular vencimientos sin esperar.

Para avanzar 16 minutos después de cargar los pedidos:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=demo" "-Dspring-boot.run.arguments=--demo.advance-by=PT16M"
```

## Consumir la API

- [Swagger UI](http://localhost:8080/swagger-ui.html): explorar y ejecutar las operaciones.
- [OpenAPI generado](http://localhost:8080/v3/api-docs) y [exportación verificada](docs/openapi.json).
- [Contrato HTTP](docs/API.md): solicitudes, respuestas, validaciones y estados.
- [Postman](postman/README.md): importación y ejecución de 40 solicitudes con 142 aserciones.

Las rutas son:

- `POST /products`: registrar producto.
- `POST /products/{sku}/stock`: agregar unidades.
- `GET /products/{sku}/availability`: consultar disponibilidad.
- `POST /reservations`: reservar unidades.
- `POST /reservations/{orderId}/confirm`: confirmar pedido pagado.

Todas las respuestas de negocio contienen únicamente `success`, `message`, `data` y `traceId`. El frontend puede leer siempre `message`; los detalles técnicos quedan en logs. `X-Trace-Id` permite correlacionar solicitudes y notificaciones.

## Reglas principales

- Cada pedido corresponde a un producto y una cantidad. Los reintentos no duplican reservas ni ventas y conservan el vencimiento original.
- `STANDARD`: 15 minutos para pagar, sin límite por categoría.
- `PRE_ORDER`: 24 horas, sin límite por categoría.
- `FLASH_SALE`: 5 minutos y máximo 2 unidades por pedido.
- Todas las reservas dependen del stock disponible. Al vencer, sus unidades quedan libres; las confirmadas permanecen vendidas.
- Con 5 unidades disponibles o menos se crea un aviso, sin repetirlo hasta reabastecer.
- Los avisos se entregan después del commit. Si fallan, hay cinco reintentos con esperas de 2, 4, 8, 16 y 32 segundos y jitter de ±20 %; después pasan a DLQ.

Los detalles están en [BUSINESS_RULES.md](BUSINESS_RULES.md).

## Contrato Java y pruebas

Se conservan sin cambios el paquete `com.store.inventory.api`, la firma de `Inventory.create(Clock, StockAlertListener)` y los tres tests originales. La fábrica puede usarse sin arrancar Spring y crea su propia H2 vacía y aislada; la implementación es `AutoCloseable` para liberar recursos.

La API HTTP usa el mismo servicio con la base configurada por Spring. La seed nunca se carga automáticamente desde la fábrica.

[TESTING.md](docs/TESTING.md) describe las pruebas de negocio, H2, concurrencia, notificaciones, HTTP y documentación. `mvn test` compara además el OpenAPI generado y las rutas de Postman con los archivos del repositorio.

## Configuración y alcance

- `SERVER_PORT`: puerto HTTP, por defecto 8080.
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: conexión de la aplicación; el valor local por defecto es H2 en memoria.
- `demo.initial-time` y `demo.advance-by`: tiempo controlado del perfil de demostración.

Actualmente el esquema se crea y elimina al iniciar y cerrar. Una base persistente necesita migraciones y verificación con el motor elegido, no solo cambiar la URL. H2 en memoria no comparte datos entre procesos.

Docker, Kubernetes, `.env.example` y GitHub Actions están preparados. La [guía de despliegue](docs/DEPLOYMENT.md) explica configuración, sondas, ejecución local y publicación opcional en GHCR. Kubernetes usa una réplica con estrategia `Recreate`; el pipeline prueba Java y Postman sobre la imagen antes de permitir publicarla. La publicación permanece desactivada hasta definir el registro.

## Documentación del proyecto

- [DECISIONS.md](DECISIONS.md): decisiones, supuestos y límites.
- [ARCHITECTURE.md](ARCHITECTURE.md): capas, almacenamiento, transacciones y observabilidad.
- [BUSINESS_RULES.md](BUSINESS_RULES.md): reglas identificadas con prefijo BR.
- [TASKS.md](TASKS.md): avance de implementación y pendientes de producción.
- [DEPLOYMENT.md](docs/DEPLOYMENT.md): Docker, Kubernetes, variables y pipeline.
