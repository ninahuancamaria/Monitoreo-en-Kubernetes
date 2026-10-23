package com.veterinaria.citas.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Health check propio — responde rápido sin depender de pacientes-service.
 * Lo usan los probes de Kubernetes (liveness y readiness).
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "servicio", "citas-service",
            "timestamp", Instant.now().toString()
        ));
    }
}
