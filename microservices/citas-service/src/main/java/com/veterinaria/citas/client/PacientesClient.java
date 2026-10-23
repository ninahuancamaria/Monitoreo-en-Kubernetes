package com.veterinaria.citas.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente HTTP que llama a pacientes-service usando WebClient.
 * Aplica timeout configurable y manejo de errores controlado.
 */
@Component
public class PacientesClient {

    private static final Logger log = LoggerFactory.getLogger(PacientesClient.class);

    private final WebClient webClient;
    private final long timeoutSegundos;

    public PacientesClient(
            @Value("${app.pacientes.url}") String pacientesUrl,
            @Value("${app.pacientes.timeout-segundos:3}") long timeoutSegundos) {

        this.webClient = WebClient.builder()
                .baseUrl(pacientesUrl)
                .build();
        this.timeoutSegundos = timeoutSegundos;
        log.info("PacientesClient configurado → URL: {}, timeout: {}s", pacientesUrl, timeoutSegundos);
    }

    /**
     * Verifica si un paciente existe en pacientes-service.
     * Retorna Optional.empty() si el servicio no responde o hay error.
     */
    @SuppressWarnings("unchecked")
    public Optional<Boolean> verificarExistencia(String pacienteId) {
        try {
            Map<String, Object> respuesta = webClient.get()
                    .uri("/api/pacientes/{id}/existe", pacienteId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(timeoutSegundos))
                    .onErrorResume(e -> {
                        log.error("Error al contactar pacientes-service para ID {}: {}", pacienteId, e.getMessage());
                        return Mono.empty();
                    })
                    .block();

            if (respuesta == null) {
                return Optional.empty();
            }
            return Optional.of((Boolean) respuesta.get("existe"));

        } catch (Exception e) {
            log.error("Excepción al verificar paciente {}: {}", pacienteId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Obtiene el nombre del paciente desde pacientes-service.
     * Retorna "Desconocido" si el servicio no está disponible.
     */
    @SuppressWarnings("unchecked")
    public String obtenerNombrePaciente(String pacienteId) {
        try {
            Map<String, Object> paciente = webClient.get()
                    .uri("/api/pacientes/{id}", pacienteId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(timeoutSegundos))
                    .onErrorResume(e -> {
                        log.error("Timeout/error al obtener nombre de paciente {}: {}", pacienteId, e.getMessage());
                        return Mono.empty();
                    })
                    .block();

            if (paciente == null) return "No disponible";
            return (String) paciente.getOrDefault("nombre", "Sin nombre");

        } catch (Exception e) {
            log.error("Excepción al obtener nombre del paciente {}: {}", pacienteId, e.getMessage());
            return "No disponible";
        }
    }
}
