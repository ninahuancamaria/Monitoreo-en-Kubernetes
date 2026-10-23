# Clínica Veterinaria — Microservicios Java con Monitoreo en Kubernetes

**Curso:** Kubernetes con Prometheus & Grafana — Valle Grande  
**Dominio:** Sistema de gestión de pacientes y citas para clínica veterinaria

---

## Arquitectura del sistema

```
┌─────────────────────────────────────────────────────────────┐
│                    Namespace: veterinaria                    │
│                                                             │
│  ┌──────────────────────┐    HTTP     ┌──────────────────┐  │
│  │   citas-service      │ ──────────► │ pacientes-service│  │
│  │   Puerto: 8082        │  WebClient  │  Puerto: 8081    │  │
│  │   NodePort: 30082     │  Timeout 3s │  NodePort: 30081 │  │
│  └──────────────────────┘             └──────────────────┘  │
│           │                                    │             │
│       /metrics                             /metrics          │
└───────────┼────────────────────────────────────┼────────────┘
            │                                    │
            ▼                                    ▼
┌─────────────────────────────────────────────────────────────┐
│                  Namespace: monitoring                       │
│                                                             │
│   ServiceMonitor ──► Prometheus ──► Grafana                 │
│   (citas)            :9090          :3000                   │
│   ServiceMonitor ──► NodePort       NodePort                │
│   (pacientes)        30090          30300                   │
└─────────────────────────────────────────────────────────────┘
```

### Servicios

| Servicio | Rol | Puerto local | NodePort |
|---|---|---|---|
| `pacientes-service` | Servicio A — CRUD de mascotas/pacientes | 8081 | 30081 |
| `citas-service` | Servicio B — agenda citas, valida pacientes | 8082 | 30082 |
| Prometheus | Scraping de métricas | 9090 | 30090 |
| Grafana | Dashboards | 3000 | 30300 |

---

## Estructura del repositorio

```
Monitoreo-en-Kubernetes/
├── microservices/
│   ├── pacientes-service/          # Servicio A — Spring Boot 3.x
│   │   ├── src/main/java/com/veterinaria/pacientes/
│   │   │   ├── PacientesApplication.java
│   │   │   ├── controller/
│   │   │   │   ├── PacienteController.java
│   │   │   │   └── HealthController.java
│   │   │   ├── model/Paciente.java
│   │   │   └── service/PacienteService.java
│   │   ├── src/main/resources/application.properties
│   │   ├── pom.xml
│   │   └── Dockerfile
│   │
│   └── citas-service/              # Servicio B — Spring Boot 3.x
│       ├── src/main/java/com/veterinaria/citas/
│       │   ├── CitasApplication.java
│       │   ├── controller/
│       │   │   ├── CitaController.java
│       │   │   └── HealthController.java
│       │   ├── model/Cita.java
│       │   ├── service/CitaService.java
│       │   └── client/PacientesClient.java
│       ├── src/main/resources/application.properties
│       ├── pom.xml
│       └── Dockerfile
│
├── k8s/
│   ├── namespace/namespace.yaml
│   ├── pacientes/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── citas/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   └── monitoring/
│       ├── values.yaml                     # Config de kube-prometheus-stack
│       ├── servicemonitor-pacientes.yaml
│       ├── servicemonitor-citas.yaml
│       └── prometheus-rules.yaml
│
├── load-testing/
│   └── stress.sh                   # Generador de tráfico parametrizable
│
├── scripts/
│   ├── setup.sh                    # Levanta todo desde cero
│   └── teardown.sh                 # Limpia todos los recursos
│
└── docs/
    ├── informe.md                  # Informe técnico completo
    └── grafana-dashboard.json      # Dashboard exportado
```

---

## Instalación de herramientas (Windows)

### Docker Desktop
Descarga e instala desde: https://www.docker.com/products/docker-desktop/  
Verifica: `docker --version`

### Minikube
```powershell
winget install Kubernetes.minikube
# o descarga el binario desde https://minikube.sigs.k8s.io/docs/start/
minikube version
```

### kubectl
```powershell
winget install Kubernetes.kubectl
kubectl version --client
```

### Helm
```powershell
winget install Helm.Helm
helm version
```

---

## Inicio rápido

### Opción 1 — Script automático (Linux/macOS/WSL)
```bash
chmod +x scripts/setup.sh
./scripts/setup.sh
```

### Opción 2 — Paso a paso manual

**1. Iniciar Minikube con recursos suficientes**
```bash
minikube start --cpus=4 --memory=6144 --driver=docker
minikube addons enable metrics-server
```

**2. Instalar stack de monitoreo**
```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
kubectl create namespace monitoring
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring \
  -f k8s/monitoring/values.yaml \
  --wait --timeout=5m
```

**3. Construir imágenes (apuntando al daemon de Minikube)**
```bash
eval "$(minikube docker-env)"
docker build -t pacientes-service:1.0.0 microservices/pacientes-service/
docker build -t citas-service:1.0.0 microservices/citas-service/
```

