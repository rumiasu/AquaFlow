// Backend resources are the text source; mirrors are only offline drafts, never a publishing route.
const fs = require('fs'), path = require('path'), crypto = require('crypto')
const root = path.resolve(__dirname, '..'), base = path.join(root, 'AquaFlow-backend/src/main/resources')
const index = JSON.parse(fs.readFileSync(path.join(base, 'agreements/index.json'), 'utf8'))
const check = process.argv.includes('--check'), selected = { CUSTOMER: [], STAFF: [] }
for (const entry of index.documents) {
  if (!/^agreements\/[a-z0-9-]+\.json$/.test(entry.resource)) throw Error('Invalid agreement resource')
  const raw = fs.readFileSync(path.join(base, entry.resource), 'utf8').replace(/\r\n?/g, '\n'), hash = crypto.createHash('sha256').update(raw).digest('hex')
  if (hash !== entry.sha256) throw Error('Agreement hash mismatch: ' + entry.resource)
  const body = JSON.parse(raw.toString('utf8'))
  if (!entry.current || entry.status !== 'DRAFT') continue
  if (!selected[body.audience] || !['user', 'privacy'].includes(body.type)) throw Error('Invalid agreement scope')
  selected[body.audience].push(Object.assign({}, body, { versionId: body.audience.toLowerCase() + '-' + body.type + '-' + hash,
    contentSha256: hash, status: 'DRAFT', active: false }))
}
for (const [audience, app] of [['CUSTOMER', 'miniapp-user'], ['STAFF', 'miniapp-delivery']]) {
  const text = JSON.stringify({ enabled: false, notice: '协议草稿尚未启用，当前登录不会记录正式协议接受或隐私告知确认。', documents: selected[audience] }, null, 2) + '\n'
  const target = path.join(root, app, 'data/agreement-drafts.json')
  if (check) {
    if (!fs.existsSync(target) || fs.readFileSync(target, 'utf8') !== text) throw Error('Agreement mirror differs: ' + app)
  } else { fs.mkdirSync(path.dirname(target), { recursive: true }); fs.writeFileSync(target, text) }
}
process.stdout.write('AQUAFLOW_AGREEMENT_MIRRORS_OK\n')
