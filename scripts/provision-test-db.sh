#!/usr/bin/env bash
# =============================================================================
# 重建集成测试库（独立、可重建）。仅供本地开发/CI 使用。
#
# 依据改造执行任务书 Phase A.4 / Phase B：本机无 Docker，采用「独立可重建测试库」方案。
# 流程：DROP DATABASE aquaflow_test → CREATE → 从权威基线 sql/schema.sql 建表。
#
# 安全约定：密码一律不写入任何文件；通过环境变量 MYSQL_PWD 注入（见下）。
#
# 用法：
#   export MYSQL_PWD='***'          # 本地 root 密码，勿提交
#   bash scripts/provision-test-db.sh
#
# 可覆盖的环境变量：
#   MYSQL_BIN     mysql 客户端路径（默认自动探测 D:/backend/MySQL/bin/mysql.exe 或 PATH 中的 mysql）
#   MYSQL_USER    默认 root
#   TEST_DB_NAME  默认 aquaflow_test
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
SCHEMA="$HERE/AquaFlow-backend/sql/schema.sql"

MYSQL_USER="${MYSQL_USER:-root}"
TEST_DB_NAME="${TEST_DB_NAME:-aquaflow_test}"

# 探测 mysql 客户端
if [ -n "${MYSQL_BIN:-}" ]; then
  MYSQL="$MYSQL_BIN"
elif [ -x "/d/backend/MySQL/bin/mysql.exe" ]; then
  MYSQL="/d/backend/MySQL/bin/mysql.exe"
else
  MYSQL="$(command -v mysql || true)"
fi
if [ -z "${MYSQL:-}" ]; then
  echo "[provision-test-db] 找不到 mysql 客户端，请设置 MYSQL_BIN" >&2
  exit 1
fi

if [ ! -f "$SCHEMA" ]; then
  echo "[provision-test-db] 找不到基线文件: $SCHEMA" >&2
  exit 1
fi

if [ -z "${MYSQL_PWD:-}" ]; then
  echo "[provision-test-db] 请先 export MYSQL_PWD（不要写进文件）" >&2
  exit 1
fi

echo "[provision-test-db] 使用客户端: $MYSQL"
echo "[provision-test-db] 重建库: $TEST_DB_NAME（来源 schema.sql）"

"$MYSQL" -u"$MYSQL_USER" -e "DROP DATABASE IF EXISTS \`$TEST_DB_NAME\`; CREATE DATABASE \`$TEST_DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
"$MYSQL" -u"$MYSQL_USER" "$TEST_DB_NAME" < "$SCHEMA"

COUNT="$("$MYSQL" -u"$MYSQL_USER" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TEST_DB_NAME';")"
echo "[provision-test-db] 完成：$TEST_DB_NAME 现有 $COUNT 个表/视图"
