'use strict'

const assert = require('assert')
const fs = require('fs')
const path = require('path')

const root = path.resolve(__dirname, '../..')
const markup = fs.readFileSync(path.join(root, 'miniapp-user/pages/order/create.wxml'), 'utf8')
const styles = fs.readFileSync(path.join(root, 'miniapp-user/pages/order/create.wxss'), 'utf8')

assert.match(markup, /<scroll-view class="detail-sheet-body" scroll-y>/,
  'fee details must use the bounded detail scroll view')

const bodyStyle = styles.match(/\.detail-sheet-body\s*\{([^}]*)\}/)
assert(bodyStyle, 'detail-sheet-body styles must exist')
assert.match(bodyStyle[1], /width:\s*100%\s*;/,
  'detail scroll view must fill the sheet width')
assert.match(bodyStyle[1], /min-width:\s*0\s*;/,
  'detail scroll view must be allowed to shrink as a flex item')
assert.match(bodyStyle[1], /box-sizing:\s*border-box\s*;/,
  'the scroll view must retain its bounded horizontal box')

// The native scroll-view host and its scrollable content have separate bounds.
// The bottom clearance must belong to a child inside the scroll range.
assert.match(markup,
  /<scroll-view class="detail-sheet-body" scroll-y>\s*<view class="detail-sheet-content">/,
  'fee rows and their clearance must be inside one scrollable content container')
assert.match(markup,
  /<\/view>\s*<\/scroll-view>\s*<view class="detail-sheet-footer">/,
  'the total footer must remain outside the padded scrolling content')
assert.match(bodyStyle[1], /min-height:\s*0\s*;/,
  'the scrolling body must shrink when header and footer need more height')
assert.match(bodyStyle[1], /padding:\s*0\s*;/,
  'clearance must not rely on native scroll-view host padding')
const contentStyles = [...styles.matchAll(/\.detail-sheet-content\s*\{([^}]*)\}/g)]
assert(contentStyles.length, 'fee detail scrollable content styles must exist')
assert.match(contentStyles.at(-1)[1], /padding:\s*12px\s+14px\s*;/,
  'the last fee line must retain bottom clearance inside the scroll range')

console.log('PASS fee detail scroll content retains horizontal inset and bottom clearance')
console.log('AQUAFLOW_SUITE_OK 1')
