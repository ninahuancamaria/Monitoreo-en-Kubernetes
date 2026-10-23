#!/bin/bash
# =============================================================================
# teardown.sh — Elimina todos los recursos del sistema
# Uso: ./teardown.sh           → borra recursos K8s, conserva el cluster
#      ./teardown.sh --all     → borra todo, incluyendo el cluster Minikube
# =============================================================================
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC}  $1"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }

DELETE_CLUSTER=false
if [[ "${1:-}" == "--all" ]]; then
  DELETE_CLUSTER=true
fi

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║   Clínica Veterinaria — Teardown del sistema    ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# ── Eliminar recursos del namespace veterinaria ───────────────────────────
log_info "Eliminando microservicios (namespace: veterinaria)..."
kubectl delete -f "$PROJECT_ROOT/k8s/citas/" --ignore-not-found=true
kubectl delete -f "$PROJECT_ROOT/k8s/pacientes/" --ignore-not-found=true
kubectl delete -f "$PROJECT_ROOT/k8s/namespace/namespace.yaml" --ignore-not-found=true
log_ok "Microservicios eliminados"

# ── Eliminar recursos de monitoreo personalizados ─────────────────────────
log_info "Eliminando ServiceMonitors y PrometheusRules..."
kubectl delete -f "$PROJECT_ROOT/k8s/monitoring/servicemonitor-pacientes.yaml" --ignore-not-found=true
kubectl delete -f "$PROJECT_ROOT/k8s/monitoring/servicemonitor-citas.yaml" --ignore-not-found=true
kubectl delete -f "$PROJECT_ROOT/k8s/monitoring/prometheus-rules.yaml" --ignore-not-found=true
log_ok "Recursos de monitoreo eliminados"

# ── Desinstalar Helm chart ────────────────────────────────────────────────
log_info "Desinstalando kube-prometheus-stack..."
helm uninstall kube-prometheus-stack -n monitoring 2>/dev/null || log_warn "Chart no encontrado, continuando"
kubectl delete namespace monitoring --ignore-not-found=true
log_ok "Stack de monitoreo desinstalado"

# ── Eliminar imágenes Docker locales de Minikube ─────────────────────────
log_info "Eliminando imágenes Docker del daemon de Minikube..."
eval "$(minikube docker-env)" 2>/dev/null || true
docker rmi pacientes-service:1.0.0 2>/dev/null || log_warn "Imagen pacientes-service no encontrada"
docker rmi citas-service:1.0.0 2>/dev/null || log_warn "Imagen citas-service no encontrada"
log_ok "Imágenes eliminadas"

# ── Eliminar cluster completo si se pidió ─────────────────────────────────
if [ "$DELETE_CLUSTER" = true ]; then
  log_warn "Eliminando cluster Minikube completo..."
  minikube delete
  log_ok "Cluster Minikube eliminado"
else
  log_info "Cluster Minikube conservado. Usa './teardown.sh --all' para eliminarlo."
fi

echo ""
log_ok "Teardown completado"
