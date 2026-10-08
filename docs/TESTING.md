# Verificación

## Suite Java

```powershell
mvn test
```

Requiere Java 21 y Maven 3.6.3 o posterior. Los tests de integración arrancan servidores en puertos aleatorios y necesitan conexiones locales habilitadas. Los tres tests originales se conservan sin modificaciones.

## Cobertura de comportamientos

- Productos, identificadores y stock: `ProductTest`, `ProductInventoryTest`, `ProductStockServiceTest` e `InventoryFactoryTest`.
- Políticas, idempotencia y estados: `CategoryPoliciesTest`, `OrderReservationTest`, `ReservationServiceTest` y `ConfirmationServiceTest`.
- H2, integridad y transacciones: pruebas de `infrastructure.jpa`, incluidas reversión, precisión temporal y conflictos entre repositorios.
- Concurrencia: `InventoryConcurrencyTest` verifica servicios independientes sobre la misma base, sobreventa, pedidos duplicados, lecturas coherentes, productos independientes y cierre en curso.
- Avisos, jitter, cancelación y DLQ: `StockAlertIntegrationTest`, `AlertRetryPolicyTest`, `LowStockPolicyTest` y `JpaStockAlertRepositoryTest`.
- HTTP y mensajes seguros: `InventoryApiIntegrationTest`, `InventoryApiFailureTest` y `ApiErrorControllerTest`, incluidos `400`, `404`, `409` y `500` con solo cuatro campos.
- Correlación: `TraceContextTest` y `AlertTracePropagationTest` verifican limpieza de MDC y propagación al trabajador de reintentos.
- Seed y documentación: `DemoSeedTest`, `DocumentationAndDemoTest` y `PostmanContractTest`.

El reloj y el planificador manuales prueban vencimientos y esperas de 2 a 32 segundos sin dormir esos intervalos. Los tests de carreras usan coordinación explícita entre hilos y esperas acotadas para verificar bloqueos.

## Postman y documentación

Ejecutar la [colección](../postman/README.md) con la API activa. Son 40 solicitudes y 142 aserciones, complementarias a JUnit. OpenAPI se exporta del servicio real y se compara automáticamente con la versión guardada.

Los reportes de Maven están en `target/surefire-reports`. Los artefactos generados de OpenAPI y Newman también quedan en `target/`.

## Límites de la verificación

Las pruebas cubren H2 y procesos locales. Una migración a una base persistente requiere pruebas con ese motor y su configuración, además de pruebas de varias instancias y entrega externa de avisos. No se declaran esos escenarios como validados por esta suite.
