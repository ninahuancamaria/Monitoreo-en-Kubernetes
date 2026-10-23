# Informe Técnico — Clínica Veterinaria
## Microservicios Java con Monitoreo en Kubernetes
**Curso:** Kubernetes con Prometheus & Grafana — Valle Grande  
---

## Parte A — Conceptos

### 1. ¿Qué es Micrometer y por qué se usa en lugar de la librería directa de Prometheus?

Micrometer es una **fachada de métricas** para aplicaciones JVM, de la misma forma que SLF4J es una fachada para logging. Proporciona una API uniforme para instrumentar el código, y luego delega el registro real a uno o varios "registries" concretos (Prometheus, Datadog, New Relic, InfluxDB, etc.).

**Por qué se prefiere sobre la librería directa de Prometheus:**

- **Portabilidad:** el código de instrumentación (`Counter.builder(...).register(registry)`) es idéntico sin importar el backend de métricas. Si mañana el equipo migra de Prometheus a Datadog, solo se cambia la dependencia Maven, no el código de negocio.
- **Integración con Spring Boot:** Spring Boot Actuator autoconfigura el `MeterRegistry` y expone el endpoint `/metrics` automáticamente al agregar `micrometer-registry-prometheus`.
- **Convenciones y nomenclatura:** Micrometer aplica automáticamente prefijos y convenciones de nomenclatura de cada sistema destino. Por ejemplo, un timer llamado `pacientes_registro_duracion` genera en Prometheus tres series: `_count`, `_sum` y `_bucket`.

En este proyecto, en `PacienteService.java` se usa directamente la API de Micrometer:

```java
// Micrometer — independiente del backend
Counter.builder("pacientes_registrados_total")
    .description("Total de pacientes registrados")
    .tag("servicio", "pacientes-service")
    .register(registry);
```

Si se usara la librería directa de Prometheus (`io.prometheus.client.Counter`), el código estaría acoplado a Prometheus y no podría exportarse a otro sistema sin reescribirlo.

---

### 2. ¿Cuál es la diferencia entre Counter, Gauge y Timer?

| Tipo | Comportamiento | Cuándo usarlo |
|------|---------------|---------------|
| **Counter** | Solo sube, nunca baja. Acumula eventos. | Contar ocurrencias: requests, errores, registros |
| **Gauge** | Sube y baja. Refleja un valor en un instante. | Valores actuales: conexiones activas, items en cola |
| **Timer** | Mide duración y cuenta ocurrencias simultáneamente. | Latencia de operaciones |

**Ejemplos tomados del código de este proyecto:**

**Counter** — `pacientes_registrados_total` en `PacienteService.java`:
```java
// Sube 1 cada vez que se registra un paciente. Nunca baja.
this.pacientesRegistradosTotal = Counter.builder("pacientes_registrados_total")
    .description("Total de pacientes registrados desde el inicio del servicio")
    .tag("servicio", "pacientes-service")
    .register(registry);
// ...
pacientesRegistradosTotal.increment(); // llamado en registrar()
```

**Gauge** — `pacientes_activos_actual` en `PacienteService.java`:
```java
// Refleja el número actual. Baja cuando se elimina un paciente, sube cuando se agrega.
Gauge.builder("pacientes_activos_actual", pacientesActivos, AtomicInteger::get)
    .description("Número actual de pacientes con estado ACTIVO")
    .tag("servicio", "pacientes-service")
    .register(registry);
```

**Timer** — `citas_latencia_llamada_pacientes_segundos` en `CitaService.java`:
```java
// Mide cuánto tarda cada llamada HTTP a pacientes-service
this.latenciaLlamadaPacientes = Timer.builder("citas_latencia_llamada_pacientes_segundos")
    .description("Latencia de las llamadas HTTP hacia pacientes-service")
    .tag("servicio", "citas-service")
    .register(registry);
// ...
latenciaLlamadaPacientes.recordCallable(
    () -> pacientesClient.verificarExistencia(cita.getPacienteId())
);
```
El Timer genera automáticamente en Prometheus:
- `citas_latencia_llamada_pacientes_segundos_count` — cuántas veces se llamó
- `citas_latencia_llamada_pacientes_segundos_sum` — suma total de tiempos
- `citas_latencia_llamada_pacientes_segundos_bucket` — histograma para percentiles

---

### 3. ¿Qué es un ServiceMonitor y cómo sabe el Prometheus Operator qué monitorear?

