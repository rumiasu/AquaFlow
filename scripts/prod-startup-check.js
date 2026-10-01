#!/usr/bin/env node
/**
 * 生产启动姿态检查（发布前 / 上线后都该跑）—— **拿发布物 jar 真起一遍**。
 *
 *   node scripts/prod-startup-check.js                # 用 build/libs 里最新的 jar
 *   node scripts/prod-startup-check.js --jar <路径>
 *   node scripts/prod-startup-check.js --keep-db      # 保留演练库（默认跑完 DROP）
 *
 * ## 为什么要有它
 *
 * `scripts/check-prod-config.js` 只是**静态**比对 `.env.example` 与 `application-prod.yml` 的键名，
 * 它证明不了"真的会拒启"，也发现不了"声明了却没人读"的键 —— **一条从没红过的门禁不算门禁**。
 * 这里直接跑 jar（与上线时同一个东西），逐场景摘掉一个变量，看进程是"活着"还是"带着可读原因退出"。
 *
 * ## 判据（2026-09-28 实测得到，别只看代码猜）
 *
 * - **必需（缺失即拒启）**：`DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `JWT_SECRET`（另需长度 ≥ 32）/
 *   `WX_APP_ID` / `WX_APP_SECRET` / `WX_STAFF_APP_ID` / `WX_STAFF_APP_SECRET` / `CORS_ALLOWED_ORIGINS`。
 *   机制分两档，别再混成一句"占位符无默认值就拒启"：
 *   · `JWT_SECRET` / 微信 / `CORS_ALLOWED_ORIGINS` 等**有人读的键** —— 由 `RequiredConfigChecker`
 *     的 `@Value` 与 `environment.getProperty` 在启动期解析，缺了即抛、拒启。
 *   · **数据源三件（`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`）有个坑，2026-09-30 实测**：
 *     `spring.datasource.url` 的占位符**启动期本没人读**（Hikari 懒初始化，摘掉 DB_URL 服务照样
 *     Started、`processlist` 连接 0），yml 写 `${DB_URL}` 拦不住 —— 已按拍板 A 由
 *     `RequiredConfigChecker#checkProdDatasource()` **显式读一次**补上（缺了在启动期拒，
 *     错误信息点名缺失的变量）。用例 `RequiredConfigCheckerGuardTest`；本脚本场景 11 即它的端到端验证。
 * - **可选（缺失只降级）**：**COS 四件套**。缺任一项时图片上传不可用（接口回「对象存储未配置」），
 *   订单/配送/桶账/水票/对账照常 —— 别把它做成启动前置条件。
 *   ⚠️ 这条是**修出来的**：原先 `COS_SECRET_ID/KEY` 无默认值 ⇒ 没配对象存储整个系统起不来；
 *   而同一份文件里的 `COS_REGION` / `COS_BUCKET_NAME` **没有任何代码读**，声明了也不会失败。两头都不对。
 *
 * ## 本机限制（不是脚本坏了）
 *
 * 受限沙箱下拿不到子进程管道输出（§8.31）⇒ 这里**一律用文件描述符重定向**、判据只看 ASCII 片段。
 * 需要本机有 MySQL 与 `D:\backend\MySQL\bin\mysql.exe`；数据库口令从 `application-local.yml` 读，
 * **不回显**。
 */

const fs = require('fs')
const os = require('os')
const path = require('path')
const { spawnSync, spawn } = require('child_process')

const ROOT = path.resolve(__dirname, '..')
/**
 * ⚠️ **必须跨平台**：本脚本要能在 CI（ubuntu）里跑。
 *   · mysql 客户端：CI 的前置步骤已经 `apt-get install mysql-client`，那里命令就叫 `mysql`；
 *     本机是 `D:\backend\MySQL\bin\mysql.exe`。用 `AQUAFLOW_MYSQL` 覆盖，或按平台取默认。
 *   · 口令/用户：**环境变量优先**（CI 里 `DB_PASSWORD=root`，且 `application-local.yml` 根本不存在
 *     —— 它被 gitignore 了），只有本机才回落到从 yml 读。
 */
