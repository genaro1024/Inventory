#!/usr/bin/env bash
set -euo pipefail
mkdir -p target
container_id=''
cleanup() {
  if [[ -n "$container_id" ]]; then
    docker logs "$container_id" > target/container.log 2>&1 || true
    docker rm -f "$container_id" > /dev/null || true
  fi
}
trap cleanup EXIT
container_id=$(docker run -d --read-only --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --cap-drop ALL --security-opt no-new-privileges --memory 768m --cpus 1 \
  -p 127.0.0.1::8080 inventory:ci)
port=$(docker inspect --format '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$container_id")
base_url="http://127.0.0.1:$port"
ready=false
for attempt in $(seq 1 90); do
  if curl --silent --fail --max-time 3 "$base_url/health/readiness" > target/container-health.json; then
    ready=true
    break
  fi
  sleep 2
done
[[ "$ready" == true ]] || { echo 'Container failed to become ready'; exit 1; }
curl --silent --fail --max-time 3 "$base_url/health/liveness" > /dev/null
node -e 'const b=require("./target/container-health.json"); if(Object.keys(b).sort().join()!=="data,message,success,traceId" || b.success!==true || b.data!==null || !b.traceId) process.exit(1)'
[[ $(docker inspect --format '{{.Config.User}}' "$container_id") == '10001:10001' ]]
npx --yes newman@6.2.2 run postman/Inventory.postman_collection.json \
  -e postman/Local.postman_environment.json --env-var "baseUrl=$base_url" \
  --reporters cli,junit --reporter-junit-export target/container-postman.xml