Un **ServiceMonitor** es un recurso personalizado (CRD) del Prometheus Operator. Define **de forma declarativa** qué Services de Kubernetes debe raspar Prometheus, en qué ruta y con qué frecuencia.

El flujo completo es:

```
Prometheus Operator  →  vigila ServiceMonitors  →  reconfigura Prometheus
```

El Operator lee todos los ServiceMonitors del cluster (según el `serviceMonitorSelector` del values.yaml) y genera automáticamente la configuración de scraping de Prometheus. No hace falta editar el `prometheus.yml` manualmente.

**Cómo sabe qué monitorear:** el campo `selector` del ServiceMonitor usa `matchLabels` para encontrar Services de Kubernetes. En este proyecto:

```yaml
# servicemonitor-pacientes.yaml
spec:
  selector:
    matchLabels:
      monitor: veterinaria
      app: pacientes-service
```

Esto le dice al Operator: "raspa todos los Services que tengan `monitor=veterinaria` y `app=pacientes-service` en el namespace `veterinaria`". El Service de Kubernetes tiene exactamente esos labels:

```yaml
# service.yaml de pacientes
metadata:
  labels:
    app: pacientes-service
    monitor: veterinaria
```

Si los labels no coinciden exactamente, el target no aparece en Prometheus.

---

### 4. ¿Cuál es la diferencia entre liveness probe y readiness probe?

Ambos son mecanismos de Kubernetes para verificar la salud de un pod, pero tienen propósitos y consecuencias distintas:

| | **Liveness Probe** | **Readiness Probe** |
|---|---|---|
| **Pregunta** | ¿Está vivo el proceso? | ¿Está listo para recibir tráfico? |
| **Acción si falla** | Reinicia el contenedor | Saca el pod del balanceo de carga (Service) |
| **Cuándo usar** | Detectar deadlocks o estados corruptos | Esperar a que la app cargue datos iniciales o dependencias |

**En este proyecto** (`deployment.yaml` de pacientes-service):

```yaml
livenessProbe:
  httpGet:
    path: /health    # Endpoint propio, responde rápido
    port: 8081
  initialDelaySeconds: 30    # Da tiempo al JVM para arrancar
  periodSeconds: 10
  failureThreshold: 3        # 3 fallos consecutivos → reinicia

readinessProbe:
  httpGet:
    path: /health
    port: 8081
  initialDelaySeconds: 20
  periodSeconds: 5
  failureThreshold: 3        # 3 fallos → sale del Service, no reinicia
```

**Escenario real:** durante la Casuística 1, al introducir un error en el Deployment, el pod entra en `CrashLoopBackOff`. La liveness probe falla repetidamente, Kubernetes reinicia el pod, pero el contenedor sigue fallando → el ciclo es visible en Grafana como `up == 0`.

---

### 5. ¿Por qué es necesario apuntar Docker al daemon de Minikube antes de construir las imágenes?

Minikube corre un cluster de Kubernetes dentro de una VM (o contenedor Docker). Ese cluster tiene su **propio daemon de Docker**, separado del daemon de Docker del sistema host.

Cuando se hace `docker build` en el host sin configurar nada, la imagen queda en el daemon del host. Pero los pods de Kubernetes corren dentro de Minikube y solo tienen acceso al daemon interno de Minikube. Por eso, la imagen no se encuentra y el pod falla con `ErrImagePull` o `ImagePullBackOff`.

El comando que resuelve esto:

```bash
eval "$(minikube docker-env)"
```

Este comando exporta variables de entorno (`DOCKER_HOST`, `DOCKER_TLS_VERIFY`, etc.) que redirigen el cliente Docker del host para hablar con el daemon interno de Minikube. A partir de ese momento, `docker build` construye la imagen directamente dentro del entorno de Minikube.

Por eso en los Deployments se usa `imagePullPolicy: Never`:

```yaml
imagePullPolicy: Never   # No intentes bajar la imagen de un registry externo
                         # usa solo lo que ya está en el daemon local
```

Sin este paso, aunque el build sea exitoso, Kubernetes nunca encontrará las imágenes.

---

### 6. ¿Qué ocurre si no configuras el selector de ServiceMonitors en el values.yaml del chart?

Por defecto, el Prometheus Operator instala Prometheus con un `serviceMonitorSelector` que solo busca ServiceMonitors en el **mismo namespace** (`monitoring`) y con labels específicos del release. Si no se sobreescribe este selector, Prometheus ignora completamente cualquier ServiceMonitor creado en otros namespaces (como `veterinaria`).

