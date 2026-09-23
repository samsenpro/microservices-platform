package com.samsenpro.platform.product.chaos;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mecanismo de DESARROLLO para simular fallos de product-service. Solo existe con el perfil "chaos"
 * (docker-compose.chaos.yml) y solo acepta peticiones desde el propio contenedor (loopback): no se
 * expone por el gateway ni a la red.
 */
@Hidden
@RestController
@Profile("chaos")
@RequestMapping(ChaosController.PATH)
public class ChaosController {

    public static final String PATH = "/internal/chaos";
    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    private final AtomicReference<ChaosSettings> settings;

    ChaosController(AtomicReference<ChaosSettings> chaosSettings) {
        this.settings = chaosSettings;
    }

    @GetMapping
    ChaosSettings current() {
        return settings.get();
    }

    @PutMapping
    ChaosSettings configure(@Valid @RequestBody ChaosSettings requested) {
        settings.set(requested);
        log.warn("Chaos settings changed: {}", requested);
        return requested;
    }

    @DeleteMapping
    ChaosSettings reset() {
        settings.set(ChaosSettings.OFF);
        log.warn("Chaos settings reset");
        return ChaosSettings.OFF;
    }

    public static boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
