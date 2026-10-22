'use strict'
// 仅生成当前 Page JS + WXML + WXSS 的合成数据快照，不连接 API，不模拟微信原生布局。
const fs = require('fs')
const path = require('path')
const { fixture, render, scope, ROOT, tap } = require('./customer-profile-visible-fixture')
const ok = data => ({ code: 0, data })
async function snapshots() {
  const cases = []
  const push = (id, name, title, p) => cases.push({ id, name, title, data: JSON.parse(JSON.stringify(p.data)) })
  const addressError = fixture('address', { api: { getAddresses: async () => { throw Error('synthetic network failure') } } })
  await addressError.p.loadAddresses(); push('address-first-error', 'address', '地址首次读取失败', addressError.p)
  const addressEmpty = fixture('address')
  await addressEmpty.p.loadAddresses(); push('address-actual-empty', 'address', '地址真实空列表', addressEmpty.p)
  const editError = fixture('edit', { api: { getAddressDetail: async () => { throw Error('synthetic network failure') } } })
  editError.p.setData({ isEdit: true, addressId: 3, formReady: false })
  await editError.p.loadAddress(3); push('edit-read-error', 'edit', '原地址读取失败，表单关闭', editError.p)
  const editUnknown = fixture('edit', { api: { createAddress: async () => { throw Error('synthetic timeout') } } })
  editUnknown.p.setData({ 'formData.name': '演示联系人 Alice', 'formData.phone': '13800000000', 'formData.detail': '演示小区 A 座 603' })
  await editUnknown.p.onSubmit(); push('edit-save-unknown', 'edit', '地址保存结果未知，保留输入', editUnknown.p)
  let noticeFails = false
  const notices = fixture('notice', { api: { getNotices: async () => {
    if (noticeFails) throw Error('synthetic timeout')
    return ok([{ id: 1, type: 2, title: '演示：节假日送水安排与联系电话变更公告', createTime: '2026-10-22 10:00' }])
  } } })
  await notices.p.loadNotices(); noticeFails = true; await notices.p.loadNotices()
  push('notice-stale', 'notice', '公告刷新失败，旧内容保留', notices.p)
  const companyError = fixture('company', { api: { getCompanyInfo: async () => { throw Error('synthetic timeout') } } })
  await companyError.p.loadInfo(); push('company-first-error', 'company', '企业资料读取失败', companyError.p)
  const companyUnknown = fixture('company', { api: {
    getCompanyInfo: async () => ok({ id: 1, companyName: '演示企业 Acme', contactPerson: '演示联系人 Alice', contactPhone: '13800000000', paymentMethod: '月结', dueDays: 0 }),
    updateCompanyInfo: async () => { throw Error('synthetic timeout') }
  } })
  await companyUnknown.p.loadInfo(); companyUnknown.p.onStartEdit(); await companyUnknown.p.onSave(); await companyUnknown.p.loadInfo()
  push('company-save-unknown', 'company', '企业保存未知与已保存资料核对', companyUnknown.p)
  const exception = fixture('exception', { api: { get: async () => ok({ records: [{
    id: 9, orderNo: 'DEMO-20261022-001', status: 'EXECUTED', statusText: '已完成', categoryText: '多回桶',
    createdAt: '2026-10-22T10:15:00', discrepancy: -2, refundCashAmount: '12.50', refundTicketQty: 3,
    staffNote: '演示记录：实际多回 2 桶。备注 A&B 保持原样。', managerNote: '演示处理说明：按原记录核对。此处为合成数据，非真实退款。'
  }] }) } })
  await exception.p.loadExceptions()
  push('exception-list', 'exception', '异常列表金额与差异', exception.p)
  exception.p.onExceptionTap(tap({ id: 9 }))
  push('exception-detail', 'exception', '异常详情弹窗', exception.p)
  const unknown = exception.p.decorateException({ id: 10, orderNo: 'DEMO-UNKNOWN', status: 'FUTURE_CODE', statusText: 'FUTURE_CODE', categoryText: 'FUTURE_CATEGORY' })
  exception.p.setData({ exceptions: [unknown], detailVisible: false })
  push('exception-unknown', 'exception', '异常状态和金额无法确认', exception.p)
  return cases
}
async function main() {
  const out = path.resolve(process.argv[2] || path.join(ROOT, 'qa/customer-profile-visible'))
  fs.mkdirSync(out, { recursive: true })
  const cases = await snapshots()
  for (const sample of cases) {
    const css = fs.readFileSync(path.join(ROOT, scope[sample.name] + '.wxss'), 'utf8').replace(/(-?\d*\.?\d+)rpx/g, (_, number) => Number(number) / 2 + 'px')
    const page = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>' + sample.title + '</title><style>' +
      'html,body{margin:0;background:#f5f6f7;font-family:Arial,"Microsoft YaHei",sans-serif}body{max-width:375px;margin:auto}button,input{font:inherit}button{border:0}span{overflow-wrap:anywhere}privacy-popup,icon{display:none}:root{--bg-color:#f5f6f7;--text-primary:#172b4d;--text-regular:#526174;--text-placeholder:#637083;--primary-color:#1264d1;--primary-bg:#edf3fb;--border-light:#e3e8ef;--border-color:#dce3ed;--bg-gray:#f0f3f7;--radius-md:10px;--radius-sm:6px}button{cursor:default}.detail-scroll{overflow:auto}.preview-note{padding:8px 12px;background:#e8edf3;color:#526174;font-size:12px;line-height:1.5}' +
      css + '</style><body><aside class="preview-note">代码状态快照 · 合成数据 · 非微信运行时<br>' + sample.title + '</aside>' + render(sample.name, sample.data) + '</body></html>'
    fs.writeFileSync(path.join(out, sample.id + '.html'), page, 'utf8')
  }
  fs.writeFileSync(path.join(out, 'state-snapshots.json'), JSON.stringify(cases, null, 2), 'utf8')
  fs.writeFileSync(path.join(out, 'index.html'), '<!doctype html><meta charset="utf-8"><title>顾客资料五页状态快照</title><h1>顾客资料五页状态快照</h1><p>当前 JS 实际执行后的数据 + 当前 WXML 条件/循环 + 当前 WXSS。未模拟微信运行时，尚未目视验收。</p><ul>' +
    cases.map(sample => '<li><a href="' + sample.id + '.html">' + sample.title + '</a></li>').join('') + '</ul>', 'utf8')
  console.log('PROFILE_PREVIEW_OK ' + cases.length + ' ' + out)
}
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1 })
module.exports = { snapshots }
