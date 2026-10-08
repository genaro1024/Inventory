# Decisiones de implementación

## Reintentos de un pedido con una reserva activa

La app puede reenviar un pedido si tarda en recibir una respuesta. Si el pedido llega con el mismo identificador (`orderId`), producto y cantidad, y su reserva sigue activa, devolveremos la reserva original. No apartaremos más unidades ni cambiaremos su fecha de vencimiento.

Así, los reintentos no duplican las reservas y el cliente conserva el plazo inicial para pagar, sin que cada reenvío lo prolongue.

## Reutilización de un identificador con datos distintos

Cada identificador (`orderId`) corresponde a un pedido de un solo producto y una cantidad concreta. Si llega otra solicitud con ese identificador, pero con un producto o una cantidad diferente, la rechazaremos con `IllegalArgumentException` y conservaremos la reserva original sin cambios.

Un cambio en esos datos no se considera un reintento del mismo pedido. Para solicitar otro producto o una cantidad distinta, se debe usar un nuevo identificador.

## Reintentos de un pedido cuya reserva venció

Si se reenvía un pedido cuya reserva ya venció, rechazaremos la solicitud y no crearemos otra reserva con el mismo identificador. Para volver a reservar, el cliente debe iniciar un nuevo pedido con otro `orderId`, sujeto al stock disponible en ese momento.

Queremos tolerar los reintentos sin repetir sus efectos sobre el inventario. Por eso, un reenvío no reactiva una reserva vencida ni abre un nuevo plazo para pagar. Conservaremos el registro del pedido vencido para reconocer esos reintentos; su retención y limpieza deberán definirse antes de producción.

## Reintentos de confirmación de un pedido ya confirmado

Si recibimos otra confirmación de un pedido que ya fue confirmado, la operación terminará exitosamente sin cambiar el inventario. Las unidades se consideran vendidas desde la primera confirmación y no se descuentan nuevamente.

Esta decisión permite tolerar los reintentos de confirmación. Interpretamos la excepción por falta de una reserva activa como aplicable a pedidos desconocidos o con reservas vencidas, y hacemos una excepción para los que ya fueron confirmados. El contrato no expresa esta excepción, por lo que debemos validar esta interpretación con el equipo antes de producción.

## Instante de vencimiento de una reserva

Una reserva se considera vencida cuando la hora actual es igual o posterior a su fecha de expiración. El pago debe confirmarse antes de ese instante; si la confirmación llega justo al vencimiento, la rechazaremos con `IllegalStateException` y las unidades quedarán disponibles para otros pedidos.

Usaremos el `Clock` recibido por el servicio para evaluar este límite, de modo que todas las operaciones sigan el mismo criterio y podamos probarlo sin depender del tiempo real.

## Liberación de reservas vencidas

Procesaremos las reservas vencidas al consultar la disponibilidad o realizar una operación que pueda afectar el inventario. Antes de calcular las unidades disponibles o confirmar un pedido, evaluaremos los vencimientos con el `Clock` del servicio.

Elegimos este enfoque porque mantiene sencilla la implementación en memoria y no requiere administrar una tarea en segundo plano. La validez de una reserva depende de su fecha de expiración, sin esperar a la siguiente ejecución de un proceso periódico.

Si no hay operaciones, la limpieza del estado puede quedar pendiente, pero en la siguiente operación las reservas vencidas ya no bloquearán unidades. Esto también significa que no garantizamos un aviso de stock disparado únicamente por el paso del tiempo; si el negocio necesita avisos sin actividad en el servicio, revisaremos esta decisión antes de producción.

## Nuevos avisos después de un reabastecimiento

Cada llamada válida a `addStock` se considera un reabastecimiento y habilita un nuevo aviso de stock bajo para ese producto. Después de agregar las unidades, si quedan 5 o menos disponibles, enviaremos un aviso con esa cantidad. Si quedan más de 5, el aviso se enviará cuando la disponibilidad vuelva a bajar hasta el umbral.

Una vez enviado el aviso, no lo repetiremos hasta el siguiente reabastecimiento. Por ejemplo, si ya avisamos cuando quedaban 2 unidades y luego se agrega 1, enviaremos un nuevo aviso indicando que hay 3 disponibles. Interpretamos así la condición del enunciado de no repetir el aviso mientras el producto no se reabastezca.
