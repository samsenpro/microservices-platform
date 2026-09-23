package com.samsenpro.platform.order.web.dto;

import com.samsenpro.platform.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(@Schema(example = "PROCESSING") @NotNull OrderStatus status) {
}
