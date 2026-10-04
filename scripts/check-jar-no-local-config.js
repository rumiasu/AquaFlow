#!/usr/bin/env node
/**
 * 发布物检查：确认打出来的 jar **不含本地开发配置**。
 *
 * 跑法：node scripts/check-jar-no-local-config.js
 * 隔离构建：node scripts/check-jar-no-local-config.js --jar <本次生成的完整 JAR 路径>
 * 退出码：0 = 通过；1 = 含本地配置、目标缺失、损坏或格式不受支持
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
const { TextDecoder } = require('util')

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
 * 只检查单卷 ZIP32 的完整中央目录，支持 STORED/DEFLATED、ASCII 或标记为 UTF-8 的名称。
 * ZIP64、多卷、加密、不明名称编码及额外目录尾记录不在支持范围，明确拒绝。
 * 此处不解压文件，也不验证压缩内容/CRC；它只为配置文件名检查提供完整条目列表。
 */
function readZipEntries(file) {
  const buf = fs.readFileSync(file)
  const refuse = message => { throw new Error(message) }
  let eocd = -1
  // EOCD 必须以其声明的注释长度结束于文件末尾；注释里的伪签名不能抢走真实 EOCD。
  const from = Math.max(0, buf.length - 65557)
  for (let i = buf.length - 22; i >= from; i--) {
    if (buf.readUInt32LE(i) !== 0x06054b50) continue
    if (i + 22 + buf.readUInt16LE(i + 20) !== buf.length) continue
    if (buf.readUInt32LE(i + 16) + buf.readUInt32LE(i + 12) !== i) continue
    eocd = i
    break
  }
  if (eocd < 0) refuse('EOCD 缺失、截断或中央目录边界不受支持')

  const entryCount = buf.readUInt16LE(eocd + 10)
  const diskCount = buf.readUInt16LE(eocd + 8)
  const directorySize = buf.readUInt32LE(eocd + 12)
  let off = buf.readUInt32LE(eocd + 16)
  if (entryCount === 0xffff || directorySize === 0xffffffff || off === 0xffffffff) refuse('不支持 ZIP64')
  if (buf.readUInt16LE(eocd + 4) !== 0 || buf.readUInt16LE(eocd + 6) !== 0 || diskCount !== entryCount) refuse('不支持多卷或条数不一致的 ZIP')

  const entries = []
  // 2026-10-03：原实现遇到坏签名就 break，未读完的污染包仍获通过；任何解析失败必须拒绝。
  for (let n = 0; n < entryCount; n++) {
    if (off + 46 > eocd) refuse('中央目录条目头被截断或声明条数过多')
    if (buf.readUInt32LE(off) !== 0x02014b50) refuse('中央目录条目签名无效')
    const flags = buf.readUInt16LE(off + 8)
    const method = buf.readUInt16LE(off + 10)
    const compressedSize = buf.readUInt32LE(off + 20)
    const nameLen = buf.readUInt16LE(off + 28)
    const extraLen = buf.readUInt16LE(off + 30)
    const commentLen = buf.readUInt16LE(off + 32)
    if (flags & 0x0041) refuse('不支持加密 ZIP')
    if (method !== 0 && method !== 8) refuse('不支持该压缩方法')
    if (compressedSize === 0xffffffff || buf.readUInt32LE(off + 24) === 0xffffffff || buf.readUInt32LE(off + 42) === 0xffffffff) refuse('不支持 ZIP64 条目')
    if (buf.readUInt16LE(off + 34) !== 0) refuse('不支持跨卷条目')
    const end = off + 46 + nameLen + extraLen + commentLen
    if (nameLen === 0 || end > eocd) refuse('中央目录名称/扩展/注释长度无效或被截断')
    const nameBytes = buf.subarray(off + 46, off + 46 + nameLen)
    if (!(flags & 0x0800) && nameBytes.some(b => b >= 0x80)) refuse('不支持未标记 UTF-8 的非 ASCII 名称')
    const name = new TextDecoder('utf-8', { fatal: true }).decode(nameBytes)
    if (name.includes('\0')) refuse('ZIP 名称含无效 NUL 字符')
    entries.push({ name, compressedSize })
    off = end
  }
  if (off !== eocd || entries.length !== entryCount) refuse('中央目录大小与条数不一致，条目列表不完整')
  return { entries, size: buf.length }
}

/** 默认检查 build/libs；显式指定产物时不回退，避免误验其他会话留下的 JAR。 */
function main() {
  const args = process.argv.slice(2)
  if (args.length && (args.length !== 2 || args[0] !== '--jar' || !args[1])) {
    console.error('用法：node scripts/check-jar-no-local-config.js [--jar <JAR 路径>]')
    process.exit(1)
  }
  // 2026-10-03：原脚本忽略目标参数，隔离构建时会检查共享目录的旧包并错误放行。
  // 显式产物必须存在且是文件，不能静默退回 build/libs；不改变无参的 CI 用法。
  let jarFiles
  if (args.length) {
    const full = path.resolve(args[1])
    if (!full.endsWith('.jar') || !fs.existsSync(full) || !fs.statSync(full).isFile()) {
      console.error(`[jar-check] 指定 JAR 不存在或不是 JAR 文件：${full}`)
      process.exit(1)
    }
    jarFiles = [full]
  } else {
    if (!fs.existsSync(libsDir)) {
      console.error(`[jar-check] 找不到 ${libsDir} —— 先执行 AquaFlow-backend\\gradlew.bat bootJar`)
      process.exit(1)
    }
    jarFiles = fs.readdirSync(libsDir).filter(f => f.endsWith('.jar')).map(f => path.join(libsDir, f))
    if (jarFiles.length === 0) {
      console.error(`[jar-check] ${libsDir} 下没有 jar —— 先执行 bootJar`)
      process.exit(1)
    }
  }

  const bad = []
  for (const full of jarFiles) {
    const jar = path.basename(full)
    const { entries, size } = readZipEntries(full)
    console.log(`[jar-check] ${full}  (${size.toLocaleString('en-US')} 字节)`)
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

try {
  main()
} catch (error) {
  console.error('[jar-check] 无法可信解析 JAR，拒绝通过：' + error.message)
  process.exit(1)
}