El resultado: los targets de los microservicios **nunca aparecen** en Prometheus (`/targets`), aunque los ServiceMonitors estén bien configurados y los pods estén corriendo correctamente.

En el `values.yaml` de este proyecto se configura así:

```yaml
prometheus:
  prometheusSpec:
    serviceMonitorSelector:
      matchLabels:
        release: kube-prometheus-stack   # solo ServiceMonitors con este label
    serviceMonitorNamespaceSelector: {}  # en CUALQUIER namespace
```

El `{}` en `serviceMonitorNamespaceSelector` es la clave: significa "sin restricción de namespace". Los ServiceMonitors de `veterinaria` tienen el label `release: kube-prometheus-stack`, por lo que coinciden con ambos criterios.

---

## Parte B — PromQL

### 1. Tasa de requests por minuto de citas-service en los últimos 5 minutos

```promql
rate(http_server_requests_seconds_count{job="citas-service-monitor"}[5m]) * 60
```

Esta query usa `rate()` para calcular la tasa de incremento por segundo del contador de requests de Spring Boot Actuator sobre una ventana de 5 minutos, y multiplica por 60 para expresarlo en requests/minuto.

---

### 2. Latencia p95 del endpoint más crítico del sistema

El endpoint más crítico es `POST /api/citas` en citas-service, porque involucra la comunicación entre servicios. La latencia p95 de la llamada WebClient a pacientes-service:

```promql
histogram_quantile(
  0.95,
  rate(citas_latencia_llamada_pacientes_segundos_bucket[5m])
)
```

Y para el endpoint HTTP completo de citas-service:

```promql
histogram_quantile(
  0.95,
  rate(http_server_requests_seconds_bucket{
    job="citas-service-monitor",
    uri="/api/citas",
    method="POST"
  }[5m])
)
```

---

### 3. Estado UP/DOWN de ambos servicios al mismo tiempo

```promql
up{job=~"pacientes-service-monitor|citas-service-monitor"}
```

Retorna `1` (UP) o `0` (DOWN) para cada servicio. En el dashboard se muestra como panel `stat` con colores verde/rojo.

---

### 4. Query de negocio propia: tasa de citas rechazadas vs agendadas

Esta query responde: "¿Qué porcentaje de los intentos de agendar una cita están fallando por paciente inválido o servicio no disponible?"

```promql
100 * (
  rate(citas_rechazadas_paciente_invalido_total[5m])
  /
  (rate(citas_agendadas_total[5m]) + rate(citas_rechazadas_paciente_invalido_total[5m]))
)
```

Si este valor supera el 20%, algo está mal en la clínica: o el pacientes-service está caído, o se están enviando IDs de pacientes incorrectos.

---

## Parte C — Evidencias de Casuísticas

### 🔴 Casuística 1 — El Servicio Caído

**Contexto narrativo:**  
Durante una actualización de configuración, el equipo de DevOps modifica por error la variable de entorno `SERVER_PORT` de pacientes-service a un valor inválido (`"abc"`). La aplicación Spring Boot arranca pero inmediatamente falla al intentar bindear el puerto. Kubernetes detecta el fallo mediante la liveness probe y reinicia el pod repetidamente, entrando en `CrashLoopBackOff`.

**Síntoma:**  
Los veterinarios no pueden agendar citas. La API de citas responde con error 400 indicando que pacientes-service no está disponible. La métrica `citas_errores_comunicacion_pacientes_total` sube bruscamente.

**Activación:**
```bash
# Introducir configuración inválida que causa crash al arrancar
kubectl set env deployment/pacientes-service \
  SERVER_PORT="abc" \
  -n veterinaria

# Observar cómo el pod entra en CrashLoopBackOff
kubectl get pods -n veterinaria -w
```

**Diagnóstico en Prometheus/Grafana:**
- Panel "Estado de Servicios": pacientes-service pasa a rojo (DOWN)
- `up{job="pacientes-service-monitor"}` == 0
- Panel "Errores de Comunicación": pico en `citas_errores_comunicacion_pacientes_total`
- Alerta `PacientesServiceCaido` en estado FIRING en Prometheus → Alertas

