package com.samsenpro.platform.order.service;

import com.samsenpro.platform.commons.error.ApiException;
import com.samsenpro.platform.commons.security.AuthenticatedUser;
import com.samsenpro.platform.commons.web.PageResponse;
import com.samsenpro.platform.order.client.ProductSnapshot;
import com.samsenpro.platform.order.client.ResilientProductClient;
import com.samsenpro.platform.order.domain.Order;
import com.samsenpro.platform.order.domain.OrderItem;
import com.samsenpro.platform.order.domain.OrderRepository;
import com.samsenpro.platform.order.domain.OrderStatus;
import com.samsenpro.platform.order.web.dto.CreateOrderRequest;
import com.samsenpro.platform.order.web.dto.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final ResilientProductClient products;

    public OrderService(OrderRepository orders, ResilientProductClient products) {
        this.orders = orders;
        this.products = products;
    }

    /**
     * Valida cada producto contra product-service (existe, activo, precio, stock) y guarda el pedido.
     * Deliberadamente NO es transaccional: las llamadas remotas se hacen antes de abrir la transacción,
     * para no retener una conexión a la base de datos mientras se espera a otro servicio.
     */
    public OrderResponse create(CreateOrderRequest request, AuthenticatedUser caller) {
        List<OrderItem> items = new ArrayList<>();
        mergeQuantities(request).forEach((productId, quantity) -> {
            ProductSnapshot product = products.getProduct(productId);
            if (!product.active()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_INACTIVE",
                        "Product " + productId + " is not available for sale");
            }
            if (product.stock() < quantity) {
                throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                        "Product " + productId + " has only " + product.stock() + " units in stock");
            }
            items.add(new OrderItem(productId, quantity, product.price()));
        });

        Order order = orders.save(new Order(caller.id(), items));
        log.info("Order {} created for user {} with {} items, total {}", order.getId(), caller.id(),
                items.size(), order.getTotal());
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id, AuthenticatedUser caller) {
        return OrderResponse.from(findVisible(id, caller));
    }

    /** Un USER ve sus pedidos; un ADMIN ve todos. */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(Pageable pageable, AuthenticatedUser caller) {
        Page<Order> page = caller.isAdmin() ? orders.findAll(pageable) : orders.findByUserId(caller.id(), pageable);
        return new PageResponse<>(page.map(OrderResponse::from).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Transactional
    public OrderResponse cancel(UUID id, AuthenticatedUser caller) {
        Order order = findVisible(id, caller);
        order.transitionTo(OrderStatus.CANCELLED);
        log.info("Order {} cancelled by {}", id, caller.id());
        return OrderResponse.from(orders.saveAndFlush(order));
    }

    @Transactional
    public OrderResponse changeStatus(UUID id, OrderStatus target, AuthenticatedUser caller) {
        Order order = findVisible(id, caller);
        OrderStatus previous = order.getStatus();
        order.transitionTo(target);
        log.info("Order {} changed from {} to {} by admin {}", id, previous, target, caller.id());
        return OrderResponse.from(orders.saveAndFlush(order));
    }

    /** Los pedidos de otros usuarios se tratan como inexistentes: no se revela que existen. */
    private Order findVisible(UUID id, AuthenticatedUser caller) {
        return orders.findById(id)
                .filter(order -> caller.isAdmin() || order.isOwnedBy(caller.id()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found"));
    }

    /** Si el mismo producto aparece dos veces en la petición, se suma en una sola línea. */
    private static Map<Long, Integer> mergeQuantities(CreateOrderRequest request) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        request.items().forEach(item -> quantities.merge(item.productId(), item.quantity(), Integer::sum));
        return quantities;
    }
}
