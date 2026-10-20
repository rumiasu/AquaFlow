/** G04 配送/退还关键文字字号与对比度的窄范围静态回归。 */
const assert = require('assert')
const fs = require('fs')
const path = require('path')

const root = path.resolve(__dirname, '..', '..')
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8')

function declarations(css, selector) {
  const source = css.replace(/\/\*[\s\S]*?\*\//g, '')
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = new RegExp('(?:^|})\\s*' + escaped + '\\s*\\{([^}]*)}', 'm').exec(source)
  assert.ok(match, '缺少选择器 ' + selector)
  return Object.fromEntries(match[1].split(';').map((part) => part.trim()).filter(Boolean).map((part) => {
    const split = part.indexOf(':')
    return [part.slice(0, split).trim(), part.slice(split + 1).trim()]
  }))
}

function rgb(value) {
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(value)
  if (hex) {
    const digits = hex[1].length === 3 ? hex[1].split('').map((part) => part + part).join('') : hex[1]
    return [0, 2, 4].map((start) => parseInt(digits.slice(start, start + 2), 16))
  }
  const rgba = /^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)$/.exec(value)
  assert.ok(rgba, '无法解析颜色 ' + value)
  return [Number(rgba[1]), Number(rgba[2]), Number(rgba[3]), rgba[4] === undefined ? 1 : Number(rgba[4])]
}

function composite(foreground, background, alpha = foreground[3] === undefined ? 1 : foreground[3]) {
  return foreground.slice(0, 3).map((channel, i) => channel * alpha + background[i] * (1 - alpha))
}

function luminance(value) {
  const linear = value.map((channel) => {
    const normalized = channel / 255
    return normalized <= 0.04045 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

function contrast(a, b) {
  const first = luminance(a)
  const second = luminance(b)
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05)
}

function px(css, selector) {
  const value = declarations(css, selector)['font-size']
  const match = /^(\d+(?:\.\d+)?)px$/.exec(value)
  assert.ok(match, selector + ' 字号须使用稳定 px 下限，实际为 ' + value)
  return Number(match[1])
}

const home = read('miniapp-delivery/pages/home/index.wxss')
const detail = read('miniapp-delivery/pages/order/detail.wxss')
const returns = read('miniapp-delivery/pages/station-mgmt/barrel-return/index.wxss')
const rendered = [
  read('miniapp-delivery/pages/home/index.wxml'),
  read('miniapp-delivery/pages/order/detail.wxml'),
  read('miniapp-delivery/pages/station-mgmt/barrel-return/index.wxml')
].join('\n')
const app = read('miniapp-delivery/app.wxss')
const tokens = declarations(app, 'page')

let passed = 0
function test(name, check) {
  check()
  passed++
  console.log('ok ' + passed + ' ' + name)
}

test('配送状态标签不低于 14px', () => assert.ok(px(home, '.stat-label') >= 14))
test('配送统计单位不低于 12px 且不靠低透明度损失对比度', () => {
  assert.ok(px(home, '.stat-unit') >= 12)
  const rule = declarations(home, '.stat-unit')
  assert.ok(contrast(composite(rgb('#FFFFFF'), rgb('#2E4A68'), Number(rule.opacity)), rgb('#2E4A68')) >= 4.5)
})
test('配送首页现金/线下标签不低于 14px', () => assert.ok(px(home, '.aux-tag') >= 14))
test('配送详情现金/线下标签不低于 14px', () => assert.ok(px(detail, '.aux-tag') >= 14))
test('退桶关键操作提示不低于 14px', () => assert.ok(px(returns, '.step-hint') >= 14))
test('退桶时间辅助文字不低于 12px', () => assert.ok(px(returns, '.time-label') >= 12))
test('首页与详情线下标签均达到普通文字 4.5:1', () => {
  const foreground = rgb(declarations(home, '.aux-tag.aux-offline').color)
  const homeBackground = composite(rgb(declarations(home, '.aux-tag.aux-offline').background), rgb('#FFFFFF'))
  const detailBackground = rgb(tokens['--warning-bg'])
  assert.ok(contrast(foreground, homeBackground) >= 4.5)
  assert.ok(contrast(rgb(declarations(detail, '.aux-tag.aux-offline').color), detailBackground) >= 4.5)
})
test('完成配送按钮达到普通文字 4.5:1', () => {
  const rule = declarations(home, '.btn-complete')
  assert.ok(contrast(rgb(rule.color), rgb(rule.background)) >= 4.5)
})
test('受检样式均绑定到当前 WXML', () => {
  for (const className of ['stat-label', 'stat-unit', 'aux-tag', 'aux-offline', 'btn-complete', 'time-label', 'step-hint']) {
    assert.ok(rendered.includes(className), 'WXML 未使用 .' + className)
  }
})

console.log('AQUAFLOW_SUITE_OK ' + passed)
