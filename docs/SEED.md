# Datos de demostración

La seed se carga únicamente al iniciar con el perfil `demo`. Usa las operaciones de `InventoryService` y guarda los datos en H2. El arranque normal y `Inventory.create(Clock, StockAlertListener)` mantienen un inventario vacío.

## Ejecutar la demostración

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
```

El reloj de demostración comienza en `2026-10-08T12:00:00Z` y permanece fijo. El listener de este perfil registra los avisos en logs. No es un perfil para producción.

## Escenario inicial

- `DEMO-STANDARD`: categoría `STANDARD`, carga de 20 unidades, reserva activa de 3 y pedido confirmado de 2. Disponibles: **15**.
- `DEMO-PREORDER`: categoría `PRE_ORDER`, carga de 30 unidades, reserva activa de 5 y pedido confirmado de 3. Disponibles: **22**.
- `DEMO-FLASH`: categoría `FLASH_SALE`, carga de 8 unidades, reserva activa de 2 y pedido confirmado de 2. Disponibles: **4**.
- `DEMO-LOW`: categoría `STANDARD`, 4 unidades disponibles; genera un aviso al cargar stock.
- `DEMO-EMPTY`: producto registrado sin stock; tiene cero disponibles y no genera un aviso inicial.

Los pedidos activos se identifican como `<SKU>-ACTIVE`; los confirmados, como `<SKU>-PAID`. Ejemplos: `DEMO-STANDARD-ACTIVE` y `DEMO-FLASH-PAID`. El escenario genera dos avisos: uno para `DEMO-FLASH` y otro para `DEMO-LOW`.

Para consultar los datos, usar `GET /products/DEMO-STANDARD/availability` o las otras rutas documentadas en [API.md](API.md).

## Simular vencimientos

El avance se aplica después de cargar los pedidos, conservando sus fechas originales:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=demo" "-Dspring-boot.run.arguments=--demo.advance-by=PT16M"
```

- `PT6M`: vence la reserva FLASH_SALE; `DEMO-FLASH` queda con 6 disponibles.
- `PT16M`: vencen FLASH_SALE y STANDARD; quedan 6 y 18 disponibles, respectivamente. PRE_ORDER conserva 22.
- `PT25H`: también vence PRE_ORDER; queda con 27 disponibles.

Las unidades de pedidos confirmados no regresan al stock. Los estados vencidos se guardan en la siguiente operación sobre el producto. Se puede cambiar el instante inicial con `--demo.initial-time=2026-10-08T12:00:00Z`.

Desde Java, `DemoClock.advance(Duration)` permite avanzar el reloj sin esperar. No se agregaron endpoints para modificar el tiempo.

## Repetir el escenario

La carga está pensada para ejecutarse una vez sobre una base vacía. Reiniciar la demostración recrea H2 y vuelve a cargar la seed. Intentar cargarla dos veces en la misma instancia se rechaza por SKU duplicado; no se suman unidades silenciosamente.

Postman crea sus propios productos y pedidos, por lo que puede usarse con o sin la seed. Cada reinicio pierde todos los datos porque H2 está en memoria.
