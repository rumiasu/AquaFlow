#!/usr/bin/env node
/**
 * 「CI / 发布依赖的件，克隆下来必须真的在」——给仓库做一次"新克隆体检"。
 *
 *   node scripts/check-tracked-inputs.js
 *
 * ## 为什么要有它（2026-09-28 的真实事故）
 *
 * `AquaFlow-backend/src/main/resources/application-prod.yml` 一度**同时**被 `.gitignore` 忽略、
 * 且未被 git 跟踪。后果是**本地全绿、上线才炸**：
 *   · 新克隆的仓库根本没有这个文件 ⇒ CI 里那条 `node scripts/check-prod-config.js`
 *     **直接失败**（该脚本文件缺失时 exit 1）⇒ **CI 必红**；
 *   · 真部署时 `--spring.profiles.active=prod` 找不到它 ⇒ **静默退化成 `application.yml` 的默认值**
 *     （CORS 默认成 localhost、员工端 appid 默认成空），而 prod yml 存在的意义正是让这几项缺失即拒启。
 * 而**所有本机门禁都不会红**：文件在工作区里就是在，构建也从工作树读，不看 gitignore。
 *
 * ⇒ 判据：**"文件存在"不等于"别人 clone 得到"**。本脚本按 **git** 视角看，而不是按文件系统视角。
 *
 * ## 查什么
 *
 * ① **显式清单**（`REQUIRED_TRACKED`）：发布/CI 依赖的关键件，必须**已被跟踪**；
 * ② **CI 步骤里点名的件**（从 `.github/workflows/ci.yml` 抽出 `node/python3/bash <路径>` 与 `path:`），
 *    必须存在、且**不能被 .gitignore 忽略**。
 *
 * 判定分级（刻意分开，别合成一个数）：
 *   · **被忽略 ⇒ 失败**：克隆后必然不存在，CI/部署一定出问题（就是上面那次事故的形状）；
 *   · **不存在 ⇒ 失败**：CI 点名了一个仓库里没有的件；
 *   · **存在但未跟踪 ⇒ 警告**：本地看起来一切正常、提交时忘了 `git add` 就会变成上一条
 *     —— 不判红（正在进行的工作必然如此），但**每次都会念一遍**；
 *   · **git 不可用 ⇒ 失败**：拿不到 `git ls-files` 的输出（退出码非 0）时**必须中止**，不许继续 ——
 *     那一刻 `tracked` 是空集、`git check-ignore` 也会一律返回"未忽略"，本门禁会**静默降级成
 *     "文件存在性检查"**（正是它自己要防的那种假结论）并给出绿灯。判据与 F-02「缺依赖/工具不可用
 *     不许判绿」同源；哨兵 `AQUAFLOW_TRACKED_INPUTS_UNKNOWN`。
 *
 * ⚠️ 有意留空的例外写在 `ALLOWED_IGNORED` 里（每条带理由）：本机密钥、备份、生成物。
 *
 * ## 环境判据
 *
 * 沙箱下拿不到子进程管道输出（见 skill §8.31）⇒ 一律 `stdio:'ignore'` + 退出码/文件重定向，
 * 不用管道；完成标记只用 ASCII。
 */

