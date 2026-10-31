const fs = require('fs'), vm = require('vm'), assert = require('assert'), path = require('path')
async function main() {
for (const app of ['miniapp-delivery', 'miniapp-user']) {
  let definition, theme, released = 0
  const wx = { getAppBaseInfo: () => ({ theme: 'dark' }), onThemeChange: fn => { theme = fn }, offThemeChange: () => {}, hideKeyboard: () => released++ }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../', app, 'components/BusinessDialog/index.js'), 'utf8'), { Component: value => { definition = value }, wx })
  const component = { data: { ...definition.data }, setData(patch) { Object.assign(this.data, patch) } }
  for (const [key, fn] of Object.entries(definition.methods)) component[key] = fn.bind(component)
  definition.lifetimes.attached.call(component)
  assert.equal(component.data.dark, true)
  theme({ theme: 'light' }); assert.equal(component.data.dark, false)
  const calls = []
  component.open({ title: '回桶', content: '订单 12345，2026-10-28', details: [{ label: '实回', value: '0 桶' }, { label: '金额', value: '¥123.45' }], success: r => calls.push(r) })
  assert.equal(component.data.content, '订单 12345，2026-10-28')
  assert.equal(component.data.details[0].value, '0 桶')
  assert.equal(component.data.details[1].value, '¥123.45')
  component.open({ title: '下一个', content: '长'.repeat(2000), success: r => calls.push(r) })
  component.onConfirm(); assert.equal(calls.length, 1); assert.equal(calls[0].confirm, true)
  component.onConfirm(); assert.equal(calls.length, 1, 'duplicate tap must not consume the next queued dialog')
  await Promise.resolve()
  assert.equal(component.data.title, '下一个')
  definition.pageLifetimes.hide.call(component)
  assert.equal(calls.length, 2); assert.equal(calls[1].cancel, true); assert.equal(component.data.visible, false)
  component.onConfirm(); assert.equal(calls.length, 2)
  definition.pageLifetimes.show.call(component)
  await Promise.resolve()
  component.open({ editable: true, content: '3', success: r => calls.push(r) })
  assert.equal(component.data.input, '3', 'legacy editable content is still the initial value')
  component.onInput({ detail: { value: '0' } }); component.onConfirm()
  assert.equal(calls[2].content, '0'); assert.ok(released >= 3)
  await Promise.resolve()
  component.open({ editable: true, content: '旧说明不得变成新初值', description: '请输入实际数量', initialValue: 0, placeholderText: '整数', success: r => calls.push(r) })
  assert.equal(component.data.description, '请输入实际数量')
  assert.equal(component.data.input, '0')
  assert.equal(component.data.placeholder, '整数')
  component.onInput({ detail: { value: '' } }); component.onCancel()
  assert.equal(calls[3].cancel, true); assert.equal(calls[3].content, '')
  await Promise.resolve()
  component.open({ editable: true, initialValue: '', description: '可选', success: r => calls.push(r) })
  assert.equal(component.data.input, '')
  definition.pageLifetimes.hide.call(component)
  assert.equal(calls[4].cancel, true)
  const hidden = await component.open({ success: r => calls.push(r) })
  assert.equal(hidden.cancel, true); assert.equal(calls.length, 6)
  definition.lifetimes.detached.call(component)

  let nativeOptions, nativeCalls = 0, nativeCallbacks = 0, completed = 0, selected
  const sandbox = { module: { exports: {} }, getCurrentPages: () => selected ? [{ selectComponent: () => selected }] : [], wx: { showModal: options => { nativeOptions = options; nativeCalls++ } } }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../', app, 'utils/business-dialog.js'), 'utf8'), sandbox)
  const show = sandbox.module.exports.showBusinessModal
  const callbackResult = Promise.resolve('async callback retained')
  const legacySuccess = () => { nativeCallbacks++; return callbackResult }
  const fallback = show({ editable: true, content: '3', success: legacySuccess, complete: () => completed++ })
  assert.equal(nativeOptions.content, '3')
  assert.equal(fallback, undefined, 'legacy native return value is unchanged')
  assert.equal(nativeOptions.success, legacySuccess)
  assert.equal(nativeOptions.success({ confirm: true, content: '0' }), callbackResult)
  nativeOptions.complete({})
  assert.equal(nativeCallbacks, 1); assert.equal(completed, 1)
  const explicit = show({ editable: true, initialValue: 0, success: () => nativeCallbacks++ })
  assert.equal(nativeOptions.content, '0'); nativeOptions.success({ confirm: false, cancel: true }); nativeOptions.success({ confirm: true, content: '9' }); await explicit
  assert.equal(nativeCallbacks, 2, 'new native contract finishes only once')
  const count = nativeCalls
  let writes = 0
  const safeCancel = await show({ editable: true, description: '不能丢失的说明', initialValue: '3', success: result => { if (result.confirm) writes++ }, complete: () => completed++ })
  assert.equal(safeCancel.cancel, true); assert.equal(safeCancel.reason, 'unsupported-editable-fallback')
  assert.equal(nativeCalls, count); assert.equal(writes, 0)
  const detailCancel = await show({ editable: true, initialValue: '3', details: [{ label: '费用', value: '未知' }] })
  assert.equal(detailCancel.cancel, true); assert.equal(nativeCalls, count)
  const ordinary = show({ content: '订单 12345', details: [{ label: '金额', value: 0 }], description: '操作说明' })
  assert.equal(nativeOptions.content, '订单 12345\n\n操作说明\n\n金额：0')
  nativeOptions.success({ confirm: false, cancel: true }); await ordinary
  let failed = 0
  const failure = show({ details: [], fail: () => failed++, complete: () => completed++ })
  nativeOptions.fail({ errMsg: 'showModal:fail' }); nativeOptions.fail({ errMsg: 'showModal:fail again' }); await failure
  assert.equal(failed, 1)
  selected = { open: options => { assert.equal(options.initialValue, 0); return Promise.resolve({ confirm: true, content: '0' }) } }
  assert.equal((await show({ editable: true, description: '数量', initialValue: 0 })).content, '0')
  console.log('PASS ' + app + ' dialog explicit details, legacy/new editor, zero/empty, duplicate tap, hide and safe native fallback')
}
for (const file of ['components/BusinessDialog/index.js', 'components/BusinessDialog/index.wxml', 'components/BusinessDialog/index.wxss', 'utils/business-dialog.js']) {
  assert.equal(fs.readFileSync(path.join(__dirname, '../../miniapp-delivery', file), 'utf8'), fs.readFileSync(path.join(__dirname, '../../miniapp-user', file), 'utf8'), 'shared implementations drifted: ' + file)
}
console.log('AQUAFLOW_SUITE_OK 2')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
