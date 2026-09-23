package com.samsenpro.platform.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Lo que order-service necesita saber de un producto, leído de la API de product-service. Es un DTO propio:
 * order-service no comparte clases ni entidades con product-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSnapshot(Long id, String name, BigDecimal price, int stock, boolean active) {
}
