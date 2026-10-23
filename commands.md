# Comandos del proyecto — Clínica Veterinaria K8s
> Ejecuta estos comandos en orden. Todos los comandos son para **Linux/macOS/WSL**.  
> En Windows nativo usa Git Bash o WSL2.

---

## SECCIÓN 1 — Verificar prerequisitos

```bash
# Verificar que todas las herramientas estén instaladas
docker --version
minikube version
kubectl version --client
helm version
```

---

## SECCIÓN 2 — Iniciar el cluster Minikube

```bash
# Iniciar Minikube con recursos suficientes para los microservicios + monitoreo
minikube start --cpus=4 --memory=6144 --driver=docker

# Habilitar metrics-server (requerido por la sección 2 del taller)
minikube addons enable metrics-server

# Verificar que el cluster está corriendo
minikube status
kubectl get nodes
```

---

## SECCIÓN 3 — Instalar stack de monitoreo con Helm

```bash
# Agregar el repositorio oficial de prometheus-community
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

# Verificar que el chart está disponible
helm search repo prometheus-community/kube-prometheus-stack

# Crear el namespace de monitoreo
kubectl create namespace monitoring

# Instalar el chart con tu configuración personalizada
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring \
  -f k8s/monitoring/values.yaml \
  --wait \
  --timeout=5m

# Verificar que todos los pods del stack están corriendo
kubectl get pods -n monitoring

# Obtener la IP de Minikube para acceder desde el navegador
minikube ip

# Verificar acceso a Grafana (reemplaza <IP> con la salida de minikube ip)
# URL: http://<IP>:30300   Usuario: admin   Contraseña: veterinaria2026
curl -s -o /dev/null -w "%{http_code}" http://$(minikube ip):30300

# Verificar acceso a Prometheus
# URL: http://<IP>:30090
curl -s -o /dev/null -w "%{http_code}" http://$(minikube ip):30090
```

---

## SECCIÓN 4 — Construir imágenes Docker dentro de Minikube

```bash
# PASO CRÍTICO: apuntar Docker al daemon interno de Minikube
# Sin este paso, las imágenes no serán visibles para Kubernetes
eval "$(minikube docker-env)"

# Verificar que estamos apuntando al daemon de Minikube
docker info | grep "Name:"

# Construir la imagen de pacientes-service (Servicio A)
docker build -t pacientes-service:1.0.0 microservices/pacientes-service/

# Construir la imagen de citas-service (Servicio B)
docker build -t citas-service:1.0.0 microservices/citas-service/

# Verificar que las imágenes existen en el daemon de Minikube
docker images | grep -E "pacientes-service|citas-service"
```

---

## SECCIÓN 5 — Desplegar microservicios en Kubernetes

```bash
# Crear el namespace veterinaria
kubectl apply -f k8s/namespace/namespace.yaml

# Desplegar pacientes-service (Deployment + Service)
kubectl apply -f k8s/pacientes/

# Desplegar citas-service (Deployment + Service)
kubectl apply -f k8s/citas/

# Aplicar ServiceMonitors (para que Prometheus detecte los servicios)
kubectl apply -f k8s/monitoring/servicemonitor-pacientes.yaml
kubectl apply -f k8s/monitoring/servicemonitor-citas.yaml

# Aplicar reglas de alerta
kubectl apply -f k8s/monitoring/prometheus-rules.yaml
```

---

## SECCIÓN 6 — Verificar el despliegue

```bash
# Ver estado de todos los pods en namespace veterinaria
kubectl get pods -n veterinaria

# Esperar a que pacientes-service esté listo
kubectl rollout status deployment/pacientes-service -n veterinaria

# Esperar a que citas-service esté listo
kubectl rollout status deployment/citas-service -n veterinaria

# Ver todos los recursos del namespace veterinaria
kubectl get all -n veterinaria

# Ver los Services (para confirmar NodePorts)
kubectl get services -n veterinaria

# Ver los ServiceMonitors creados
kubectl get servicemonitors -n monitoring

# Ver las reglas de alerta
kubectl get prometheusrules -n monitoring
```

---

## SECCIÓN 7 — Probar los endpoints desde fuera del cluster

