#!/bin/sh
# Preparation gate only; existing RequiredConfigChecker and schema guards remain authoritative.
# No network, DB initialization, credential output, or automatic fallback is performed here.
set -eu
reject() { printf '%s\n' "AQUAFLOW_TRIAL_CONFIG_REJECTED: $1" >&2; exit 2; }
required() {
  case "$2" in
    ''|*[![:space:]]*) : ;;
    *) reject "$1 is blank" ;;
  esac
  case "$2" in
    ''|*'__REQUIRED__'*|*'__REPLACE__'*|*'your-domain.com'*|*'example.invalid'*|*'${'*|*'<REQUIRED>'*) reject "$1 is missing or a placeholder" ;;
  esac
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
[ "${#JWT_SECRET}" -ge 32 ] || reject 'JWT_SECRET is too short'
case "$DB_URL" in jdbc:mysql://*) : ;; *) reject 'DB_URL must be a MySQL JDBC URL' ;; esac
[ "${SPRING_PROFILES_ACTIVE:-}" = prod ] || reject 'SPRING_PROFILES_ACTIVE must be prod'
[ "${DEV_LOGIN_ENABLED:-}" = false ] || reject 'DEV_LOGIN_ENABLED must be false'
[ "${MOCK_WECHAT_PAY:-}" = false ] || reject 'MOCK_WECHAT_PAY must be false'
[ "${APP_DEV_LOGIN_ENABLED:-false}" = false ] || reject 'APP_DEV_LOGIN_ENABLED must be false'
[ "${APP_PAYMENT_MOCK_WECHAT_PAY:-false}" = false ] || reject 'APP_PAYMENT_MOCK_WECHAT_PAY must be false'
[ "${SERVER_FORWARD_HEADERS_STRATEGY:-}" = none ] || reject 'SERVER_FORWARD_HEADERS_STRATEGY must be none'
[ "${SPRING_SQL_INIT_MODE:-}" = never ] || reject 'SPRING_SQL_INIT_MODE must be never'
# 2026-10-13 review: a trial must never silently switch existing independent assets to the old model.
# Reject conflicts rather than letting Spring precedence hide a false input. Unset means true;
# explicitly empty/false/other spellings fail. c19 reads aquaflow.barrel.independent-rights-enabled.
[ "${INDEPENDENT_BARREL_RIGHTS_ENABLED-true}" = true ] || reject 'INDEPENDENT_BARREL_RIGHTS_ENABLED must be true'
[ "${AQUAFLOW_BARREL_INDEPENDENT_RIGHTS_ENABLED-true}" = true ] || reject 'AQUAFLOW_BARREL_INDEPENDENT_RIGHTS_ENABLED must be true'
[ "${AQUAFLOW_BARREL_INDEPENDENTRIGHTSENABLED-true}" = true ] || reject 'AQUAFLOW_BARREL_INDEPENDENTRIGHTSENABLED must be true'
export INDEPENDENT_BARREL_RIGHTS_ENABLED=true
export AQUAFLOW_BARREL_INDEPENDENT_RIGHTS_ENABLED=true AQUAFLOW_BARREL_INDEPENDENTRIGHTSENABLED=true
# This limited template accepts no alternate config locations or JVM launch injection.
# Required production heap/settings should be reviewed into the launch command, not pasted as strings.
[ -z "${SPRING_APPLICATION_JSON:-}${SPRING_CONFIG_IMPORT:-}${SPRING_CONFIG_LOCATION:-}${SPRING_CONFIG_ADDITIONAL_LOCATION:-}${SPRING_PROFILES_INCLUDE:-}${JAVA_TOOL_OPTIONS:-}${JDK_JAVA_OPTIONS:-}${_JAVA_OPTIONS:-}" ] || reject 'configuration/JVM override is outside this reviewed template'
case "$#:${1:-}" in
  1:--check-only) printf '%s\n' 'AQUAFLOW_TRIAL_CONFIG_SHAPE_OK (offline only; no connectivity or credential validation)'; exit 0 ;;
  0:) : ;;
  *) reject 'unsupported launch arguments' ;;
esac
exec java -jar /opt/aquaflow/app.jar --spring.profiles.active=prod --server.port=8080 --aquaflow.barrel.independent-rights-enabled=true