**4. Desplegar microservicios**
```bash
kubectl apply -f k8s/namespace/namespace.yaml
kubectl apply -f k8s/pacientes/
kubectl apply -f k8s/citas/
kubectl apply -f k8s/monitoring/servicemonitor-pacientes.yaml
kubectl apply -f k8s/monitoring/servicemonitor-citas.yaml
kubectl apply -f k8s/monitoring/prometheus-rules.yaml
```

**5. Verificar pods**
```bash
kubectl get pods -n veterinaria
kubectl get pods -n monitoring
```

---

## URLs de acceso

Reemplaza `<MINIKUBE_IP>` con la salida de `minikube ip`.

| Servicio | URL | Credenciales |
|---|---|---|
| Grafana | `http://<MINIKUBE_IP>:30300` | admin / veterinaria2026 |
| Prometheus | `http://<MINIKUBE_IP>:30090` | — |
| AlertManager | `http://<MINIKUBE_IP>:30093` | — |
| pacientes-service | `http://<MINIKUBE_IP>:30081` | — |
| citas-service | `http://<MINIKUBE_IP>:30082` | — |

---

## Endpoints de la API

### pacientes-service (`:30081`)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/pacientes` | Listar todos los pacientes |
| GET | `/api/pacientes/{id}` | Buscar paciente por ID |
| POST | `/api/pacientes` | Registrar nuevo paciente |
| PUT | `/api/pacientes/{id}` | Actualizar datos |
| DELETE | `/api/pacientes/{id}` | Eliminar paciente |
| GET | `/api/pacientes/estadisticas/especies` | Conteo por especie |
| GET | `/api/pacientes/{id}/existe` | Verificar existencia (usado por citas-service) |
| POST | `/api/pacientes/admin/delay?ms=N` | Configurar delay artificial (Casuística 2) |
| GET | `/health` | Health check (probes de K8s) |
| GET | `/metrics` | Métricas Prometheus |

### citas-service (`:30082`)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/citas` | Listar todas las citas |
| GET | `/api/citas/{id}` | Buscar cita por ID |
| POST | `/api/citas` | Agendar nueva cita (valida paciente) |
| PATCH | `/api/citas/{id}/estado?estado=X` | Cambiar estado |
| DELETE | `/api/citas/{id}` | Cancelar cita |
| GET | `/api/citas/paciente/{pacienteId}` | Citas de un paciente |
| GET | `/health` | Health check |
| GET | `/metrics` | Métricas Prometheus |

---

## Métricas expuestas

### pacientes-service
| Métrica | Tipo | Descripción |
|---|---|---|
| `pacientes_registrados_total` | Counter | Total de pacientes registrados |
| `pacientes_consultas_por_id_total` | Counter | Total de consultas por ID |
| `pacientes_no_encontrados_total` | Counter | Consultas sin resultado |
| `pacientes_registro_duracion_segundos` | Timer | Tiempo de procesamiento del registro |
| `pacientes_activos_actual` | Gauge | Pacientes activos en este momento |
| `pacientes_delay_artificial_ms` | Gauge | Delay artificial configurado (Casuística 2) |

### citas-service
| Métrica | Tipo | Descripción |
|---|---|---|
| `citas_agendadas_total` | Counter | Citas agendadas exitosamente |
| `citas_rechazadas_paciente_invalido_total` | Counter | Citas rechazadas por paciente inválido |
| `citas_errores_comunicacion_pacientes_total` | Counter | Timeouts/errores con pacientes-service |
| `citas_latencia_llamada_pacientes_segundos` | Timer | Latencia HTTP a pacientes-service |
| `citas_pendientes_actual` | Gauge | Citas en estado PENDIENTE |
| `citas_confirmadas_actual` | Gauge | Citas en estado CONFIRMADA |

---

## Casuísticas

| # | Nombre | Activación | Lección |
|---|---|---|---|
| 🔴 1 | El Servicio Caído | `kubectl set env deployment/pacientes-service SERVER_PORT="abc" -n veterinaria` | Liveness vs Readiness probe |
| 🟡 2 | El Servicio Lento | `curl -X POST "http://<IP>:30081/api/pacientes/admin/delay?ms=4000"` | Propagación de latencia entre servicios |
| 🟠 3 | Sobrecarga de Inválidos | `./load-testing/stress.sh invalidos --rps 20 --dur 180` | Métricas de negocio vs métricas técnicas |

Ver detalle completo en `docs/informe.md`.

---

## Limpieza

```bash
# Solo recursos K8s (conserva Minikube)
./scripts/teardown.sh

# Todo, incluyendo el cluster
./scripts/teardown.sh --all
```

---

## Dashboard de Grafana

Importar `docs/grafana-dashboard.json` en Grafana:  
Dashboards → Import → Upload JSON file

El dashboard incluye 11 paneles:
- Estado UP/DOWN de ambos servicios
- Tasa de requests por servicio (req/min)
- Latencia p95 de pacientes-service
- Latencia de llamadas WebClient entre servicios
- Pacientes activos (Gauge)
- Citas pendientes y confirmadas (Gauge)
- Errores de comunicación entre servicios
- Delay artificial en tiempo real (Casuística 2)
- Citas agendadas vs rechazadas (panel de negocio)
- Total pacientes registrados (Counter acumulado)
