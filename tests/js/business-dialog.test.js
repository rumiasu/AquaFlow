const fs = require('fs'), vm = require('vm'), assert = require('assert'), path = require('path')
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
  component.open({ title: '回桶', content: '实回 0 桶，金额 ¥123.45', success: r => calls.push(r) })
  assert.ok(component.data.parts.some(part => part.number && part.text.includes('123.45')))
  component.open({ title: '下一个', content: '长'.repeat(2000), success: r => calls.push(r) })
  component.onConfirm(); assert.equal(calls.length, 1); assert.equal(calls[0].confirm, true)
  assert.equal(component.data.title, '下一个')
  definition.pageLifetimes.hide.call(component)
  assert.equal(calls.length, 2); assert.equal(calls[1].cancel, true); assert.equal(component.data.visible, false)
  component.onConfirm(); assert.equal(calls.length, 2)
  definition.pageLifetimes.show.call(component)
  component.open({ editable: true, content: '3', success: r => calls.push(r) })
  component.onInput({ detail: { value: '0' } }); component.onConfirm()
  assert.equal(calls[2].content, '0'); assert.ok(released >= 3)
  definition.lifetimes.detached.call(component)
  console.log('PASS ' + app + ' dialog theme, numeric emphasis, queue, hide cancellation and keyboard release')
}
console.log('AQUAFLOW_SUITE_OK 2')
