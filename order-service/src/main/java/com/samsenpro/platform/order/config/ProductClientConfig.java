package com.samsenpro.platform.order.config;

import com.samsenpro.platform.commons.web.CorrelationIdPropagationInterceptor;
import com.samsenpro.platform.order.client.ProductClientProperties;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

@Configuration
class ProductClientConfig {

    /**
     * {@code @LoadBalanced}: las URLs con nombre lógico (http://product-service) se resuelven contra Eureka
     * en cada llamada. El configurer de Spring Boot añade la instrumentación (métricas y propagación de
     * la traza W3C).
     */
    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    /**
     * Apache HttpClient con reintentos automáticos DESACTIVADOS: el cliente HTTP del JDK, por ejemplo,
     * repite por su cuenta los GET cuando la conexión se cierra sin respuesta, lo que duplicaría en
     * silencio los intentos configurados en Resilience4j.
     */
    @Bean(destroyMethod = "close")
    CloseableHttpClient productHttpClient(ProductClientProperties properties) {
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                        .setSocketTimeout(Timeout.of(properties.readTimeout()))
                        .build())
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(20)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connections)
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    RestClient productRestClient(RestClient.Builder loadBalancedRestClientBuilder, CloseableHttpClient productHttpClient,
                                 ProductClientProperties properties) {
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(productHttpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return loadBalancedRestClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(new CorrelationIdPropagationInterceptor())
                .requestInterceptor(bearerTokenPropagation())
                .build();
    }

    /**
     * product-service no confía en order-service por estar en la misma red: vuelve a validar el JWT del
     * usuario y aplica su propia autorización. Por eso se reenvía el token de la petición original.
     */
    private static ClientHttpRequestInterceptor bearerTokenPropagation() {
        return (request, body, execution) -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwt) {
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getToken().getTokenValue());
            }
            return execution.execute(request, body);
        };
    }
}
