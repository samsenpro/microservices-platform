package com.samsenpro.platform.product.web;

import com.samsenpro.platform.commons.error.ApiError;
import com.samsenpro.platform.commons.security.AuthenticatedUser;
import com.samsenpro.platform.commons.web.PageResponse;
import com.samsenpro.platform.product.service.ProductService;
import com.samsenpro.platform.product.web.dto.ProductRequest;
import com.samsenpro.platform.product.web.dto.ProductResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Products", description = "Catálogo. GET: USER/ADMIN. POST, PUT, DELETE: ADMIN.")
@ApiResponse(responseCode = "401", description = "Token ausente, inválido o caducado",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
class ProductController {

    private final ProductService productService;

    ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(summary = "Listar productos", description = "USER ve solo los activos; ADMIN ve todos.")
    @ApiResponse(responseCode = "200", description = "Página de productos")
    PageResponse<ProductResponse> list(@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC)
                                       Pageable pageable) {
        return productService.list(pageable, AuthenticatedUser.current());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtener un producto", description = "Incluye el campo active y el stock disponible.")
    @ApiResponse(responseCode = "200", description = "Producto encontrado")
    @ApiResponse(responseCode = "404", description = "No existe (PRODUCT_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
    }

    @PostMapping
    @Operation(summary = "Crear un producto (ADMIN)")
    @ApiResponse(responseCode = "201", description = "Producto creado")
    @ApiResponse(responseCode = "400", description = "Datos inválidos (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Requiere rol ADMIN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar un producto (ADMIN)")
    @ApiResponse(responseCode = "200", description = "Producto actualizado")
    @ApiResponse(responseCode = "403", description = "Requiere rol ADMIN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No existe (PRODUCT_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Modificación concurrente (CONCURRENT_MODIFICATION)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desactivar un producto (ADMIN)",
            description = "Borrado lógico: el producto deja de poder comprarse, pero los pedidos existentes lo conservan.")
    @ApiResponse(responseCode = "204", description = "Producto desactivado")
    @ApiResponse(responseCode = "403", description = "Requiere rol ADMIN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No existe (PRODUCT_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
