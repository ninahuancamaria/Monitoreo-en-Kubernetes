package com.veterinaria.citas.service;

import com.veterinaria.citas.client.PacientesClient;
import com.veterinaria.citas.model.Cita;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CitaService {

    private static final Logger log = LoggerFactory.getLogger(CitaService.class);

    // Almacenamiento en memoria
    private final Map<String, Cita> citas = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(200);

    private final PacientesClient pacientesClient;

    // ── Métricas Micrometer ──────────────────────────────────────────────────

    // Counter: total de citas agendadas exitosamente
    private final Counter citasAgendadasTotal;

    // Counter: total de citas rechazadas porque el paciente no existe
    private final Counter citasRechazadasPacienteInvalido;

    // Counter: total de errores de comunicación con pacientes-service
    private final Counter erroresComunicacionPacientes;

    // Timer: latencia de la llamada completa a pacientes-service
    private final Timer latenciaLlamadaPacientes;

    // Gauge: citas en estado PENDIENTE (número en tiempo real)
    private final AtomicInteger citasPendientes = new AtomicInteger(0);

    // Gauge: citas en estado CONFIRMADA
    private final AtomicInteger citasConfirmadas = new AtomicInteger(0);

    public CitaService(PacientesClient pacientesClient, MeterRegistry registry) {
        this.pacientesClient = pacientesClient;

        this.citasAgendadasTotal = Counter.builder("citas_agendadas_total")
                .description("Total de citas agendadas exitosamente")
                .tag("servicio", "citas-service")
                .register(registry);

        this.citasRechazadasPacienteInvalido = Counter.builder("citas_rechazadas_paciente_invalido_total")
                .description("Citas rechazadas porque el paciente no existe o no pudo verificarse")
                .tag("servicio", "citas-service")
                .register(registry);

        this.erroresComunicacionPacientes = Counter.builder("citas_errores_comunicacion_pacientes_total")
                .description("Errores de comunicación con pacientes-service (timeouts, conexión rechazada)")
                .tag("servicio", "citas-service")
                .register(registry);

        this.latenciaLlamadaPacientes = Timer.builder("citas_latencia_llamada_pacientes_segundos")
                .description("Latencia de las llamadas HTTP hacia pacientes-service")
                .tag("servicio", "citas-service")
                .register(registry);

        // Gauge: citas pendientes
        Gauge.builder("citas_pendientes_actual", citasPendientes, AtomicInteger::get)
                .description("Número actual de citas en estado PENDIENTE")
                .tag("servicio", "citas-service")
                .register(registry);

        // Gauge: citas confirmadas
        Gauge.builder("citas_confirmadas_actual", citasConfirmadas, AtomicInteger::get)
                .description("Número actual de citas en estado CONFIRMADA")
                .tag("servicio", "citas-service")
                .register(registry);
    }

    @PostConstruct
    public void inicializarDatos() {
        // Citas de muestra (usan IDs de pacientes precargados en pacientes-service)
        Cita c1 = new Cita(null, "PAC-100", "Dr. Ramírez", "2026-10-25", "09:00", "Vacunación anual");
        Cita c2 = new Cita(null, "PAC-101", "Dra. Morales", "2026-10-26", "10:30", "Revisión general");
        Cita c3 = new Cita(null, "PAC-102", "Dr. Ramírez", "2026-10-27", "14:00", "Control de peso");

        // En init no llamamos a pacientes-service para no fallar si aún no arrancó
        c1.setId("CIT-200"); c1.setNombrePaciente("Fido");
        c2.setId("CIT-201"); c2.setNombrePaciente("Michi");
        c3.setId("CIT-202"); c3.setNombrePaciente("Loro");

        citas.put(c1.getId(), c1);
        citas.put(c2.getId(), c2);
        citas.put(c3.getId(), c3);

        recalcularGauges();
        log.info("Datos iniciales de citas cargados: {} citas", citas.size());
    }

    /**
     * Agenda una nueva cita. Valida que el paciente exista consultando pacientes-service.
     * Si el servicio no responde → error registrado, cita rechazada.
     */
    public AgendarResult agendar(Cita cita) {
        // Medir latencia de la llamada a pacientes-service
        long inicio = System.currentTimeMillis();
        Optional<Boolean> existeOpt;
        try {
            existeOpt = latenciaLlamadaPacientes.recordCallable(
                () -> pacientesClient.verificarExistencia(cita.getPacienteId())
            );
        } catch (Exception e) {
            existeOpt = Optional.empty();
        }
        long duracion = System.currentTimeMillis() - inicio;

        // Si no se pudo contactar pacientes-service
        if (existeOpt == null || existeOpt.isEmpty()) {
            erroresComunicacionPacientes.increment();
            citasRechazadasPacienteInvalido.increment();
            log.error("No se pudo verificar existencia del paciente {} (timeout/error tras {}ms)",
                      cita.getPacienteId(), duracion);
            return AgendarResult.error("pacientes-service no disponible o timeout superado");
        }

        // Si el paciente no existe
        if (!existeOpt.get()) {
            citasRechazadasPacienteInvalido.increment();
            log.warn("Cita rechazada: paciente {} no existe", cita.getPacienteId());
            return AgendarResult.error("El paciente con ID " + cita.getPacienteId() + " no existe");
        }

        // Paciente válido → obtener nombre y agendar
        String nombrePaciente = pacientesClient.obtenerNombrePaciente(cita.getPacienteId());
        cita.setId("CIT-" + idCounter.getAndIncrement());
        cita.setNombrePaciente(nombrePaciente);
        cita.setEstado("PENDIENTE");
        citas.put(cita.getId(), cita);
        citasAgendadasTotal.increment();
        recalcularGauges();

        log.info("Cita agendada: {} para paciente {} ({})", cita.getId(), cita.getPacienteId(), nombrePaciente);
        return AgendarResult.ok(cita);
    }

    public List<Cita> listarTodas() {
        return new ArrayList<>(citas.values());
    }

    public Optional<Cita> buscarPorId(String id) {
        return Optional.ofNullable(citas.get(id));
    }

    public Optional<Cita> cambiarEstado(String id, String nuevoEstado) {
        return Optional.ofNullable(citas.get(id)).map(cita -> {
            cita.setEstado(nuevoEstado);
            recalcularGauges();
            return cita;
        });
    }

    public boolean cancelar(String id) {
        Optional<Cita> cita = Optional.ofNullable(citas.get(id));
        cita.ifPresent(c -> {
            c.setEstado("CANCELADA");
            recalcularGauges();
        });
        return cita.isPresent();
    }

    public List<Cita> buscarPorPaciente(String pacienteId) {
        return citas.values().stream()
                .filter(c -> pacienteId.equals(c.getPacienteId()))
                .toList();
    }

    private void recalcularGauges() {
        citasPendientes.set((int) citas.values().stream()
                .filter(c -> "PENDIENTE".equals(c.getEstado())).count());
        citasConfirmadas.set((int) citas.values().stream()
                .filter(c -> "CONFIRMADA".equals(c.getEstado())).count());
    }

    // ── Clase de resultado para agendar ─────────────────────────────────────
    public static class AgendarResult {
        private final boolean exitoso;
        private final Cita cita;
        private final String mensajeError;

        private AgendarResult(boolean exitoso, Cita cita, String mensajeError) {
            this.exitoso = exitoso;
            this.cita = cita;
            this.mensajeError = mensajeError;
        }

        public static AgendarResult ok(Cita cita) {
            return new AgendarResult(true, cita, null);
        }

        public static AgendarResult error(String mensaje) {
            return new AgendarResult(false, null, mensaje);
        }

        public boolean isExitoso() { return exitoso; }
        public Cita getCita() { return cita; }
        public String getMensajeError() { return mensajeError; }
    }
}
