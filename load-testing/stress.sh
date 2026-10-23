#!/bin/bash
# =============================================================================
# stress.sh — Generador de tráfico para casuísticas
# Uso:
#   ./stress.sh normal          → tráfico mixto normal
#   ./stress.sh citas           → solo agendamiento de citas
#   ./stress.sh pacientes       → solo consultas de pacientes
#   ./stress.sh invalidos       → citas con pacientes inexistentes (errores)
#   ./stress.sh casuistica2     → activa delay + genera tráfico continuo
#   ./stress.sh reset-delay     → restaura delay a 0
#
# Opciones:
#   --host IP           IP de Minikube (default: auto-detectada)
#   --rps  N            Requests por segundo (default: 5)
#   --dur  SEGUNDOS     Duración total (default: 120)
# =============================================================================
set -euo pipefail

# ── Defaults ─────────────────────────────────────────────────────────────
MINIKUBE_HOST="${MINIKUBE_HOST:-$(minikube ip 2>/dev/null || echo "192.168.49.2")}"
RPS=5
DURACION=120
MODO="${1:-normal}"

# ── Parsear argumentos opcionales ────────────────────────────────────────
shift || true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) MINIKUBE_HOST="$2"; shift 2 ;;
    --rps)  RPS="$2";           shift 2 ;;
    --dur)  DURACION="$2";      shift 2 ;;
    *)      shift ;;
  esac
done

PACIENTES_URL="http://${MINIKUBE_HOST}:30081"
CITAS_URL="http://${MINIKUBE_HOST}:30082"
SLEEP_ENTRE_REQUESTS=$(echo "scale=4; 1/$RPS" | bc)

echo "══════════════════════════════════════════════════"
echo "  stress.sh — Modo: $MODO"
echo "  Host Minikube: $MINIKUBE_HOST"
echo "  pacientes: $PACIENTES_URL"
echo "  citas:     $CITAS_URL"
echo "  RPS: $RPS | Duración: ${DURACION}s"
echo "══════════════════════════════════════════════════"

# ── Función: verificar curl disponible ───────────────────────────────────
check_curl() {
  if ! command -v curl &>/dev/null; then
    echo "[ERROR] curl no está instalado"
    exit 1
  fi
}

# ── Función: request silencioso con log de status ────────────────────────
req() {
  local method="$1"
  local url="$2"
  local data="${3:-}"
  local status

  if [[ -n "$data" ]]; then
    status=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" \
      -H "Content-Type: application/json" -d "$data" "$url" 2>/dev/null || echo "000")
  else
    status=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" "$url" 2>/dev/null || echo "000")
  fi

  local ts
  ts=$(date '+%H:%M:%S')
  echo "[$ts] $method $url → HTTP $status"
}

check_curl

END_TIME=$((SECONDS + DURACION))

# ─────────────────────────────────────────────────────────────────────────
case "$MODO" in

  # ── Tráfico normal mixto ───────────────────────────────────────────────
  normal)
    echo "[INFO] Iniciando tráfico normal mixto por ${DURACION}s..."
    while [ $SECONDS -lt $END_TIME ]; do
      req GET  "$PACIENTES_URL/api/pacientes"
      req GET  "$PACIENTES_URL/api/pacientes/PAC-100"
      req GET  "$CITAS_URL/api/citas"
      req POST "$CITAS_URL/api/citas" \
        '{"pacienteId":"PAC-100","veterinario":"Dr. Ramírez","fecha":"2026-11-01","hora":"10:00","motivo":"Control"}'
      req GET  "$PACIENTES_URL/api/pacientes/estadisticas/especies"
      sleep "$SLEEP_ENTRE_REQUESTS"
    done
    ;;

  # ── Solo citas ────────────────────────────────────────────────────────
  citas)
    PACIENTES=("PAC-100" "PAC-101" "PAC-102" "PAC-103")
    VETS=("Dr. Ramírez" "Dra. Morales" "Dr. Soto")
    echo "[INFO] Generando citas por ${DURACION}s..."
    i=0
    while [ $SECONDS -lt $END_TIME ]; do
      PAC="${PACIENTES[$((i % ${#PACIENTES[@]}))]}"
      VET="${VETS[$((i % ${#VETS[@]}))]}"
      req POST "$CITAS_URL/api/citas" \
        "{\"pacienteId\":\"$PAC\",\"veterinario\":\"$VET\",\"fecha\":\"2026-11-10\",\"hora\":\"09:00\",\"motivo\":\"Consulta stress test\"}"
      ((i++)) || true
      sleep "$SLEEP_ENTRE_REQUESTS"
    done
    ;;

  # ── Solo consultas de pacientes ───────────────────────────────────────
  pacientes)
    echo "[INFO] Consultando pacientes por ${DURACION}s..."
    while [ $SECONDS -lt $END_TIME ]; do
      req GET "$PACIENTES_URL/api/pacientes"
      req GET "$PACIENTES_URL/api/pacientes/PAC-100"
      req GET "$PACIENTES_URL/api/pacientes/PAC-101"
      req GET "$PACIENTES_URL/api/pacientes/estadisticas/especies"
      sleep "$SLEEP_ENTRE_REQUESTS"
    done
    ;;

  # ── Requests con pacientes inválidos (genera errores de negocio) ──────
  invalidos)
    echo "[INFO] Generando citas con pacientes inválidos por ${DURACION}s..."
    i=0
    while [ $SECONDS -lt $END_TIME ]; do
      req POST "$CITAS_URL/api/citas" \
        "{\"pacienteId\":\"PAC-INEXISTENTE-$i\",\"veterinario\":\"Dr. Test\",\"fecha\":\"2026-11-10\",\"hora\":\"10:00\",\"motivo\":\"Test\"}"
      ((i++)) || true
      sleep "$SLEEP_ENTRE_REQUESTS"
    done
    ;;

  # ── Casuística 2: activa delay y genera tráfico para ver timeouts ─────
  casuistica2)
    DELAY_MS="${DELAY_MS:-4000}"
    echo "[WARN] Activando delay artificial de ${DELAY_MS}ms en pacientes-service..."
    req POST "$PACIENTES_URL/api/pacientes/admin/delay?ms=${DELAY_MS}"
    echo "[INFO] Delay activado. Generando tráfico por ${DURACION}s para ver timeouts..."
    echo "[INFO] Observa en Grafana: citas_errores_comunicacion_pacientes_total"
    while [ $SECONDS -lt $END_TIME ]; do
      req POST "$CITAS_URL/api/citas" \
        '{"pacienteId":"PAC-100","veterinario":"Dr. Ramírez","fecha":"2026-11-15","hora":"11:00","motivo":"Timeout test"}'
      sleep "$SLEEP_ENTRE_REQUESTS"
    done
    ;;

  # ── Restaurar delay a 0 ───────────────────────────────────────────────
  reset-delay)
    echo "[INFO] Restaurando delay artificial a 0ms..."
    req POST "$PACIENTES_URL/api/pacientes/admin/delay?ms=0"
    echo "[OK] Delay restaurado."
    ;;

  *)
    echo "Modo '$MODO' no reconocido."
    echo "Modos válidos: normal | citas | pacientes | invalidos | casuistica2 | reset-delay"
    exit 1
    ;;
esac

echo ""
echo "[OK] stress.sh finalizado."
