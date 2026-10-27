#!/bin/sh
# Offline shape checks first; actual URL/instance/marker checks remain in the datasource boundary.
set -eu
reject() { printf '%s\n' "AQUAFLOW_PAYMENT_SANDBOX_REJECTED: $1" >&2; exit 2; }
required() {
  case "$2" in ''|*[![:space:]]*) : ;; *) reject "$1 is blank" ;; esac
  case "$2" in ''|*'__REQUIRED__'*|*'${'*|*'<REQUIRED>'*) reject "$1 is missing or a placeholder" ;; esac
}
required DB_URL "${DB_URL:-}"
required DB_USERNAME "${DB_USERNAME:-}"
required DB_PASSWORD "${DB_PASSWORD:-}"
required JWT_SECRET "${JWT_SECRET:-}"
required WX_APP_ID "${WX_APP_ID:-}"
required WX_APP_SECRET "${WX_APP_SECRET:-}"
required WX_STAFF_APP_ID "${WX_STAFF_APP_ID:-}"
required WX_STAFF_APP_SECRET "${WX_STAFF_APP_SECRET:-}"
required CORS_ALLOWED_ORIGINS "${CORS_ALLOWED_ORIGINS:-}"
required PAYMENT_SANDBOX_ENV_ID "${PAYMENT_SANDBOX_ENV_ID:-}"
required PAYMENT_SANDBOX_SERVICE_ID "${PAYMENT_SANDBOX_SERVICE_ID:-}"
required PAYMENT_SANDBOX_BINDING_ID "${PAYMENT_SANDBOX_BINDING_ID:-}"
required PAYMENT_SANDBOX_RUNTIME_ENV_ID "${PAYMENT_SANDBOX_RUNTIME_ENV_ID:-}"
required PAYMENT_SANDBOX_RUNTIME_SERVICE_ID "${PAYMENT_SANDBOX_RUNTIME_SERVICE_ID:-}"
required PAYMENT_SANDBOX_DB_HOST "${PAYMENT_SANDBOX_DB_HOST:-}"
required PAYMENT_SANDBOX_DB_PORT "${PAYMENT_SANDBOX_DB_PORT:-}"
required PAYMENT_SANDBOX_DB_NAME "${PAYMENT_SANDBOX_DB_NAME:-}"
required PAYMENT_SANDBOX_DB_SERVER_UUID "${PAYMENT_SANDBOX_DB_SERVER_UUID:-}"
[ "${#JWT_SECRET}" -ge 32 ] || reject 'JWT_SECRET is too short'
[ "${SPRING_PROFILES_ACTIVE:-}" = payment-sandbox ] || reject 'only payment-sandbox profile is supported'
[ "${DEV_LOGIN_ENABLED:-}" = false ] || reject 'DEV_LOGIN_ENABLED must be false'
[ "${APP_DEV_LOGIN_ENABLED:-false}" = false ] || reject 'APP_DEV_LOGIN_ENABLED must be false'
[ "${MOCK_PAYMENT_ISOLATED:-}" = true ] || reject 'explicit isolation declaration is required'
case "${MOCK_WECHAT_PAY:-}" in true) required MOCK_PAYMENT_CUSTOMER_IDS "${MOCK_PAYMENT_CUSTOMER_IDS:-}" ;; false) : ;; *) reject 'MOCK_WECHAT_PAY must be true or false' ;; esac
[ "${APP_PAYMENT_MOCK_WECHAT_PAY-${MOCK_WECHAT_PAY}}" = "${MOCK_WECHAT_PAY}" ] || reject 'conflicting mock aliases'
[ "${PAYMENT_SANDBOX_ENV_ID}" = "${PAYMENT_SANDBOX_RUNTIME_ENV_ID}" ] || reject 'runtime environment mismatch'
[ "${PAYMENT_SANDBOX_SERVICE_ID}" = "${PAYMENT_SANDBOX_RUNTIME_SERVICE_ID}" ] || reject 'runtime service mismatch'
[ "${SERVER_FORWARD_HEADERS_STRATEGY:-}" = none ] || reject 'forward headers must remain none'
[ "${SPRING_SQL_INIT_MODE:-}" = never ] || reject 'automatic SQL initialization is forbidden'
[ -z "${SPRING_APPLICATION_JSON:-}${SPRING_CONFIG_IMPORT:-}${SPRING_CONFIG_LOCATION:-}${SPRING_CONFIG_ADDITIONAL_LOCATION:-}${SPRING_PROFILES_INCLUDE:-}${JAVA_TOOL_OPTIONS:-}${JDK_JAVA_OPTIONS:-}${_JAVA_OPTIONS:-}" ] || reject 'unreviewed configuration/JVM override'
case "$#:${1:-}" in
  1:--check-only) printf '%s\n' 'AQUAFLOW_PAYMENT_SANDBOX_SHAPE_OK (offline only; identity/DB not yet verified)'; exit 0 ;;
  0:) : ;;
  *) reject 'unsupported launch arguments' ;;
esac
exec java -jar /opt/aquaflow/app.jar --spring.profiles.active=payment-sandbox --server.port=8080
