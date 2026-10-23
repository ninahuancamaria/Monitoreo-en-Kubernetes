package com.veterinaria.citas.model;

public class Cita {

    private String id;
    private String pacienteId;
    private String nombrePaciente;    // se rellena consultando pacientes-service
    private String veterinario;
    private String fecha;             // formato: YYYY-MM-DD
    private String hora;              // formato: HH:mm
    private String motivo;
    private String estado;            // PENDIENTE, CONFIRMADA, CANCELADA, ATENDIDA

    public Cita() {}

    public Cita(String id, String pacienteId, String veterinario,
                String fecha, String hora, String motivo) {
        this.id = id;
        this.pacienteId = pacienteId;
        this.veterinario = veterinario;
        this.fecha = fecha;
        this.hora = hora;
        this.motivo = motivo;
        this.estado = "PENDIENTE";
    }

    // Getters y Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPacienteId() { return pacienteId; }
    public void setPacienteId(String pacienteId) { this.pacienteId = pacienteId; }

    public String getNombrePaciente() { return nombrePaciente; }
    public void setNombrePaciente(String nombrePaciente) { this.nombrePaciente = nombrePaciente; }

    public String getVeterinario() { return veterinario; }
    public void setVeterinario(String veterinario) { this.veterinario = veterinario; }

    public String getFecha() { return fecha; }
    public void setFecha(String fecha) { this.fecha = fecha; }

    public String getHora() { return hora; }
    public void setHora(String hora) { this.hora = hora; }

    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