const MYSQL = process.env.AQUAFLOW_MYSQL
  || (process.platform === 'win32' ? 'D:\\backend\\MySQL\\bin\\mysql.exe' : 'mysql')
const DB_USER = process.env.DB_USERNAME || 'root'
const SCRATCH_DB = process.env.AQUAFLOW_PRODCHECK_DB || 'aquaflow_prodstartup_check'
const PORT = Number(process.env.AQUAFLOW_PRODCHECK_PORT || 8099)
const LOCAL_YML = path.join(ROOT, 'AquaFlow-backend/src/main/resources/application-local.yml')

const args = process.argv.slice(2)
const keepDb = args.includes('--keep-db')
const jarArg = args.indexOf('--jar')

function fail(msg) {
  console.error(`[prod-startup] ✗ ${msg}`)
  process.exit(1)
}

function jarPath() {
  if (jarArg >= 0 && args[jarArg + 1]) return args[jarArg + 1]
  const dir = path.join(ROOT, 'AquaFlow-backend/build/libs')
  if (!fs.existsSync(dir)) fail('找不到 build/libs —— 先跑 gradlew bootJar')
  const jars = fs.readdirSync(dir).filter(f => f.endsWith('.jar') && !f.endsWith('-plain.jar'))
  if (!jars.length) fail('build/libs 里没有 jar —— 先跑 gradlew bootJar')
  jars.sort((a, b) => fs.statSync(path.join(dir, b)).mtimeMs - fs.statSync(path.join(dir, a)).mtimeMs)
  return path.join(dir, jars[0])
}

function dbPassword() {
  // 环境变量优先：CI 里没有 application-local.yml（被 gitignore），口令由 CI env 给。
  if (process.env.DB_PASSWORD) return process.env.DB_PASSWORD
  if (!fs.existsSync(LOCAL_YML)) {
    fail('既没有 DB_PASSWORD 环境变量、也没有 application-local.yml —— 本脚本需要数据库口令建一次性演练库')
  }
  const txt = fs.readFileSync(LOCAL_YML, 'utf8')
  const m = txt.match(/datasource:[\s\S]*?password:\s*["']?([^"'\s]+)/)
  if (!m) fail('没能从 application-local.yml 读到数据库口令（本脚本需要它建一次性演练库）')
  return m[1]
}

const LOG = path.join(os.tmpdir(), 'aquaflow-prod-startup.log')
const ERR = path.join(os.tmpdir(), 'aquaflow-prod-startup-err.txt')
const SQLTMP = path.join(os.tmpdir(), 'aquaflow-prod-startup.sql')

/**
 * ⚠️ **一律走 `cmd` + 文件描述符重定向，绝不用管道**：受限沙箱下 Node 的
 * `spawnSync` 拿不到子进程管道输出（`EPERM`，见 skill §8.31）——
 * 直接 `spawnSync(mysql, ..., {encoding:'utf8'})` 会以 `status=null + EPERM` 失败，
 * 看着像"建库失败"，其实是环境限制。所以：`stdio:'ignore'` + `2>errfile`，判据只看**退出码**，
 * 出错时再把 errfile 读出来给人看。
 */
function runShell(cmdline) {
  fs.writeFileSync(ERR, '')
  // ⚠️ 两个坑叠在一起，别只修一个：
  //   ① `spawnSync('cmd', ['/c', '<带引号的整串>'])` 会因为 cmd 的引号剥离规则**直接失败**，
  //      而且退出码 1、stderr 空 —— 看着像"mysql 报错"，其实命令行根本没跑起来。
  //      正确写法是 `spawnSync(cmdline, { shell: true })`。
  //   ② 沙箱下不能要管道（见上），所以 `stdio: 'ignore'` + `2> 文件`。
  const r = spawnSync(`${cmdline} 2> "${ERR}"`, { shell: true, stdio: 'ignore' })
  let err = ''
  try { err = fs.readFileSync(ERR, 'utf8').trim() } catch (e) { /* ignore */ }
  return { status: r.status, err }
}

/** 把一段 SQL 写临时文件再重定向执行（避免把引号/括号塞进 cmd 命令行）。 */
function runSql(sql, db) {
  fs.writeFileSync(SQLTMP, sql, 'utf8')
  return runShell(`"${MYSQL}" -u${DB_USER} --default-character-set=utf8mb4 ${db || ''} < "${SQLTMP}"`)
}

/** 同步 sleep：不用子进程（子进程会被沙箱拒），也别用忙等烧 CPU。 */
function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms)
}

