#!/usr/bin/env node
/**
 * `sql/` 目录与 `sql/README.md` 的**双向**一致性门禁。
 *
 *   node scripts/check-sql-catalog.js
 *
 * ## 为什么要有它
 *
 * `sql/README.md` 被 `AGENTS.md` §4 与运维文档同时指定为**迁移清单的正本**。而正本一旦与目录脱节，
 * 后果不是"文档不好看"，是**运维真的会踩**：
 *   · **正向**（文件有、README 没有）：一个脚本躺在那儿没有任何出处，运维分不清它是
 *     "废弃的 / 一次性的 / 我漏跑了" —— 这正是 `docs/operations/01-部署与运维.md` 里
 *     自称「**当前最可能踩的运维坑**」那一条（漏跑 ⇒ 老库缺表缺列 ⇒ 运行时 1054）。
 *     2026-09-28 实测：**83 个 `.sql` 里有 21 个在 README 里完全没被点名**
 *     （其中还包括 `clear_data.sql` 与 `reset_passwords.sql` 两个最危险的 —— 已补进「严禁在生产执行」表）。
 *   · **反向**（README 指向不存在的文件）：运维照着**必跑清单**逐条执行，走到某一行才发现
 *     "文件根本不在" —— 那时人已经在改生产库了，最坏的情况是**跳过一个必跑的迁移还不自知**。
 *
 * ## 两条判据（刻意分开报，别合成一个数）
 *
 * 1. **正向**：`sql/*.sql` 的每个文件都必须在 `sql/README.md` 里出现过（正文里点一次名即可）；
 * 2. **反向**：**必跑清单那一节**里点到的 `*.sql` 必须真实存在于 `sql/`。
 *    ⚠️ 只查这一节，不查全文：README 里还有「已废弃的 Migration」与「历史迁移演进（**脚本已删除**，结论保留）」
 *    两类**有意指向已删除脚本**的内容 —— 全文查会把它们全判成死链（那是文档的**结论留档**，不是漏文件）。
 *
 * ## 环境判据
 *
 * 不启子进程、不连库、不写文件（纯读 + 正则），因此在任何环境都能跑；完成标记只用 ASCII。
 */

const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const SQL_DIR = path.join(ROOT, 'AquaFlow-backend/sql')
const README = path.join(SQL_DIR, 'README.md')
const RUNBOOK_HEADING = '## 基线之后必须补跑的迁移（已有库升级）'

const failed = []

if (!fs.existsSync(README)) {
  console.error(`[sql-catalog] ✗ 找不到 ${README}`)
  process.exit(1)
}
const readme = fs.readFileSync(README, 'utf8')
const files = fs.readdirSync(SQL_DIR).filter(f => f.endsWith('.sql'))

console.log(`[sql-catalog] sql/ 下 .sql 文件 = ${files.length} 个；正本 = sql/README.md`)
console.log('')

// ── 判据 1：正向（文件必须在 README 里被点名） ──────────────────────────────
console.log('① 正向：每个脚本都要在 README 里有出处')
const unlisted = files.filter(f => !readme.includes(f)).sort()
if (unlisted.length) {
  for (const f of unlisted) {
    console.log(`  ✗ ${f}   README 里没有任何出处`)
    failed.push(`sql/${f} 未在 sql/README.md 中被点名`)
  }
} else {
  console.log(`  ✓ ${files.length} 个脚本全部有出处`)
}

// ── 判据 2：反向（必跑清单点到的文件必须存在） ──────────────────────────────
console.log('')
console.log('② 反向：必跑清单里点到的脚本必须真实存在')
const start = readme.indexOf(RUNBOOK_HEADING)
if (start < 0) {
  console.log(`  ! 找不到小节「${RUNBOOK_HEADING}」—— 若是有意改名，请同步本脚本的 RUNBOOK_HEADING`)
  failed.push(`sql/README.md 缺少小节「${RUNBOOK_HEADING}」`)
} else {
  const nextHeading = readme.indexOf('\n## ', start + RUNBOOK_HEADING.length)
  const section = readme.slice(start, nextHeading < 0 ? readme.length : nextHeading)
  // 抓这一节里所有反引号包起来的 *.sql（清单里既有 migration_vNN_*.sql 也有少数非 migration_ 前缀的）
  // ⚠️ 首字符必须是**字母或数字**：清单里允许出现 `_backfill.sql` 这种**散文里的后缀引用**
  //    （第 94 行「`migration_aq_bucket_right_v1_ddl.sql` + `..._v1_backfill.sql`。**注意**：`_backfill.sql` 会顺手…」）
  //    —— 把它当成文件名就会报一个永远修不掉的假阳性（本门禁第一版正是这样，写完当场跑就撞上）。
  //    已确认 `sql/` 下**没有**以 `_` 开头的脚本，所以这条收紧不会漏掉真文件。
  const cited = [...new Set([...section.matchAll(/`([A-Za-z0-9][A-Za-z0-9_.-]*\.sql)`/g)].map(m => m[1]))]
  console.log(`  该节点到 ${cited.length} 个脚本`)
  const missing = cited.filter(f => !fs.existsSync(path.join(SQL_DIR, f)))
  if (missing.length) {
    for (const f of missing) {
      console.log(`  ✗ ${f}   清单里点名了，但 sql/ 下没有这个文件`)
      failed.push(`必跑清单引用了不存在的脚本：${f}`)
    }
  } else {
    console.log('  ✓ 点到的脚本全部存在')
  }
}

console.log('')
console.log('-'.repeat(78))
if (failed.length) {
  console.log(`[sql-catalog] ✗ ${failed.length} 项不一致：`)
  for (const f of failed) console.log(`    - ${f}`)
  console.log('  ⇒ 判据：迁移清单是**正本**，"文件躺在目录里没人知道它是什么"与')
  console.log('     "清单指向一个不存在的脚本"都会让运维在改生产库时做错决定。')
  process.exit(1)
}
console.log('[sql-catalog] ✓ sql/ 与 sql/README.md 双向一致')
console.log('AQUAFLOW_SQL_CATALOG_OK')
