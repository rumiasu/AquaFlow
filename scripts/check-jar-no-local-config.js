#!/usr/bin/env node
/**
 * 发布物检查：确认打出来的 jar **不含本地开发配置**。
 *
 * 跑法：node scripts/check-jar-no-local-config.js
 * 退出码：0 = 通过；1 = 发现本地配置被打进包（或找不到 jar）
 *
 * ---------------------------------------------------------------------------
 * 背景（2026-09-27 实测）
 * ---------------------------------------------------------------------------
 * `src/main/resources/**` 默认会被打进 `BOOT-INF/classes/`，而 `application-local.yml`
 * 是**本地开发配置**（含真实密钥），它**被 .gitignore 忽略、也被 scripts/scan-secrets.sh 排除**
 * （后者用 `git grep`，只看已跟踪的文本文件）—— **两道防线都不会看它**。
 * 实测旧 jar（44887868 字节）里确实有 `BOOT-INF/classes/application-local.yml`（7954 字节，
 * 比 application.yml 的 7667 还大）。一旦把 jar 传到服务器或交给别人，密钥就跟着走了。
 *
 * ---------------------------------------------------------------------------
 * 为什么是 node 脚本，不是 .ps1
 * ---------------------------------------------------------------------------
 * Windows PowerShell **5.1** 会把**无 BOM** 的 .ps1 按 ANSI（本机 GBK）解码 ⇒ 文件里的中文
 * 变乱码、连带把脚本语法打坏（实测报 `Unexpected token '瀛楄妭'`，而"瀛楄妭"就是"字节"被
 * GBK 解 UTF-8 的结果）。而 `.ps1` 不在 `.gitattributes` 的强制 LF 列表里，加 BOM 又会在
 * Linux CI 上引入新问题。node 读源码一律 UTF-8、无歧义，且本仓 `tests/js/**` 已有先例。
 *
 * 为什么单独一个脚本、而不并进 scripts/verify.sh：
 *   verify.sh 是**开发期**门禁（跑得越勤越好），不该被"要先打个 40MB 的包"拖慢；
 *   本检查是**发布前**一步，与「上线检查表」同批执行。
 *
 * ⚠️ 本脚本**只读 jar 的条目名与大小**，不解压、不回显任何配置内容 —— 命中即报位置。
 */

const fs = require('fs')
const path = require('path')

const repoRoot = path.resolve(__dirname, '..')
const libsDir = path.join(repoRoot, 'AquaFlow-backend', 'build', 'libs')

/**
 * 要拦住的形态：**任何**本地/开发专用配置。
 * 只查 `application-local.yml` 会漏掉 yaml/properties 后缀，以及被打进子目录
 * （如 `BOOT-INF/classes/config/`）的那几份。
 */
const FORBIDDEN = [
  'application-local.yml',
  'application-local.yaml',
  'application-local.properties',
  'application-dev.yml',
  'application-dev.yaml',
  'application-dev.properties'
]
const EXPECTED = ['BOOT-INF/classes/application.yml', 'BOOT-INF/classes/application-prod.yml']

/**
 * 不引入 zip 库，直接读中央目录里的条目名。
 *
 * 做法：找 End of Central Directory（EOCD，签名 PK\x05\x06）→ 拿中央目录偏移与条数 →
 * 逐个读中央目录头（PK\x01\x02），取其中的文件名。只读名字与大小，不碰文件内容。
 * 为什么不用现成库：本仓没有 node_modules，为一个门禁引依赖不划算。
 */
function readZipEntries(file) {
  const buf = fs.readFileSync(file)
  const eocdSig = 0x06054b50
  let eocd = -1
  // EOCD 在文件末尾，注释最长 65535 字节；从后往前找第一条签名
  const from = Math.max(0, buf.length - 65557)
  for (let i = buf.length - 22; i >= from; i--) {
    if (buf.readUInt32LE(i) === eocdSig) { eocd = i; break }
  }
  if (eocd < 0) throw new Error('不是有效的 zip（找不到 EOCD）：' + file)

  const entryCount = buf.readUInt16LE(eocd + 10)
  let off = buf.readUInt32LE(eocd + 16)
  const entries = []
  for (let n = 0; n < entryCount; n++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) break
    const compressedSize = buf.readUInt32LE(off + 20)
    const nameLen = buf.readUInt16LE(off + 28)
    const extraLen = buf.readUInt16LE(off + 30)
    const commentLen = buf.readUInt16LE(off + 32)
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen)
    entries.push({ name, compressedSize })
    off += 46 + nameLen + extraLen + commentLen
  }
  return { entries, size: buf.length }
}

function main() {
  if (!fs.existsSync(libsDir)) {
    console.error(`[jar-check] 找不到 ${libsDir} —— 先执行 AquaFlow-backend\\gradlew.bat bootJar`)
    process.exit(1)
  }
  const jars = fs.readdirSync(libsDir).filter(f => f.endsWith('.jar'))
  if (jars.length === 0) {
    console.error(`[jar-check] ${libsDir} 下没有 jar —— 先执行 bootJar`)
    process.exit(1)
  }

  const bad = []
  for (const jar of jars) {
    const full = path.join(libsDir, jar)
    const { entries, size } = readZipEntries(full)
    console.log(`[jar-check] ${jar}  (${size.toLocaleString('en-US')} 字节)`)
    for (const e of entries) {
      const base = path.basename(e.name)
      if (FORBIDDEN.includes(base)) {
        bad.push({ jar, entry: e.name, bytes: e.compressedSize })
      }
    }
    for (const want of EXPECTED) {
      const hit = entries.find(e => e.name === want)
      if (hit) console.log(`  ✓ ${want}  (${hit.compressedSize} 字节)`)
      else console.log(`  ! 缺少 ${want} —— 生产启动会缺配置`)
    }
    const appYml = entries
      .filter(e => /^application-.*\.(yml|yaml|properties)$/.test(path.basename(e.name)))
      .map(e => path.basename(e.name))
    console.log(`  包内 application-* 配置共 ${appYml.length} 个：${appYml.join(', ')}`)
  }

  if (bad.length > 0) {
    console.error('')
    console.error('[jar-check] ✗ 发现本地开发配置被打进发布物（只报位置，不回显内容）：')
    bad.forEach(b => console.error(`  ${b.jar}  ->  ${b.entry}`))
    console.error('')
    console.error("修法：在 AquaFlow-backend/build.gradle 的 bootJar 任务上 exclude 'application-local.yml'。")
    console.error('注意**不要**写进 processResources —— bootRun 与 test 用的就是它的输出目录，')
    console.error('在那里排除会让本地 --spring.profiles.active=local 直接起不来。')
    process.exit(1)
  }

  console.log('')
  console.log('[jar-check] ✓ 发布物不含本地开发配置')
  process.exit(0)
}

main()
