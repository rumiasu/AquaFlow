#!/usr/bin/env node
/**
 * 备份 / 恢复演练（本机可重复执行）。
 *
 * 跑法：
 *   node scripts/backup-restore-drill.js backup            # 只备份 + 出清单
 *   node scripts/backup-restore-drill.js backup --label before-x
 *   node scripts/backup-restore-drill.js verify <备份文件> <库名>   # 校验某库是否与备份一致
 *   node scripts/backup-restore-drill.js drill             # 全流程：备份 → 灌进临时库 → 逐项校验
 *   node scripts/backup-restore-drill.js cleanup           # 删掉演练用临时库
 *   drill/cleanup 必须先确认目标可清空，再将 AQUAFLOW_ALLOW_DB_RESET 设为目标库名。
 *
 * ---------------------------------------------------------------------------
 * 为什么必须有"恢复成功的判据"，而不能只看退出码
 * ---------------------------------------------------------------------------
 * 本仓的既定判据是「只判退出码不算判过」（skill §8.31）：
 *   · `mysql < file` 退出码 0 只说明**客户端没报错**，不说明数据真进去了
 *     （脚本里若有 `--force`、或语句被静默跳过，照样 0）；
 *   · 两张表互换了内容、行数一样，退出码也是 0。
 * 所以 drill 会对**恢复后的库**逐项核对下面 6 条（R1–R6）：
 *   R1 表数 = 源库表数（不是硬编码 51，避免库漂移后判据失效）
 *   R2 每张表行数与源库逐表相同
 *   R3 **中文注释按字节相同**（HEX(TABLE_COMMENT)）—— 恢复链路上最容易悄悄坏的就是编码
 *   R4 水票批次账 E8 两条等式在恢复库上仍平（有账户有批次才有意义）
 *   R5 桶权益账在恢复库上仍平（权益 == Σ批次余额）
 *   R6 关键外键关系没有孤儿（订单→客户/水站）
 *
 * ⚠️ 本脚本**只写**演练用的临时库（默认 `aquaflow_restoredrill`），
 *    绝不写 `aquaflow` 与 `aquaflow_test`。删除也只删演练库（名字必须含 restoredrill）。
 */

const fs = require('fs')
const os = require('os')
const path = require('path')
const { spawnSync } = require('child_process')
const { assertIdentifier, assertScratchDatabase } = require('./lib/scratch-database')

const repoRoot = path.resolve(__dirname, '..')
const MYSQL_BIN = process.env.AQUAFLOW_MYSQL_BIN || 'D:\\backend\\MySQL\\bin'
const MYSQL = path.join(MYSQL_BIN, 'mysql.exe')
const MYSQLDUMP = path.join(MYSQL_BIN, 'mysqldump.exe')

const SRC_DB = process.env.AQUAFLOW_DB || 'aquaflow'
const DRILL_DB = process.env.AQUAFLOW_DRILL_DB || 'aquaflow_restoredrill'
const BACKUP_DIR = path.join(repoRoot, 'backup', 'dryrun')

/** F-68：恢复不能覆盖源库；先拒绝不合法目标，再读口令/导出备份。 */
function guardDrillDb() {
  return assertScratchDatabase({ name: DRILL_DB, kind: 'restoredrill', source: SRC_DB,
    confirmation: process.env.AQUAFLOW_ALLOW_DB_RESET })
}
assertIdentifier(SRC_DB)
if (['drill', 'cleanup'].includes(process.argv[2])) guardDrillDb()
if (process.argv[2] === 'verify') assertIdentifier(process.argv[4])

