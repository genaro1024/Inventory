# Reglas de negocio del inventario

Reglas previstas para la API, basadas en el [README](README.md), el contrato Java y las decisiones acordadas en [DECISIONS.md](DECISIONS.md). Documentarlas no significa que ya estén implementadas.

## Productos e identificadores

- **BR-01. Identificadores obligatorios.** El SKU identifica un producto y `orderId` identifica un pedido. No pueden ser nulos, vacíos ni contener solo espacios.
- **BR-02. Comparación exacta.** Los identificadores distinguen mayúsculas y minúsculas y se conservan sin normalizarlos.
- **BR-03. Registro único.** Un producto se registra con su categoría. Registrar un SKU existente se rechaza con “El producto ya existe”, sin cambiar su categoría, stock ni reservas.
- **BR-04. Reabastecimiento.** Solo se puede agregar stock a productos registrados, con cantidades positivas. Agregar unidades no requiere registrar nuevamente el producto.

## Stock disponible

- **BR-05. Disponibilidad.** Las unidades disponibles son las ingresadas menos las vendidas y las comprometidas en reservas activas. Una reserva vencida deja de comprometer unidades.
- **BR-06. Productos desconocidos.** Consultar un SKU válido pero desconocido devuelve cero unidades disponibles. Intentar reservarlo se rechaza por falta de stock.
- **BR-07. Sin sobreventa.** No se pueden reservar más unidades de las disponibles. Ante solicitudes simultáneas, una misma unidad no puede asignarse a dos pedidos.
- **BR-08. Cantidades válidas.** La cantidad de una reserva debe ser positiva. Un intento rechazado no modifica el inventario.

## Categorías

- **BR-09. STANDARD.** El plazo para pagar es de 15 minutos, sin límite de unidades por categoría.
- **BR-10. PRE_ORDER.** El plazo para pagar es de 24 horas, sin límite de unidades por categoría.
- **BR-11. FLASH_SALE.** El plazo para pagar es de 5 minutos y el máximo es de 2 unidades por pedido.

No tener límite por categoría permite solicitar cualquier cantidad positiva que esté disponible; no permite reservar sin stock.

## Pedidos y reintentos

- **BR-12. Un producto por pedido.** Cada `orderId` corresponde a un producto y una cantidad concreta. Reutilizarlo con datos distintos se rechaza y conserva el pedido original.
- **BR-13. Reserva activa repetida.** Reenviar el mismo pedido devuelve su reserva original, sin apartar más unidades ni renovar el plazo para pagar.
- **BR-14. Pedido confirmado repetido.** Solicitar otra vez su reserva con los mismos datos devuelve la original y mantiene el pedido confirmado, aunque su fecha de expiración original ya haya pasado.
- **BR-15. Pedido vencido.** Reenviar un pedido cuya reserva venció se rechaza. Para reservar otra vez se necesita un nuevo `orderId`, sujeto al stock disponible.
- **BR-16. Rechazo por falta de stock.** Si no se creó una reserva, el identificador no queda consumido. El mismo pedido puede volver a intentarlo después de un reabastecimiento.

## Confirmación y vencimiento

- **BR-17. Reserva temporal.** Reservar aparta las unidades inmediatamente, sin venderlas todavía. El plazo se calcula desde la creación de la reserva según la categoría.
- **BR-18. Límite de tiempo.** La reserva vence cuando la hora actual es igual o posterior a su fecha de expiración. Desde ese instante no puede confirmarse y sus unidades quedan disponibles.
- **BR-19. Pago confirmado.** La confirmación de una reserva activa convierte sus unidades en vendidas. No se descuentan por segunda vez ni regresan al stock por vencimiento.
- **BR-20. Confirmación repetida.** Confirmar un pedido ya confirmado termina correctamente sin modificar el inventario.
- **BR-21. Confirmación inválida.** Confirmar un pedido desconocido o con una reserva vencida se rechaza.

La BR-20 es una decisión de idempotencia acordada. Debe validarse con el equipo porque el contrato exige una reserva activa y no contempla explícitamente una confirmación repetida exitosa.

## Avisos a compras

- **BR-22. Umbral.** Se genera un aviso cuando un producto tiene 5 unidades disponibles o menos. Registrar un producto con cero unidades no avisa; la primera evaluación ocurre al cargar stock.
- **BR-23. Sin repetición.** Una vez generado el aviso, no se genera otro para ese producto hasta el siguiente reabastecimiento. Recuperar disponibilidad por vencimiento no equivale a reabastecer.
- **BR-24. Nuevo ciclo.** Cada agregado válido de stock habilita un nuevo aviso. Si quedan 5 unidades o menos, se genera inmediatamente; si quedan más, se espera a que la disponibilidad baje al umbral.
- **BR-25. Avisos desactualizados.** Reabastecer cancela los reintentos pendientes del aviso anterior. Si siguen quedando 5 unidades o menos, se genera uno nuevo con la cantidad actualizada. Una entrega ya iniciada no puede retirarse.
- **BR-26. Independencia del inventario.** Un fallo al entregar el aviso no revierte una reserva, confirmación ni reabastecimiento válido.

Los mecanismos de entrega, reintentos y DLQ, así como respuestas HTTP, logs e infraestructura, se describen en las decisiones técnicas de [DECISIONS.md](DECISIONS.md).
