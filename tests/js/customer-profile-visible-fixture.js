'use strict'
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const ROOT = path.resolve(__dirname, '../..')
const scope = {
  address: 'miniapp-user/pages/address/list',
  edit: 'miniapp-user/pages/address/edit',
  notice: 'miniapp-user/pages/notice/index',
  company: 'miniapp-user/pages/mine/company',
  exception: 'miniapp-user/pages/exception/list/list'
}
function fixture(name, overrides = {}) {
  let sessionEpoch = 1
  const drafts = new Map()
  const calls = { toast: [], modal: [], nav: [], write: [], logs: [], timers: [], stop: 0 }
  const defaults = {
    getAddresses: async () => ({ data: [] }), getAddressDetail: async id => ({ data: { id, name: '原联系人', detail: '原门牌' } }),
    createAddress: async () => ({ data: { id: 9 } }), updateAddress: async () => ({ data: null }),
    deleteAddress: async () => ({ data: null }), setDefaultAddress: async () => ({ data: null }),
    getNotices: async () => ({ data: [] }), getCompanyInfo: async () => ({ data: null }),
    updateCompanyInfo: async () => ({ data: { id: 1, companyName: '已保存企业' } }),
    get: async () => ({ data: { records: [] } })
  }
  const api = Object.assign(defaults, overrides.api || {})
  const wx = Object.assign({
    getStorageSync: key => drafts.has(key) ? drafts.get(key) : '', setStorageSync: (key, value) => drafts.set(key, value), removeStorageSync: key => drafts.delete(key),
    showToast: o => calls.toast.push(o),
    showModal: o => {
      for (const k of ['confirmText', 'cancelText']) if (o[k] && [...o[k]].length > 4) throw Error('Modal button too long')
      calls.modal.push(o)
    },
    navigateTo: o => calls.nav.push(o), redirectTo: o => calls.nav.push(o),
    navigateBack: o => calls.nav.push(o || { back: true }),
    stopPullDownRefresh: () => { calls.stop++ },
    getSetting: o => { calls.setting = o }, chooseLocation: o => { calls.location = o }, openSetting: () => { calls.openSetting = true }
  }, overrides.wx || {})
  const deps = {
    'api/address': api, 'api/notice': api, 'api/company': api, 'utils/request': api,
    'utils/storage': { storage: { set: (key, value) => calls.write.push({ key, value }) } },
    'utils/address': { formatAddress: a => [a.district, a.detail].filter(Boolean).join(''), parseRegion: s => ({ province: '', city: '', district: '', detail: s }) },
    'utils/validator': { validateForm: () => ({ valid: true }), rules: {} },
    'utils/token': { getCustomerId: () => 7, captureSession: () => ({ epoch: sessionEpoch, customerId: 7, loggedIn: true }), isCurrentSession: value => value.epoch === sessionEpoch },
    'utils/format': { formatTime: s => s || '' },
    'config/api': { API: { CUSTOMER_EXCEPTIONS: '/api/customer/exceptions' } }
  }
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, scope[name] + '.js'), 'utf8'), {
    Page: value => { definition = value }, wx, getApp: () => ({ globalData: { isLogin: true } }),
    require: p => {
      if (p.endsWith('draft-guard')) return { createDraftGuard: scope => require('../../miniapp-user/utils/draft-guard').createDraftGuard(scope, wx) }
      if (p.endsWith("business-dialog")) return { showBusinessModal: options => wx.showModal(options) }
      const key = Object.keys(deps).find(k => p.endsWith(k))
      if (!key) throw Error('Unexpected dependency ' + p)
      return deps[key]
    },
    console: { error: (...args) => calls.logs.push(args), warn: (...args) => calls.logs.push(args) },
    setTimeout: callback => { calls.timers.push(callback); return calls.timers.length }, clearTimeout() {}
  }, { filename: scope[name] + '.js' })
  const p = Object.assign({}, definition)
  p.data = JSON.parse(JSON.stringify(definition.data))
  p.setData = (patch, callback) => { Object.entries(patch).forEach(([key, value]) => {
    const parts = key.split('.')
    let target = p.data
    parts.slice(0, -1).forEach(part => { target = target[part] })
    target[parts[parts.length - 1]] = value
  }); if (callback) callback() }
  Object.keys(p).forEach(key => { if (typeof p[key] === 'function') p[key] = p[key].bind(p) })
  return { p, calls, api, wx, name, switchSession: () => { sessionEpoch++ } }
}
function deferred() {
  let resolve, reject
  const promise = new Promise((a, b) => { resolve = a; reject = b })
  return { promise, resolve, reject }
}
const event = (field, value) => ({ currentTarget: { dataset: { field } }, detail: { value } })
const tap = dataset => ({ currentTarget: { dataset } })