**Resolución:**
```bash
# Restaurar el puerto correcto
kubectl set env deployment/pacientes-service \
  SERVER_PORT="8081" \
  -n veterinaria

# Verificar que el pod vuelve a Running
kubectl rollout status deployment/pacientes-service -n veterinaria
```

**Lección técnica:**  
La **liveness probe** detecta que el proceso no responde en `/health` y ordena el reinicio. Sin ella, Kubernetes no sabría que el proceso está en estado irrecuperable (podría estar corriendo pero bloqueado). La **readiness probe** complementa esto sacando el pod del Service mientras no esté listo, evitando que el tráfico llegue a un pod que no puede responder. La diferencia es crítica: liveness reinicia, readiness aísla. Ambas son necesarias para una recuperación automática robusta.

---

### 🟡 Casuística 2 — El Servicio Lento

**Contexto narrativo:**  
pacientes-service acumula una gran cantidad de operaciones en memoria por un bug no detectado. El hilo principal empieza a tardar varios segundos en responder. citas-service tiene configurado un timeout de 3 segundos. Cuando el delay supera ese umbral, cada intento de agendar una cita falla con timeout, aunque pacientes-service técnicamente "está vivo" y Prometheus lo muestra como UP.

**Síntoma:**  
Los veterinarios pueden consultar citas existentes (endpoint GET que no llama a pacientes-service), pero no pueden crear citas nuevas. El sistema parece "a medias": UP pero no funcional.

**Activación:**
```bash
# Activar delay artificial de 4000ms (supera el timeout de 3s de citas-service)
MINIKUBE_IP=$(minikube ip)
curl -X POST "http://${MINIKUBE_IP}:30081/api/pacientes/admin/delay?ms=4000"

# Generar tráfico para ver los timeouts en Grafana
cd load-testing
./stress.sh casuistica2 --dur 120

# En otra terminal, observar los errores en tiempo real
kubectl logs -f deployment/citas-service -n veterinaria | grep "timeout\|error"
```

**Diagnóstico en Prometheus/Grafana:**
- Panel "Delay Artificial": sube a 4000ms (fondo rojo por superar umbral de 2500ms)
- Panel "Errores de Comunicación": pico sostenido en `citas_errores_comunicacion_pacientes_total`
- Panel "Latencia llamadas pacientes→citas": `citas_latencia_llamada_pacientes_segundos` sube sobre 3s
- Panel "Estado de Servicios": ambos siguen en UP → esto demuestra que UP no significa funcional
- Alerta `DelayArtificialPacientesAlto` y `AltaTasaErroresComunicacionVeterinaria` en FIRING

**Resolución:**
```bash
# Opción 1: restaurar delay vía API
MINIKUBE_IP=$(minikube ip)
curl -X POST "http://${MINIKUBE_IP}:30081/api/pacientes/admin/delay?ms=0"

# Opción 2: restaurar vía variable de entorno en K8s (simula un rollout)
kubectl set env deployment/pacientes-service \
  ARTIFICIAL_DELAY_MS=0 \
  -n veterinaria
```

**Lección técnica:**  
La latencia se propaga entre microservicios en cascada. Sin observabilidad, un ingeniero vería que "los dos servicios están UP" y no podría determinar cuál es el culpable. El **Timer de Micrometer** (`citas_latencia_llamada_pacientes_segundos`) aísla exactamente la llamada entre servicios, permitiendo responder: "el problema no está en citas-service, está en el tiempo que tarda pacientes-service en responder". Esto es el valor real del monitoreo distribuido.

---

### 🟠 Casuística 3 — Sobrecarga de Citas Inválidas

**Contexto narrativo:**  
Un cliente desarrolla una integración defectuosa con la API de la clínica. Su sistema envía miles de solicitudes de citas por minuto, pero con IDs de pacientes generados aleatoriamente que no existen en el sistema. citas-service procesa cada solicitud, consulta a pacientes-service para verificar la existencia, recibe la respuesta negativa y rechaza la cita. Aunque ninguna cita se agenda, el tráfico generado hacia pacientes-service es real y consume recursos.

**Síntoma:**  
La tasa de requests en ambos servicios se dispara, pero la tasa de citas agendadas exitosamente permanece en cero. El porcentaje de error de negocio llega al 100%. En producción esto agotaría los recursos del cluster.

**Activación:**
```bash
# Generar ráfaga de citas con pacientes inválidos a alta velocidad
cd load-testing
./stress.sh invalidos --rps 20 --dur 180
```