/** 从 gitignore 的本地配置取口令。**绝不打印它。** */
function localDbPassword() {
  const p = path.join(repoRoot, 'AquaFlow-backend', 'src', 'main', 'resources', 'application-local.yml')
  const m = /datasource:[\s\S]*?password:\s*["']?([^"'\s]+)/.exec(fs.readFileSync(p, 'utf8'))
  if (!m) throw new Error('读不到本地库口令（application-local.yml 的 datasource.password）')
  return m[1]
}
const PW = localDbPassword()

/**
 * 跑一条命令并把 **stdout / stderr 分开**落文件。
 *
 * ⚠️ 三个都试过、只有这一种可靠（2026-09-27 实测，每一步都留下过错误结论）：
 *   ✗ `spawnSync('cmd', ['/c', '<整条含引号的命令>'])` —— Node 把内层引号转义成 `\"`，
 *     cmd 报 "The filename, directory name, or volume label syntax is incorrect."
 *     （连 `echo hello > "D:\...\x.sql"` 都失败 ⇒ 与 mysqldump、口令、路径空格都无关）。
 *   ✗ 把 stdout 与 stderr **指到同一个 fd**：两者交错插进数据里，
 *     实测 `mysql -e "<51 张表的 UNION ALL>"` 报
 *     **`Identifier name 'mysql: [Warning] Using a password...' is too long`** ——
 *     那条口令警告被当成 SQL 解析了。行数统计因此恒为 0 行。
 *   ✓ 命令走 `shell: true`（命令字符串，让 Node 自己转义）+ stdout/stderr **分开**文件。
 * 另外用 `MYSQL_PWD` 环境变量传口令：命令行 `-p<口令>` 会触发上面那条警告（且口令会出现在进程列表里）。
 */
function runSplit(cmd, args, opts) {
  const o = opts || {}
  const stamp = `${process.pid}-${Math.random().toString(36).slice(2)}`
  const outFile = path.join(os.tmpdir(), `aquaflow-out-${stamp}.txt`)
  const errFile = path.join(os.tmpdir(), `aquaflow-err-${stamp}.txt`)
  const fo = fs.openSync(outFile, 'w')
  const fe = fs.openSync(errFile, 'w')
  let r
  try {
    r = spawnSync(cmd, args, {
      cwd: o.cwd || repoRoot,
      shell: !!o.shell,
      stdio: ['ignore', fo, fe],
      env: Object.assign({}, process.env, { MYSQL_PWD: PW }, o.env || {})
    })
  } finally {
    fs.closeSync(fo)
    fs.closeSync(fe)
  }
  const stdout = fs.existsSync(outFile) ? fs.readFileSync(outFile, 'utf8') : ''
  const stderr = fs.existsSync(errFile) ? fs.readFileSync(errFile, 'utf8') : ''
  try { fs.unlinkSync(outFile); fs.unlinkSync(errFile) } catch (e) { /* 忽略 */ }
  return { status: r.status, stdout, stderr, eperm: !!(r.error && r.error.code === 'EPERM') }
}

/** 跑一条「带 shell 重定向」的命令（`mysqldump ... > file` / `mysql ... < file`）。 */
function runShell(cmdline) {
  const r = runSplit(cmdline, [], { shell: true })
  const clean = r.stderr.split(/\r?\n/).filter(l => l.trim() && !/Using a password/.test(l))
  return { status: r.status, out: r.stdout + r.stderr, lines: clean, eperm: r.eperm }
}

/** 执行 SQL 并返回行（`-N -B` = 无表头、Tab 分隔）。 */
function sql(db, statement) {
  const args = ['-u', 'root', '--default-character-set=utf8mb4', '-N', '-B', '-e', statement]
  if (db) args.push(db)
  const r = runSplit(MYSQL, args)
  if (r.eperm) throw new Error('EPERM：本沙箱不允许该子进程（不是 SQL 的问题）')
  if (r.status !== 0) {
    const line = r.stderr.split(/\r?\n/).filter(l => l.trim() && !/Using a password/.test(l)).pop()
    throw new Error(`SQL 失败(${db || '-'}): ${line || '(无输出)'}`)
  }
  return r.stdout.split(/\r?\n/).map(l => l.split('\t')).filter(a => a.length && a[0] !== '')
}

function scalar(db, statement) {
  const rows = sql(db, statement)
  return rows.length ? rows[0][0] : null
}

function dbExists(db) {
  const rows = sql('information_schema',
    `SELECT COUNT(*) FROM SCHEMATA WHERE SCHEMA_NAME='${db}'`)
  return rows[0][0] !== '0'
}

function tables(db) {
  return sql('information_schema',
    `SELECT TABLE_NAME FROM TABLES WHERE TABLE_SCHEMA='${db}' ORDER BY TABLE_NAME`).map(r => r[0])
}

/** 逐表行数（一次查询拿全，避免 N 次往返）。 */
function rowCounts(db) {
  const names = tables(db)
  if (names.length === 0) return {}
  const q = names.map(n => `SELECT '${n}' t, COUNT(*) c FROM \`${db}\`.\`${n}\``).join(' UNION ALL ')
  const counts = {}
  sql('information_schema', q).forEach(r => { counts[r[0]] = Number(r[1]) })
  return counts
}

/** 中文注释的字节指纹：编码在恢复链路上坏了，这一项会立刻不同。 */
function commentHex(db) {
  return sql('information_schema',
    `SELECT TABLE_NAME, HEX(TABLE_COMMENT) FROM TABLES WHERE TABLE_SCHEMA='${db}' ORDER BY TABLE_NAME`)
    .map(r => `${r[0]}=${r[1]}`)
}

function nowStamp() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

/**
 * 保留策略：每次备份成功后只保留最近 N 份（含清单），超出的按 mtime 从旧到新删。
 *
 * ⚠️ 只在**备份成功之后**调用 —— 备份失败时删旧的，等于把唯一的退路也删了。
 * ⚠️ 只删本目录里 `.sql`/`.manifest.json` 这一对，且**只认本脚本自己的命名**
 *    （`<库名>_<label>_<时间戳>.*`），避免误删同目录里手工放的 dump（例如迁移前的备份）。
 */
function applyRetention(keep) {
  if (!keep || keep <= 0) return
  const files = fs.readdirSync(BACKUP_DIR)
  // 按"配对"处理：以 .sql 为锚，配对同名 .manifest.json
  const dumps = files
    .filter(f => /^[A-Za-z0-9_]+_\d{8}-\d{6}\.sql$/.test(f))
    .map(f => ({ file: f, mtime: fs.statSync(path.join(BACKUP_DIR, f)).mtimeMs }))
    .sort((a, b) => b.mtime - a.mtime)
  if (dumps.length <= keep) {
    console.log(`[backup] 保留策略：现有 ${dumps.length} 份 ≤ 上限 ${keep}，无需清理`)
    return
  }
  const doomed = dumps.slice(keep)
  doomed.forEach(d => {
    const sql = path.join(BACKUP_DIR, d.file)
    const mf = sql.replace(/\.sql$/, '.manifest.json')
    try { fs.unlinkSync(sql) } catch (e) { /* 忽略 */ }
    try { if (fs.existsSync(mf)) fs.unlinkSync(mf) } catch (e) { /* 忽略 */ }
    console.log(`[backup] 保留策略：删除过期备份 ${d.file}`)
  })
  console.log(`[backup] 保留策略：保留最近 ${keep} 份，删除 ${doomed.length} 份`)
}

// ---------------------------------------------------------------------------
// backup
// ---------------------------------------------------------------------------
function doBackup(label, keep) {
  if (label && !/^[a-zA-Z0-9_-]+$/.test(label)) throw new Error('备份标签只允许字母、数字、下划线或连字符')
  fs.mkdirSync(BACKUP_DIR, { recursive: true })
  const tag = label || 'manual'
  const file = path.join(BACKUP_DIR, `${SRC_DB}_${tag}_${nowStamp()}.sql`)

  console.log(`[backup] 源库 ${SRC_DB} → ${path.relative(repoRoot, file)}`)
  // --single-transaction：InnoDB 一致性快照，不锁表（业务在跑也能备）
  // --routines --triggers --events：漏了它们，"能恢复"是假的
  // --default-character-set=utf8mb4：与 schema.sql 导入同口径
  // 用 shell 的 `>` 重定向落文件：不要用管道，PowerShell 管道会按控制台代码页重编码。
  // 走 runShell（shell:true），不要写成 spawnSync('cmd', ['/c', ...]) —— 后者引号会被转义坏。
  const cmdline = `"${MYSQLDUMP}" -u root --single-transaction --routines --triggers --events ` +
    `--default-character-set=utf8mb4 --hex-blob ${SRC_DB} > "${file}"`
  const r = runShell(cmdline)
  if (r.eperm) { console.error('[backup] EPERM：沙箱限制，请在普通终端执行'); process.exit(3) }
  if (r.status !== 0) {
    console.error('[backup] ✗ mysqldump 失败：')
    console.error(r.lines.slice(-6).join('\n'))
    process.exit(1)
  }
  if (!fs.existsSync(file) || fs.statSync(file).size === 0) {
    console.error('[backup] ✗ 备份文件为空 —— 不能当成备份成功')
    process.exit(1)
  }

  const manifest = {
    sourceDatabase: SRC_DB,
    backupFile: path.basename(file),
    bytes: fs.statSync(file).size,
    sha256: require('crypto').createHash('sha256').update(fs.readFileSync(file)).digest('hex'),
    takenAt: new Date().toISOString(),
    mysqlVersion: (runSplit(MYSQL, ['--version']).stdout || '').trim(),
    tableCount: tables(SRC_DB).length,
    rowCounts: rowCounts(SRC_DB),
    commentHex: commentHex(SRC_DB)
  }
  const mf = file.replace(/\.sql$/, '.manifest.json')
  fs.writeFileSync(mf, JSON.stringify(manifest, null, 2), 'utf8')

  console.log(`[backup] ✓ ${(manifest.bytes / 1024).toFixed(0)} KB，表 ${manifest.tableCount} 张，` +
    `行数合计 ${Object.values(manifest.rowCounts).reduce((a, b) => a + b, 0)}`)
  console.log(`[backup] 清单：${path.relative(repoRoot, mf)}`)
  console.log(`[backup] sha256：${manifest.sha256.slice(0, 16)}…`)
  // 保留策略放在**成功之后**：备份失败时删旧的，等于把唯一的退路也删了
  applyRetention(keep)
  return file
}

// ---------------------------------------------------------------------------
// verify：逐项核对某库与某备份是否一致
// ---------------------------------------------------------------------------
function doVerify(backupSql, db) {
  assertIdentifier(db)
  const mf = backupSql.replace(/\.sql$/, '.manifest.json')
  if (!fs.existsSync(mf)) {
    console.error(`[verify] 找不到清单 ${mf} —— 清单是"恢复成功"的判据来源，不能省`)
    process.exit(1)
  }
  const m = JSON.parse(fs.readFileSync(mf, 'utf8'))

  // R0：备份文件本身没被改过（字节级）
  const sha = require('crypto').createHash('sha256').update(fs.readFileSync(backupSql)).digest('hex')
  report('R0 备份文件完整性（sha256 与清单一致）', sha === m.sha256, `${sha.slice(0, 16)}…`)

  // R1 表数
  const dbTables = tables(db)
  report('R1 表数与源库相同', dbTables.length === m.tableCount, `${dbTables.length} vs ${m.tableCount}`)

  // R2 逐表行数
  const rc = rowCounts(db)
  const diffs = Object.keys(m.rowCounts).filter(t => Number(rc[t]) !== Number(m.rowCounts[t]))
  report('R2 逐表行数与源库相同', diffs.length === 0,
    diffs.length ? `不一致 ${diffs.length} 张：${diffs.slice(0, 5).map(t => `${t}(${rc[t]}≠${m.rowCounts[t]})`).join(', ')}`
      : `${Object.keys(rc).length} 张表逐表可比`)

  // R3 中文注释按字节相同
  const ch = commentHex(db)
  const chDiff = ch.filter((v, i) => v !== m.commentHex[i]).length
  report('R3 中文表注释按字节相同（编码没坏）', chDiff === 0, chDiff ? `${chDiff} 张不同` : `${ch.length} 张逐一比过`)

  // R4 水票批次账 E8 两条等式
  // ⚠️ 用 scalar() 取值，不要写 `sql(...) === '0'` —— sql() 返回的是**行数组**（[['0']]），
  //    那样比恒为 false；而模板串拼出来又恰好显示成 "0"，看着像"通过但被误判成失败"（踩过）。
  try {
    const bad = scalar(db, `
      SELECT COUNT(*) FROM (
        SELECT COALESCE(a.customer_id,l.customer_id) c, COALESCE(a.station_id,l.station_id) s,
               COALESCE(a.product_id,l.product_id) p,
               IFNULL(a.remain_quantity,0) aq, IFNULL(l.lot_qty,0) lq,
               IFNULL(a.right_amount,0) aa, IFNULL(l.lot_amt,0) la
        FROM (SELECT customer_id,station_id,product_id,SUM(remain_quantity) remain_quantity,
                     SUM(right_amount) right_amount FROM ticket_account GROUP BY 1,2,3) a
        LEFT JOIN (SELECT customer_id,station_id,product_id,SUM(remain_qty) lot_qty,
                          SUM(remain_qty*unit_price) lot_amt FROM ticket_lot GROUP BY 1,2,3) l
          ON l.customer_id=a.customer_id AND l.station_id=a.station_id AND l.product_id=a.product_id
      ) x WHERE aq <> lq OR ABS(aa - la) > 0.005`)
    report('R4 水票批次账 E8 两条等式在恢复库上仍平', bad === '0', `不平 ${bad} 组`)
  } catch (e) {
    report('R4 水票批次账 E8', false, '查询失败（表结构可能漂移）：' + e.message)
  }

  // R5 桶权益账：权益 == Σ批次余额
  try {
    const bad = scalar(db, `
      SELECT COUNT(*) FROM (
        SELECT COALESCE(a.customer_id,l.customer_id) c, COALESCE(a.station_id,l.station_id) s,
               COALESCE(a.product_id,l.product_id) p,
               IFNULL(a.qty,0) aq, IFNULL(l.rq,0) lq
        FROM (SELECT customer_id,station_id,product_id,SUM(quantity) qty
              FROM customer_barrel_asset GROUP BY 1,2,3) a
        LEFT JOIN (SELECT customer_id,station_id,product_id,SUM(remain_qty) rq
                   FROM customer_barrel_lot GROUP BY 1,2,3) l
          ON l.customer_id=a.customer_id AND l.station_id=a.station_id AND l.product_id=a.product_id
      ) x WHERE aq <> lq`)
    report('R5 桶权益账在恢复库上仍平（权益 == Σ批次余额）', bad === '0', `不平 ${bad} 组`)
  } catch (e) {
    report('R5 桶权益账', false, '查询失败：' + e.message)
  }

  // R6 关键外键没有孤儿
  try {
    const orphans = scalar(db,
      `SELECT COUNT(*) FROM orders o LEFT JOIN customer c ON c.id=o.customer_id WHERE c.id IS NULL`)
    report('R6 订单的客户没有孤儿', orphans === '0', `孤儿=${orphans}`)
  } catch (e) {
    report('R6 孤儿检查', false, '查询失败：' + e.message)
  }
}

let failures = 0
function report(name, ok, detail) {
  if (!ok) failures++
  console.log(`  ${ok ? '✓' : '✗'} ${name}${detail ? '   [' + detail + ']' : ''}`)
}

// ---------------------------------------------------------------------------
// drill：备份 → 灌进临时库 → 逐项校验
// ---------------------------------------------------------------------------
function doDrill() {
  guardDrillDb()
  console.log('='.repeat(70))
  console.log(`恢复演练：${SRC_DB} → ${DRILL_DB}（只写演练库，不碰 aquaflow / aquaflow_test）`)
  console.log('='.repeat(70))

  const backupFile = doBackup('drill')
  console.log('')
  console.log(`[drill] 重建演练库 ${DRILL_DB}…`)
  guardDrillDb()
  sql('information_schema', `DROP DATABASE IF EXISTS \`${DRILL_DB}\``)
  sql('information_schema',
    `CREATE DATABASE \`${DRILL_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`)

  console.log(`[drill] 灌入备份（字节级重定向：mysql ... < 文件）…`)
  // ⚠️ 必须走 `<` 重定向（不是 PowerShell 管道 —— 管道会按控制台代码页重编码、把中文注释弄坏；
  //    核对一律比 HEX，不要看控制台）。同样走 runShell，理由见它上面的注释。
  const cmdline = `"${MYSQL}" -u root --default-character-set=utf8mb4 ${DRILL_DB} < "${backupFile}"`
  const r = runShell(cmdline)
  if (r.eperm) { console.error('[drill] EPERM：沙箱限制，请在普通终端执行'); process.exit(3) }
  const errLines = r.lines
  if (r.status !== 0) {
    console.error('[drill] ✗ 灌入失败：'); console.error(errLines.slice(-8).join('\n')); process.exit(1)
  }
  if (errLines.length) {
    console.log('[drill] 灌入期间有输出（下面逐条列出，不吞掉）：')
    errLines.slice(0, 10).forEach(l => console.log('    | ' + l))
  }

  console.log('')
  console.log('[drill] 校验恢复结果（R0–R6）：')
  doVerify(backupFile, DRILL_DB)

  console.log('')
  if (failures) {
    console.error(`[drill] ✗ ${failures} 项未通过 —— 这次恢复**不能**算成功`)
    console.error(`        演练库 ${DRILL_DB} **保留着**，便于排查（清理：node scripts/backup-restore-drill.js cleanup）`)
    process.exit(1)
  }
  console.log('[drill] ✓ 全部通过：这份备份**可以**恢复出与源库一致的数据')
  console.log(`        备份：${path.relative(repoRoot, backupFile)}`)
  // 成功就自动收掉演练库：不留垃圾（失败的路径上面已经 return，会留着给人排查）
  guardDrillDb()
  sql('information_schema', `DROP DATABASE \`${DRILL_DB}\``)
  console.log(`        演练库 ${DRILL_DB} 已清理`)
}

function doCleanup() {
  guardDrillDb()
  if (!DRILL_DB.includes('restoredrill')) {
    console.error(`[cleanup] 拒绝执行：演练库名 "${DRILL_DB}" 不含 restoredrill —— 防误删真实库`)
    process.exit(1)
  }
  if (!dbExists(DRILL_DB)) { console.log(`[cleanup] ${DRILL_DB} 不存在，无需清理`); return }
  sql('information_schema', `DROP DATABASE \`${DRILL_DB}\``)
  console.log(`[cleanup] ✓ 已删除演练库 ${DRILL_DB}`)
}

// ---------------------------------------------------------------------------
const argv = process.argv.slice(2)
/** 从 argv 里取 `--名字 值`，缺省返回 fallback。 */
function argValue(name, fallback) {
  const i = argv.indexOf(name)
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback
}

const cmd = argv[0] || 'help'
if (cmd === 'backup') {
  const keepRaw = argValue('--keep', null)
  const keep = keepRaw === null ? null : Number(keepRaw)
  if (keepRaw !== null && (!Number.isFinite(keep) || keep <= 0)) {
    console.error('--keep 必须是正整数（要保留几份备份）'); process.exit(2)
  }
  doBackup(argValue('--label', null), keep)
} else if (cmd === 'verify') {
  if (!argv[1] || !argv[2]) { console.error('用法：verify <备份文件> <库名>'); process.exit(2) }
  doVerify(argv[1], argv[2])
  if (failures) { console.error(`\n[verify] ✗ ${failures} 项未通过`); process.exit(1) }
  console.log('\n[verify] ✓ 全部通过')
} else if (cmd === 'drill') {
  doDrill()
} else if (cmd === 'cleanup') {
  doCleanup()
} else {
  console.log(`用法：
  node scripts/backup-restore-drill.js backup [--label 名字] [--keep N]
        备份 + 出清单（sha256 / 逐表行数 / 注释字节）；--keep 保留最近 N 份（默认不清理）
  node scripts/backup-restore-drill.js verify <备份> <库名>      校验某库与某备份是否一致（R0–R6）
  node scripts/backup-restore-drill.js drill                     全流程：备份 → 灌演练库 → 逐项校验
  node scripts/backup-restore-drill.js cleanup                   删除演练库

源库默认 ${SRC_DB}（只读），演练库默认 ${DRILL_DB}（唯一被写的库）。
drill/cleanup 要求 AQUAFLOW_ALLOW_DB_RESET 精确等于演练库名，且目标与源库不同。
备份落在 ${path.relative(repoRoot, BACKUP_DIR)}。`)
}
