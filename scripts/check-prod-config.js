#!/usr/bin/env node
/**
 * 生产配置覆盖检查：`application-prod.yml` 要求的每个环境变量，都必须在 `.env.example` 里有说明；
 * 反过来，`.env.example` 里不该出现 prod 根本没读的变量（那是会误导运维的"幽灵项"）。
 *
 * 跑法：node scripts/check-prod-config.js
 * 退出码：0 = 通过；1 = 有缺口（缺说明 / 幽灵变量 / 危险默认值）
 *
 * ---------------------------------------------------------------------------
 * 为什么需要它
 * ---------------------------------------------------------------------------
 * 「生产缺哪个变量会拒启」目前**只能靠人读 yml**。而这里有个容易搞错的机制差异：
 *
 *   · `config/RequiredConfigChecker` 用 `@Value("${x:}")` 带空默认值 ⇒ 缺失**不报错**，
 *     由它自己在 `@PostConstruct` 里判断并抛出。它**只硬校验 3 项**：
 *     `JWT_SECRET`（且长度 ≥ 32）、`WX_APP_ID`、`WX_APP_SECRET`。
 *   · `application-prod.yml` 里的 `${DB_URL}` 这类**没有默认值** ⇒ 缺失时 Spring 解析占位符
 *     就失败、应用起不来。所以**真正"缺一即拒启"的是 prod yml 里那几个**，
 *     比 Checker 校验的 3 项多 —— 两者是**两套机制**，别把它们混成一个数
 *     （实测 `.env.example` 的注释就写着"不填只影响员工端微信登录"，而 prod yml 里
 *     `${WX_STAFF_APP_ID}` **无默认值** ⇒ 生产不填其实起不来）。
 *
 * 本脚本不去执行 yml 解析（没有 yaml 依赖），只用两条稳定判据：
 *   ① `application-prod.yml` 里出现 `\$\{NAME\}` 的，NAME 必须在 `.env.example` 里有定义行；
 *   ② `.env.example` 里有定义行、但 prod yml 与 `application.yml` 都没读到的，报"幽灵变量"。
 * 另外顺带扫一条高危项：prod yml 里不许出现 `dev-login-enabled: true`。
 */

const fs = require('fs')
const path = require('path')

const repoRoot = path.resolve(__dirname, '..')
const resDir = path.join(repoRoot, 'AquaFlow-backend', 'src', 'main', 'resources')
const prodYml = path.join(resDir, 'application-prod.yml')
const baseYml = path.join(resDir, 'application.yml')
const envExample = path.join(repoRoot, 'AquaFlow-backend', '.env.example')

function read(p) {
  if (!fs.existsSync(p)) {
    console.error(`[prod-config] 找不到文件：${p}`)
    process.exit(1)
  }
  return fs.readFileSync(p, 'utf8')
}

/** 抽出 `${NAME}` / `${NAME:default}` 里的 NAME。 */
function placeholders(text) {
  const out = new Set()
  const re = /\$\{([A-Za-z_][A-Za-z0-9_]*)(?::[^}]*)?\}/g
  let m
  while ((m = re.exec(text)) !== null) out.add(m[1])
  return out
}

/** `.env.example` 里"定义行"（`NAME=...`），不含被注释掉的示例。 */
function envExampleDefined(text) {
  const out = new Map()
  text.split(/\r?\n/).forEach((line, i) => {
    const m = /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(line)
    if (m) out.set(m[1], { line: i + 1, empty: m[2].trim() === '' })
  })
  return out
}

const prod = read(prodYml)
const base = read(baseYml)
const env = read(envExample)

const prodVars = placeholders(prod)
const baseVars = placeholders(base)
const allReadVars = new Set([...prodVars, ...baseVars])
const defined = envExampleDefined(env)

let problems = 0

// ① prod 要求、但 .env.example 没写 ⇒ 运维不知道要配它，而生产会拒启
const undocumented = [...prodVars].filter(v => !defined.has(v)).sort()
if (undocumented.length) {
  problems++
  console.error('[prod-config] ✗ 以下变量 application-prod.yml 必需，但 .env.example 里没有定义行：')
  undocumented.forEach(v => console.error('    ' + v))
} else {
  console.log(`[prod-config] ✓ prod 必需的 ${prodVars.size} 个变量，.env.example 全部有定义`)
}

