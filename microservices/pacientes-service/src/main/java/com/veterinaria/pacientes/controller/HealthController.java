package com.veterinaria.pacientes.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Endpoint de health check propio — responde rápido y sin depender
 * de otros servicios. Lo usan los probes de Kubernetes.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "servicio", "pacientes-service",
            "timestamp", Instant.now().toString()
        ));
    }
}
