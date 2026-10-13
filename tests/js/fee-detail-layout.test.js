'use strict'

const assert = require('assert')
const fs = require('fs')
const path = require('path')

const root = path.resolve(__dirname, '../..')
const markup = fs.readFileSync(path.join(root, 'miniapp-user/pages/order/create.wxml'), 'utf8')
const styles = fs.readFileSync(path.join(root, 'miniapp-user/pages/order/create.wxss'), 'utf8')

assert.match(markup, /<scroll-view class="detail-sheet-body" scroll-y>/,
  'fee details must use the padded detail scroll view')

const bodyStyle = styles.match(/\.detail-sheet-body\s*\{([^}]*)\}/)
assert(bodyStyle, 'detail-sheet-body styles must exist')
assert.match(bodyStyle[1], /width:\s*100%\s*;/,
  'detail scroll view must fill the sheet width')
assert.match(bodyStyle[1], /min-width:\s*0\s*;/,
  'detail scroll view must be allowed to shrink as a flex item')
assert.match(bodyStyle[1], /box-sizing:\s*border-box\s*;/,
  'horizontal padding must be included in the scroll view width')

console.log('PASS fee detail scroll view keeps padded rows inside the sheet width')
console.log('AQUAFLOW_SUITE_OK 1')