// ② .env.example 里定义了、但代码根本不读 ⇒ 幽灵变量，会误导运维
const ghosts = [...defined.keys()].filter(v => !allReadVars.has(v)).sort()
if (ghosts.length) {
  problems++
  console.error('[prod-config] ✗ .env.example 里有定义、但 application*.yml 都没读到（幽灵变量）：')
  ghosts.forEach(v => console.error(`    ${v}  （.env.example:${defined.get(v).line}）`))
} else {
  console.log(`[prod-config] ✓ .env.example 的 ${defined.size} 个定义行都能在配置里找到落点`)
}

// ③ 定义行是空的 —— 有一类**有意留空**，不算问题：
//    · 密钥类：`.env.example` 本来就不该带真值（照抄会拒启，这正是"逼你填"）。
//    · 域名类（CORS_ALLOWED_ORIGINS）：同样不给默认值 —— 给了 `your-domain.com`
//      就等于鼓励把占位域名带上生产（本仓 `miniapp-*/config/api.js` 已有同款占位符拦截）。
//    ⚠️ 这份清单是**白名单**，加条目必须同时写清"为什么它可以空"，
//       否则它会退化成"静默放行一切"的后门。
const EMPTY_BY_DESIGN = new Map([
  ['JWT_SECRET', '密钥：必须由部署方生成（长度 ≥ 32），示例里不能有真值'],
  ['WX_APP_ID', '密钥：微信平台申请'],
  ['WX_APP_SECRET', '密钥：微信平台申请'],
  ['WX_STAFF_APP_ID', '密钥：员工端小程序（与客户端是两个 appid）'],
  ['WX_STAFF_APP_SECRET', '密钥：员工端小程序'],
  ['COS_SECRET_ID', '密钥：腾讯云控制台'],
  ['COS_SECRET_KEY', '密钥：腾讯云控制台'],
  ['COS_BUCKET_NAME', '资源名：各环境不同，给出默认值反而容易连错桶'],
  ['DB_PASSWORD', '密钥：数据库口令'],
  ['CORS_ALLOWED_ORIGINS', '域名：必须写真实域名，禁止 * 与 localhost，故不给默认值']
])
const emptyUnexpected = [...defined.entries()]
  .filter(([v, info]) => info.empty && prodVars.has(v) && !EMPTY_BY_DESIGN.has(v))
  .map(([v, info]) => `${v}（.env.example:${info.line}）`)
if (emptyUnexpected.length) {
  problems++
  console.error('[prod-config] ✗ 以下 prod 必需变量在 .env.example 里是空值，且不在"有意留空"白名单里：')
  emptyUnexpected.forEach(v => console.error('    ' + v))
} else {
  const empties = [...defined.keys()].filter(v => defined.get(v).empty && prodVars.has(v)).sort()
  console.log(`[prod-config] ✓ prod 必需变量里留空的 ${empties.length} 项都属"有意留空"（密钥/域名，部署时填）`)
  empties.forEach(v => console.log(`      ${v} —— ${EMPTY_BY_DESIGN.get(v)}`))
}

// ④ 高危默认值：生产不许开后门
if (/dev-login-enabled:\s*true/.test(prod)) {
  problems++
  console.error('[prod-config] ✗ application-prod.yml 里 dev-login-enabled: true —— 生产禁止开后门')
} else {
  console.log('[prod-config] ✓ 生产配置未开启 dev-login 后门')
}
if (/mock-wechat-pay:\s*true/.test(prod)) {
  problems++
  console.error('[prod-config] ✗ application-prod.yml 里 mock-wechat-pay: true —— 等于零元购')
} else {
  console.log('[prod-config] ✓ 生产配置未开启微信支付模拟渠道')
}

console.log('')
if (problems) {
  console.error(`[prod-config] ✗ 发现 ${problems} 类问题，见上。`)
  process.exit(1)
}
console.log('[prod-config] ✓ 生产配置检查通过')
process.exit(0)
