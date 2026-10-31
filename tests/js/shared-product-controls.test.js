'use strict'
const fs = require('fs'), vm = require('vm'), path = require('path'), assert = require('assert')
const root = path.resolve(__dirname, '../..')
function component(app, name) {
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(root, app, 'components', name, 'index.js'), 'utf8'), { Component: value => { definition = value }, wx: { hideKeyboard() {} } })
  const state = {}, events = []
  for (const [key, property] of Object.entries(definition.properties || {})) state[key] = property.value
  Object.assign(state, definition.data)
  const result = { data: state, events, setData(patch) {
    for (const [key, value] of Object.entries(patch)) {
      const match = key.match(/^viewItems\[(\d+)\]\.image$/)
      if (match) state.viewItems[Number(match[1])].image = value
      else state[key] = value
    }
  }, triggerEvent(name, detail) { events.push({ name, detail }) } }
  for (const [key, method] of Object.entries(definition.methods)) result[key] = method.bind(result)
  return result
}
for (const app of ['miniapp-user', 'miniapp-delivery']) {
  const stepper = component(app, 'IntegerStepper')
  Object.assign(stepper.data, { min: 0, max: 5, step: 1 })
  stepper.syncInput(''); assert.equal(stepper.data.inputValue, '')
  stepper.onStep({ currentTarget: { dataset: { direction: 'add' } } }); assert.equal(stepper.events[0].detail.value, '0')
  stepper.onInput({ detail: { value: '' } }); assert.equal(stepper.events[1].detail.value, '')
  stepper.onInput({ detail: { value: '9' } }); assert.equal(stepper.data.inputValue, '9'); assert.ok(stepper.data.inputError)
  const count = stepper.events.length
  stepper.onStep({ currentTarget: { dataset: { direction: 'add' } } }); assert.equal(stepper.events.length, count)
  stepper.syncInput('0'); assert.equal(stepper.data.minusDisabled, true)
  stepper.data.disabled = true; stepper.onInput({ detail: { value: '2' } }); assert.equal(stepper.events.length, count)

  const selector = component(app, 'ProductSelector')
  const items = [{ id: 8, name: '长商品名'.repeat(20), spec: '18.9L', priceText: '¥0.00', image: 'synthetic.png' },
    { id: 9, name: '待核价' }, { id: 10, name: '停用', disabled: true }]
  Object.assign(selector.data, { items, selectedId: '8' }); selector.syncItems()
  assert.equal(selector.data.viewItems[0].selected, true); assert.equal(selector.data.viewItems[0].priceText, '¥0.00')
  assert.equal(selector.data.viewItems[1].priceText, '价格待确认')
  const event = index => ({ currentTarget: { dataset: { index } } })
  selector.onSelect(event(1)); assert.equal(selector.events[0].detail.id, 9); assert.equal(selector.events[0].detail.item, items[1])
  selector.onSelect(event(2)); selector.onSelect(event(-1)); assert.equal(selector.events.length, 1)
  selector.onImageError(event(0)); assert.equal(selector.data.viewItems[0].image, ''); assert.equal(items[0].image, 'synthetic.png')
  selector.data.disabled = true; selector.syncItems(); selector.onSelect(event(0)); assert.equal(selector.events.length, 1)
  const registration = JSON.parse(fs.readFileSync(path.join(root, app, 'app.json'), 'utf8')).usingComponents
  assert.equal(registration['integer-stepper'], '/components/IntegerStepper/index')
  assert.equal(registration['product-selector'], '/components/ProductSelector/index')
  console.log('PASS ' + app + ' parent-owned selection, unknown/zero price, image fallback, disabled selection and integer drafts')
}
for (const name of ['IntegerStepper', 'ProductSelector']) for (const file of ['index.js', 'index.json', 'index.wxml', 'index.wxss']) {
  assert.equal(fs.readFileSync(path.join(root, 'miniapp-user/components', name, file), 'utf8'), fs.readFileSync(path.join(root, 'miniapp-delivery/components', name, file), 'utf8'))
}
console.log('AQUAFLOW_SUITE_OK 2')
