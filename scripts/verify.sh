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

echo "==================== [1/5] 重建测试库 ===================="
bash scripts/provision-test-db.sh

echo "==================== [2/5] 后端集成测试 ===================="
( cd AquaFlow-backend && ./gradlew test --no-daemon --project-cache-dir .gradle_alt )

echo "==================== [3/5] 小程序静态扫描 ===================="
PY="$(command -v python || command -v python3 || true)"
if [ -n "$PY" ]; then
  # 七个脚本都有真实退出码：0 通过 / 1 有致命问题。这里不再用 `|| echo` 吞掉失败，
  # 否则脚本红着也会打印「验证全部通过」。audit_wxml_handlers.py 自带两端遍历；
  # 另两个需显式传端名，故两端各跑一次（与 .github/workflows/ci.yml 保持一致）。
  SCAN_FAILED=0
  run_scan() {
    echo "--- $* ---"
    "$PY" "$@" || { echo "[verify] 失败：$*"; SCAN_FAILED=1; }
  }
  run_scan audit_wxml_handlers.py
  run_scan page_reach_audit.py miniapp-user
  run_scan page_reach_audit.py miniapp-delivery
  run_scan static_audit_user.py miniapp-user
  run_scan static_audit_user.py miniapp-delivery
  # 注释体检：揪出「悬空 javadoc」。悬空注释会误导读者（已有三次真实事故，
  # 见 AGENTS.md §8 第 14 条），故纳入门禁。自带两端遍历，无需传参。
  run_scan audit_comments.py
  # js 语法体检（node --check 全量）：解析期错误（重复 const 声明 / 少括号）会让**整个模块**
  # 加载失败，而上面五个脚本都不解析 js。2026-09-19 真实事故：重复声明
  # updateOfflinePayment → 站长端一批页面同时白屏，门禁与 381 条用例一个都没报出来。
  # 缺 node 时该脚本退出码是 2（跳过），不能当失败算，故单独判一次。
  if command -v node >/dev/null 2>&1; then
    run_scan audit_js_syntax.py
  else
    echo "[verify] 未找到 node，跳过 audit_js_syntax.py（CI 上会强制执行）"
  fi
  # WXSS 选择器体检（2026-09-26）：`class="popup-content asset-warning"`（**同一个元素**两个类）
  # 被写成 `.asset-warning .popup-content`（子孙选择器）⇒ 整块定位样式静默失效，
  # 首次资产告知的卡片被留在 translateY(100%) 屏幕外、只剩遮罩，客户首单被挡死，
  # 而上面六个脚本全绿（没有一个解析 wxss）。自带两端遍历，无需传参。
  run_scan audit_wxss_selectors.py
  # 场景测试矩阵一致性门禁（2026-09-21）：矩阵是「哪个业务场景有覆盖」的指定入口，
  # 但它是手写文档、测试类会被改名/删掉。本步让它的声称可被机器核对
  # （引用的类/方法必须存在、✅ 行必须指得出证据、STATS 件数必须与实测一致）。
  # ⚠️ 依赖上一步刚跑完的 build/test-results/test/*.xml，故顺序不可提前。
  run_scan audit_scenario_matrix.py
  if [ "$SCAN_FAILED" -ne 0 ]; then
    echo "❌ 静态扫描未通过"; exit 1
  fi
else
  echo "[verify] 未找到 python，跳过静态扫描（CI 上会强制执行）"
fi

echo "==================== [4/5] 小程序流程测试 ===================="
# 「流程测试」= 真的执行页面处理函数、用假 wx / 假后端回调驱动，断言"发了几次请求、
# 用的哪个幂等键、跳去哪一页、页面什么状态"。静态门禁只能看出"页面里有没有写 needConfirm"，
# 而缺货当成功、重试变第二单、把响应对象当订单号这三类事故**都是流程错**，只有流程测试抓得住。
if command -v node >/dev/null 2>&1; then
  node tests/js/run-all.js || { echo "❌ 小程序流程测试未通过"; exit 1; }
else
  echo "[verify] 未找到 node，跳过流程测试（CI 上会强制执行）"
fi

echo "==================== [5/5] 敏感信息扫描 ===================="
bash scripts/scan-secrets.sh

echo ""
echo "✅ 本地验证全部通过"
