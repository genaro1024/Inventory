# Guía para base persistente y Kubernetes en producción

## Alcance y estado actual

GitHub Actions ejecuta las pruebas, construye la imagen, verifica Postman contra el contenedor y publica en GHCR desde la rama predeterminada o etiquetas `v*`. El despliegue a Kubernetes está únicamente indicado: no hay un job que acceda al clúster.

Esta guía describe los cambios pendientes para producción. La imagen actual sigue usando H2 en memoria, `create-drop`, un listener de avisos que escribe en logs y planificación local de reintentos. Configurar secretos y cambiar la URL no completa una migración a otra base.

Se usa PostgreSQL como ejemplo; el motor definitivo puede ser otro. Mantener el contrato público Java, las reglas de negocio y el JSON de cuatro campos durante la migración.

## 1. Implementar la persistencia

1. Elegir motor y versión, crear una base por entorno y definir respaldo, restauración y retención. Una base administrada puede vivir fuera del clúster; no ponerla dentro del Pod de la API. Si se aloja en Kubernetes, preparar almacenamiento persistente y una operación de base independiente.
2. Agregar el driver JDBC al `pom.xml`, con alcance runtime. Para PostgreSQL: `org.postgresql:postgresql`, usando la versión gestionada por Spring Boot cuando corresponda. H2 puede conservarse para las pruebas originales y la fábrica aislada.
3. Agregar Flyway o Liquibase y migraciones versionadas. Con Spring Boot 4, revisar el starter y el módulo del motor; para Flyway/PostgreSQL se requieren el soporte de Flyway y `org.flywaydb:flyway-database-postgresql`. No generar el esquema productivo mediante Hibernate. [Inicialización de bases en Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
4. Crear la migración inicial a partir de `ProductInventoryEntity`, `ReservationEntity` y `StockAlertEntity`: tablas `product_inventory`, `reservations` y `stock_alerts`, claves primarias, foráneas, índices, checks y estados. Las políticas de categoría actualmente están en el código; no existe una tabla de políticas.
5. Crear un perfil `prod` que exija URL y credenciales explícitas, configure `spring.jpa.hibernate.ddl-auto=validate` y falle al arrancar si recibe una URL H2 en memoria o configuración incompleta. Mantener `create-drop` únicamente en demostración/pruebas. **Ese perfil y su validación aún deben implementarse**.
6. Revisar mappings dependientes del motor: UUID, `@Lob` de `last_error`, nombres y precisión de timestamps. Actualmente las entidades solicitan nueve decimales; PostgreSQL admite hasta seis. Definir precisión canónica y normalizar instantes antes de guardarlos y compararlos, conservando el vencimiento exacto e idempotencia. [Tipos temporales de PostgreSQL](https://www.postgresql.org/docs/current/datatype-datetime.html).
7. Verificar bloqueos `PESSIMISTIC_WRITE`, actualizaciones condicionales y rollback en el motor elegido. `JpaTransactions` reconoce duplicados mediante SQLSTATE `23505`; si otro motor usa un código diferente, adaptar esa detección y probar carreras entre pedidos.
8. Definir reintentos acotados para deadlocks y fallos de serialización cuando el motor los requiera, sin ejecutar notificaciones dentro de transacciones ni duplicar descuentos.

Separar el usuario de migraciones, con permisos DDL, del usuario de la aplicación, con los permisos necesarios de lectura/escritura. Ejecutar las migraciones una vez por entrega mediante un Job o una etapa controlada. Si se usa ese enfoque, desactivar su ejecución desde cada réplica y mantener `validate` al arrancar. No crear credenciales DDL en el ConfigMap ni en la imagen.

Para datos existentes de demostración, acordar si se descartan o se exportan antes de cerrar el proceso; la H2 actual desaparece al reiniciar. No activar la seed `demo` en producción: además de cargar datos ficticios, usa un reloj fijo.

## 2. Completar notificaciones durables

- Implementar la integración externa de `StockAlertListener` y sus credenciales por entorno; el listener actual solo escribe en logs.
- Conectar la recuperación de avisos pendientes al arranque y agregar un trabajador que consulte periódicamente la base. Existe `resumePending()`, pero guardar los registros no hace que todas las instancias los descubran automáticamente.
- Recuperar entregas que quedaron en estado `DELIVERING` tras una caída mediante una concesión con vencimiento o mecanismo equivalente. Verificar cancelación por reabastecimiento y competencia entre trabajadores.
- Deduplicar en el receptor usando el identificador del aviso: una caída entre entrega externa y confirmación en la base puede repetirlo.
- Definir permisos y procedimiento de consulta/reproceso de DLQ, métricas de fallos, retención y alertas operativas. El reproceso debe respetar el ciclo vigente del producto.

Inicialmente mantener una réplica. Habilitar varias solo después de validar el motor compartido, las operaciones concurrentes y el trabajador durable. El almacenamiento persistente por sí solo no completa esos requisitos.

## 3. Configuración y secretos de GitHub

Crear un GitHub Environment llamado `production`, restringir ramas y configurar revisores cuando el plan y la política del repositorio lo permitan. El futuro job de despliegue debe referenciar ese environment para recibir sus secretos. [Environments y secretos de despliegue](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).

Variables previstas:

- `K8S_NAMESPACE`: namespace, por ejemplo `inventory-production`; crearlo previamente.
- `K8S_CONTEXT`: nombre exacto del contexto autorizado en el kubeconfig.
- `K8S_DEPLOYMENT`: nombre del Deployment, actualmente `inventory`.
- `K8S_OVERLAY`: ruta del futuro overlay productivo, por ejemplo `k8s/overlays/production`; aún no existe.

Secretos previstos:

- `KUBE_CONFIG_B64`: kubeconfig codificado en base64, con acceso limitado al namespace. Base64 es transporte, no cifrado. El runner debe poder alcanzar el API server; para un clúster privado puede requerirse un runner dentro de la red. Si el proveedor admite identidad federada/OIDC, usar ese método en lugar de una credencial permanente y ajustar el job al proveedor.
- `DB_URL`: URL JDBC del motor persistente, incluyendo TLS según el proveedor. Se propone tratarla como secreto para no exponer datos de conexión.
- `DB_USERNAME` y `DB_PASSWORD`: usuario de aplicación, sin permisos de migración.
- `GHCR_PULL_USERNAME` y `GHCR_PULL_TOKEN`: solo para paquetes privados; token de lectura, por ejemplo PAT classic con `read:packages` y acceso al paquete. No reutilizar el `GITHUB_TOKEN` temporal del workflow como credencial permanente del clúster. [Autenticación de GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).
- Credenciales del proveedor de notificaciones y de migración: definir nombres al elegir esas integraciones; entregarlas únicamente al componente que las necesita.

La publicación en GHCR usa el `GITHUB_TOKEN` automático con `packages: write`. No requiere `PUBLISH_IMAGE` ni un secreto personal de escritura. Si el paquete ya existe, conceder acceso al repositorio desde sus ajustes. Un paquete privado necesita permisos de lectura en el clúster.

## 4. Preparar un overlay productivo

Crear `k8s/overlays/production` una vez implementada y probada la migración. Los manifiestos actuales son de demostración; no aplicarlos directamente con datos de producción.

- Cambiar la imagen por `ghcr.io/genaro1024/inventory@sha256:DIGEST_REAL` de la entrega validada. No usar `latest` para producción.
- Eliminar `DB_URL` H2 del ConfigMap productivo y obtener URL, usuario y contraseña del Secret `inventory-db`, claves `url`, `username` y `password`. La referencia `url` todavía debe agregarse al Deployment; actualmente solo usuario y contraseña vienen de Secret.
- Mantener únicamente configuración no sensible en ConfigMap: puerto, logs, memoria y perfil `prod`.
- Agregar `imagePullSecrets: [{name: ghcr-pull}]` para imágenes privadas, en el mismo namespace del Pod. [Imágenes privadas en Kubernetes](https://kubernetes.io/docs/tasks/configure-pod-container/pull-image-private-registry/).
- Conservar usuario sin privilegios, filesystem de solo lectura, `/tmp` temporal, recursos, cierre gradual y sondas. Ajustar memoria, CPU y pool JDBC con pruebas de carga; la suma de conexiones de todas las réplicas debe caber en el límite de la base.
- Mantener una réplica y `Recreate` en la primera entrega. Después de validar convivencia de versiones y múltiples instancias, evaluar `RollingUpdate` y escalado.
- Definir acceso HTTP con TLS, autenticación/autorización de consumidores y políticas de red; actualmente la API no incluye autenticación. Limitar acceso a Swagger según el entorno y enviar logs con `traceId` al sistema de observabilidad.

Ejemplo de variables **para una imagen futura que ya incluya driver, perfil y esquema migrado**, no para la imagen actual:

```properties
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://db.internal:5432/inventory?sslmode=verify-full
DB_USERNAME=<usuario-aplicacion>
DB_PASSWORD=<secreto>
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
SPRING_SQL_INIT_MODE=never
```

Configurar la CA y certificados del proveedor para que la verificación TLS funcione. `validate` comprueba el esquema, no crea tablas. Crear el Secret no sustituye las migraciones.

## 5. Despliegue futuro desde Actions: solo indicado

El workflow tiene comentarios con los parámetros previstos y termina al publicar en GHCR. Para implementar CD posteriormente:

1. Agregar un job que dependa de `publish`, use `environment: production` y tenga concurrencia exclusiva por entorno, sin cancelar un despliegue ya iniciado.
2. Instalar una versión de `kubectl` compatible con el clúster y configurar autenticación. Decodificar el kubeconfig en `$RUNNER_TEMP`, con permisos restrictivos; pasarlo mediante `KUBECONFIG` y eliminarlo al finalizar. No imprimirlo ni usar `set -x` con secretos.
3. Verificar explícitamente `K8S_CONTEXT` y namespace, ejecutar las migraciones controladas y comprobar que terminaron correctamente.
4. Crear/actualizar `inventory-db` y, si hace falta, `ghcr-pull`, desde secretos o un gestor externo. Evitar publicar YAML de Secrets en logs, artefactos o Git; pasar contenido mediante archivos temporales protegidos o entrada estándar y limpiar al finalizar.
5. Resolver el digest de la imagen publicada en esta ejecución y suministrarlo al overlay. Pasar el digest como salida entre jobs cuando se implemente CD; no consultar `latest` para determinar qué desplegar.
6. Renderizar y validar el overlay, aplicar al namespace autorizado y ejecutar `kubectl rollout status` con timeout. Si cambian Secrets/ConfigMaps consumidos como variables, provocar una nueva revisión del Pod para que reciba los valores.
7. Verificar readiness, persistencia y una prueba funcional con datos controlados. Usar staging para la colección Postman completa, porque crea productos y pedidos de prueba.

Referencia de comandos, con el contexto y overlay previamente preparados; **no ejecutados por el workflow actual**:

```bash
kubectl --context "$K8S_CONTEXT" -n "$K8S_NAMESPACE" apply --dry-run=server -k "$K8S_OVERLAY"
kubectl --context "$K8S_CONTEXT" -n "$K8S_NAMESPACE" apply -k "$K8S_OVERLAY"
kubectl --context "$K8S_CONTEXT" -n "$K8S_NAMESPACE" rollout status deployment/"$K8S_DEPLOYMENT" --timeout=180s
```

## 6. Pruebas y primera entrega

- Ejecutar los tests originales y los nuevos; agregar integración contra el motor elegido, por ejemplo con Testcontainers, aplicando las migraciones reales.
- Comprobar unicidad de SKU/pedido, rollback de confirmación, idempotencia, vencimiento exacto con la precisión acordada, carreras, deadlocks y avisos/DLQ entre instancias.
- Reiniciar la aplicación y verificar que conserva stock, pedidos y avisos; simular caída durante una entrega y confirmar recuperación sin doble efecto externo.
- Probar pérdida de conexión a la base: readiness debe rechazar tráfico; liveness no debe reiniciar el proceso por esa causa.
- Ensayar backups y restauración, y migración de una versión anterior con datos. Medir conexiones, memoria, latencia, reintentos y crecimiento de tablas.
- Publicar y desplegar primero en staging, verificar el digest y obtener aprobación de producción según la política del equipo.

Para recuperación, conservar el digest anterior y usar migraciones compatibles entre versiones. Volver a una imagen anterior no revierte el esquema ni los datos; un cambio incompatible requiere un plan específico. Restaurar un backup puede perder operaciones posteriores, por lo que debe seguir un procedimiento acordado.

## Resultado esperado

La primera entrega productiva mantiene una réplica y datos durables en una base respaldada, con esquema versionado, recuperación de avisos, imagen identificada por digest y configuración por entorno. Varias réplicas y despliegues sin interrupción se habilitan después de probar esos comportamientos. Esta guía no certifica que esas capacidades ya estén implementadas.
