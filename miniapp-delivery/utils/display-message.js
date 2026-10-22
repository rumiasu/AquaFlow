/** 仅用于展示，原枚举仍用于提交与判权。来源：InventoryChangeType 与交接约定枚举。 */
function inventoryTypeText(type) {
  return ({ INIT: '期初库存', INBOUND: '采购入库', CONSUME: '订单扣减与出库',
    CANCEL_RESTORE: '取消回补', REFUND_RESTORE: '退款回补', ADJUST: '盘点调整' })[type] || '变动类型待核实'
}

function barrelModeText(mode) {
  return ({ RETURN_EMPTY: '补同型空桶', SETTLE_BARREL: '按约定折款' })[mode] || '桶交接方案待核实'
}

module.exports = { inventoryTypeText, barrelModeText }
