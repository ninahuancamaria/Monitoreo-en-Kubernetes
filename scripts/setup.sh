#!/bin/bash
# =============================================================================
# setup.sh — Levanta todo el sistema desde cero
# Clínica Veterinaria — Monitoreo en Kubernetes
# =============================================================================
set -euo pipefail

# ── Colores para output ────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info()    { echo -e "${BLUE}[INFO]${NC}  $1"; }
log_ok()      { echo -e "${GREEN}[OK]${NC}    $1"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC}  $1"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║   Clínica Veterinaria — Setup de Monitoreo en K8s       ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ── PASO 1: Verificar prerequisitos ───────────────────────────────────────
log_info "Paso 1/7 — Verificando prerequisitos..."

for tool in docker minikube kubectl helm; do
  if ! command -v "$tool" &> /dev/null; then
    log_error "$tool no está instalado. Revisa docs/README.md para instrucciones."
  fi
  log_ok "$tool encontrado: $(command -v $tool)"
done

# ── PASO 2: Iniciar Minikube ───────────────────────────────────────────────
log_info "Paso 2/7 — Iniciando Minikube..."

if minikube status | grep -q "Running"; then
  log_warn "Minikube ya está corriendo. Continuando..."
else
  minikube start \
    --cpus=4 \
    --memory=6144 \
    --driver=docker \
    --addons=metrics-server
  log_ok "Minikube iniciado"
fi

# Habilitar metrics-server si no está activo
minikube addons enable metrics-server 2>/dev/null || true
log_ok "metrics-server habilitado"

# ── PASO 3: Instalar stack de monitoreo con Helm ───────────────────────────
log_info "Paso 3/7 — Instalando kube-prometheus-stack con Helm..."

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts 2>/dev/null || true
helm repo update

kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -

if helm status kube-prometheus-stack -n monitoring &> /dev/null; then
  log_warn "kube-prometheus-stack ya instalado. Actualizando..."
  helm upgrade kube-prometheus-stack prometheus-community/kube-prometheus-stack \
    -n monitoring \
    -f "$PROJECT_ROOT/k8s/monitoring/values.yaml" \
    --wait --timeout=5m
else
  helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
    -n monitoring \
    -f "$PROJECT_ROOT/k8s/monitoring/values.yaml" \
    --wait --timeout=5m
fi
log_ok "Stack de monitoreo instalado"

# ── PASO 4: Apuntar Docker al daemon de Minikube y construir imágenes ──────
log_info "Paso 4/7 — Construyendo imágenes Docker dentro de Minikube..."

# Este es el paso crítico: sin esto, K8s no encuentra las imágenes locales
eval "$(minikube docker-env)"
log_ok "Docker apuntando al daemon de Minikube"

log_info "  Construyendo pacientes-service..."
docker build -t pacientes-service:1.0.0 "$PROJECT_ROOT/microservices/pacientes-service/"
log_ok "  pacientes-service:1.0.0 construida"

log_info "  Construyendo citas-service..."
docker build -t citas-service:1.0.0 "$PROJECT_ROOT/microservices/citas-service/"
log_ok "  citas-service:1.0.0 construida"

# ── PASO 5: Aplicar manifiestos de Kubernetes ─────────────────────────────
log_info "Paso 5/7 — Aplicando manifiestos de Kubernetes..."

kubectl apply -f "$PROJECT_ROOT/k8s/namespace/namespace.yaml"
kubectl apply -f "$PROJECT_ROOT/k8s/pacientes/"
kubectl apply -f "$PROJECT_ROOT/k8s/citas/"
kubectl apply -f "$PROJECT_ROOT/k8s/monitoring/servicemonitor-pacientes.yaml"
kubectl apply -f "$PROJECT_ROOT/k8s/monitoring/servicemonitor-citas.yaml"
kubectl apply -f "$PROJECT_ROOT/k8s/monitoring/prometheus-rules.yaml"
log_ok "Manifiestos aplicados"

# ── PASO 6: Esperar a que los pods estén Running ───────────────────────────
log_info "Paso 6/7 — Esperando que los pods arranquen (máx 3 min)..."

kubectl rollout status deployment/pacientes-service -n veterinaria --timeout=3m
log_ok "pacientes-service en Running"

kubectl rollout status deployment/citas-service -n veterinaria --timeout=3m
log_ok "citas-service en Running"

# ── PASO 7: Mostrar URLs de acceso ────────────────────────────────────────
log_info "Paso 7/7 — Calculando URLs de acceso..."

MINIKUBE_IP=$(minikube ip)

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                  SISTEMA LISTO ✓                            ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo -e "║  Grafana:            http://${MINIKUBE_IP}:30300             ║"
echo -e "║  Usuario Grafana:    admin                                   ║"
echo -e "║  Contraseña:         veterinaria2026                         ║"
echo "║                                                              ║"
echo -e "║  Prometheus:         http://${MINIKUBE_IP}:30090             ║"
echo "║                                                              ║"
echo -e "║  pacientes-service:  http://${MINIKUBE_IP}:30081             ║"
echo -e "║  citas-service:      http://${MINIKUBE_IP}:30082             ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
log_ok "Setup completado exitosamente"
