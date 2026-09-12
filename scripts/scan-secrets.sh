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

if git grep -nEI "$PATTERN" -- \
      ':(exclude)*.md' \
      ':(exclude)*example*' \
      ':(exclude)*.lock' \
      ':(exclude)*.example' \
      ':(exclude)*application-local.yml' \
      ':(exclude)*.min.js' \
      ':(exclude)archive/*' ; then
  echo ""
  echo "[scan-secrets] ✗ 发现疑似硬编码密钥（见上方文件:行号）。请改用环境变量注入。" >&2
  exit 1
fi

echo "[scan-secrets] ✓ 未发现硬编码密钥"