```bash
# Guardar la IP de Minikube en una variable
MINIKUBE_IP=$(minikube ip)
echo "Minikube IP: $MINIKUBE_IP"

# --- pacientes-service ---
# Health check
curl http://${MINIKUBE_IP}:30081/health

# Listar pacientes (datos precargados)
curl http://${MINIKUBE_IP}:30081/api/pacientes

# Buscar paciente por ID
curl http://${MINIKUBE_IP}:30081/api/pacientes/PAC-100

# Registrar un nuevo paciente
curl -X POST http://${MINIKUBE_IP}:30081/api/pacientes \
  -H "Content-Type: application/json" \
  -d '{
    "nombre": "Max",
    "especie": "perro",
    "raza": "Golden Retriever",
    "edadAnios": 2,
    "propietario": "Pedro Soto",
    "telefono": "099-000-1111"
  }'

# Ver estadísticas por especie
curl http://${MINIKUBE_IP}:30081/api/pacientes/estadisticas/especies

# Ver métricas de Prometheus (debe retornar texto en formato exposition)
curl http://${MINIKUBE_IP}:30081/metrics | head -40

# --- citas-service ---
# Health check
curl http://${MINIKUBE_IP}:30082/health

# Listar citas
curl http://${MINIKUBE_IP}:30082/api/citas

# Agendar una cita (llama internamente a pacientes-service)
curl -X POST http://${MINIKUBE_IP}:30082/api/citas \
  -H "Content-Type: application/json" \
  -d '{
    "pacienteId": "PAC-100",
    "veterinario": "Dr. Ramírez",
    "fecha": "2026-11-15",
    "hora": "10:00",
    "motivo": "Vacunación anual"
  }'

# Intentar agendar con paciente inexistente (debe retornar error controlado)
curl -X POST http://${MINIKUBE_IP}:30082/api/citas \
  -H "Content-Type: application/json" \
  -d '{
    "pacienteId": "PAC-NOEXISTE",
    "veterinario": "Dr. Test",
    "fecha": "2026-11-15",
    "hora": "11:00",
    "motivo": "Test"
  }'

# Ver métricas
curl http://${MINIKUBE_IP}:30082/metrics | head -40
```

---

## SECCIÓN 8 — Verificar integración con Prometheus

```bash
# Verificar targets en Prometheus vía API REST
curl -s "http://$(minikube ip):30090/api/v1/targets" | \
  python3 -c "
import sys, json
data = json.load(sys.stdin)
for t in data['data']['activeTargets']:
    print(t['labels'].get('job','?'), '->', t['health'])
"

# Consultar una métrica específica en Prometheus
curl -s "http://$(minikube ip):30090/api/v1/query?query=pacientes_activos_actual" | \
  python3 -m json.tool

# Consultar tasa de requests de citas-service
curl -s "http://$(minikube ip):30090/api/v1/query?query=rate(citas_agendadas_total[5m])" | \
  python3 -m json.tool

# Ver las alertas configuradas
curl -s "http://$(minikube ip):30090/api/v1/rules" | python3 -m json.tool | grep '"name"'
```

---

## SECCIÓN 9 — Importar dashboard en Grafana

```bash
# Abrir Grafana en el navegador
open "http://$(minikube ip):30300"
# En Windows: start http://$(minikube ip):30300

# Credenciales: admin / veterinaria2026
# Ruta para importar: Dashboards → Import → Upload JSON file
# Archivo: docs/grafana-dashboard.json
```

---

## SECCIÓN 10 — Generar tráfico de prueba

```bash
# Dar permisos de ejecución al script
chmod +x load-testing/stress.sh

# Tráfico normal mixto (120 segundos)
./load-testing/stress.sh normal --dur 120

# Solo agendamiento de citas
./load-testing/stress.sh citas --rps 10 --dur 60

# Citas con pacientes inválidos (genera errores de negocio)
./load-testing/stress.sh invalidos --rps 15 --dur 90
```

---

## SECCIÓN 11 — Casuística 1: El Servicio Caído

```bash
# ACTIVACIÓN: introducir error de configuración
kubectl set env deployment/pacientes-service \
  SERVER_PORT="abc" \
  -n veterinaria

# Observar el pod entrar en CrashLoopBackOff
kubectl get pods -n veterinaria -w

# Ver logs del pod fallido
kubectl logs deployment/pacientes-service -n veterinaria --previous

# Verificar que la alerta se dispara en Prometheus
# URL: http://<MINIKUBE_IP>:30090/alerts
# Alerta: PacientesServiceCaido

# RESOLUCIÓN: restaurar configuración correcta
kubectl set env deployment/pacientes-service \
  SERVER_PORT="8081" \
  -n veterinaria

# Verificar recuperación
kubectl rollout status deployment/pacientes-service -n veterinaria
kubectl get pods -n veterinaria
```

