#!/usr/bin/env bash
# =============================================================================
# 敏感信息扫描：在「将被提交的已跟踪文件」里找疑似硬编码密钥。
# 依据改造执行任务书 Phase E.5：CI 可用前至少提供本地一键验证脚本（含敏感信息扫描）。
#
# 只报位置，不回显命中内容（避免把密钥二次打印到日志）。
# 有意放行：application-local.yml（已 gitignore，本就不该被跟踪，但防御性排除）、
#           *.example / *.md 文档、依赖锁文件。
# =============================================================================
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

# 高置信度密钥特征：键名 + 等号/冒号 + 长度 >= 12 的值
PATTERN='(jwt[_-]?secret|app[_-]?secret|secret[_-]?key|secret-id|access[_-]?key|password|passwd|wx[_-]?app[_-]?secret)["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"'${}[:space:]]{12,}["'"'"']'

# 注意：不能用 `git grep -n`（它会连命中整行一起打印，等于把密钥二次抄进 CI 日志）。
# 这里用 -c 只输出「文件:处数」，既定位到文件，又保证任何密钥值都不会出现在输出里。
HITS="$(git grep -cEI "$PATTERN" -- \
      ':(exclude)*.md' \
      ':(exclude)*example*' \
      ':(exclude)*.lock' \
      ':(exclude)*.example' \
      ':(exclude)*application-local.yml' \
      ':(exclude)*.min.js' \
      ':(exclude)archive/*' || true)"

if [ -n "$HITS" ]; then
  echo "$HITS" | while IFS= read -r line; do
    echo "  疑似硬编码密钥：${line%:*}（${line##*:} 处）"
  done
  echo ""
  echo "[scan-secrets] ✗ 发现疑似硬编码密钥（仅列出文件与处数，不回显内容）。请改用环境变量注入。" >&2
  exit 1
fi

echo "[scan-secrets] ✓ 未发现硬编码密钥"
