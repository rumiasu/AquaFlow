#!/usr/bin/env bash
# =============================================================================
# 本地一键验证（改造执行任务书 Phase E.5）。
# 步骤：重建测试库 → 后端集成测试 → 小程序静态扫描 → 敏感信息扫描。
#
# 用法：
#   export MYSQL_PWD='***'         # 本地 root 密码，勿提交
#   export AQUAFLOW_ALLOW_DB_RESET=aquaflow_test
#   export AQUAFLOW_ALLOW_TEST_DB_TARGET=127.0.0.1:3306/aquaflow_test
#   bash scripts/verify.sh
#
# 说明：
#   * 集成测试只连 aquaflow_test（独立可重建），不会触碰真实库 aquaflow。
#   * 用 --project-cache-dir .gradle_alt 规避「后端 bootRun 运行中导致 gradle 锁冲突」。
#
# 退出码：0 = 真的全跑且全过；1 = 有门禁失败，**或**有整组门禁因缺依赖没跑（见下）。
#   ⚠️ 缺 python/node 时本脚本**不再返回 0**（2026-09-30 修 F-02）：那时会打印 ASCII 哨兵
#   `AQUAFLOW_VERIFY_INCOMPLETE` 并 exit 1 —— 原版只 echo 一句、结尾仍无条件打印「全部通过」，
#   在 PATH 被裁剪的机器上给出**假绿**（本仓反复记录过的"本机全绿、CI 红"形状）。
# =============================================================================
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

# 「整组门禁根本没跑」与「某个门禁跑红了」是两回事，但**都不许返回 0**（见文件头 F-02 说明）。
MISSING_DEP=0

# [2026-10-02 F-68] 原来 SQL 清空一个库，Gradle 却继承另一个 JDBC 目标。
# 先校验完整地址/驱动参数/所有受支持的配置入口，再规范化并原样交给两条链。
TARGET_OUTPUT="$(node scripts/lib/test-database-target.js --verify)"
mapfile -t TARGET_FIELDS <<< "$TARGET_OUTPUT"
export MYSQL_HOST="${TARGET_FIELDS[0]}" MYSQL_PORT="${TARGET_FIELDS[1]}"
export TEST_DB_NAME="${TARGET_FIELDS[2]}" TEST_DB_URL="${TARGET_FIELDS[3]}"

# F-68：整轮会重建两个不同目标，分别显式确认；预检失败时不得先清测试库。
AQUAFLOW_ALLOW_DB_RESET="${AQUAFLOW_ALLOW_PRODCHECK_RESET:-}" node scripts/lib/scratch-database.js \
  prodcheck "${AQUAFLOW_PRODCHECK_DB:-aquaflow_prodstartup_check}" "${AQUAFLOW_DB:-aquaflow}"

echo "==================== [1/7] 重建测试库 ===================="
# F-68：调用者须先确认 TEST_DB_NAME 对应可清空库，并显式设置 AQUAFLOW_ALLOW_DB_RESET。
bash scripts/provision-test-db.sh

echo "==================== [2/7] 后端集成测试 ===================="
( cd AquaFlow-backend && ./gradlew test --no-daemon --project-cache-dir .gradle_alt )

echo "==================== [3/7] 小程序静态扫描 ===================="
PY="$(command -v python || command -v python3 || true)"
if [ -n "$PY" ]; then
  # 九个脚本都有真实退出码：0 通过 / 1 有致命问题（check-miniapp-text.js 遇"环境不允许"退 3）。
  # 这里不再用 `|| echo` 吞掉失败，否则脚本红着也会打印「验证全部通过」。audit_wxml_handlers.py 自带两端遍历；
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
  # 小程序文本体检（2026-09-30 新增，F-33 + F-13 的一半）：按扩展名**全仓遍历**两端小程序的
  # .js/.wxml/.wxss/.json，查 ① UTF-8 BOM（带 BOM 的 .wxss 让开发者工具报编译错且不指名文件，
  # 而当时四个门禁全绿，见 skill §8.28）② `<text>` 内出现开发词（面向站长/顾客的文案禁令，
  # AGENTS §6；注释里随便写，脚本先剥注释再匹配）。
  # ⚠️ 它取代了 scripts/debug/__check-no-bom.js 那个**没有任何入口**的孤儿脚本（check-gate-parity.js
  #    的 GATE_PATTERN 认不出 `__` 前缀；旧脚本是硬编码 16 文件清单）。旧脚本有意留着，别再往它清单里加文件。
  node scripts/check-miniapp-text.js || { echo "❌ 小程序文本体检未通过"; SCAN_FAILED=1; }
  # 场景测试矩阵一致性门禁（2026-09-21）：矩阵是「哪个业务场景有覆盖」的指定入口，
  # 但它是手写文档、测试类会被改名/删掉。本步让它的声称可被机器核对
  # （引用的类/方法必须存在、✅ 行必须指得出证据、STATS 件数必须与实测一致）。
  # ⚠️ 依赖上一步刚跑完的 build/test-results/test/*.xml，故顺序不可提前。
  run_scan audit_scenario_matrix.py
  if [ "$SCAN_FAILED" -ne 0 ]; then
    echo "❌ 静态扫描未通过"; exit 1
  fi
