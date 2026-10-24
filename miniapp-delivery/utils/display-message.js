/** 仅用于展示，原枚举仍用于提交与判权。来源：InventoryChangeType 与交接约定枚举。 */
function inventoryTypeText(type, note) {
  if (type === 'CONSUME' && note === '完成配送出库') return '配送出库'
  return ({ INIT: '期初库存', INBOUND: '采购入库', CONSUME: '订单库存扣减',
    CANCEL_RESTORE: '取消回补', REFUND_RESTORE: '退款回补', ADJUST: '盘点调整' })[type] || '变动类型待核实'
}

function barrelModeText(mode) {
  return ({ RETURN_EMPTY: '补同型空桶', SETTLE_BARREL: '按约定折款' })[mode] || '桶交接方案待核实'
}

// 2026-10-24：只展示当前响应/会话已授权给本页的名称；缺名明确保留编号，不额外查询客户。
function referenceText(name, id, kind = '商品') {
  const text = typeof name === 'string' ? name.trim() : ''
  const number = id == null ? '' : String(id).trim()
  return text ? text + (number ? '（' + kind + '编号：' + number + '）' : '')
    : number ? kind + '编号：' + number : kind + '待核实'
}

module.exports = { inventoryTypeText, barrelModeText, referenceText }