---

## SECCIÓN 12 — Casuística 2: El Servicio Lento

```bash
# ACTIVACIÓN: configurar delay de 4000ms (supera timeout de 3s)
MINIKUBE_IP=$(minikube ip)
curl -X POST "http://${MINIKUBE_IP}:30081/api/pacientes/admin/delay?ms=4000"

# Verificar que el delay está activo
curl -s "http://${MINIKUBE_IP}:30090/api/v1/query?query=pacientes_delay_artificial_ms" | \
  python3 -m json.tool

# Generar tráfico para provocar timeouts en citas-service
./load-testing/stress.sh casuistica2 --dur 120

# En otra terminal: ver logs de citas-service en tiempo real
kubectl logs -f deployment/citas-service -n veterinaria | grep -E "timeout|error|Error"

# RESOLUCIÓN: restaurar delay a 0
curl -X POST "http://${MINIKUBE_IP}:30081/api/pacientes/admin/delay?ms=0"

# Verificar en Grafana que la métrica vuelve a 0
# Panel: "Delay Artificial en pacientes-service"
```

---

## SECCIÓN 13 — Casuística 3: Sobrecarga de Citas Inválidas

```bash
# ACTIVACIÓN: ráfaga de citas con IDs de pacientes inexistentes
./load-testing/stress.sh invalidos --rps 20 --dur 180

# Consultar en Prometheus la tasa de errores de negocio
MINIKUBE_IP=$(minikube ip)
curl -s "http://${MINIKUBE_IP}:30090/api/v1/query?query=rate(citas_rechazadas_paciente_invalido_total[5m])*60" | \
  python3 -m json.tool

# Ver el porcentaje de citas rechazadas (query de negocio)
curl -s "http://${MINIKUBE_IP}:30090/api/v1/query?query=100*(rate(citas_rechazadas_paciente_invalido_total[5m])/(rate(citas_agendadas_total[5m])+rate(citas_rechazadas_paciente_invalido_total[5m])))" | \
  python3 -m json.tool

# RESOLUCIÓN: detener el script con Ctrl+C
# En producción se aplicaría rate limiting en el ingress
```

---

## SECCIÓN 14 — Comandos de diagnóstico general

```bash
# Ver todos los recursos de todos los namespaces del proyecto
kubectl get all -n veterinaria
kubectl get all -n monitoring

# Ver eventos recientes (útil para diagnosticar problemas)
kubectl get events -n veterinaria --sort-by='.lastTimestamp'

# Describir un pod en detalle (probes, condiciones, eventos)
kubectl describe pod -l app=pacientes-service -n veterinaria
kubectl describe pod -l app=citas-service -n veterinaria

# Ver logs de un servicio
kubectl logs deployment/pacientes-service -n veterinaria
kubectl logs deployment/citas-service -n veterinaria

# Acceso interactivo a un pod (para debug)
kubectl exec -it deployment/pacientes-service -n veterinaria -- /bin/sh

# Ver consumo de recursos
kubectl top pods -n veterinaria
kubectl top nodes

# Ver ServiceMonitors y confirmar que están en el namespace correcto
kubectl get servicemonitors -n monitoring -o wide

# Ver PrometheusRules
kubectl get prometheusrules -n monitoring
kubectl describe prometheusrule veterinaria-alertas -n monitoring
```

---

## SECCIÓN 15 — Limpieza

```bash
# Eliminar solo los recursos K8s (conserva el cluster Minikube)
chmod +x scripts/teardown.sh
./scripts/teardown.sh

# Eliminar todo, incluyendo el cluster Minikube
./scripts/teardown.sh --all

# Verificar que todo fue eliminado
kubectl get namespaces
minikube status
```

---

## Referencia rápida de puertos NodePort

| Servicio | NodePort | URL completa |
|---|---|---|
| pacientes-service | 30081 | `http://$(minikube ip):30081` |
| citas-service | 30082 | `http://$(minikube ip):30082` |
| Prometheus | 30090 | `http://$(minikube ip):30090` |
| Grafana | 30300 | `http://$(minikube ip):30300` |
| AlertManager | 30093 | `http://$(minikube ip):30093` |