**Diagnóstico en Prometheus/Grafana:**
- Panel "Tasa de Requests citas-service": pico sostenido en POST /api/citas
- Panel "Citas Agendadas vs Rechazadas": `citas_rechazadas_paciente_invalido_total` se dispara, `citas_agendadas_total` queda en 0
- Query de negocio: `100 * rate(citas_rechazadas_paciente_invalido_total[5m]) / (rate(citas_agendadas_total[5m]) + rate(citas_rechazadas_paciente_invalido_total[5m]))` → 100%
- Panel "Latencia p95 pacientes-service": sube por la carga de verificaciones

**Resolución:**
```bash
# Detener el script de stress (Ctrl+C)
# En producción: aplicar rate limiting en el ingress o implementar circuit breaker
# Para simulación, simplemente detener el generador de carga
```

**Lección técnica:**  
Los **Counters de negocio** (`citas_rechazadas_paciente_invalido_total`) son esenciales para distinguir entre un sistema "ocupado" y un sistema "ocupado haciendo trabajo útil". Un sistema con alta carga y 0% de éxito tiene un problema diferente a uno con alta carga y 95% de éxito. Sin métricas de negocio, la diferencia es invisible. Esta casuística también demuestra la necesidad de implementar rate limiting y validación de entrada a nivel de API gateway para proteger los microservicios internos.

---

## Parte D — Evidencias del sistema

## Parte D — Evidencias del sistema

> Guarda las capturas como archivos PNG en la carpeta `docs/` y referéncialas aquí.  
> Ejemplo: toma el screenshot → guárdalo como `docs/evidencia-targets.png` → ya aparece abajo.

---

### D.1 — Targets de Prometheus: ambos servicios en estado UP

> Cómo obtenerla: abre `http://<MINIKUBE_IP>:30090/targets` y toma el screenshot.  
> Guarda el archivo como `docs/evidencia-targets.png`.

![Targets Prometheus - pacientes-service y citas-service en UP](evidencia-targets.png)

---

### D.2 — Dashboard de Grafana con los paneles funcionando

> Cómo obtenerla: importa `docs/grafana-dashboard.json` en Grafana, espera que carguen las métricas y toma el screenshot mostrando los 11 paneles.  
> Guarda el archivo como `docs/evidencia-grafana-dashboard.png`.

![Dashboard Grafana - Clínica Veterinaria con 11 paneles](evidencia-grafana-dashboard.png)

---

### D.3 — kubectl get pods: todos los pods en Running

> Cómo obtenerla: ejecuta los comandos de abajo, toma screenshot de la terminal.  
> Guarda el archivo como `docs/evidencia-pods-running.png`.

```bash
kubectl get pods -n veterinaria
kubectl get pods -n monitoring
```

![kubectl get pods - todos en estado Running 1/1](evidencia-pods-running.png)

---

### D.4 — Alerta en estado FIRING en Prometheus

> Cómo obtenerla: activa la Casuística 1 (ver Parte C), espera ~1 minuto, abre `http://<MINIKUBE_IP>:30090/alerts` y toma el screenshot mostrando la alerta en rojo con estado FIRING.  
> Guarda el archivo como `docs/evidencia-alerta-firing.png`.

```bash
# Comando para activar la alerta (Casuística 1)
kubectl set env deployment/pacientes-service SERVER_PORT="abc" -n veterinaria
# Esperar 1 minuto y abrir http://<MINIKUBE_IP>:30090/alerts
```

![Alerta PacientesServiceCaido en estado FIRING](evidencia-alerta-firing.png)

---

### D.5 — Evidencias de Casuísticas en Grafana

#### Casuística 1 — Panel mostrando servicio caído (UP = 0)

> Guarda el archivo como `docs/evidencia-casuistica1-grafana.png`.

![Grafana - pacientes-service DOWN durante Casuística 1](evidencia-casuistica1-grafana.png)

---

#### Casuística 2 — Panel de delay artificial y errores de comunicación

> Guarda el archivo como `docs/evidencia-casuistica2-grafana.png`.

![Grafana - delay artificial alto y errores de timeout durante Casuística 2](evidencia-casuistica2-grafana.png)

---

#### Casuística 3 — Panel de citas rechazadas al 100%

> Guarda el archivo como `docs/evidencia-casuistica3-grafana.png`.

![Grafana - tasa de citas rechazadas durante Casuística 3](evidencia-casuistica3-grafana.png)