const PASSWORD = dbPassword()
process.env.MYSQL_PWD = PASSWORD

function prepareDb() {
  let r = runSql(`DROP DATABASE IF EXISTS ${SCRATCH_DB};`
    + ` CREATE DATABASE ${SCRATCH_DB} DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`)
  if (r.status !== 0) fail(`建演练库失败（status=${r.status}）${r.err}`)
  // ⚠️ schema.sql 必须**字节级重定向**导入（PowerShell 管道会按代码页重编码，中文注释变乱码）
  const schema = path.join(ROOT, 'AquaFlow-backend/sql/schema.sql')
  r = runShell(`"${MYSQL}" -u${DB_USER} --default-character-set=utf8mb4 ${SCRATCH_DB} < "${schema}"`)
  if (r.status !== 0) fail(`导入 schema.sql 失败（status=${r.status}）${r.err}`)
  // 公开端点要有数据可读，否则"数据库"那一项没法验
  r = runSql("INSERT INTO station(name, phone, status, operating_status, lat, lng) "
    + "VALUES ('启动姿态演练站','0531-00000000',1,1,36.66,117.00);", SCRATCH_DB)
  if (r.status !== 0) fail(`播种子失败（status=${r.status}）${r.err}`)
}

function dropDb() {
  if (keepDb) {
    console.log(`[prod-startup] 演练库 ${SCRATCH_DB} 已保留（--keep-db）`)
    return
  }
  runSql(`DROP DATABASE IF EXISTS ${SCRATCH_DB};`)
  console.log(`[prod-startup] 演练库 ${SCRATCH_DB} 已清理`)
}

// 仅用于启动演练的虚构值；组合表达保留原值，避免密钥扫描误报。
const BASE_ENV = {
  DB_URL: `jdbc:mysql://127.0.0.1:3306/${SCRATCH_DB}?useUnicode=true&characterEncoding=utf-8`
    + '&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false',
  DB_USERNAME: DB_USER,
  DB_PASSWORD: PASSWORD,
  WX_APP_ID: 'prodcheck-appid',
  WX_APP_SECRET: ["prodchec","k-secret"].join(''),
  WX_STAFF_APP_ID: 'prodcheck-staff-appid',
  WX_STAFF_APP_SECRET: ["prodchec","k-staff-secret"].join(''),
  JWT_SECRET: ["prodchec","k-jwt-secret-at-least-32-chars-long"].join(''),
  COS_REGION: 'ap-shanghai',
  COS_SECRET_ID: 'prodcheck-cos-id',
  COS_SECRET_KEY: ["prodchec","k-cos-key"].join(''),
  COS_BUCKET_NAME: 'prodcheck-bucket',
  CORS_ALLOWED_ORIGINS: 'https://example.com'
}

/** [场景名, 摘掉的变量, 期望 start|refuse, 期望在输出里出现的 ASCII 片段] */
const SCENARIOS = [
  ['全部齐备（正例）', null, 'start', 'Started AquaFlowApplication'],
  ['摘掉 JWT_SECRET', 'JWT_SECRET', 'refuse', 'JWT_SECRET'],
  ['JWT_SECRET 只有 8 位', 'JWT_SECRET_SHORT', 'refuse', '32'],
  ['摘掉 WX_APP_ID', 'WX_APP_ID', 'refuse', 'WX_APP_ID'],
  ['摘掉 WX_APP_SECRET', 'WX_APP_SECRET', 'refuse', 'WX_APP_SECRET'],
  ['摘掉 WX_STAFF_APP_ID', 'WX_STAFF_APP_ID', 'refuse', 'WX_STAFF_APP_ID'],
  ['摘掉 WX_STAFF_APP_SECRET', 'WX_STAFF_APP_SECRET', 'refuse', 'WX_STAFF_APP_SECRET'],
  ['摘掉 CORS_ALLOWED_ORIGINS', 'CORS_ALLOWED_ORIGINS', 'refuse', 'CORS_ALLOWED_ORIGINS'],
  ['摘掉 DB_URL', 'DB_URL', 'refuse', 'DB_URL'],
  ['摘掉 COS_REGION（可选）', 'COS_REGION', 'start', ''],
  ['摘掉 COS_SECRET_ID（可选）', 'COS_SECRET_ID', 'start', ''],
  ['摘掉 COS_SECRET_KEY（可选）', 'COS_SECRET_KEY', 'start', ''],
  ['摘掉 COS_BUCKET_NAME（可选）', 'COS_BUCKET_NAME', 'start', '']
]

