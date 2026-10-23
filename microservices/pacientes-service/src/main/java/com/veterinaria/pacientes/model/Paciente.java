package com.veterinaria.pacientes.model;

public class Paciente {

    private String id;
    private String nombre;
    private String especie;      // perro, gato, ave, etc.
    private String raza;
    private int edadAnios;
    private String propietario;
    private String telefono;
    private String estado;       // ACTIVO, INACTIVO

    public Paciente() {}

    public Paciente(String id, String nombre, String especie, String raza,
                    int edadAnios, String propietario, String telefono) {
        this.id = id;
        this.nombre = nombre;
        this.especie = especie;
        this.raza = raza;
        this.edadAnios = edadAnios;
        this.propietario = propietario;
        this.telefono = telefono;
        this.estado = "ACTIVO";
    }

    // Getters y Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getEspecie() { return especie; }
    public void setEspecie(String especie) { this.especie = especie; }

    public String getRaza() { return raza; }
    public void setRaza(String raza) { this.raza = raza; }

    public int getEdadAnios() { return edadAnios; }
    public void setEdadAnios(int edadAnios) { this.edadAnios = edadAnios; }

    public String getPropietario() { return propietario; }
    public void setPropietario(String propietario) { this.propietario = propietario; }

    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
