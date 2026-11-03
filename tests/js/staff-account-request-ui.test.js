'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { parseWxml, renderElements } = require('./wxml-tree')
const root = path.resolve(__dirname, '../..')
const read = name => fs.readFileSync(path.join(root, name), 'utf8')
const page = 'miniapp-delivery/pages/mine/account-data/index'
const wxml = read(page + '.wxml'), tree = parseWxml(wxml)
const config = JSON.parse(read(page + '.json'))
assert.equal(config.navigationBarTitleText, '个人信息与账号申请')
assert(!read('miniapp-delivery/pages/mine/index.wxml').includes('个人信息与账号申请'))
assert(read('miniapp-delivery/pages/settings/index.wxml').includes('onOpenAccountHistory'))
function view(patch = {}) { return renderElements(tree, { activeView: 'apply', detailsExpanded: false, error: '', originalUnavailable: false, loading: false, items: [], nextBeforeId: null, check: null, checkError: '', ...patch }, { includeText: true }) }
function has(nodes, handler) { return nodes.some(n => n.attrs && n.attrs.bindtap === handler) }
const closed = view({ options: { intakeEnabled: false, closureCheckSupported: false } })
assert(closed.some(n => n.text && n.text.includes('申请暂未开放')))
assert(closed.some(n => n.text && n.text.includes('修改昵称或手机号')))
assert(!has(closed, 'onSubmit')); assert(!has(closed, 'onToggleDetails')); assert(!has(closed, 'onRetry'))
assert(has(closed, 'onSetView'), 'application records remain reachable')
const opened = view({ options: { intakeEnabled: true, requestTypes: [], closureCheckSupported: false } })
assert(has(opened, 'onSubmit')); assert(has(opened, 'onToggleDetails')); assert(!has(opened, 'onRetry'))
assert(has(view({ options: null, error: '查询失败' }), 'onRetry'))
assert(has(view({ options: { intakeEnabled: false }, originalUnavailable: true }), 'onRetry'), 'unknown prior request retains the existing recovery route')
assert(has(view({ activeView: 'apply', options: { intakeEnabled: false, closureCheckSupported: true } }), 'onCheck'), 'closure precheck remains available')
console.log('AQUAFLOW_SUITE_OK 8')
