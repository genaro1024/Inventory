# Colección de Postman

## Archivos

- `Inventory.postman_collection.json`: colección v2.1 con **40 solicitudes** y **142 aserciones** por ejecución completa.
- `Local.postman_environment.json`: entorno local con `baseUrl=http://localhost:8080`.

La colección fue ejecutada contra el JAR local con H2 y el perfil `demo`. Los ejemplos guardados ilustran el contrato; los identificadores y fechas no representan datos actuales del servidor.

## Importar y ejecutar

1. Iniciar la aplicación con `mvn spring-boot:run`, con o sin la [seed](../docs/SEED.md).
2. Importar ambos JSON en Postman y seleccionar **Inventory · Local**.
3. Cambiar `baseUrl` si es necesario, sin barra final.
4. Ejecutar todas las carpetas en su orden, con una iteración y sin pausas ni ejecución paralela.
5. Revisar las aserciones, además de los estados HTTP.

La primera solicitud genera SKU, pedidos y `traceId` únicos. No sobrescribir esas variables en el entorno. Ejecutar solicitudes individuales requiere preparar sus pasos anteriores.

La comprobación de plazos compara las categorías con la reserva STANDARD de la misma ejecución y admite menos de un minuto entre sus creaciones. Para probar paso a paso sin depender del tiempo real, usar `demo` con su reloj fijo. La colección no depende de los SKU de la seed.

## Escenarios

- STANDARD: registro, carga de stock, reserva, reintento, confirmación y repetición de un pedido confirmado.
- FLASH_SALE: plazo de 5 minutos y rechazo de más de 2 unidades.
- PRE_ORDER: plazo de 24 horas.
- Errores: stock insuficiente, recuperación tras reabastecer, datos inválidos, duplicados, pedidos incompatibles y recursos desconocidos.

Todas las respuestas comprueban los cuatro campos JSON y la coincidencia de `traceId` con el encabezado. El stock se verifica después de éxitos y rechazos para detectar efectos adicionales.

Cada ejecución agrega datos nuevos. No hay endpoints de eliminación; reiniciar H2 limpia el escenario. No repetir una carga de stock aislada sin ajustar los resultados esperados, porque suma unidades cada vez.

## Ejecutar con Newman

Requiere Node.js compatible con Newman. La verificación de esta entrega usó Newman **6.2.2**.

```powershell
npx --yes newman@6.2.2 run postman/Inventory.postman_collection.json -e postman/Local.postman_environment.json
```

Para otro puerto:

```powershell
npx --yes newman@6.2.2 run postman/Inventory.postman_collection.json -e postman/Local.postman_environment.json --env-var "baseUrl=http://localhost:8081"
```

Los reportes de la verificación local se guardan en `target/postman-report.json` y `target/postman-validation.log`; son archivos generados, fuera de Git.

## Alcance

El `500` controlado, el instante exacto de vencimiento, la concurrencia, los reintentos de notificaciones y la DLQ se verifican con pruebas Java. No se agregaron endpoints de fallo artificial ni para cambiar el reloj, ni se afirma que Postman observe la entrega interna de notificaciones.

El contrato está en [API.md](../docs/API.md) y [openapi.json](../docs/openapi.json). `PostmanContractTest` comprueba rutas y variables de la colección; Newman valida los scripts y las respuestas reales.
