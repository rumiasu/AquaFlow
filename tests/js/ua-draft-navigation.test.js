const assert = require('assert')
const { loadPage, createWx, createApp } = require('./harness')
const wx = createWx(), app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, customerId: 7, accessToken: 'synthetic-token' } })
let modal
wx.showModal = opts => { modal = opts }
const page = loadPage('miniapp-user/pages/service/index.js', { wx, app })
page._noteEpoch = 1
page.setData({ content: '未提交反馈', contact: '联系方式', refundOptions: [{ refundType: 'ORDER', refundId: 9 }] })
let loads = 0; page.loadRefundThread = () => { loads++ }
page.onSelectRefund({ detail: { value: '0' } })
assert(modal); assert.equal(page.data.content, '未提交反馈'); assert.equal(page.data.refundRef, null)
modal.success({ confirm: false }); assert.equal(page.data.content, '未提交反馈'); assert.equal(loads, 0)
page.onSelectRefund({ detail: { value: '0' } }); modal.success({ confirm: true })
assert.equal(page.data.refundRef.refundId, 9); assert.equal(loads, 1)
page.setData({ content: '新草稿', refundRef: null }); page.onSelectRefund({ detail: { value: '0' } }); page._noteEpoch++
modal.success({ confirm: true }); assert.equal(page.data.content, '新草稿'); assert.equal(loads, 1)
page.onServiceTab({currentTarget:{dataset:{tab:'records'}}}); assert.equal(page.data.content, '新草稿')
const template = loadPage('miniapp-user/pages/template/index.js', { wx, app })
template.loadData = () => Promise.resolve(); template.loadFormData = () => Promise.resolve()
template.onShow()
template.setData({ showEditModal: true, formReady: true, formDirty: false, form: {name:'',addressId:null,specialNote:'',items:[]} })
template.onNameInput({ detail: { value: '每周送水' } }); assert.equal(template.data.formDirty, true)
template.onTemplateBeforeLeave(); assert.equal(template.data.showEditModal, false); assert.equal(template.data.formDirty, true)
template.onResumeDraft(); assert.equal(template.data.showEditModal, true)
template.onCloseEditModal(); assert(modal); modal.success({ confirm: false }); assert.equal(template.data.showEditModal, true)
template.onCloseEditModal(); modal.success({ confirm: true }); assert.equal(template.data.formDirty, false)
console.log('AQUAFLOW_SUITE_OK 9')
