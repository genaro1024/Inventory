# Docker, Kubernetes y GitHub Actions

## Configuración local

Copiar `.env.example` a `.env` y ajustar los valores. `.env` queda fuera de Git y del contexto Docker. Spring Boot recibe variables del entorno y no carga ese archivo automáticamente.

```powershell
Copy-Item .env.example .env
mvn test
docker build -t inventory:local .
docker run --rm --name inventory-local --env-file .env -p 8080:8080 --memory 768m --read-only --tmpfs /tmp:rw,noexec,nosuid,size=128m --cap-drop ALL --security-opt no-new-privileges inventory:local
```

- `SERVER_PORT`: puerto dentro del contenedor; conservar 8080 para Kubernetes. Para otro puerto del host, usar por ejemplo `-p 8081:8080`.
- `SPRING_PROFILES_ACTIVE`: vacío inicia sin datos; `demo` carga la seed y usa el reloj fijo de [SEED.md](SEED.md). Usar el perfil normal para observar el tiempo real.
- `DB_URL`: H2 en memoria, independiente por proceso. Cambiar la URL no basta para habilitar persistencia: Hibernate usa `create-drop`.
- `DB_USERNAME` y `DB_PASSWORD`: ejemplos locales de H2; Kubernetes los obtiene de un Secret.
- `JAVA_TOOL_OPTIONS`: heap máximo de 65 % de la memoria disponible y salida ante falta de memoria. El resto queda para la JVM y sus buffers.
- `LOGGING_LEVEL_ROOT`: nivel de logs, por defecto `INFO`.

El Dockerfile compila con Maven y JDK 21; la etapa final contiene JRE 21 y el JAR. Ejecuta con UID/GID 10001 y temporales en `/tmp`. CI ejecuta tests antes de construir; el contexto incluye solo `pom.xml` y `src/main`. Para entregas reproducibles en producción, fijar los digests de las imágenes base y gestionar sus actualizaciones.

Para detenerlo: `docker stop inventory-local`. El cierre HTTP es gradual, con hasta 20 segundos para la fase de Spring. Al detener o reemplazar el proceso se pierden productos, pedidos, avisos y DLQ.

## Sondas

- `GET /health/liveness`: `200` cuando Spring declara el proceso saludable; `503` si está marcado como averiado. No depende de H2 para evitar reinicios por una falla de conexión.
- `GET /health/readiness`: `200` después del arranque si Spring acepta tráfico y una conexión a la base es válida; `503` durante arranque, cierre o indisponibilidad de la base.

Ambas responden únicamente `success`, `message`, `data: null` y `traceId`, con `X-Trace-Id`. No publican diagnósticos. Son rutas operativas separadas de las cinco operaciones de OpenAPI.

## Kubernetes

La base en memoria requiere **una réplica**. El Deployment usa `Recreate`: elimina el proceso anterior antes de iniciar el nuevo e introduce una interrupción al actualizar. No configurar HPA ni aumentar réplicas hasta implementar almacenamiento compartido. Reemplazar un Pod pierde sus datos, incluso al revertir la imagen.

- ConfigMap para configuración sin credenciales.
- Referencias obligatorias al Secret `inventory-db`, claves `username` y `password`; crearlo fuera del repositorio.
- Deployment con límites de CPU/memoria, usuario sin privilegios, filesystem de solo lectura y volumen temporal `emptyDir`.
- Sondas de arranque, vida y disponibilidad; 180 segundos de tolerancia de arranque.
- Service interno `ClusterIP`, puerto 80 hacia 8080. La exposición pública depende del entorno elegido.

Antes de aplicar, revisar el contexto, elegir un namespace de demostración y sustituir `image` en `k8s/deployment.yaml` por una etiqueta de commit o un digest real. `inventory:local` es un marcador para una imagen cargada en un clúster local. También puede usarse una transformación `images` en `k8s/kustomization.yaml`.

```powershell
kubectl config current-context
kubectl create namespace inventory-demo
# Solo ejemplos locales de H2; no credenciales de producción.
kubectl -n inventory-demo create secret generic inventory-db --from-literal=username=sa --from-literal=password=
kubectl kustomize k8s
# Aplicar después de configurar la imagen y revisar el contexto.
kubectl -n inventory-demo apply -k k8s
kubectl -n inventory-demo rollout status deployment/inventory --timeout=180s
kubectl -n inventory-demo port-forward service/inventory 8080:80
```

Si el paquete GHCR es privado, crear un Secret de tipo `kubernetes.io/dockerconfigjson` con credenciales de lectura y referenciarlo en `spec.template.spec.imagePullSecrets`. No guardar tokens en manifiestos. El ejemplo inicia vacío; para cargar seed, ajustar `SPRING_PROFILES_ACTIVE` en el ConfigMap y reiniciar el Deployment.

## Pipeline

`.github/workflows/container.yml` corre en pull requests, pushes de ramas, etiquetas `v*` y ejecución manual:

1. Configura Temurin 21, ejecuta `mvn clean verify` y conserva reportes Java.
2. Si pasa, construye y carga la imagen con Buildx y caché.
3. Prueba la imagen con filesystem de solo lectura y límites del ejemplo; comprueba sondas y usuario, y ejecuta 40 solicitudes y 142 aserciones de Postman con Newman 6.2.2. Conserva logs y reporte JUnit incluso ante fallos.
4. Si pasa todo y la ejecución corresponde a la rama predeterminada o una etiqueta `v*`, transfiere la misma imagen probada al job de publicación y la envía a GHCR.

Las acciones están fijadas a commits; sus versiones mayores aparecen en comentarios. Solo el job de publicación recibe `packages: write`. Los pull requests nunca publican ni inician sesión en el registro.

La publicación en **GHCR está habilitada**. No requiere `PUBLISH_IMAGE`. El workflow usa `GITHUB_TOKEN`, generado por GitHub, con `packages: write`; no necesita un PAT adicional. El paquete y la organización deben permitir escritura desde el repositorio. Si el paquete ya existe, revisar su acceso desde Actions. [Autenticación y permisos de GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

El nombre se deriva del repositorio en minúsculas: `ghcr.io/genaro1024/inventory`. Publica desde la rama predeterminada o una etiqueta de versión:

- `sha-<commit completo>`: identifica el commit.
- `1.0.0`: al recibir una etiqueta SemVer `v1.0.0`; coordinarla con la versión del `pom.xml`.
- `latest`: solo desde la rama predeterminada.

El prefijo `v*` activa el flujo, pero solo SemVer produce una etiqueta de versión. Usar commits o digests para despliegues reproducibles. Los artefactos intermedios de imagen duran un día; los reportes, siete días.

La entrega automatizada termina en GHCR. El despliegue a Kubernetes queda únicamente indicado en comentarios del workflow y en [PRODUCTION.md](PRODUCTION.md), con secretos y variables previstos de GitHub. No hay un job de despliegue activo. Subir el workflow a GitHub es necesario para verificar la ejecución alojada y los permisos del registro.

## Verificación local

```bash
# Bash, Docker, curl y Node.js 24.
mvn clean verify
docker build -t inventory:ci .
bash scripts/verify-container.sh
kubectl kustomize k8s
```

El script asigna un puerto local libre y elimina únicamente su contenedor al terminar. Los reportes quedan en `target/`. Renderizar Kustomize valida la composición local; validar contra el servidor y desplegar requiere un contexto elegido expresamente.