else
  echo "[verify] 未找到 python，跳过静态扫描整组（CI 上会强制执行）"
  MISSING_DEP=1
fi

echo "==================== [4/7] 小程序流程测试 ===================="
# 「流程测试」= 真的执行页面处理函数、用假 wx / 假后端回调驱动，断言"发了几次请求、
# 用的哪个幂等键、跳去哪一页、页面什么状态"。静态门禁只能看出"页面里有没有写 needConfirm"，
# 而缺货当成功、重试变第二单、把响应对象当订单号这三类事故**都是流程错**，只有流程测试抓得住。
if command -v node >/dev/null 2>&1; then
  node tests/js/run-all.js || { echo "❌ 小程序流程测试未通过"; exit 1; }
else
  echo "[verify] 未找到 node，跳过流程测试（CI 上会强制执行）"
  MISSING_DEP=1
fi

echo "==================== [5/7] 生产配置 / 发布物 / 冒烟（2026-09-27 新增） ===================="
# 三个 node 门禁，都有真实退出码（0 通过 / 1 有问题；check-* 遇"环境不允许"会退 3）：
#   check-prod-config.js        ：prod 要求的每个环境变量在 .env.example 都有定义行；
#                                 没有幽灵变量；留空的必须在带理由的白名单里；两个致命开关没开。
#                                 （为什么需要：RequiredConfigChecker 只硬校验 3 项，
#                                  其余 10 项是 prod yml 里无默认占位符导致的失败 —— 两套机制别混。）
#   check-jar-no-local-config.js：**打出来的 jar 里不许有 application-local.yml**。
#                                 为什么单独查：它被 .gitignore 忽略、也被 scan-secrets.sh 排除
#                                 （那个脚本用 git grep，只看已跟踪的文本文件）—— 两道防线都不看它。
#                                 需要先 bootJar（本步会自己打一次）。
#   smoke-check.js              ：判"这次发布起对了没有"（存活 / 连得上库 / 认证真在拦）。
#                                 打本机 8080；**服务没起时它会失败**，故先探端口再决定跑不跑。
if command -v node >/dev/null 2>&1; then
  node scripts/check-prod-config.js || { echo "❌ 生产配置门禁未通过"; exit 1; }
  # 另两道**纯静态**门禁（2026-09-28 新增，与 CI / verify-local 对齐）：
  #   check-tracked-inputs.js：CI/发布依赖的件是否**都已入库**（被 .gitignore 忽略的必需件 = 克隆后必然没有）。
  #     实测事故：application-prod.yml 一度同时被忽略且未被跟踪 ⇒ 新克隆的 CI 第一步就红，而本机全绿。
  #   check-sql-catalog.js：sql/ 与 sql/README.md（迁移清单**正本**）双向一致。
  #     实测曾有 83 个 .sql 里 21 个无出处，含 clear_data.sql（清全库）与 reset_passwords.sql（重置全部员工密码）。
  node scripts/check-tracked-inputs.js || { echo "❌ 入库件检查未通过"; exit 1; }
  node scripts/check-sql-catalog.js || { echo "❌ 迁移清单一致性未通过"; exit 1; }
  #   check-ledger-claims.js：台账 §2 的「度量方法」列落成可执行断言（F-49，2026-09-30 第六批）。
  #     MUST（领域不变量）不达标即红；FINDING 与 §4 状态位互证 —— 台账标 FIXED 却实测不达标 = 红。
  #     为什么要有：台账自己栽过三次「判据写在表里、没有一条能跑」（"全有用例"错 4 条等）。
  node scripts/check-ledger-claims.js || { echo "❌ 台账判据可执行化未通过"; exit 1; }
  #   check-gate-parity.js：三处验证入口（ci.yml / verify.sh / verify-local.js）的门禁清单是否一致 ——
  #     新加门禁只接一处时，另一处**永远不会告诉你它漏了**，表现为「本机全绿、CI 红」且本地复现不出。
  node scripts/check-gate-parity.js || { echo "❌ 门禁清单不一致"; exit 1; }
  #   check-pending-decisions.js：「待拍板」标记必须指向**真实存在**的 docs/** 正本（AGENTS §0.6 的强制项）。
  #     实测事故：退桶押金"钱怎么回到客户手上"只挂在小程序一行注释里，全仓文档查无此问 ——
  #     下一个人既不知道它悬着、也不知道去哪拍。只判**代码侧**，文档侧只列出来给人过。
  node scripts/check-pending-decisions.js || { echo "❌ 待拍板可追溯性未通过"; exit 1; }
  #   check-api-doc.js：controller 注解里的端点 ↔ `docs/api/01-REST-API参考.md` **双向一致**。
  #     实测 2026-09-28：v67 站间结算 4 条 + v66 押金交付 2 条新端点加进代码后，文档一个字没动（漂 6 条）
  #     —— 两个方向的后果不同：文档漏登记 ⇒ 新人不知道有这个能力；文档多登记 ⇒ 照它调用必然 404。
  node scripts/check-api-doc.js || { echo "❌ 端点契约与 API 参考不一致"; exit 1; }

  echo "--- 发布物检查（先 bootJar）---"
  ( cd AquaFlow-backend && ./gradlew bootJar --no-daemon --console=plain -q ) \
    || { echo "❌ bootJar 失败"; exit 1; }
  node scripts/check-jar-no-local-config.js || { echo "❌ 发布物检查未通过"; exit 1; }

  # 生产启动姿态（2026-09-28 新增）：拿**上一步刚打出来的 jar** 真起 13 次，每次摘掉一个环境变量，
  # 断言「必需项缺失必须拒启、COS 四件套缺失只降级」。约 3.5 分钟。
  # 为什么用真 jar 起：静态比对键名证明不了「真的会拒启」，也发现不了「声明了却没人读」的键 ——
  # 实测就抓到过 COS_SECRET_ID/KEY 假必需、COS_REGION/BUCKET_NAME 悬空两处。
  AQUAFLOW_ALLOW_DB_RESET="${AQUAFLOW_ALLOW_PRODCHECK_RESET:-}" node scripts/prod-startup-check.js \
    || { echo "❌ 生产启动姿态未通过"; exit 1; }

  echo "--- 冒烟检查（仅当本机 8080 有服务时跑）---"
  if command -v curl >/dev/null 2>&1 && curl -s -o /dev/null --max-time 3 http://127.0.0.1:8080/api/stations/public; then
    node scripts/smoke-check.js || { echo "❌ 冒烟检查未通过"; exit 1; }
  else
    echo "[verify] 本机 8080 没有服务，跳过冒烟检查（部署后必须单独跑：node scripts/smoke-check.js <URL> --prod）"
  fi
