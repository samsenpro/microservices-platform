# Arquitectura

Este documento describe cómo encajan las piezas de la plataforma y por qué. Las decisiones con sus
alternativas están en [architecture-decisions.md](architecture-decisions.md).

## Índice

1. [Vista general](#1-vista-general)
2. [API Gateway](#2-api-gateway)
3. [Service Discovery](#3-service-discovery)
4. [Config Server](#4-config-server)
5. [Microservicios](#5-microservicios)
6. [Bases de datos](#6-bases-de-datos)
7. [Comunicación entre servicios](#7-comunicación-entre-servicios)
8. [Resiliencia](#8-resiliencia)
9. [Seguridad](#9-seguridad)
10. [Observabilidad](#10-observabilidad)
11. [Arranque y recuperación](#11-arranque-y-recuperación)

---

## 1. Vista general

```mermaid
flowchart TB
    client([Cliente])

    subgraph edge[Borde]
        gw[api-gateway<br/>routing · JWT · rate limit · correlation ID]
    end

    subgraph platform[Plataforma]
        cfg[config-server<br/>config-repo/]
        eureka[service-discovery<br/>Eureka]
    end

    subgraph services[Microservicios]
        us[user-service]
        ps[product-service]
        os[order-service]
    end

    subgraph data[Datos]
        udb[(users_db)]
        pdb[(products_db)]
        odb[(orders_db)]
    end

    client -->|HTTPS/JSON + Bearer JWT| gw
    gw -->|lb://user-service| us
    gw -->|lb://product-service| ps
    gw -->|lb://order-service| os
    os -->|HTTP + JWT del usuario<br/>Retry · CB · Bulkhead · Timeout| ps

    us --> udb
    ps --> pdb
    os --> odb

    gw -. registro / descubrimiento .-> eureka
    us -. registro .-> eureka
    ps -. registro .-> eureka
    os -. registro / descubrimiento .-> eureka

    gw -. configuración .-> cfg
    eureka -. configuración .-> cfg
    us -. configuración .-> cfg
    ps -. configuración .-> cfg
    os -. configuración .-> cfg
```

| Componente          | Puerto | Publicado en el host | Responsabilidad                                            |
|---------------------|--------|----------------------|------------------------------------------------------------|
| `api-gateway`       | 8090   | Sí                   | Único punto de entrada                                     |
| `service-discovery` | 8761   | Sí (127.0.0.1)       | Registro de instancias (dashboard con basic auth)          |
| `config-server`     | 8888   | No                   | Configuración centralizada                                 |
| `user-service`      | 8091   | No                   | Usuarios, roles, login y emisión de JWT                    |
| `product-service`   | 8092   | No                   | Catálogo de productos                                      |
| `order-service`     | 8093   | No                   | Pedidos; valida productos contra product-service           |
| `users-db` / `products-db` / `orders-db` | 5432 | Sí (127.0.0.1:5441-5443) | Una base de datos PostgreSQL por servicio |

Los microservicios no publican puertos: desde fuera solo se llega a ellos a través del gateway (lo
comprueba `SecurityE2ETest`).

## 2. API Gateway

Spring Cloud Gateway (WebFlux). Solo aplica políticas transversales; no hay lógica de negocio
([ADR-007](architecture-decisions.md#adr-007-el-gateway-no-contiene-lógica-de-negocio)).

```mermaid
flowchart LR
    req([Petición]) --> cid[CorrelationIdWebFilter<br/>genera o valida X-Correlation-ID]
    cid --> sec[Spring Security<br/>valida JWT: firma, exp, iss, role]
    sec -->|401 JSON| out1([Respuesta])
    sec --> route[Route predicate<br/>/api/users/** · /api/products/** · /api/orders/**]
    route --> rl[ClientRateLimit<br/>token bucket por IP o por usuario]
    rl -->|429 JSON + Retry-After| out2([Respuesta])
    rl --> lb[LoadBalancer<br/>lb://servicio → instancia UP en Eureka]
    lb --> svc[Microservicio]
    lb -->|sin instancias: 503| out3([Respuesta])
    svc -->|timeout 10 s: 504| out4([Respuesta])
```

- **Routing**: las rutas usan `lb://user-service`, `lb://product-service`, `lb://order-service`. La
  dirección real sale de Eureka en cada petición; no hay IPs ni puertos en la configuración.
- **JWT**: se valida en el borde para que el tráfico no autenticado no entre en la red interna. El token
  se reenvía intacto: cada servicio lo vuelve a validar.
- **Rate limiting**: filtro `ClientRateLimit` propio (token bucket en memoria). Login y registro se
  limitan por IP (10/min por defecto, contra fuerza bruta y registro masivo); el resto de la API, por
  usuario (100 cada 10 s). Respuesta `429` con `Retry-After`, `X-RateLimit-Limit` y `X-RateLimit-Remaining`.
- **Errores**: `GatewayErrorHandler` devuelve el mismo formato JSON que los servicios cuando el error se
  produce en el gateway (sin instancias → `503 SERVICE_UNAVAILABLE`, timeout → `504 UPSTREAM_TIMEOUT`,
  ruta inexistente → `404`). Los errores de los servicios pasan sin modificar.
- **Swagger UI** agregado en `/swagger-ui.html`: muestra por separado la especificación OpenAPI de cada
  servicio (rutas `/docs/{servicio}/v3/api-docs`).

## 3. Service Discovery

Eureka Server protegido con basic auth (un cliente sin credenciales no puede registrar una instancia
falsa ni leer el registro). Los clientes envían las credenciales como **cabecera**, no dentro de la URL
(`http://usuario:clave@...`): el cliente de Netflix escribe la URL completa en el log cada vez que falla un
heartbeat. Se detectó al simular una caída de Eureka (la contraseña aparecía 64 veces en los logs) y se
corrigió con `EurekaClientAuthentication` (platform-commons) y su equivalente en el gateway.

- Cada servicio se registra al arrancar con su IP de la red de Docker (`prefer-ip-address` en el perfil
  `docker`) y renueva su lease cada 5 s.
- `eureka.client.healthcheck.enabled=true`: el estado de `/actuator/health` se publica en Eureka. Una
  instancia con la base de datos caída pasa a `DOWN` y el balanceador deja de enviarle tráfico. Así el
  gateway detecta servicios no saludables.
- Los clientes refrescan el registro cada 5 s y el balanceador cachea 5 s (valores de demostración para
  que las caídas se noten rápido; en producción se ajustan al tamaño del registro).
- La autopreservación de Eureka está desactivada: con pocas instancias mantendría en el registro
  instancias muertas.

## 4. Config Server

Spring Cloud Config Server con backend `native` sobre el directorio `config-repo/`, montado como volumen
de solo lectura.

```
config-repo/
├── application.yml          Común: Eureka, JWT (issuer + ${JWT_SECRET}), Actuator, logging, tracing, JPA
├── application-docker.yml   Perfil docker: logs JSON (ECS), registro por IP
├── application-local.yml    Perfil local: logs de texto, DEBUG, detalles de health
├── service-discovery.yml    Eureka Server (standalone)
├── service-discovery-docker.yml  Eureka Server en Docker (sin autorreplicación)
├── api-gateway.yml          Rutas, rate limits, timeouts del gateway, Swagger UI
├── user-service.yml         Puerto, base de datos, administrador inicial
├── product-service.yml      Puerto, base de datos
└── order-service.yml        Base de datos, URL lógica y timeouts de product-service, Resilience4j
```

- **Precedencia**: `{servicio}-{perfil}.yml` > `application-{perfil}.yml` > `{servicio}.yml` > `application.yml`.
- **Secretos**: nunca en el repositorio. La configuración contiene placeholders (`${JWT_SECRET}`,
  `${DB_PASSWORD}`) que el Config Server devuelve sin resolver y que cada servicio resuelve con sus
  propias variables de entorno (lo comprueba `ConfigServerIntegrationTest`).
- **Acceso**: basic auth (`CONFIG_SERVER_USERNAME` / `CONFIG_SERVER_PASSWORD`).
- **Arranque**: `spring.config.import=configserver:` con `fail-fast` y reintentos. Si el Config Server no
  responde, el servicio reintenta (hasta 20 veces, backoff hasta 10 s) en lugar de arrancar con una
  configuración incompleta.
- **Perfiles**: `local` (IDE + bases de datos de compose), `docker` (compose) y `test` (tests
  automatizados, sin Config Server ni Eureka).

## 5. Microservicios

| Servicio          | Dueño de      | Endpoints                                                                                  |
|-------------------|---------------|--------------------------------------------------------------------------------------------|
| `user-service`    | `users`       | `POST /register`, `POST /login`, `GET /me`, `GET /{id}`, `GET /` (ADMIN), `PATCH /{id}` (ADMIN) |
| `product-service` | `products`    | `GET /`, `GET /{id}` (USER/ADMIN); `POST`, `PUT /{id}`, `DELETE /{id}` (ADMIN)            |
| `order-service`   | `orders`, `order_items` | `POST /`, `GET /`, `GET /{id}`, `POST /{id}/cancel`, `PATCH /{id}/status` (ADMIN) |

Estructura interna de cada servicio: `domain` (entidades y repositorios), `service` (casos de uso y
reglas), `web` (controladores y DTOs), `config` (seguridad y clientes). Sin interfaces que tengan una sola
implementación ni capas vacías.

`platform-commons` es una librería **solo de infraestructura** que usan los tres servicios de negocio:
filtro de correlation ID, formato de error y `@RestControllerAdvice` global, y validación JWT como
resource server. No contiene entidades ni lógica de negocio
([ADR-008](architecture-decisions.md#adr-008-librería-común-solo-para-infraestructura)).

Ciclo de vida de un pedido:

```mermaid
stateDiagram-v2
    [*] --> CREATED: POST /api/orders
    CREATED --> PROCESSING: ADMIN
    CREATED --> CANCELLED: dueño o ADMIN
    PROCESSING --> COMPLETED: ADMIN
    PROCESSING --> FAILED: ADMIN
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

## 6. Bases de datos

Database-per-service: tres contenedores PostgreSQL independientes, cada uno con su propio usuario y
contraseña. Un servicio no tiene credenciales de la base de datos de otro.

```mermaid
erDiagram
    USERS {
        uuid id PK
        varchar username UK "único sin distinguir mayúsculas"
        varchar email UK
        varchar password "hash BCrypt"
        varchar role "USER | ADMIN"
        boolean enabled
    }
    PRODUCTS {
        bigint id PK
        varchar name
        numeric price
        int stock
        boolean active "borrado lógico"
    }
    ORDERS {
        uuid id PK
        uuid user_id "sub del JWT (sin FK)"
        varchar status
        numeric total
    }
    ORDER_ITEMS {
        bigint id PK
        uuid order_id FK
        bigint product_id "id en product-service (sin FK)"
        int quantity
        numeric unit_price "precio copiado al comprar"
        numeric subtotal
    }
    ORDERS ||--|{ ORDER_ITEMS : contiene
```

- `orders.user_id` y `order_items.product_id` son **referencias lógicas**: no hay claves foráneas entre
  bases de datos. La integridad se valida por API en el momento de crear el pedido.
- El precio se copia en `unit_price`: un cambio de precio posterior no altera pedidos ya creados.
- Cada servicio gestiona su esquema con Flyway (`ddl-auto: validate`: Hibernate nunca modifica el esquema).

## 7. Comunicación entre servicios

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant G as api-gateway
    participant O as order-service
    participant E as Eureka
    participant P as product-service

    C->>G: POST /api/orders (Bearer JWT)
    G->>G: correlation ID, JWT, rate limit
    G->>E: instancias de order-service (caché 5 s)
    G->>O: POST /api/orders + JWT + X-Correlation-ID + traceparent
    O->>O: valida JWT y rol (no confía en el gateway)
    loop por cada producto del pedido
        O->>E: instancias de product-service (caché 5 s)
        O->>P: GET /api/products/{id} + JWT del usuario + X-Correlation-ID + traceparent
        P->>P: valida JWT y rol
        P-->>O: 200 {price, stock, active}
    end
    O->>O: valida activo y stock, calcula total
    O->>O: guarda el pedido (transacción local)
    O-->>G: 201 Created
    G-->>C: 201 Created + X-Correlation-ID
```

- **Cliente**: `RestClient` con `@LoadBalanced` (Spring Cloud LoadBalancer sobre Eureka) y Apache
  HttpClient 5 con los reintentos automáticos desactivados. No se usa `RestTemplate`.
- **Identidad**: order-service reenvía el JWT del usuario. product-service lo valida y aplica sus propias
  reglas: una petición interna no es de confianza por venir de dentro de la red.
- **Propiedad de los datos**: order-service nunca accede a `products_db`; todo pasa por la API de
  product-service.
- **Transacciones**: las llamadas remotas se hacen **antes** de abrir la transacción local, para no
  retener una conexión a la base de datos mientras se espera a otro servicio.

## 8. Resiliencia

Las políticas se aplican en la única dependencia síncrona entre servicios (order-service → product-service)
y en el borde (gateway). Los valores están en `config-repo/order-service.yml` y `config-repo/api-gateway.yml`.

```mermaid
flowchart LR
    call([getProduct]) --> retry
    subgraph retry[Retry: 3 intentos, backoff 200→400 ms, solo transitorios]
        subgraph cb[Circuit Breaker: ventana 10, OPEN con 50 % fallos u 80 % lentas]
            subgraph bh[Bulkhead: 20 llamadas concurrentes]
                http[HTTP: connect 1 s, read 2 s]
            end
        end
    end
    http --> ps[(product-service)]
```

| Patrón               | Dónde                        | Qué protege                                         | Qué ocurre al activarse                                |
|----------------------|------------------------------|-----------------------------------------------------|--------------------------------------------------------|
| **Timeout**          | order → product (1 s / 2 s)  | Hilos de Tomcat esperando indefinidamente           | El intento falla; puede reintentarse                   |
|                      | gateway → servicios (10 s)   | Conexiones del gateway                              | `504 UPSTREAM_TIMEOUT`                                  |
| **Retry**            | order → product              | Fallos transitorios (red, 502/503/504, sin instancias) | Hasta 3 intentos; después, `503`/`504` controlado  |
| **Circuit Breaker**  | order → product              | La dependencia caída y a nosotros mismos            | `OPEN`: fallo inmediato `503` sin tocar la red         |
| **Bulkhead**         | order → product (20 concurrentes) | Hilos de order-service ante un product-service lento | `503 PRODUCT_SERVICE_BUSY` inmediato              |
| **Rate Limiter**     | gateway                      | Login/registro (fuerza bruta) y API por usuario     | `429 RATE_LIMIT_EXCEEDED` con `Retry-After`           |

**Estados del Circuit Breaker**

- `CLOSED`: las llamadas pasan y se registra su resultado en una ventana de las últimas 10. Con al menos 5
  llamadas, si el 50 % falla o el 80 % tarda más de 1,5 s, pasa a `OPEN`.
- `OPEN`: durante 10 s ninguna llamada llega a product-service; se responde al instante con el fallback.
  Así la dependencia tiene margen para recuperarse y order-service no acumula hilos esperando.
- `HALF_OPEN`: pasados los 10 s se permiten 3 llamadas de prueba. Si van bien vuelve a `CLOSED`; si
  fallan, a `OPEN` otra vez.

**Qué cuenta como fallo**: solo los fallos de la dependencia (`ProductServiceFailure`: timeouts, errores
de conexión, 5xx, ninguna instancia). Un `404` es la respuesta correcta de un servicio sano: no abre el
circuito ni se reintenta.

**Fallback**: no se inventan datos. No hay un precio o un stock "por defecto" razonables. Cada fallo se
traduce a un error específico y rápido:

| Situación                               | Respuesta                          |
|-----------------------------------------|------------------------------------|
| Caído, sin instancias, 503 persistente o circuito abierto | `503 PRODUCT_SERVICE_UNAVAILABLE` |
| No responde a tiempo tras los reintentos | `504 PRODUCT_SERVICE_TIMEOUT`     |
| Responde 500 (error no transitorio)     | `502 PRODUCT_SERVICE_ERROR`        |
| Bulkhead lleno                          | `503 PRODUCT_SERVICE_BUSY`         |
| Producto inexistente (404)              | `422 PRODUCT_NOT_FOUND`            |

**Un solo nivel de reintentos**: el reintento propio de Spring Cloud LoadBalancer está desactivado y el
cliente HTTP es Apache HttpClient con `disableAutomaticRetries()`. El cliente HTTP del JDK repite por su
cuenta los GET cuando la conexión se cierra sin respuesta: los tests lo detectaron (6 llamadas en lugar de
3) y por eso se cambió de cliente.

## 9. Seguridad

```mermaid
sequenceDiagram
    participant C as Cliente
    participant G as api-gateway
    participant U as user-service
    participant P as product-service

    C->>G: POST /api/users/login (rate limit por IP)
    G->>U: login
    U->>U: BCrypt.matches (mismo coste si el usuario no existe)
    U-->>C: {accessToken, tokenType: Bearer, expiresIn: 3600}
    C->>G: GET /api/products (Bearer)
    G->>G: firma HS256, exp, iss, role ∈ {USER, ADMIN}
    G->>P: GET /api/products (mismo Bearer)
    P->>P: vuelve a validar el JWT + regla GET → USER/ADMIN
    P-->>C: 200
```

- **JWT** HS256 con claims mínimos: `iss`, `sub` (id del usuario), `role`, `iat`, `exp`. Sin email ni
  username. Se valida firma, expiración (con 60 s de tolerancia de reloj), emisor y que el rol sea válido.
- **Contraseñas**: BCrypt; límite de 72 caracteres (el máximo que BCrypt tiene en cuenta). Usuario
  inexistente, contraseña incorrecta y cuenta deshabilitada dan la misma respuesta y el mismo coste de CPU.
- **Autorización en cada servicio**: el gateway rechaza lo no autenticado, pero cada servicio decide sobre
  sus recursos (roles por endpoint, pedidos visibles solo para su dueño o un ADMIN).
- **Infraestructura**: Config Server y Eureka con basic auth; microservicios sin puertos publicados;
  Actuator (salvo `health` e `info`) solo con rol ADMIN.
- **Secretos**: todos por variables de entorno (`.env`, excluido de Git). El administrador inicial solo se
  crea si `ADMIN_PASSWORD` está definida: no hay credenciales por defecto.
- **Logs**: nunca contraseñas ni tokens. Los DTOs con contraseña sobrescriben `toString()` y los logs de
  acceso no incluyen cabeceras.

## 10. Observabilidad

- **Actuator**: `health` (con grupos `liveness` y `readiness`), `info` y `metrics` en todos los servicios;
  además, `circuitbreakers`, `circuitbreakerevents`, `retries` y `bulkheads` en order-service.
- **Logs estructurados** (perfil `docker`): JSON ECS con `@timestamp`, `service.name`, `log.level`,
  `message`, `traceId`, `spanId` y `correlationId`.
- **Correlation ID**: nace en el gateway (o se respeta el del cliente si es válido: `[A-Za-z0-9._-]{1,64}`,
  para evitar inyección en logs), se propaga en `X-Correlation-ID` a order-service y de ahí a
  product-service, y vuelve en la respuesta.
- **Trazas**: Micrometer Tracing con el bridge de OpenTelemetry propaga el contexto W3C (`traceparent`)
  Gateway → Order → Product. El mismo `traceId` aparece en los logs de los tres servicios (lo comprueba
  `OrderFlowE2ETest`). No se exportan spans a un backend: con el proyecto anterior
  (observability-platform) bastaría con añadir el exporter OTLP y un Collector.

Ejemplo real (`docker compose logs api-gateway order-service product-service | grep doc-example-001`,
campos principales, ordenado por tiempo):

```json
{"@timestamp":"2026-09-23T17:51:01.645Z","service.name":"product-service","traceId":"94191e5dcc1b8de682bdc941093ab9b7","spanId":"bb470ae722f3da19","correlationId":"doc-example-001","message":"GET /api/products/1 -> 200 (5 ms)"}
{"@timestamp":"2026-09-23T17:51:01.650Z","service.name":"order-service","traceId":"94191e5dcc1b8de682bdc941093ab9b7","spanId":"332b50ad150aeb02","correlationId":"doc-example-001","message":"Order 379caca7-… created for user 92c78d0e-… with 1 items, total 49.90"}
{"@timestamp":"2026-09-23T17:51:01.652Z","service.name":"order-service","traceId":"94191e5dcc1b8de682bdc941093ab9b7","spanId":"0719dcafb51b865f","correlationId":"doc-example-001","message":"POST /api/orders -> 201 (18 ms)"}
{"@timestamp":"2026-09-23T17:51:01.653Z","service.name":"api-gateway","traceId":"94191e5dcc1b8de682bdc941093ab9b7","correlationId":"doc-example-001","message":"POST /api/orders -> 201 (24 ms)"}
```

## 11. Arranque y recuperación

```mermaid
flowchart LR
    cfg[config-server<br/>healthy] --> eureka[service-discovery<br/>healthy]
    eureka --> svc[user / product / order-service<br/>healthy]
    dbs[(bases de datos<br/>healthy)] --> svc
    svc --> gw[api-gateway]
```

docker compose respeta ese orden con `depends_on: condition: service_healthy`, pero la plataforma no
depende de él:

| Dependencia no disponible | Mecanismo                                                                  |
|---------------------------|----------------------------------------------------------------------------|
| Config Server             | `fail-fast` + reintentos con backoff exponencial durante el arranque       |
| Base de datos             | Flyway `connect-retries` al arrancar; Hikari reconecta cuando vuelve       |
| Eureka                    | El cliente reintenta el registro y el heartbeat; el servicio sigue funcionando con su última copia del registro |
| Un microservicio          | Eureka lo retira (lease 15 s / health DOWN); gateway y order-service responden `503` controlado mientras tanto |
| Contenedor caído          | `restart: on-failure`                                                      |

`ResilienceE2ETest` para product-service y después su base de datos, y comprueba que el sistema responde
con errores controlados y que se recupera solo al volver la dependencia.
