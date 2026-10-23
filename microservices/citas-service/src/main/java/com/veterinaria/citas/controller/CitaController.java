package com.veterinaria.citas.controller;

import com.veterinaria.citas.model.Cita;
import com.veterinaria.citas.service.CitaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/citas")
public class CitaController {

    private final CitaService citaService;

    public CitaController(CitaService citaService) {
        this.citaService = citaService;
    }

    // ── Endpoint 1: listar todas las citas ───────────────────────────────────
    @GetMapping
    public ResponseEntity<List<Cita>> listar() {
        return ResponseEntity.ok(citaService.listarTodas());
    }

    // ── Endpoint 2: buscar cita por ID ───────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Cita> buscarPorId(@PathVariable String id) {
        return citaService.buscarPorId(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Endpoint 3: agendar nueva cita (llama a pacientes-service) ───────────
    @PostMapping
    public ResponseEntity<Object> agendar(@RequestBody Cita cita) {
        CitaService.AgendarResult resultado = citaService.agendar(cita);
        if (resultado.isExitoso()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(resultado.getCita());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", resultado.getMensajeError()));
    }

    // ── Endpoint 4: cambiar estado de una cita ───────────────────────────────
    @PatchMapping("/{id}/estado")
    public ResponseEntity<Object> cambiarEstado(@PathVariable String id,
                                                @RequestParam String estado) {
        return citaService.cambiarEstado(id, estado)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Endpoint 5: cancelar una cita ────────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelar(@PathVariable String id) {
        if (citaService.cancelar(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    // ── Endpoint 6: citas de un paciente específico ──────────────────────────
    @GetMapping("/paciente/{pacienteId}")
    public ResponseEntity<List<Cita>> buscarPorPaciente(@PathVariable String pacienteId) {
        return ResponseEntity.ok(citaService.buscarPorPaciente(pacienteId));
    }
}
