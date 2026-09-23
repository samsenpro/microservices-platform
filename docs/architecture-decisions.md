# Architecture Decision Records

Cada ADR recoge el contexto, la decisión, las alternativas descartadas y sus consecuencias (también las
negativas).

| ADR | Decisión |
|-----|----------|
| [ADR-001](#adr-001-api-gateway-como-único-punto-de-entrada) | API Gateway como único punto de entrada |
| [ADR-002](#adr-002-service-discovery-con-eureka) | Service Discovery con Eureka |
| [ADR-003](#adr-003-configuración-centralizada-con-config-server) | Configuración centralizada con Config Server |
| [ADR-004](#adr-004-una-base-de-datos-por-servicio) | Una base de datos por servicio |
| [ADR-005](#adr-005-circuit-breaker-en-la-llamada-order--product) | Circuit Breaker en la llamada order → product |
| [ADR-006](#adr-006-cuándo-reintentar-y-cuándo-no) | Cuándo reintentar y cuándo no |
| [ADR-007](#adr-007-el-gateway-no-contiene-lógica-de-negocio) | El gateway no contiene lógica de negocio |
| [ADR-008](#adr-008-librería-común-solo-para-infraestructura) | Librería común solo para infraestructura |
| [ADR-009](#adr-009-jwt-validado-en-el-gateway-y-en-cada-servicio) | JWT validado en el gateway y en cada servicio |
| [ADR-010](#adr-010-rate-limiting-en-memoria) | Rate limiting en memoria |

---

## ADR-001: API Gateway como único punto de entrada

**Contexto.** Hay tres microservicios con APIs públicas. Si los clientes los llamaran directamente,
tendrían que conocer sus direcciones, y cada servicio tendría que implementar por su cuenta el rate
limiting, el correlation ID y la primera validación del token. Además, cualquier servicio expuesto sería
superficie de ataque.

**Decisión.** Spring Cloud Gateway (WebFlux) es el único componente con puerto publicado para la API.
Enruta por prefijo (`/api/users/**`, `/api/products/**`, `/api/orders/**`) a nombres lógicos
(`lb://…`) y aplica las políticas transversales: validación JWT, rate limiting, correlation ID, timeouts
hacia los servicios y formato de error común.

**Alternativas.**
- *Clientes contra cada servicio*: acopla los clientes a la topología interna y duplica políticas.
- *Nginx/Kong/Envoy*: son buenas opciones en producción, pero no se integran de forma nativa con Eureka ni
  con el resto del stack Spring, y el proyecto quiere demostrar Spring Cloud.
- *Gateway servlet (Spring Cloud Gateway Server MVC)*: viable; se eligió WebFlux porque un gateway es
  I/O puro y el modelo no bloqueante aguanta muchas conexiones lentas con pocos hilos.

**Consecuencias.**
- (+) Una sola superficie expuesta; los clientes no conocen la topología interna.
- (+) Las políticas transversales se aplican en un único sitio.
- (−) Es un salto de red más y un punto crítico: en producción necesita varias réplicas detrás de un
  balanceador.
- (−) El gateway es reactivo y los servicios son servlet: no pueden compartir código de filtros
  (ver ADR-008).

## ADR-002: Service Discovery con Eureka

**Contexto.** Las instancias arrancan con IPs dinámicas (Docker asigna una distinta en cada arranque) y
puede haber varias por servicio. Fijar `host:puerto` en la configuración hace el sistema frágil y rompe al
escalar.

**Decisión.** Eureka Server como registro. Cada servicio se registra al arrancar, renueva su lease cada
5 s y publica su estado de salud (`eureka.client.healthcheck.enabled`). El gateway y order-service
resuelven los nombres lógicos con Spring Cloud LoadBalancer sobre Eureka. Eureka se protege con basic auth.

**Alternativas.**
- *DNS de Docker/Kubernetes*: resuelve nombres, pero no conoce el estado de salud de cada instancia a nivel
  de aplicación ni permite balanceo en cliente sin más piezas. En Kubernetes, `Service` + readiness
  probes harían innecesario Eureka (ver "Production Considerations" en el README).
- *Consul*: más completo (KV, health checks activos), pero es otra tecnología que operar. Eureka encaja de
  forma nativa con Spring Cloud.

**Consecuencias.**
- (+) Ninguna IP ni puerto en la configuración; escalar es arrancar otra instancia.
- (+) Una instancia con su base de datos caída pasa a `DOWN` y deja de recibir tráfico.
- (−) El registro es eventualmente consistente: una instancia muerta puede seguir apareciendo unos
  segundos (lease de 15 s + cachés de 5 s). Por eso la llamada entre servicios necesita además retry y
  circuit breaker.
- (−) Los valores de refresco se han bajado para la demostración; con muchas instancias generarían más
  tráfico hacia Eureka.

## ADR-003: Configuración centralizada con Config Server

**Contexto.** Todos los servicios comparten configuración (Eureka, emisor JWT, Actuator, logging, tracing,
JPA) y cada uno tiene la suya (base de datos, timeouts, Resilience4j). Duplicarla en cada
`application.yml` provoca divergencias, y cambiar un timeout exigiría recompilar la imagen.

**Decisión.** Spring Cloud Config Server con backend `native` sobre `config-repo/`: un fichero común, uno
por servicio y uno por perfil (`local`, `docker`). Los secretos no están en esos ficheros: aparecen como
placeholders (`${JWT_SECRET}`) que cada servicio resuelve con sus variables de entorno. Los clientes usan
`fail-fast` con reintentos.

**Alternativas.**
- *Variables de entorno para todo*: sin jerarquía ni reutilización; el `docker-compose.yml` crecería con
  decenas de valores repetidos.
- *Backend Git*: es lo habitual en producción (versionado y auditoría de cambios), pero para ejecutar el
  proyecto hace falta un repositorio remoto. El backend `native` sirve la misma estructura y cambiar a Git
  es cambiar una propiedad.
- *Kubernetes ConfigMaps / Vault*: dependen de una plataforma concreta.

**Consecuencias.**
- (+) La configuración común está en un único sitio, y cambiar un timeout o un umbral no requiere
  reconstruir imágenes (basta reiniciar el servicio).
- (+) Los secretos siguen fuera del repositorio.
- (−) El Config Server es una dependencia de arranque. Se mitiga con `fail-fast` + retry; en producción se
  despliegan varias réplicas.
- (−) Sin Spring Cloud Bus, los cambios no se propagan en caliente: se aplican al reiniciar.

## ADR-004: Una base de datos por servicio

**Contexto.** Si order-service leyera `products_db` directamente, cualquier cambio en el esquema de
productos rompería pedidos, los dos servicios tendrían que desplegarse juntos y la frontera entre ellos
dejaría de existir: sería un monolito distribuido.

**Decisión.** Cada servicio tiene su propio PostgreSQL (contenedor, usuario y contraseña propios), gestiona
su esquema con Flyway y es el único que lo toca. Los demás acceden a sus datos solo por API. Las
referencias entre servicios (`orders.user_id`, `order_items.product_id`) son lógicas, sin claves foráneas.

**Alternativas.**
- *Base de datos compartida*: más simple, con joins y transacciones ACID entre dominios, pero acopla
  esquemas y despliegues.
- *Una instancia con un esquema por servicio*: aísla los esquemas y ahorra recursos, pero comparte fallos y
  capacidad. Sería una opción razonable en producción; aquí se usan contenedores separados para que el
  aislamiento sea evidente y se pueda simular la caída de una sola base de datos.

**Consecuencias.**
- (+) Cada servicio evoluciona y despliega su esquema de forma independiente.
- (+) La caída de `products_db` no afecta a usuarios ni a los pedidos ya creados.
- (−) No hay transacciones entre servicios: crear un pedido valida el stock pero no lo reserva. Una
  reserva consistente requiere una saga o eventos (ver "Production Considerations").
- (−) Los datos de otro servicio se copian cuando hace falta conservarlos (el precio en `unit_price`).

## ADR-005: Circuit Breaker en la llamada order → product

**Contexto.** Crear un pedido requiere una llamada síncrona a product-service. Si product-service está caído
o degradado, cada petición de pedido esperaría el timeout, se reintentaría y ocuparía un hilo de Tomcat.
Con carga, order-service agotaría sus hilos y caería también: un fallo en cascada.

**Decisión.** Resilience4j Circuit Breaker sobre esa llamada (ventana de 10 llamadas, mínimo 5, umbral
del 50 % de fallos o del 80 % de llamadas lentas de más de 1,5 s, 10 s en `OPEN`, 3 llamadas de prueba en
`HALF_OPEN`). Solo cuentan como fallo los errores de la dependencia; los errores de negocio (404) cuentan
como éxito. Se complementa con un Bulkhead (20 llamadas concurrentes) y timeouts explícitos.

**Alternativas.**
- *Solo timeouts*: limitan la espera de cada petición, pero se sigue golpeando a un servicio que ya se sabe
  que está caído.
- *Spring Cloud CircuitBreaker (abstracción)*: añade una capa sobre Resilience4j sin aportar nada con un
  solo proveedor. Se usa Resilience4j directamente, con su autoconfiguración de Spring Boot.
- *Anotaciones (`@CircuitBreaker`, `@Retry`)*: el orden de los aspectos es implícito. La composición
  programática (`Retry(CircuitBreaker(Bulkhead(llamada)))`) deja el orden visible en el código.

**Consecuencias.**
- (+) Con el circuito abierto, la respuesta tarda milisegundos en lugar de segundos (medido en los tests
  E2E) y product-service deja de recibir tráfico mientras se recupera.
- (+) El fallback es explícito: un error concreto (`503`, `504`, `502`), nunca datos inventados.
- (−) Durante `OPEN` también fallan peticiones que quizá habrían funcionado.
- (−) Los umbrales dependen del tráfico real y hay que ajustarlos observando las métricas
  (`resilience4j.circuitbreaker.*`, `resilience4j.retry.calls` y `resilience4j.bulkhead.*` en el
  `/actuator/metrics` de order-service).

## ADR-006: Cuándo reintentar y cuándo no

**Contexto.** Reintentar un error permanente solo multiplica la carga y la latencia: un 404 seguirá
siendo 404. Reintentar sin límite ante una caída amplifica el problema (tormenta de reintentos). Y si hay
varias capas reintentando, los intentos se multiplican entre sí.

**Decisión.**
- **Se reintenta** solo lo transitorio: timeouts, errores de conexión, ninguna instancia disponible y
  respuestas `502`, `503` y `504`.
- **No se reintenta**: `400`, `401`, `403`, `404`, `409` ni `500`. El 500 indica un error del propio
  servicio: cuenta para el circuit breaker, pero repetir la misma petición daría el mismo resultado.
- **Límites**: 3 intentos en total con backoff exponencial (200 ms, 400 ms). Nunca infinito.
- **Una sola capa**: el reintento de Spring Cloud LoadBalancer está desactivado y el cliente HTTP es Apache
  HttpClient con `disableAutomaticRetries()`. Los tests detectaron que el cliente HTTP del JDK reintentaba
  por su cuenta los GET ante una conexión cortada (6 llamadas en lugar de 3).
- Solo se reintentan llamadas **idempotentes** (`GET /api/products/{id}`). La creación de pedidos no se
  reintenta internamente.
- **Retry por fuera del Circuit Breaker**: cada intento cuenta en el circuito y, si se abre, no se sigue
  reintentando.

**Consecuencias.**
- (+) La latencia en el peor caso está acotada: 3 × 2 s + 0,6 s ≈ 6,6 s, por debajo del timeout de 10 s
  del gateway.
- (+) Los errores de negocio responden al primer intento.
- (−) Un 500 esporádico que se habría resuelto al reintentar llega al cliente como `502`.

## ADR-007: El gateway no contiene lógica de negocio

**Contexto.** Es tentador validar en el gateway reglas como "solo ADMIN crea productos" o componer
respuestas de varios servicios. Pero entonces cada cambio de negocio obliga a desplegar el gateway, que es
compartido por todos los servicios, y las reglas quedan partidas entre dos sitios.

**Decisión.** El gateway solo hace enrutado, autenticación (que el JWT sea válido), rate limiting,
correlation ID, timeouts y traducción de sus propios errores. La autorización por recurso (roles por
endpoint, quién puede ver qué pedido) y todas las reglas de negocio están en los servicios.

**Alternativas.**
- *Autorización por rol en el gateway*: rechazaría antes algunas peticiones, pero duplicaría reglas que los
  servicios necesitan igualmente.
- *Backend-for-frontend en el gateway*: no hay un cliente concreto que lo justifique.

**Consecuencias.**
- (+) El gateway cambia poco y cualquier servicio puede evolucionar sin tocarlo.
- (+) Los servicios siguen protegidos si alguien consigue llegar a ellos sin pasar por el gateway.
- (−) Una petición con rol insuficiente llega hasta el servicio antes de recibir el 403.

## ADR-008: Librería común solo para infraestructura

**Contexto.** Los tres servicios de negocio necesitan el mismo filtro de correlation ID, el mismo formato de
error y la misma validación JWT. Copiarlo tres veces provoca divergencias. Pero una librería compartida
con dominio (entidades, DTOs de otro servicio) acoplaría los servicios.

**Decisión.** `platform-commons` es una autoconfiguración de Spring Boot con piezas **solo técnicas**:
`CorrelationIdFilter`, `ApiError` y `GlobalExceptionHandler`, `PlatformHttpSecurity` (base de la cadena de
seguridad) y el `JwtDecoder`. No contiene entidades, repositorios ni DTOs de negocio. Cada servicio define
sus propias reglas de autorización y sus DTOs; order-service tiene su propio `ProductSnapshot` en lugar de
importar clases de product-service.

**Consecuencias.**
- (+) Un solo formato de error y una sola forma de validar tokens en toda la plataforma.
- (−) Cambiar la librería obliga a reconstruir los tres servicios. Es aceptable porque cambia poco y
  porque se versiona junto a ellos en este monorepo.
- (−) El gateway (reactivo) no puede usarla y replica solo el contrato JSON de `ApiError`.

## ADR-009: JWT validado en el gateway y en cada servicio

**Contexto.** Si solo el gateway validara el token y pasara la identidad en una cabecera (`X-User-Id`),
cualquiera que llegara a la red interna podría falsificar esa cabecera. Los requisitos piden que los
servicios internos no asuman que una petición interna es de confianza.

**Decisión.** El gateway valida el JWT (firma, expiración, emisor, rol) y lo reenvía **sin modificar**.
Cada servicio vuelve a validarlo como OAuth2 Resource Server y aplica su autorización. Para las llamadas
entre servicios, order-service reenvía el JWT del usuario original a product-service. Algoritmo: HS256 con
un secreto compartido de al menos 256 bits, recibido por variable de entorno.

**Alternativas.**
- *Cabeceras de identidad añadidas por el gateway*: más baratas, pero exigen confiar en la red interna.
- *RS256 con JWKS publicado por user-service*: mejor en producción (solo user-service tiene la clave
  privada y se pueden rotar claves sin redistribuir secretos), pero añade un endpoint JWKS y gestión de
  claves. HS256 mantiene el proyecto simple; el cambio queda como consideración de producción.
- *Tokens de servicio (client credentials) para llamadas internas*: más adecuados para operaciones que no
  actúan en nombre de un usuario. Aquí toda llamada interna ocurre en nombre del usuario que hace el pedido.

**Consecuencias.**
- (+) Defensa en profundidad: saltarse el gateway no da acceso.
- (+) product-service aplica sus reglas también a las llamadas de order-service.
- (−) Todos los servicios comparten el secreto HMAC: si se filtra uno, se pueden emitir tokens.
- (−) Un JWT no se puede revocar antes de su expiración (1 h): deshabilitar un usuario impide nuevos
  logins, pero no invalida los tokens ya emitidos.

## ADR-010: Rate limiting en memoria

**Contexto.** Los endpoints de login y registro son objetivo de fuerza bruta y de registros masivos.
El `RequestRateLimiter` de Spring Cloud Gateway viene con `RedisRateLimiter`, que requiere Redis.

**Decisión.** Un filtro propio `ClientRateLimit` con un token bucket en memoria por (ruta, cliente). El
cliente es la IP de la conexión para login y registro, y el usuario del JWT para el resto. Límites
configurables en `config-repo/api-gateway.yml` (10 por minuto en autenticación y 100 cada 10 s en la API,
por defecto). Respuesta `429` con `Retry-After`. No se confía en `X-Forwarded-For`, que el cliente puede
falsificar.

**Alternativas.**
- *RedisRateLimiter*: correcto con varias réplicas del gateway, pero añade Redis solo para esto. Los
  requisitos piden no añadir tecnologías sin necesidad, y hay una sola réplica.
- *Resilience4j RateLimiter*: limita globalmente, no por cliente, así que un atacante agotaría el cupo de
  todos.

**Consecuencias.**
- (+) Sin infraestructura adicional; mismo formato de error que el resto.
- (−) Con N réplicas del gateway el límite efectivo es N veces el configurado, y el estado se pierde al
  reiniciar. En producción: `RedisRateLimiter`, o rate limiting en el balanceador o WAF.
- (−) Detrás de un proxy, todas las peticiones llegarían con la IP del proxy. Habría que activar
  `server.forward-headers-strategy` solo con proxies de confianza.
