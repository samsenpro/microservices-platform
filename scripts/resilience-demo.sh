#!/usr/bin/env bash
# Demostración guiada: failure → retry → circuit breaker → fallback → recuperación → timeout.
# Requiere la plataforma con el perfil chaos:
#   docker compose -f docker-compose.yml -f docker-compose.chaos.yml up -d --build
set -euo pipefail
cd "$(dirname "$0")/.."

GATEWAY="http://localhost:$(grep -E '^GATEWAY_PORT=' .env | cut -d= -f2- || echo 8090)"
ADMIN_USER=$(grep -E '^ADMIN_USERNAME=' .env | cut -d= -f2-)
ADMIN_PASSWORD=$(grep -E '^ADMIN_PASSWORD=' .env | cut -d= -f2-)

step() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
token_of() { sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'; }
field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }

login() {
  curl -s -X POST "$GATEWAY/api/users/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | token_of
}

order() {
  local correlation_id=$1
  curl -s -o /tmp/order-response.json -w '%{http_code} %{time_total}s' -X POST "$GATEWAY/api/orders" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -H "X-Correlation-ID: $correlation_id" -d "{\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}]}"
  echo "  $(cat /tmp/order-response.json | cut -c1-160)"
}

step "Usuarios: ADMIN y un comprador nuevo"
ADMIN_TOKEN=$(login "${ADMIN_USER:-admin}" "$ADMIN_PASSWORD")
BUYER="demo-$RANDOM$RANDOM"
curl -s -o /dev/null -X POST "$GATEWAY/api/users/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$BUYER\",\"email\":\"$BUYER@demo.local\",\"password\":\"Demo-Passw0rd!\"}"
USER_TOKEN=$(login "$BUYER" "Demo-Passw0rd!")
echo "  comprador: $BUYER"

step "ADMIN crea un producto"
PRODUCT_ID=$(curl -s -X POST "$GATEWAY/api/products" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"Demo item","price":10.00,"stock":1000}' | field id)
echo "  producto $PRODUCT_ID"

step "1. Todo funciona: pedido creado (201)"
./scripts/chaos.sh off > /dev/null
order "demo-ok-$RANDOM"

step "2. product-service devuelve 503: order-service reintenta 3 veces y responde un error controlado"
./scripts/chaos.sh error-503 > /dev/null
CID="demo-retry-$RANDOM"
order "$CID"
sleep 2
echo "  fallos inyectados en product-service para $CID: $(docker compose logs --since 1m product-service | grep "$CID" | grep -c 'Chaos: injecting')"

step "3. Más fallos: el circuit breaker se abre y order-service falla al instante (sin llamar a product-service)"
order "demo-open-$RANDOM"
echo "  estado: $(./scripts/chaos.sh circuit | field state)"
order "demo-fast-$RANDOM"

step "4. product-service se recupera: tras 10 s en OPEN el circuito pasa a HALF_OPEN y se cierra"
./scripts/chaos.sh off > /dev/null
sleep 11
order "demo-recover-$RANDOM"
order "demo-recover-$RANDOM"
order "demo-recover-$RANDOM"
echo "  estado: $(./scripts/chaos.sh circuit | field state)"

step "5. product-service lento (5 s): timeout de 2 s por intento, 504 en ~7 s en lugar de esperar 15 s"
./scripts/chaos.sh delay 5000 > /dev/null
order "demo-timeout-$RANDOM"

./scripts/chaos.sh off > /dev/null
step "Fin: modo chaos desactivado"
