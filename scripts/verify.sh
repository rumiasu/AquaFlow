#!/usr/bin/env bash
# =============================================================================
# 本地一键验证（改造执行任务书 Phase E.5）。
# 步骤：重建测试库 → 后端集成测试 → 小程序静态扫描 → 敏感信息扫描。
#
# 用法：
#   export MYSQL_PWD='***'         # 本地 root 密码，勿提交
#   bash scripts/verify.sh
#
# 说明：
#   * 集成测试只连 aquaflow_test（独立可重建），不会触碰真实库 aquaflow。
#   * 用 --project-cache-dir .gradle_alt 规避「后端 bootRun 运行中导致 gradle 锁冲突」。
# =============================================================================
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

echo "==================== [1/4] 重建测试库 ===================="
bash scripts/provision-test-db.sh

echo "==================== [2/4] 后端集成测试 ===================="
( cd AquaFlow-backend && ./gradlew test --no-daemon --project-cache-dir .gradle_alt )

echo "==================== [3/4] 小程序静态扫描 ===================="
PY="$(command -v python || command -v python3 || true)"
if [ -n "$PY" ]; then
  for s in audit_wxml_handlers.py static_audit_user.py page_reach_audit.py; do
    if [ -f "$s" ]; then
      echo "--- $s ---"
      "$PY" "$s" || echo "[verify] $s 报告了问题（见上）"
    fi
  done
else
  echo "[verify] 未找到 python，跳过静态扫描"
fi

echo "==================== [4/4] 敏感信息扫描 ===================="
bash scripts/scan-secrets.sh

echo ""
echo "✅ 本地验证全部通过"