// 测试及浏览器近似预览共用：解析当前 WXML 条件/循环，样式直接取当前 WXSS。
// 不模拟微信布局或原生组件；用于核对状态是否真落到了模板，不能替代真机验收。
function parse(source) {
  const root = { children: [] }, stack = [root]
  const tokens = source.replace(/<!--[\s\S]*?-->/g, '').match(/<(?:[^"'<>]|"[^"]*"|'[^']*')+>|[^<]+/g) || []
  for (const token of tokens) {
    if (!token.startsWith('<')) { stack[stack.length - 1].children.push({ text: token }); continue }
    if (token.startsWith('</')) {
      const name = token.slice(2, -1).trim()
      if (stack.length < 2 || stack[stack.length - 1].tag !== name) throw Error('Unbalanced WXML ' + name)
      stack.pop()
      continue
    }
    const tag = (token.match(/^<([\w-]+)/) || [])[1]
    if (!tag) continue
    const attrs = {}
    const rest = token.slice(tag.length + 1).replace(/\/?>$/, '')
    const attrPattern = /([\w:-]+)(?:\s*=\s*("[^"]*"|'[^']*'))?/g
    let m
    while ((m = attrPattern.exec(rest))) attrs[m[1]] = m[2] ? m[2].slice(1, -1) : true
    const node = { tag, attrs, children: [] }
    stack[stack.length - 1].children.push(node)
    if (!token.endsWith('/>')) stack.push(node)
  }
  if (stack.length !== 1) throw Error('Unclosed WXML ' + stack[stack.length - 1].tag)
  return root
}
function expression(raw, data) {
  if (raw === true) return true
  const m = String(raw).match(/^\{\{([\s\S]*)\}\}$/)
  return m ? vm.runInNewContext(m[1], data) : raw
}
function interpolate(value, data) {
  return String(value).replace(/\{\{([\s\S]*?)\}\}/g, (_, code) => {
    const result = vm.runInNewContext(code, data)
    return result == null ? '' : String(result)
  })
}
const escape = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
function children(nodes, data) {
  let branch = false
  return nodes.map(node => {
    if (node.text !== undefined) return escape(interpolate(node.text, data))
    if ('wx:if' in node.attrs) {
      branch = !!expression(node.attrs['wx:if'], data)
      if (!branch) return ''
    } else if ('wx:elif' in node.attrs) {
      if (branch) return ''
      branch = !!expression(node.attrs['wx:elif'], data)
      if (!branch) return ''
    } else if ('wx:else' in node.attrs) {
      if (branch) return ''
      branch = true
    } else branch = false
    return element(node, data)
  }).join('')
}
function element(node, data, looped) {
  if (node.attrs['wx:for'] && !looped) {
    const list = expression(node.attrs['wx:for'], data)
    return (list || []).map((item, index) => element(node, Object.assign({}, data, {
      [node.attrs['wx:for-item'] || 'item']: item, [node.attrs['wx:for-index'] || 'index']: index
    }), true)).join('')
  }
  const tag = ({ view: 'div', text: 'span', block: 'div', 'scroll-view': 'div' })[node.tag] || node.tag
  let attrs = ''
  for (const [key, value] of Object.entries(node.attrs)) {
    if (key.startsWith('wx:')) continue
    if (/^(bind|catch)/.test(key)) { attrs += ' data-handler="' + escape(value) + '"'; continue }
    if (['disabled', 'loading'].includes(key)) {
      if (expression(value, data)) attrs += key === 'disabled' ? ' disabled' : ' data-loading="true"'
      continue
    }
    if (key.startsWith('data-') && value !== true && /^\{\{/.test(value)) continue
    attrs += ' ' + key + '="' + escape(interpolate(value, data)) + '"'
  }
  return '<' + tag + attrs + '>' + children(node.children, data) + (tag === 'input' ? '' : '</' + tag + '>')
}
function render(name, data) {
  return children(parse(fs.readFileSync(path.join(ROOT, scope[name] + '.wxml'), 'utf8')).children, data)
}
function visible(name, data) { return render(name, data).replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ') }
module.exports = { ROOT, scope, fixture, deferred, event, tap, parse, render, visible }
