const assert = require('assert')
const vm = require('vm')

// A test projection of element ancestry and conditional siblings, not a WeChat compiler.
// Quoted attribute values may contain >; comments and whitespace do not break a chain.
function parseWxml(source) {
  const root = { tag: '#root', attrs: {}, children: [] }
  const stack = [root]
  const tokens = /<!--[\s\S]*?-->|{{[\s\S]*?}}|<\/?[A-Za-z][\w:-]*(?:\s+[\w:.-]+(?:\s*=\s*(?:"[^"]*"|'[^']*'))?)*\s*\/?>/g
  let end = 0
  const text = value => { if (value.trim()) stack[stack.length - 1].children.push({ tag: '#text', value }) }
  for (const match of source.matchAll(tokens)) {
    text(source.slice(end, match.index))
    end = match.index + match[0].length
    const token = match[0]
    if (token.startsWith('<!--')) continue
    if (token.startsWith('{{')) { text(token); continue }
    const tag = /^<\/?([\w:-]+)/.exec(token)[1]
    if (token.startsWith('</')) {
      assert(stack.length > 1 && stack[stack.length - 1].tag === tag, 'unbalanced closing ' + tag)
      stack.pop()
      continue
    }
    const attrs = {}
    for (const attr of token.slice(tag.length + 1).matchAll(/([\w:.-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'))?/g)) {
      attrs[attr[1]] = attr[2] === undefined ? (attr[3] === undefined ? '' : attr[3]) : attr[2]
    }
    const node = { tag, attrs, children: [], line: source.slice(0, match.index).split('\n').length }
    stack[stack.length - 1].children.push(node)
    if (!token.endsWith('/>')) stack.push(node)
  }
  text(source.slice(end))
  assert.strictEqual(stack.length, 1, 'unclosed WXML element')
  return root
}

function validateConditionalSiblings(root) {
  const visit = parent => {
    let chain = false
    for (const node of parent.children || []) {
      const attrs = node.attrs || {}
      const conditions = ['wx:if', 'wx:elif', 'wx:else'].filter(k => k in attrs)
      assert(conditions.length <= 1, 'multiple conditions on one element at line ' + node.line)
      if ('wx:if' in attrs) chain = true
      else if ('wx:elif' in attrs || 'wx:else' in attrs) {
        assert(chain, `${conditions[0]} without preceding sibling wx:if/wx:elif at line ${node.line}`)
        if ('wx:else' in attrs) chain = false
      } else chain = false
      visit(node)
    }
  }
  visit(root)
}

function expression(value, data) {
  const match = /^{{([\s\S]*)}}$/.exec(value)
  assert(match, 'expected binding expression')
  const context = new Proxy({ ...data }, { get: (target, key) => target[key] })
  return vm.runInNewContext(match[1], context, { timeout: 100 })
}

function renderElements(root, data) {
  validateConditionalSiblings(root)
  const elements = []
  const visit = (children, context) => {
    let taken = false
    for (const node of children) {
      if (node.tag === '#text') { taken = false; continue }
      const attrs = node.attrs
      if ('wx:for' in attrs) {
        const rows = expression(attrs['wx:for'], context) || []
        assert(Array.isArray(rows), 'test projection requires an array wx:for')
        rows.forEach((row, index) => {
          const copy = { ...node, attrs: { ...attrs } }
          delete copy.attrs['wx:for']
          visit([copy], { ...context, [attrs['wx:for-item'] || 'item']: row, [attrs['wx:for-index'] || 'index']: index })
        })
        taken = false
        continue
      }
      let visible = true
      if ('wx:if' in attrs) { visible = !!expression(attrs['wx:if'], context); taken = visible }
      else if ('wx:elif' in attrs) { visible = !taken && !!expression(attrs['wx:elif'], context); taken ||= visible }
      else if ('wx:else' in attrs) { visible = !taken; taken = true }
      else taken = false
      if (!visible) continue
      const className = (attrs.class || '').replace(/{{([\s\S]*?)}}/g, (_, e) => expression('{{' + e + '}}', context) || '')
      const id = 'data-id' in attrs ? expression(attrs['data-id'], context) : undefined
      elements.push({ tag: node.tag, attrs, className, id })
      visit(node.children, context)
    }
  }
  visit(root.children, data)
  return elements
}

module.exports = { parseWxml, validateConditionalSiblings, renderElements }
