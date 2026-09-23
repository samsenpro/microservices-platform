package com.samsenpro.platform.order.web.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateOrderRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsAWellFormedOrder() {
        assertThat(validator.validate(new CreateOrderRequest(List.of(new CreateOrderRequest.Item(1L, 2))))).isEmpty();
    }

    @Test
    void rejectsEmptyOrdersAndTooManyLines() {
        assertThat(paths(new CreateOrderRequest(List.of()))).contains("items");
        assertThat(paths(new CreateOrderRequest(Collections.nCopies(21, new CreateOrderRequest.Item(1L, 1)))))
                .contains("items");
    }

    @Test
    void rejectsInvalidLines() {
        Set<String> violations = paths(new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(null, 1),
                new CreateOrderRequest.Item(-5L, 1),
                new CreateOrderRequest.Item(1L, 0),
                new CreateOrderRequest.Item(1L, 1001))));

        assertThat(violations).contains("items[0].productId", "items[1].productId", "items[2].quantity",
                "items[3].quantity");
    }

    private Set<String> paths(CreateOrderRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .map(path -> path.replace(".<list element>", ""))
                .collect(java.util.stream.Collectors.toSet());
    }
}
