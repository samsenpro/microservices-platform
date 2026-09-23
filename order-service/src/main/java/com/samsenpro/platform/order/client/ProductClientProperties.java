package com.samsenpro.platform.order.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * config-repo/order-service.yml → clients.product-service.
 *
 * @param baseUrl        nombre lógico (http://product-service) que resuelve el balanceador contra Eureka
 * @param connectTimeout tiempo máximo para establecer la conexión TCP
 * @param readTimeout    tiempo máximo esperando la respuesta
 */
@Validated
@ConfigurationProperties("clients.product-service")
public record ProductClientProperties(
        @NotBlank String baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
