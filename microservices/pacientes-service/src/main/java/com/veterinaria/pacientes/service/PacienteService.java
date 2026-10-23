package com.veterinaria.pacientes.service;

import com.veterinaria.pacientes.model.Paciente;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PacienteService {

    private static final Logger log = LoggerFactory.getLogger(PacienteService.class);

    // Almacenamiento en memoria
    private final Map<String, Paciente> pacientes = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(100);

    // Delay artificial para la Casuística 2
    @Value("${app.pacientes.artificial-delay-ms:0}")
    private long artificialDelayMs;

    // ── Métricas Micrometer ──────────────────────────────────────────────────

    // Counter: total de pacientes registrados desde que arrancó el servicio
    private final Counter pacientesRegistradosTotal;

    // Counter: total de consultas de paciente por ID
    private final Counter consultasPorIdTotal;

    // Counter: consultas fallidas (paciente no encontrado)
    private final Counter consultasNoEncontradasTotal;

    // Timer: tiempo de procesamiento del registro de un paciente
    private final Timer tiempoRegistroPaciente;

    // Gauge: número actual de pacientes activos (se actualiza en cada operación)
    private final AtomicInteger pacientesActivos = new AtomicInteger(0);

    // Gauge: delay artificial actual (visible en Grafana para la Casuística 2)
    // Se expone como AtomicLong para poder actualizarse dinámicamente
    private volatile long delayActualMs = 0;

    public PacienteService(MeterRegistry registry) {
        this.pacientesRegistradosTotal = Counter.builder("pacientes_registrados_total")
                .description("Total de pacientes registrados desde el inicio del servicio")
                .tag("servicio", "pacientes-service")
                .register(registry);

        this.consultasPorIdTotal = Counter.builder("pacientes_consultas_por_id_total")
                .description("Total de consultas de paciente por ID")
                .tag("servicio", "pacientes-service")
                .register(registry);

        this.consultasNoEncontradasTotal = Counter.builder("pacientes_no_encontrados_total")
                .description("Total de consultas donde el paciente no fue encontrado")
                .tag("servicio", "pacientes-service")
                .register(registry);

        this.tiempoRegistroPaciente = Timer.builder("pacientes_registro_duracion_segundos")
                .description("Tiempo que tarda en procesarse el registro de un paciente")
                .tag("servicio", "pacientes-service")
                .register(registry);

        // Gauge: refleja el valor actual del AtomicInteger pacientesActivos
        Gauge.builder("pacientes_activos_actual", pacientesActivos, AtomicInteger::get)
                .description("Número actual de pacientes con estado ACTIVO en el sistema")
                .tag("servicio", "pacientes-service")
                .register(registry);

        // Gauge: delay artificial en milisegundos (para Casuística 2)
        Gauge.builder("pacientes_delay_artificial_ms", this, PacienteService::getDelayActualMs)
                .description("Delay artificial configurado en el servicio de pacientes (ms)")
                .tag("servicio", "pacientes-service")
                .register(registry);
    }

    @PostConstruct
    public void inicializarDatos() {
        this.delayActualMs = artificialDelayMs;
        log.info("Delay artificial configurado: {} ms", artificialDelayMs);

        // Datos de muestra en memoria
        registrar(new Paciente(null, "Fido", "perro", "Labrador", 3, "Juan Pérez", "099-111-2222"));
        registrar(new Paciente(null, "Michi", "gato", "Siamés", 5, "Ana García", "099-333-4444"));
        registrar(new Paciente(null, "Loro", "ave", "Loro verde", 2, "Carlos López", "099-555-6666"));
        registrar(new Paciente(null, "Rocky", "perro", "Pastor Alemán", 7, "María Torres", "099-777-8888"));
        log.info("Datos iniciales cargados: {} pacientes", pacientes.size());
    }

    public Paciente registrar(Paciente paciente) {
        return tiempoRegistroPaciente.record(() -> {
            if (paciente.getId() == null || paciente.getId().isBlank()) {
                paciente.setId("PAC-" + idCounter.getAndIncrement());
            }
            paciente.setEstado("ACTIVO");
            pacientes.put(paciente.getId(), paciente);
            pacientesRegistradosTotal.increment();
            recalcularActivos();
            log.info("Paciente registrado: {} - {}", paciente.getId(), paciente.getNombre());
            return paciente;
        });
    }

    public Optional<Paciente> buscarPorId(String id) {
        // Aplicar delay artificial si está configurado (Casuística 2)
        if (delayActualMs > 0) {
            try {
                log.warn("Aplicando delay artificial de {} ms", delayActualMs);
                Thread.sleep(delayActualMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        consultasPorIdTotal.increment();
        Optional<Paciente> resultado = Optional.ofNullable(pacientes.get(id));
        if (resultado.isEmpty()) {
            consultasNoEncontradasTotal.increment();
            log.warn("Paciente no encontrado: {}", id);
        }
        return resultado;
    }

    public List<Paciente> listarTodos() {
        return new ArrayList<>(pacientes.values());
    }

    public Optional<Paciente> actualizar(String id, Paciente datos) {
        return Optional.ofNullable(pacientes.get(id)).map(existente -> {
            existente.setNombre(datos.getNombre());
            existente.setEspecie(datos.getEspecie());
            existente.setRaza(datos.getRaza());
            existente.setEdadAnios(datos.getEdadAnios());
            existente.setPropietario(datos.getPropietario());
            existente.setTelefono(datos.getTelefono());
            recalcularActivos();
            return existente;
        });
    }

    public boolean eliminar(String id) {
        boolean eliminado = pacientes.remove(id) != null;
        if (eliminado) recalcularActivos();
        return eliminado;
    }

    public boolean existePaciente(String id) {
        return pacientes.containsKey(id);
    }

    public Map<String, Long> contarPorEspecie() {
        Map<String, Long> conteo = new HashMap<>();
        pacientes.values().forEach(p ->
            conteo.merge(p.getEspecie(), 1L, Long::sum)
        );
        return conteo;
    }

    // Actualiza el gauge de pacientes activos
    private void recalcularActivos() {
        long activos = pacientes.values().stream()
                .filter(p -> "ACTIVO".equals(p.getEstado()))
                .count();
        pacientesActivos.set((int) activos);
    }

    public long getDelayActualMs() {
        return delayActualMs;
    }

    public void setDelayActualMs(long ms) {
        this.delayActualMs = ms;
        log.warn("Delay artificial actualizado a {} ms", ms);
    }
}
