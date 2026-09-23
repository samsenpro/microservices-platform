package com.samsenpro.platform.product.service;

import com.samsenpro.platform.commons.error.ApiException;
import com.samsenpro.platform.commons.security.AuthenticatedUser;
import com.samsenpro.platform.commons.web.PageResponse;
import com.samsenpro.platform.product.domain.Product;
import com.samsenpro.platform.product.domain.ProductRepository;
import com.samsenpro.platform.product.web.dto.ProductRequest;
import com.samsenpro.platform.product.web.dto.ProductResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    /** Un USER solo ve el catálogo activo; un ADMIN ve también los productos desactivados. */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(Pageable pageable, AuthenticatedUser caller) {
        Page<Product> page = caller.isAdmin() ? products.findAll(pageable) : products.findByActiveTrue(pageable);
        return new PageResponse<>(page.map(ProductResponse::from).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /**
     * Devuelve también productos inactivos (con {@code active=false}): order-service necesita distinguir
     * "no existe" de "existe pero no se puede comprar".
     */
    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        return ProductResponse.from(find(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = products.save(new Product(request.name(), request.description(), request.price(),
                request.stock(), request.activeOrDefault()));
        log.info("Product {} created", product.getId());
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = find(id);
        product.update(request.name(), request.description(), request.price(), request.stock(),
                request.activeOrDefault());
        log.info("Product {} updated", id);
        return ProductResponse.from(products.saveAndFlush(product));
    }

    @Transactional
    public void deactivate(Long id) {
        Product product = find(id);
        product.deactivate();
        log.info("Product {} deactivated", id);
    }

    private Product find(Long id) {
        return products.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product " + id + " not found"));
    }
}
