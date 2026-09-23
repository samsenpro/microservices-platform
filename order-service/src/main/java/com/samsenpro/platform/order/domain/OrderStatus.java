package com.samsenpro.platform.order.domain;

import java.util.Set;

/**
 * Ciclo de vida de un pedido:
 * <pre>
 * CREATED ──► PROCESSING ──► COMPLETED
 *    │             └───────► FAILED
 *    └──► CANCELLED
 * </pre>
 */
public enum OrderStatus {
    CREATED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<OrderStatus> allowedTargets() {
        return switch (this) {
            case CREATED -> Set.of(PROCESSING, CANCELLED);
            case PROCESSING -> Set.of(COMPLETED, FAILED);
            case COMPLETED, FAILED, CANCELLED -> Set.of();
        };
    }
}