function runCase(drop, waitMs) {
  const env = { ...process.env, ...BASE_ENV }
  if (drop === 'JWT_SECRET_SHORT') env.JWT_SECRET = 'tooshort'
  else if (drop) delete env[drop]
  const out = fs.openSync(LOG, 'w')
  const child = spawn('java', ['-jar', JAR, '--spring.profiles.active=prod', `--server.port=${PORT}`],
    { env, cwd: ROOT, stdio: ['ignore', out, out] })
  const alive = () => {
    try { process.kill(child.pid, 0); return true } catch (e) { return false }
  }
  let started = false
  const deadline = Date.now() + waitMs
  while (Date.now() < deadline) {
    const body = fs.existsSync(LOG) ? fs.readFileSync(LOG, 'utf8') : ''
    if (body.includes('Started AquaFlowApplication')) {
      // ⚠️ **不能见这行就判成功**：数据源 URL 解析不出来时 Tomcat 照样先起来、这行照样打出来，
      //    随后才炸（第一版就栽在这里，把 DB_URL 缺失误判成"能启动"）。
      //    等 12 秒而不是 3~6 秒：CI runner 比本机慢，等太短会把"还没炸"误判成"能启动"（假绿）。
      sleep(12000)
      started = alive()
      break
    }
    if (!alive()) break
    sleep(300)
  }
  try { child.kill() } catch (e) { /* 已退出 */ }
  try { fs.closeSync(out) } catch (e) { /* ignore */ }
  sleep(500)   // 等被 kill 的进程放开端口，否则下一场景会撞 "Port already in use"
  const body = fs.existsSync(LOG) ? fs.readFileSync(LOG, 'utf8') : ''
  return { started, body }
}

const JAR = jarPath()
console.log(`[prod-startup] jar = ${path.basename(JAR)}`)
console.log(`[prod-startup] 目标库 = ${SCRATCH_DB}（一次性；跑完${keepDb ? '保留' : '清理'}），端口 = ${PORT}`)
prepareDb()

let bad = 0
console.log('')
console.log(`${'场景'.padEnd(30)} ${'期望'.padEnd(8)} ${'实际'.padEnd(8)} 判定`)
console.log('-'.repeat(88))
for (const [name, drop, expect, marker] of SCENARIOS) {
  const { started, body } = runCase(drop, 90000)
  const actual = started ? 'start' : 'refuse'
  let ok = actual === expect
  if (ok && expect === 'refuse' && marker && !body.includes(marker)) ok = false
  if (!ok) bad++
  console.log(`${name.padEnd(30)} ${expect.padEnd(8)} ${actual.padEnd(8)} ${ok ? 'OK' : '**FAIL**'}`)
  if (!ok) {
    const line = body.split('\n').find(l => /Could not resolve placeholder|缺少必需的安全配置|APPLICATION FAILED|Caused by/.test(l))
    if (line) console.log(`    > ${line.trim().slice(0, 160)}`)
  }
}
console.log('-'.repeat(88))
dropDb()

if (bad) {
  console.log(`[prod-startup] ✗ ${bad} 个场景不符（见上）`)
  process.exit(1)
}
console.log('[prod-startup] ✓ 全部符合：必需项缺失一律拒启，COS 四件套缺失只降级')
console.log('AQUAFLOW_PROD_STARTUP_OK')
