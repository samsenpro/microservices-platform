package com.samsenpro.platform.order.domain;

import com.samsenpro.platform.commons.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void totalIsTheSumOfSubtotals() {
        Order order = new Order(UUID.randomUUID(), List.of(
                new OrderItem(1L, 3, new BigDecimal("12.50")),
                new OrderItem(2L, 1, new BigDecimal("0.99"))));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getItems().get(0).getSubtotal()).isEqualByComparingTo("37.50");
        assertThat(order.getTotal()).isEqualByComparingTo("38.49");
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, PROCESSING, true", "CREATED, CANCELLED, true", "CREATED, COMPLETED, false",
            "PROCESSING, COMPLETED, true", "PROCESSING, FAILED, true", "PROCESSING, CANCELLED, false",
            "COMPLETED, CANCELLED, false", "FAILED, PROCESSING, false", "CANCELLED, CREATED, false"
    })
    void statusTransitions(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void invalidTransitionIsAConflict() {
        Order order = new Order(UUID.randomUUID(), List.of(new OrderItem(1L, 1, BigDecimal.TEN)));
        order.transitionTo(OrderStatus.CANCELLED);

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.PROCESSING))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void emptyOrdersAndNonPositiveQuantitiesAreRejected() {
        assertThatThrownBy(() -> new Order(UUID.randomUUID(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderItem(1L, 0, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    }
}
