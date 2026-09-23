package com.samsenpro.platform.user.web;

import com.samsenpro.platform.commons.error.ApiError;
import com.samsenpro.platform.commons.security.AuthenticatedUser;
import com.samsenpro.platform.commons.web.PageResponse;
import com.samsenpro.platform.user.service.AuthService;
import com.samsenpro.platform.user.service.UserService;
import com.samsenpro.platform.user.web.dto.LoginRequest;
import com.samsenpro.platform.user.web.dto.RegisterRequest;
import com.samsenpro.platform.user.web.dto.TokenResponse;
import com.samsenpro.platform.user.web.dto.UpdateUserRequest;
import com.samsenpro.platform.user.web.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Registro, autenticación y administración de usuarios")
@ApiResponse(responseCode = "400", description = "Petición inválida",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
class UserController {

    private final UserService userService;
    private final AuthService authService;

    UserController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Registrar un usuario", description = "Crea un usuario con rol USER. Limitado por IP en el gateway.")
    @ApiResponse(responseCode = "201", description = "Usuario creado")
    @ApiResponse(responseCode = "409", description = "Username o email ya registrados (USERNAME_TAKEN, EMAIL_TAKEN)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "Demasiadas peticiones desde la misma IP (gateway)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return userService.register(request);
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Iniciar sesión", description = "Devuelve un JWT (HS256) con sub, role, iss, iat y exp.")
    @ApiResponse(responseCode = "200", description = "Token emitido")
    @ApiResponse(responseCode = "401", description = "Credenciales inválidas (INVALID_CREDENTIALS)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "Demasiados intentos desde la misma IP (gateway)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Usuario autenticado")
    @ApiResponse(responseCode = "200", description = "Datos del usuario del token")
    @ApiResponse(responseCode = "401", description = "Token ausente, inválido o caducado",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    UserResponse me() {
        AuthenticatedUser caller = AuthenticatedUser.current();
        return userService.get(caller.id(), caller);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtener un usuario", description = "ADMIN: cualquier usuario. USER: solo a sí mismo.")
    @ApiResponse(responseCode = "200", description = "Usuario encontrado")
    @ApiResponse(responseCode = "404", description = "No existe o no es visible para el usuario (USER_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    UserResponse get(@PathVariable UUID id) {
        return userService.get(id, AuthenticatedUser.current());
    }

    @GetMapping
    @Operation(summary = "Listar usuarios (ADMIN)")
    @ApiResponse(responseCode = "200", description = "Página de usuarios")
    @ApiResponse(responseCode = "403", description = "Requiere rol ADMIN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    PageResponse<UserResponse> list(@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                    Pageable pageable) {
        return userService.list(pageable);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Cambiar rol o estado de un usuario (ADMIN)")
    @ApiResponse(responseCode = "200", description = "Usuario actualizado")
    @ApiResponse(responseCode = "403", description = "Requiere rol ADMIN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Usuario no encontrado (USER_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    UserResponse update(@PathVariable UUID id, @RequestBody UpdateUserRequest request) {
        return userService.update(id, request, AuthenticatedUser.current());
    }
}
