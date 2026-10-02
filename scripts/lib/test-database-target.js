'use strict'

const net = require('net')
const { assertScratchDatabase } = require('./scratch-database')
const DEFAULT_QUERY = 'useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
const OPTIONS = Object.freeze({
  useUnicode: /^(true|false)$/,
  characterEncoding: /^(utf-8|UTF-8|utf8)$/,
  serverTimezone: /^Asia\/Shanghai$/,
  allowPublicKeyRetrieval: /^(true|false)$/,
  useSSL: /^(true|false)$/,
  sslMode: /^(DISABLED|PREFERRED|REQUIRED|VERIFY_CA|VERIFY_IDENTITY)$/
})
// Test schemas are prepared explicitly; startup SQL/migrations run before the
// base test's connection check and can USE a different, unconfirmed schema.
const STARTUP_SQL_PREFIXES = Object.freeze(['springsqlinit', 'springflyway', 'springliquibase',
  'springbatchjdbc', 'springquartzjdbc', 'springsessionjdbc', 'springjpa', 'springhibernate', 'springr2dbc'])

// [2026-10-02 F-68] JDBC is not a web URL: host lists, address=(), proxy factories,
// driver property transforms and encoded delimiters can change the real target.
// Support only this explicit single-host TCP subset; never echo rejected input.
function hostOf(value) {
  if (typeof value !== 'string' || value.length > 253) throw Error('测试服务器地址不受支持')
  const host = value.toLowerCase()
  if (net.isIP(host) === 4) return host
  throw Error('测试服务器仅支持完整 IPv4；主机名可能解析到多台服务器，别名/IPv6/特殊地址无法证明一致')
}
function portOf(value) {
  const text = String(value)
  if (!/^[1-9][0-9]{0,4}$/.test(text) || Number(text) > 65535) throw Error('测试端口须为 1–65535 的规范十进制')
  return text
}
function parseJdbcTarget(url) {
  if (typeof url !== 'string' || /[\s%+#\\]/.test(url)) throw Error('测试 JDBC 地址含不支持的字符或编码')
  const match = /^jdbc:mysql:\/\/([a-zA-Z0-9.-]+)(?::([0-9]+))?\/([a-z][a-z0-9_]{0,63})(?:\?(.+))?$/.exec(url)
  if (!match) throw Error('测试 JDBC 仅支持单主机 mysql TCP 地址及明确库名')
  const host = hostOf(match[1]), port = portOf(match[2] || '3306'), name = match[3]
  const seen = new Set()
  if (match[4]) for (const pair of match[4].split('&')) {
    const option = /^([a-zA-Z]+)=([^=]+)$/.exec(pair)
    if (!option || !Object.hasOwn(OPTIONS, option[1]) || seen.has(option[1]) || !OPTIONS[option[1]].test(option[2])) {
      throw Error('测试 JDBC 含未知、重复或不支持的驱动参数；禁止改变连接目标及执行初始化语句')
    }
    seen.add(option[1])
  }
  return { host, port, name, endpoint: `${host}:${port}/${name}`,
    jdbcUrl: `jdbc:mysql://${host}:${port}/${name}` + (match[4] ? '?' + match[4] : '') }
}
function assertNoAlternateConfiguration(env) {
  const bindingKeys = ['MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_USER', 'TEST_DB_NAME', 'TEST_DB_URL', 'DB_URL',
    'AQUAFLOW_DB', 'AQUAFLOW_ALLOW_DB_RESET', 'AQUAFLOW_ALLOW_TEST_DB_TARGET']
  for (const [key, value] of Object.entries(env)) {
    if (!value) continue
    if (bindingKeys.includes(key.toUpperCase()) && key !== key.toUpperCase()) {
      throw Error('目标绑定变量必须使用规定的大写名称，避免 Windows 与 JVM 环境读取差异')
    }
    const normalized = key.toLowerCase().replace(/[^a-z0-9]/g, '')
    if (STARTUP_SQL_PREFIXES.some(prefix => normalized.startsWith(prefix))
        && !(normalized === 'springsqlinitmode' && value === 'never')) {
      throw Error('测试库须显式准备；禁止启动 SQL/迁移及其脚本、凭据或目标覆盖，仅允许 spring.sql.init.mode=never')
    }
    if (/^(springdatasource|springconfig|springprofiles)/.test(normalized)
        || ['springapplicationjson', 'springapplicationname', 'springmain', 'springautoconfigureexclude'].some(x => normalized.startsWith(x))
        || ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS', 'GRADLE_OPTS',
          'MYSQL_PROTOCOL', 'MYSQL_SOCKET', 'MYSQL_UNIX_PORT', 'MYSQL_TCP_PORT'].includes(key.toUpperCase())) {
      throw Error('存在不受支持的 Spring/JVM/MySQL 配置入口，无法证明测试目标一致；请移除覆盖配置')
    }
  }
}
function getTestDatabaseTarget(env = process.env) {
  assertNoAlternateConfiguration(env)
  const name = env.TEST_DB_NAME || 'aquaflow_test'
  assertScratchDatabase({ name, kind: 'test', source: env.AQUAFLOW_DB || 'aquaflow', confirmation: env.AQUAFLOW_ALLOW_DB_RESET })
  if (['backup', 'bak', 'old', 'prod', 'archive', 'restore', '_pre'].some(marker => name.includes(marker))) {
    throw Error('原集成测试名称护栏拒绝此库，不能先建库后才发现测试不可运行')
  }
  const host = hostOf(env.MYSQL_HOST || '127.0.0.1'), port = portOf(env.MYSQL_PORT || '3306')
  const target = parseJdbcTarget(env.TEST_DB_URL || `jdbc:mysql://${host}:${port}/${name}?${DEFAULT_QUERY}`)
  if (target.name !== name || target.host !== host || target.port !== port) throw Error('建库名称/服务器/端口与 TEST_DB_URL 不一致，拒绝任何数据库命令')
  if (env.DB_URL && parseJdbcTarget(env.DB_URL).endpoint !== target.endpoint) throw Error('DB_URL 与测试目标不一致')
  if (env.AQUAFLOW_ALLOW_TEST_DB_TARGET !== target.endpoint) throw Error('须明确确认完整测试目标并设置 AQUAFLOW_ALLOW_TEST_DB_TARGET=服务器:端口/库名；仅库名确认不足')
  if (!/^[a-zA-Z0-9_]{1,64}$/.test(env.MYSQL_USER || 'root')) throw Error('测试 MySQL 用户名不受支持')
  return target
}

module.exports = { parseJdbcTarget, getTestDatabaseTarget, assertNoAlternateConfiguration }
if (require.main === module) {
  try {
    const target = getTestDatabaseTarget()
    if (process.argv[2] === '--verify' && (target.host !== '127.0.0.1' || target.port !== '3306')) {
      throw Error('完整 verify 的生产启动检查目前固定本机 127.0.0.1:3306；其它目标只支持独立准备及受保护的直接测试')
    }
    console.log([target.host, target.port, target.name, target.jdbcUrl].join('\n'))
  } catch (error) { console.error('[test-db-target] ' + error.message); process.exitCode = 1 }
}
