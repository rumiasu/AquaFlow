'use strict'

/**
 * F-68 (2026-10-02): a test-looking name does not authorize deleting its data.
 * Restrict each tool to its own namespace AND require the exact reset target.
 * Call before reading credentials/launching clients, and again before each DROP.
 */
const PREFIXES = Object.freeze({
  test: 'aquaflow_test',
  prodcheck: 'aquaflow_prodstartup_check',
  restoredrill: 'aquaflow_restoredrill'
})

function assertIdentifier(name) {
  if (typeof name !== 'string' || !/^[a-z][a-z0-9_]{0,63}$/.test(name)) {
    throw new Error('数据库名必须为 1–64 位小写字母、数字或下划线，以字母开头')
  }
  return name
}

function assertScratchDatabase({ name, kind, source = 'aquaflow', confirmation }) {
  assertIdentifier(name)
  assertIdentifier(source)
  const prefix = Object.hasOwn(PREFIXES, kind) ? PREFIXES[kind] : null
  if (!prefix || !(name === prefix || (name.startsWith(prefix + '_')
      && /^[a-z0-9][a-z0-9_]*$/.test(name.slice(prefix.length + 1))))) {
    throw new Error(`拒绝清空目标：本工具只允许 ${prefix || '已登记的临时库'} 及其会话后缀`)
  }
  if (/backup|archive|(^|_)(bak|prod|production|live)($|_)/.test(name)) {
    throw new Error('拒绝清空业务、生产或备份标记的数据库')
  }
  if (name === source) throw new Error('拒绝执行：源数据库与临时目标相同')
  if (confirmation !== name) {
    throw new Error(`请先确认目标确实可清空，再设置 AQUAFLOW_ALLOW_DB_RESET=${name}；库名本身不代表允许删除数据`)
  }
  return name
}

module.exports = { assertIdentifier, assertScratchDatabase }

if (require.main === module) {
  try {
    const [kind, name, source] = process.argv.slice(2)
    assertScratchDatabase({ kind, name, source, confirmation: process.env.AQUAFLOW_ALLOW_DB_RESET })
    if (kind === 'test') {
      const target = require('./test-database-target').getTestDatabaseTarget()
      if (target.name !== name) throw Error('清空参数与已确认的测试连接目标不一致')
    }
    console.log('AQUAFLOW_SCRATCH_TARGET_OK')
  } catch (error) {
    console.error('[scratch-db] ' + error.message)
    process.exitCode = 1
  }
}
