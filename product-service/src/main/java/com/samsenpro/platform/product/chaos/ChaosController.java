package com.samsenpro.platform.product.chaos;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * POST con parámetros de query (sin cuerpo JSON) para poder usarlo con el wget de busybox de la imagen
     * y desde cualquier shell: {@code POST /internal/chaos?mode=DELAY&delayMs=3000&failureRate=1.0}.
     * {@code mode=NONE} lo desactiva.
     */
    @PostMapping
    ChaosSettings configure(@RequestParam ChaosSettings.Mode mode,
                            @RequestParam(defaultValue = "0") @Min(0) @Max(30_000) long delayMs,
                            @RequestParam(defaultValue = "1.0") @DecimalMin("0.0") @DecimalMax("1.0") double failureRate) {
        ChaosSettings requested = new ChaosSettings(mode, delayMs, failureRate);
        settings.set(requested);
        log.warn("Chaos settings changed: {}", requested);
        return requested;
    }

    public static boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
