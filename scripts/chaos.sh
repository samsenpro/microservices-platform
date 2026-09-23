#!/usr/bin/env bash
# Simulación de fallos de product-service. SOLO DESARROLLO.
#
# Los modos delay/error-* necesitan el perfil "chaos":
#   docker compose -f docker-compose.yml -f docker-compose.chaos.yml up -d
#
# Uso:
#   ./scripts/chaos.sh delay [ms] [rate]   Latencia (por defecto 5000 ms en el 100 % de peticiones)
#   ./scripts/chaos.sh error-503 [rate]    HTTP 503 (transitorio: order-service reintenta)
#   ./scripts/chaos.sh error-500 [rate]    HTTP 500 (no se reintenta)
#   ./scripts/chaos.sh off                 Desactiva la inyección de fallos
#   ./scripts/chaos.sh status              Configuración actual
#   ./scripts/chaos.sh stop | start        Para / arranca el contenedor de product-service
#   ./scripts/chaos.sh db-stop | db-start  Para / arranca la base de datos de product-service
#   ./scripts/chaos.sh circuit             Estado del circuit breaker de order-service
set -euo pipefail
cd "$(dirname "$0")/.."

inject() {
  docker compose exec -T product-service wget -qO- --post-data= "http://localhost:8092/internal/chaos?$1"
  echo
}

circuit_state() {
  # El Actuator de order-service no se publica: se consulta desde dentro de su contenedor con un JWT de ADMIN
  local admin_user admin_password gateway token
  admin_user=$(grep -E '^ADMIN_USERNAME=' .env | cut -d= -f2-)
  admin_password=$(grep -E '^ADMIN_PASSWORD=' .env | cut -d= -f2-)
  gateway="http://localhost:$(grep -E '^GATEWAY_PORT=' .env | cut -d= -f2- || echo 8090)"
  token=$(curl -s -X POST "$gateway/api/users/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"${admin_user:-admin}\",\"password\":\"$admin_password\"}" \
    | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  docker compose exec -T order-service wget -qO- --header="Authorization: Bearer $token" \
    http://localhost:8093/actuator/circuitbreakers
  echo
}

case "${1:-}" in
  delay)     inject "mode=DELAY&delayMs=${2:-5000}&failureRate=${3:-1.0}" ;;
  error-503) inject "mode=ERROR_503&failureRate=${2:-1.0}" ;;
  error-500) inject "mode=ERROR_500&failureRate=${2:-1.0}" ;;
  off)       inject "mode=NONE" ;;
  status)    docker compose exec -T product-service wget -qO- http://localhost:8092/internal/chaos; echo ;;
  stop)      docker compose stop product-service ;;
  start)     docker compose start product-service ;;
  db-stop)   docker compose stop products-db ;;
  db-start)  docker compose start products-db ;;
  circuit)   circuit_state ;;
  *)         sed -n '2,17p' "$0"; exit 1 ;;
esac