const fs = require('fs')
const os = require('os')
const path = require('path')
const { spawnSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..')
const CI = path.join(ROOT, '.github/workflows/ci.yml')

/** 发布 / CI 依赖的关键件（相对仓库根）。加新门禁脚本时**同时加到这里**。 */
const REQUIRED_TRACKED = [
  '.github/workflows/ci.yml',
  'AGENTS.md',
  'AquaFlow-backend/.env.example',
  'AquaFlow-backend/src/main/resources/application.yml',
  'AquaFlow-backend/src/main/resources/application-prod.yml',
  'AquaFlow-backend/sql/schema.sql',
  'AquaFlow-backend/sql/README.md',
  'tests/js/run-all.js',
  'tests/js/harness.js',
  'scripts/verify.sh',
  'scripts/verify-local.js',
  'scripts/check-prod-config.js',
  'scripts/check-jar-no-local-config.js',
  'scripts/check-sql-catalog.js',
  'scripts/check-gate-parity.js',
  // 2026-09-30 新增（第六批 F-49）：台账 §2 判据可执行化 —— 三处入口都调它，
  // 不入库则新克隆的 CI 在该步找不到文件（同 check-miniapp-text 的事故形状）。
  'scripts/check-ledger-claims.js',
  // 2026-09-30 新增（WP3/F-33+F-13）：小程序文本体检 —— 三处入口都调它，
  // 不入库则新克隆的 CI 在「小程序文本体检」那一步直接找不到文件（本仓 2026-09-28 那次事故同形）。
  'scripts/check-miniapp-text.js',
  'scripts/prod-startup-check.js',
  'scripts/smoke-check.js',
  'scripts/backup-restore-drill.js',
  'scripts/scan-secrets.sh',
  // 7 个静态门禁（CI 与 verify.sh 都直接调它们；不入库则新克隆的 CI 必然失败）
  'audit_wxml_handlers.py',
  'page_reach_audit.py',
  'static_audit_user.py',
  'audit_comments.py',
  'audit_js_syntax.py',
  'audit_scenario_matrix.py',
  'audit_wxss_selectors.py'
]

/** 允许被忽略的（每条都要有理由，否则下一个人会以为漏了）。 */
const ALLOWED_IGNORED = [
  ['AquaFlow-backend/src/main/resources/application-local.yml', '本机真实密钥'],
  ['backup/', '本机备份产物'],
  ['.env', '本机环境变量文件']
]

function gitOutput(cmdline) {
  // ⚠️ 沙箱下要不了管道：把输出重定向到文件再读（同 prod-startup-check.js 的判据）
  const tmp = path.join(os.tmpdir(), 'aquaflow-git-out.txt')
  fs.writeFileSync(tmp, '')
  const r = spawnSync(`${cmdline} > "${tmp}"`, { shell: true, cwd: ROOT, stdio: 'ignore' })
  let out = ''
  try { out = fs.readFileSync(tmp, 'utf8') } catch (e) { /* ignore */ }
  return { status: r.status, out }
}

/** 是否被 .gitignore / .git/info/exclude 忽略（只看退出码，不要输出）。 */
function isIgnored(rel) {
  const r = spawnSync(`git check-ignore -q "${rel}"`, { shell: true, cwd: ROOT, stdio: 'ignore' })
  return r.status === 0
}

function allowedIgnored(rel) {
  return ALLOWED_IGNORED.some(([p]) => rel === p || rel.startsWith(p))
}

/*
 * ⚠️ 判据：**git 不可用 = 什么也不知道**，绝不许降级成绿灯。
 * 原实现只看 `out` 不看退出码 —— git 缺失/不可执行时 `out` 为空、`tracked` 变空集，
 * 每个件都会被印成"存在但未跟踪"（只 warn 不 fail），结尾照样 `AQUAFLOW_TRACKED_INPUTS_OK`
 * 且 exit 0。本门禁存在的全部意义（"文件在工作区里在" ≠ "别人 clone 得到"）在那条路径上归零，
 * 而它看起来像"检查过了、只有几处提醒"。
 * 判据同 F-02（`scripts/verify.sh` 缺 python/node 时打 `AQUAFLOW_VERIFY_INCOMPLETE` 并 exit 1）：
 * **没跑 ≠ 通过**。这里用 `AQUAFLOW_TRACKED_INPUTS_UNKNOWN` 明确区分"没跑成"与"跑出来有问题"。
 */
const lsFiles = gitOutput('git ls-files')
if (lsFiles.status !== 0) {
  console.error(`[tracked-inputs] ✗ 拿不到 git 文件清单（\`git ls-files\` 退出码 ${lsFiles.status}）——`)
  console.error('  本门禁的判据是「按 **git** 视角看这个件克隆得到吗」，git 不可用时它答不了任何一项：')
  console.error('  `tracked` 会是空集、`git check-ignore` 也一律返回"未忽略"，继续跑只会把每个件')
  console.error('  印成"存在但未跟踪"并给出绿灯（假绿）。故中止，而不是降级成"文件存在性检查"。')
  console.error('  修法：确认 git 可用（`git --version` 有输出）后重跑；CI 上这一步必然可用。')
  console.log('AQUAFLOW_TRACKED_INPUTS_UNKNOWN')
  process.exit(1)
}
const tracked = new Set(lsFiles.out.split('\n').map(s => s.trim()).filter(Boolean))

/** 从 ci.yml 的 run 块与 path: 里抽出被点名的仓库内件。 */
function ciReferenced() {
  if (!fs.existsSync(CI)) return []
  const text = fs.readFileSync(CI, 'utf8')
  const found = new Set()
  const patterns = [
    /(?:^|\s)(?:node|python3|bash)\s+([\w./-]+\.(?:js|py|sh|mjs))/g,   // node scripts/x.js / python3 audit.py
    /^\s*path:\s*([\w./*-]+)\s*$/gm                                     // upload-artifact 的 path
  ]
  for (const re of patterns) {
    let m
    while ((m = re.exec(text)) !== null) {
      let p = m[1]
      if (p.includes('*')) continue           // 通配（测试报告目录）交给别的检查
      if (/^(node|python3|bash|chmod)$/.test(p)) continue
      found.add(p)
    }
  }
  return [...found]
}

const failed = []
const warned = []

console.log('[tracked-inputs] 按 **git** 视角检查「CI / 发布依赖的件，克隆下来是否真的在」')
console.log('')

console.log('① 显式清单（发布/CI 关键件）')
for (const rel of REQUIRED_TRACKED) {
  const exists = fs.existsSync(path.join(ROOT, rel))
  const isTrk = tracked.has(rel)
  const ign = isIgnored(rel)
  let mark = '✓'
  let note = '已跟踪'
  if (!exists) { mark = '✗'; note = '文件不存在'; failed.push(`${rel}：不存在`) }
  else if (ign && !allowedIgnored(rel)) { mark = '✗'; note = '**被 .gitignore 忽略**（克隆后必然没有）'; failed.push(`${rel}：被忽略`) }
  else if (!isTrk) { mark = '!'; note = '存在但**未跟踪** —— 提交时别忘 git add'; warned.push(rel) }
  console.log(`  ${mark} ${rel.padEnd(58)} ${note}`)
}

console.log('')
console.log('② CI 步骤里点名的件（从 ci.yml 抽出）')
const refs = ciReferenced()
if (!refs.length) {
  console.log('  ! 没能从 ci.yml 抽出任何路径 —— 检查正则是否与 ci.yml 的写法脱节')
  warned.push('ci.yml 路径抽取为空')
}
for (const rel of refs) {
  const exists = fs.existsSync(path.join(ROOT, rel))
  const ign = isIgnored(rel)
  const isTrk = tracked.has(rel)
  let mark = '✓'
  let note = '已跟踪'
  if (!exists) { mark = '✗'; note = 'CI 点名了它，但仓库里没有'; failed.push(`CI 引用 ${rel}：不存在`) }
  else if (ign && !allowedIgnored(rel)) { mark = '✗'; note = '**被忽略** —— CI 里会找不到'; failed.push(`CI 引用 ${rel}：被忽略`) }
  else if (!isTrk) { mark = '!'; note = '未跟踪（提交时别忘 git add）'; warned.push(rel) }
  console.log(`  ${mark} ${rel.padEnd(58)} ${note}`)
}

console.log('')
console.log('③ 有意留空的（不判红，逐条给出理由）')
for (const [p, why] of ALLOWED_IGNORED) {
  console.log(`  · ${p.padEnd(56)} ${why}`)
}

console.log('')
console.log('-'.repeat(80))
if (failed.length) {
  console.log(`[tracked-inputs] ✗ ${failed.length} 项克隆后会缺失：`)
  for (const f of failed) console.log(`    - ${f}`)
  console.log('  ⇒ 判据：**"文件存在"不等于"别人 clone 得到"**（2026-09-28 application-prod.yml 事故）')
  process.exit(1)
}
if (warned.length) {
  const uniq = [...new Set(warned)]
  console.log(`[tracked-inputs] ! ${uniq.length} 项未跟踪（不判红，但请确认它们会随下次提交入库）：`)
  for (const w of uniq) console.log(`    - ${w}`)
}
console.log('[tracked-inputs] ✓ 没有"克隆后必然缺失"的件')
console.log('AQUAFLOW_TRACKED_INPUTS_OK')