else
  # F-29（2026-09-30 修）：原文引用 `make check-prod-config`，而**仓库没有 Makefile** ——
  # 照着提示敲必然报 "No rule to make target"，还得回头猜真实命令。改成实际的 node 调用写法；
  # 同时把「这三个门禁」改成真的数得出来的清单（本步实际是下面这 8 道 node 门禁，加 bootJar 的
  # 发布物检查、生产启动姿态、冒烟检查）：
  #   node scripts/check-prod-config.js / check-tracked-inputs.js / check-sql-catalog.js /
  #   check-ledger-claims.js / check-gate-parity.js / check-pending-decisions.js /
  #   check-api-doc.js / check-jar-no-local-config.js
  #   node scripts/prod-startup-check.js / node scripts/smoke-check.js
  # ⚠️ 上面这 10 个路径**故意写在 `#` 注释里**：`check-gate-parity.js` 会剥掉注释与 `echo` 提示行，
  #    只剩"真的会执行"的行 —— 把脚本名写进 `echo` 的续行会被它当成"这道门禁跑了"（假绿）。
  echo "[verify] 未找到 node，跳过本步的 8 道 node 门禁与生产启动姿态、冒烟检查（CI 上会强制执行）。"
  echo "         手动补跑：node scripts/check-prod-config.js（其余见本步上方注释）"
  MISSING_DEP=1
fi

echo "==================== [6/7] 敏感信息扫描 ===================="
bash scripts/scan-secrets.sh

echo "==================== [7/7] 备份 / 恢复演练（可选，需显式开启） ===================="
# 默认**不跑**：它会向明确授权的空演练库导入，属"会写库的动作"，
# 不该混进每次日常验证。上线前与改动迁移后各跑一次：
#   node scripts/backup-restore-drill.js help
echo "[verify] 未自动执行。先查看：node scripts/backup-restore-drill.js help"
echo "         按 operations/05 核准完整TCP及服务器身份 --config；恢复只用已有空库，全部保留。"

echo ""
# 结尾判据（2026-09-30 修 F-02）：**只有真的全跑且全过才打印成功并返回 0**。
# 原版此处**无条件**打印「✅ 本地验证全部通过」，于是缺 python/node（整组门禁根本没跑）时
# 仍给绿结论、退出码 0 —— 在 PATH 被裁剪的机器/镜像上就是"本机全绿、CI 红"的假绿形状。
if [ "$MISSING_DEP" -ne 0 ]; then
  echo "⚠️  本次验证**不完整**：有整组门禁因缺少依赖没有跑（见上方 [verify] 行）。"
  echo "   判据：**「没跑」不许当成「通过」**（AGENTS §5）—— 这些门禁在 CI 上会强制执行。"
  echo "AQUAFLOW_VERIFY_INCOMPLETE 1"
  exit 1
fi
echo "✅ 本地验证全部通过（全部门禁都真的跑过，无跳过项）"
