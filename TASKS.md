# Tareas de implementación

Checklist basada en [DECISIONS.md](DECISIONS.md). Las tareas completadas se marcan abajo. Las pruebas se agregarán junto con cada funcionalidad.

## Negocio y arquitectura

- [x] **1. Preparar Spring Boot y DDD.** Configurar Java 21 y Maven, separar dominio, aplicación e infraestructura y preservar el contrato público y la firma de `Inventory.create(...)`.
- [x] **2. Modelar el dominio.** Definir productos, reservas, estados y la tabla de políticas de las tres categorías, sin depender de Spring.
- [x] **3. Implementar almacenamiento en H2 en memoria con JPA.** Guardar productos, stock, reservas y registros de pedidos con entidades separadas del dominio. La fábrica deberá crear una base vacía y aislada sin arrancar Spring.
- [x] **4. Implementar productos y stock.** Registro, reabastecimiento, disponibilidad y validaciones; rechazar SKU duplicados e identificadores inválidos y respetar las excepciones del contrato.
- [x] **5. Implementar reservas e idempotencia.** Aplicar límites y disponibilidad, devolver reservas originales ante reintentos y rechazar cambios de datos. Los rechazos por falta de stock no consumirán el identificador.
- [ ] **6. Implementar confirmaciones y vencimientos.** Usar `Clock`, liberar reservas vencidas durante las operaciones y cubrir el instante exacto de expiración. Confirmar repetidamente no volverá a descontar unidades; una reserva vencida no se reactivará.
- [ ] **7. Proteger la concurrencia.** Garantizar operaciones atómicas por producto y unicidad de pedidos, incluso entre solicitudes de distintos productos. Entregar notificaciones fuera de los bloqueos.

## Notificaciones

- [ ] **8. Implementar avisos de stock bajo.** Notificar mediante `StockAlertListener` con 5 unidades disponibles o menos, una vez por ciclo de reabastecimiento. Registrar productos sin stock no generará avisos.
- [ ] **9. Implementar reintentos y cancelaciones.** Guardar avisos y su estado de entrega en H2. Ejecutar hasta cinco reintentos en segundo plano con esperas de 2, 4, 8, 16 y 32 segundos y jitter de ±20 %. Evitar avisos pendientes duplicados y cancelarlos al reabastecer, evaluando la disponibilidad actual.
- [ ] **10. Implementar la DLQ en H2.** Guardar avisos que agoten los intentos con contexto y último error, detener sus reintentos y comprobar su vigencia antes de reprocesarlos. Los fallos de avisos no revertirán el inventario.

## API y observabilidad

- [ ] **11. Implementar los cinco endpoints REST.** Registrar productos, agregar stock, consultar disponibilidad, reservar y confirmar, delegando en el mismo `InventoryService`.
- [ ] **12. Unificar respuestas y errores.** Devolver únicamente `success`, `message`, `data` y `traceId` en JSON. Centralizar errores con `@RestControllerAdvice`, cubrir errores de Spring MVC y rutas inexistentes, respetar los estados HTTP y no usar `204`.
- [ ] **13. Incorporar logs y correlación.** Propagar `traceId` también a tareas en segundo plano, registrar eventos con niveles adecuados y guardar detalles técnicos únicamente en logs, sin datos sensibles ni trazas duplicadas.

## Pruebas y documentación

- [ ] **14. Crear la seed.** Carga explícita en H2 mediante la API Java, con las tres categorías, stock variado, reservas activas y pedidos confirmados. Usar reloj controlado y listener de demostración; documentar su ejecución.
- [ ] **15. Completar la cobertura automatizada.** Conservar los tests originales y cubrir reglas, validaciones, idempotencia, vencimientos, concurrencia, avisos, cancelaciones, jitter y DLQ. Usar reloj y planificador controlables; verificar JSON y mensajes seguros en la API, incluidos `400`, `409`, `404` y `500`. Todo deberá pasar con `mvn test`.
- [ ] **16. Documentar y preparar Postman.** Crear OpenAPI, Swagger UI, colección importable, entorno local, ejemplos y guía. Alinear contratos y estados HTTP, ejecutar los escenarios contra la aplicación y actualizar la documentación según lo implementado.

## Infraestructura

- [ ] **17. Preparar Docker, configuración y Kubernetes.** Crear Dockerfile de varias etapas con usuario sin privilegios, `.dockerignore` y `.env.example` documentado; excluir `.env` de Git. Incluir Deployment de una réplica, Service, ConfigMaps, referencias a Secrets, recursos y sondas de salud. Documentar la pérdida de datos al reiniciar.
- [ ] **18. Configurar GitHub Actions.** Ejecutar tests antes de construir la imagen y preparar su publicación con etiquetas de versión y commit, variables y secretos. Verificar el flujo; el registro de destino y el despliegue automático quedan pendientes de conocer el entorno.

## Pendientes antes de producción

- Validar con el equipo la confirmación idempotente, cuya interpretación difiere de la lectura literal del contrato.
- Migrar de H2 en memoria a una base persistente con migraciones de esquema, retención de pedidos y atomicidad y unicidad entre instancias.
- Persistir avisos y DLQ, deduplicar entregas y definir monitoreo y reprocesamiento.
- Definir el registro y el entorno de despliegue antes de habilitar publicación o despliegue efectivos.
