const assert = require('assert'), fs = require('fs'), path = require('path')
const { ROOT } = require('./harness')
// Lexical guard only: wx compiler/native cold-start acceptance remains a separate QA check.
function codeOnly(source) {
  let out = '', i = 0
  while (i < source.length) {
    const c = source[i], n = source[i + 1]
    if (c === '/' && (n === '/' || n === '*')) {
      const block = n === '*'; out += '  '; i += 2
      while (i < source.length) {
        if (block && source[i] === '*' && source[i+1] === '/') { out += '  '; i += 2; break }
        if (!block && source[i] === '\n') break
        out += source[i] === '\n' ? '\n' : ' '; i++
      }
    } else if (c === "'" || c === '"' || c === '`') {
      const quote = c; out += ' '; i++
      while (i < source.length) {
        const ch = source[i++]; out += ch === '\n' ? '\n' : ' '
        if (ch.charCodeAt(0) === 92 && i < source.length) { out += ' '; i++ }
        else if (ch === quote) break
      }
    } else { out += c; i++ }
  }
  return out
}
function violations(source) {
  return Array.from(codeOnly(source).matchAll(/\.{3}\s*(?:\(\s*)*require\s*\(/g)).map(m => m.index)
}
function files(dir) { return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? files(path.join(dir,e.name)) : e.name.endsWith('.js') ? [path.join(dir,e.name)] : []) }
let passed = 0, failed = 0
function test(name, fn) { try { fn(); passed++; console.log('PASS '+name) } catch (e) { failed++; console.error('FAIL '+name+': '+e.message) } }
test('guard rejects direct spread imports including whitespace/comments/parenthesized require', () => {
  for (const source of ["Page({...require('x').methods})", "Page({data:{...\n require ('x').data}})", "Page({... /* compiler trap */ ( require('x').methods)})"]) assert.equal(violations(source).length,1)
})
test('guard accepts top-level require and ignores comments and literal examples', () => {
  for (const source of ["const helper = require('x'); Page({...helper.methods,data:{...helper.data}})", "// ...require('x')\nconst note = \"...require('x')\";", "/* ...require('x') */ const note = `...require('x')`; "]) assert.equal(violations(source).length,0)
})
const targets = [
 ['miniapp-delivery/pages/station-mgmt/customers/adjust/index.js','adjustmentCustomer','../../../../utils/adjustment-customer'],
 ['miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js','adjustmentCustomer','../../../../../utils/adjustment-customer'],
 ['miniapp-delivery/pages/station-mgmt/orders/index.js','historyCustomer','../../../utils/station-history-customer'],
 ['miniapp-delivery/pages/station-mgmt/payments/index.js','historyCustomer','../../../utils/station-history-customer']
]
for (const [file, variable, modulePath] of targets) test('shared dependency declared before Page: '+file, () => {
  const source = fs.readFileSync(path.join(ROOT,file),'utf8'), declaration = `const ${variable} = require('${modulePath}')`
  assert.ok(source.includes(declaration) && source.indexOf(declaration) < source.indexOf('Page({'))
  assert.ok(source.includes(`...${variable}.methods`) && source.includes(`...${variable}.data`))
})
test('both maintained miniapps forbid direct require inside spread expressions', () => {
  const affected = []
  for (const base of ['miniapp-user','miniapp-delivery']) for (const file of files(path.join(ROOT,base))) if (violations(fs.readFileSync(file,'utf8')).length) affected.push(path.relative(ROOT,file))
  assert.deepEqual(affected,[])
})
console.log(`wechat shared import guard: ${passed} passed, ${failed} failed`)
if (failed) process.exitCode=1; else console.log('AQUAFLOW_SUITE_OK '+passed)
