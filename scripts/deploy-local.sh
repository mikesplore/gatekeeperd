#!/usr/bin/env bash
# Run the already-built Gatekeeperd JAR locally and wait for readiness.
# This is a local smoke deployment; production deploy.sh remains unchanged.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
JAR_PATH="${DEPLOY_LOCAL_JAR:-${PROJECT_DIR}/build/libs/gatekeeperd-all.jar}"
ENV_FILE="${DEPLOY_LOCAL_ENV_FILE:-${PROJECT_DIR}/.env}"
LOCAL_PORT="${DEPLOY_LOCAL_PORT:-18081}"
READY_PATH="${DEPLOY_LOCAL_READY_PATH:-/api/health/ready}"
STARTUP_TIMEOUT="${DEPLOY_LOCAL_STARTUP_TIMEOUT:-120}"
BASE_URL="http://127.0.0.1:${LOCAL_PORT}"
LOG_FILE="${DEPLOY_LOCAL_LOG_FILE:-${PROJECT_DIR}/build/deploy-local.log}"
RESPONSE_FILE=""
APP_PID=""

log() { printf '[%s] ==> %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { echo "Error: $*" >&2; exit 1; }

cleanup() {
  if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
    log "Stopping local Gatekeeperd process..."
    kill -TERM "${APP_PID}" 2>/dev/null || true
    wait "${APP_PID}" 2>/dev/null || true
  fi
  [[ -z "${RESPONSE_FILE}" ]] || rm -f "${RESPONSE_FILE}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

usage() {
  cat <<EOF
Usage: $0

Starts the existing build/libs/gatekeeperd-all.jar on localhost:${LOCAL_PORT},
waits for ${READY_PATH} to report ready, and keeps the app running for local
manual testing. Press Ctrl+C to stop it.

Optional environment:
  DEPLOY_LOCAL_JAR                 JAR path (default: ${PROJECT_DIR}/build/libs/gatekeeperd-all.jar)
  DEPLOY_LOCAL_ENV_FILE            env file (default: ${PROJECT_DIR}/.env)
  DEPLOY_LOCAL_PORT                local listen port (default: ${LOCAL_PORT})
  DEPLOY_LOCAL_STARTUP_TIMEOUT     readiness timeout in seconds (default: 120)
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then usage; exit 0; fi
[[ $# -eq 0 ]] || { usage >&2; exit 2; }
[[ -f "${JAR_PATH}" && -s "${JAR_PATH}" ]] || die "Built JAR not found: ${JAR_PATH}. Run './gradlew test buildFatJar' first."
[[ "${LOCAL_PORT}" =~ ^[0-9]+$ ]] && (( LOCAL_PORT >= 1024 && LOCAL_PORT <= 65535 )) || die "DEPLOY_LOCAL_PORT must be between 1024 and 65535."
[[ "${STARTUP_TIMEOUT}" =~ ^[0-9]+$ ]] && (( STARTUP_TIMEOUT > 0 )) || die "DEPLOY_LOCAL_STARTUP_TIMEOUT must be a positive integer."
command -v java >/dev/null 2>&1 || die "Java is not available on PATH."
command -v curl >/dev/null 2>&1 || die "curl is not available on PATH."

if [[ -r "${ENV_FILE}" ]]; then
  log "Loading local environment from ${ENV_FILE}..."
  set -a
  # The project .env is a trusted local shell-style KEY=value file.
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

PORT="${LOCAL_PORT}"
export PORT
mkdir -p "$(dirname -- "${LOG_FILE}")"
RESPONSE_FILE="$(mktemp)"
log "Starting existing JAR ${JAR_PATH} on ${BASE_URL}..."
java -jar "${JAR_PATH}" >"${LOG_FILE}" 2>&1 &
APP_PID=$!

start_time="${SECONDS}"
last_report="${SECONDS}"
while (( SECONDS - start_time < STARTUP_TIMEOUT )); do
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    die "Gatekeeperd exited during startup. Startup log: ${LOG_FILE}"
  fi
  status="$(curl --silent --show-error --max-time 3 -o "${RESPONSE_FILE}" -w '%{http_code}' "${BASE_URL}${READY_PATH}" 2>/dev/null || true)"
  if [[ "${status}" == "200" ]]; then
    log "Gatekeeperd is ready. Response: $(cat "${RESPONSE_FILE}")"
    log "Use the local API at ${BASE_URL}; press Ctrl+C to stop. Startup log: ${LOG_FILE}"
    if wait "${APP_PID}"; then exit_code=0; else exit_code=$?; fi
    APP_PID=""
    exit "${exit_code}"
  fi
  if (( SECONDS - last_report >= 10 )); then
    detail="$(cat "${RESPONSE_FILE}" 2>/dev/null || true)"
    log "Waiting for readiness ($((SECONDS - start_time))s elapsed; HTTP ${status:-000})${detail:+: ${detail}}"
    last_report="${SECONDS}"
  fi
  sleep 2
done

detail="$(cat "${RESPONSE_FILE}" 2>/dev/null || true)"
die "Gatekeeperd did not become ready within ${STARTUP_TIMEOUT}s${detail:+. Last response: ${detail}}. Startup log: ${LOG_FILE}"
