package com.veterinaria.pacientes.controller;

import com.veterinaria.pacientes.model.Paciente;
import com.veterinaria.pacientes.service.PacienteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pacientes")
public class PacienteController {

    private final PacienteService pacienteService;

    public PacienteController(PacienteService pacienteService) {
        this.pacienteService = pacienteService;
    }

    // ── Endpoint de negocio 1: listar todos los pacientes ───────────────────
    @GetMapping
    public ResponseEntity<List<Paciente>> listar() {
        return ResponseEntity.ok(pacienteService.listarTodos());
    }

    // ── Endpoint de negocio 2: buscar paciente por ID ────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Paciente> buscarPorId(@PathVariable String id) {
        return pacienteService.buscarPorId(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Endpoint de negocio 3: registrar nuevo paciente ──────────────────────
    @PostMapping
    public ResponseEntity<Paciente> registrar(@RequestBody Paciente paciente) {
        Paciente creado = pacienteService.registrar(paciente);
        return ResponseEntity.status(HttpStatus.CREATED).body(creado);
    }

    // ── Endpoint de negocio 4: actualizar datos de un paciente ───────────────
    @PutMapping("/{id}")
    public ResponseEntity<Paciente> actualizar(@PathVariable String id,
                                               @RequestBody Paciente datos) {
        return pacienteService.actualizar(id, datos)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Endpoint de negocio 5: eliminar paciente ─────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable String id) {
        if (pacienteService.eliminar(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    // ── Endpoint de negocio 6: conteo por especie ────────────────────────────
    @GetMapping("/estadisticas/especies")
    public ResponseEntity<Map<String, Long>> contarPorEspecie() {
        return ResponseEntity.ok(pacienteService.contarPorEspecie());
    }

    // ── Endpoint interno: verificar existencia (lo usa citas-service) ────────
    @GetMapping("/{id}/existe")
    public ResponseEntity<Map<String, Object>> existePaciente(@PathVariable String id) {
        boolean existe = pacienteService.existePaciente(id);
        Map<String, Object> respuesta = Map.of(
            "id", id,
            "existe", existe
        );
        return ResponseEntity.ok(respuesta);
    }

    // ── Casuística 2: configurar delay artificial vía API ────────────────────
    @PostMapping("/admin/delay")
    public ResponseEntity<Map<String, Object>> configurarDelay(@RequestParam long ms) {
        pacienteService.setDelayActualMs(ms);
        return ResponseEntity.ok(Map.of(
            "mensaje", "Delay artificial configurado",
            "delayMs", ms
        ));
    }
}
