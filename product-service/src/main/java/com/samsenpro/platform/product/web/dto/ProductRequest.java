package com.samsenpro.platform.product.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductRequest(
        @Schema(example = "Mechanical keyboard")
        @NotBlank @Size(max = 120)
        String name,

        @Schema(example = "Hot-swappable switches, ISO layout")
        @Size(max = 1000)
        String description,

        @Schema(example = "89.90")
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2)
        BigDecimal price,

        @Schema(example = "25")
        @NotNull @Min(0) @Max(1_000_000)
        Integer stock,

        @Schema(description = "Si se omite, el producto se crea activo", example = "true")
        Boolean active) {

    public boolean activeOrDefault() {
        return active == null || active;
    }
}
