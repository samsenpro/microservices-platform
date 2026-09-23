package com.samsenpro.platform.order.web;

import com.samsenpro.platform.commons.error.ApiError;
import com.samsenpro.platform.commons.security.AuthenticatedUser;
import com.samsenpro.platform.commons.web.PageResponse;
import com.samsenpro.platform.order.service.OrderService;
import com.samsenpro.platform.order.web.dto.CreateOrderRequest;
import com.samsenpro.platform.order.web.dto.OrderResponse;
import com.samsenpro.platform.order.web.dto.UpdateStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Pedidos. Un USER gestiona los suyos; un ADMIN, todos.")
@ApiResponse(responseCode = "401", description = "Token ausente, inválido o caducado",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Crear un pedido",
            description = "Consulta product-service (vía Eureka, con timeout, retry, circuit breaker y bulkhead) "
                    + "para validar existencia, estado, precio y stock de cada producto.")
    @ApiResponse(responseCode = "201", description = "Pedido creado con estado CREATED")
    @ApiResponse(responseCode = "400", description = "Petición inválida (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Stock insuficiente (INSUFFICIENT_STOCK)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "Producto inexistente o inactivo (PRODUCT_NOT_FOUND, PRODUCT_INACTIVE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "product-service falló (PRODUCT_SERVICE_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "product-service no disponible, circuito abierto o saturado "
            + "(PRODUCT_SERVICE_UNAVAILABLE, PRODUCT_SERVICE_BUSY)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "504", description = "product-service no respondió a tiempo (PRODUCT_SERVICE_TIMEOUT)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.create(request, AuthenticatedUser.current());
        return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "Listar pedidos", description = "USER: los suyos. ADMIN: todos.")
    @ApiResponse(responseCode = "200", description = "Página de pedidos")
    PageResponse<OrderResponse> list(@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                     Pageable pageable) {
        return orderService.list(pageable, AuthenticatedUser.current());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtener un pedido", description = "Solo el dueño o un ADMIN.")
    @ApiResponse(responseCode = "200", description = "Pedido encontrado")
    @ApiResponse(responseCode = "404", description = "No existe o pertenece a otro usuario (ORDER_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    OrderResponse get(@PathVariable UUID id) {
        return orderService.get(id, AuthenticatedUser.current());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancelar un pedido", description = "Solo pedidos en estado CREATED; dueño o ADMIN.")
    @ApiResponse(responseCode = "200", description = "Pedido cancelado")
    @ApiResponse(responseCode = "404", description = "No existe o pertenece a otro usuario (ORDER_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Transición no permitida (INVALID_STATUS_TRANSITION)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    OrderResponse cancel(@PathVariable UUID id) {
        return orderService.cancel(id, AuthenticatedUser.current());
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Cambiar el estado de un pedido (ADMIN)",
            description = "CREATED → PROCESSING | CANCELLED; PROCESSING → COMPLETED | FAILED.")
    @ApiResponse(responseCode = "200", description = "Estado actualizado")
    @ApiResponse(responseCode = "403", description = "Requiere rol ADMIN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Transición no permitida (INVALID_STATUS_TRANSITION)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    OrderResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return orderService.changeStatus(id, request.status(), AuthenticatedUser.current());
    }
}
