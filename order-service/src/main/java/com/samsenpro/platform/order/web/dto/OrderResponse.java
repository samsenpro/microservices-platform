package com.samsenpro.platform.order.web.dto;

import com.samsenpro.platform.order.domain.Order;
import com.samsenpro.platform.order.domain.OrderItem;
import com.samsenpro.platform.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID userId,
        OrderStatus status,
        BigDecimal total,
        List<Item> items,
        Instant createdAt,
        Instant updatedAt) {

    public record Item(Long id, Long productId, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {

        static Item from(OrderItem item) {
            return new Item(item.getId(), item.getProductId(), item.getQuantity(), item.getUnitPrice(),
                    item.getSubtotal());
        }
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getUserId(), order.getStatus(), order.getTotal(),
                order.getItems().stream().map(Item::from).toList(), order.getCreatedAt(), order.getUpdatedAt());
    }
}
