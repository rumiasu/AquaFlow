import {esc, facts, money, positive, rows, screen, table} from './desktop.js';
import {inventory} from './inventory.js';

const REASONS = [['customer_kept','客户留存'],['lost','路上丢失'],['damaged','破损'],['wrong','送错'],['other','其他']];

export function completeRequest(order,form) {
  if (order.status !== 2 || typeof order.needCollect !== 'boolean' || order.stockPrep && order.stockPrep.ready !== true) throw new Error('当前配送或备货状态待核实');
  const items = rows(order.items);
  if (!items.length || items.some(item => typeof item.barrelItem !== 'boolean')) throw new Error('订单商品及回桶计划待核实');
  const barrels = order.firstBarrelOrder === true ? [] : items.filter(row => row.barrelItem === true);
  const itemReturns = barrels.map(item => {
    const quantity = form.get(`actual-${item.id}`);
    const expected = item.suggestedReturnQty;
    if (!positive(item.id) || !Number.isInteger(expected) || expected < 0 || !/^\d+$/.test(quantity ?? '') || Number(quantity) > 2147483647) throw new Error('请逐项核对实际回桶数量');
    const actual = Number(quantity);
    const reasons = REASONS.flatMap(([key]) => {
      const value = form.get(`${item.id}-${key}`);
      if (!value || value === '0') return [];
      if (!positive(value)) throw new Error('异常原因数量须为正整数');
      return [{key,qty:Number(value)}];
    });
    if (reasons.reduce((sum,row) => sum + row.qty,0) > Math.max(0,expected - actual)) throw new Error('异常原因的数量不能超过少回差额');
    return {orderItemId:item.id,productName:item.productNameSnapshot ?? item.productName,expected,actual,reasons};
  });
  const cash = form.get('cash');
  if (order.needCollect && !['received','pending'].includes(cash)) throw new Error('请选择实际收款情况');
  if (order.needCollect && order.hasOrderBarrelPurchase === true && cash !== 'received') throw new Error('收齐本单应收款后才能登记交桶');
  const floor = form.get('floor');
  if (floor && (!positive(floor) || Number(floor) > 200)) throw new Error('请填写实际楼层，范围为 1 至 200');
  return {itemReturns,collected:order.needCollect ? cash === 'received' : true,reportedFloor:floor ? Number(floor) : null,note:String(form.get('note') ?? '').trim()};
}

export function fulfillment(context,order,refresh) {
  const {user,container} = context;
  const {listen,submit} = screen(context);
  const host = container.querySelector('#fulfillment');
  if (!positive(order.id) || Number(order.deliveryStationId ?? order.stationId) !== Number(user.stationId)) return;
  const path = `/api/delivery/orders/${order.id}`;
  const ordinaryAccept = order.status === 1 && Number(order.stationId) === Number(user.stationId) &&
    order.dispatchAgreement?.status !== 'OFFERED' && !order.deliveryStaffId && (order.paymentStatus === 2 || order.paymentMethod === 2);
  if (ordinaryAccept) {
    host.innerHTML = '<div class="actions"><button id="self-accept">站长接单配送</button></div>';
    listen('#self-accept','click',async () => {
      if (await submit(`${path}/accept`,'POST',undefined,`由当前站长接单配送 #${order.id}？`)) await refresh();
    });
  }
  if ([1,2].includes(order.status) && order.stockPrep && order.stockPrep.ready !== true) {
    const shortages = Array.isArray(order.stockPrep.items) ? order.stockPrep.items.filter(item => item && positive(item.productId) && positive(item.shortage)) : [];
    const buttons = shortages.map(item => {
      const product = Array.isArray(order.items) && order.items.find(row => String(row.productId) === String(item.productId));
      const label = [item.productName || product?.productNameSnapshot || ('商品 #' + item.productId), product?.specSnapshot || product?.spec].filter(Boolean).join(' · ');
      return `<button data-restock="${esc(item.productId)}">补货 · ${esc(label)}（缺 ${esc(item.shortage)}）</button>`;
    }).join('');
    host.insertAdjacentHTML('beforeend', `<div class="actions"><strong>本单待备货</strong>${buttons}<button id="check-order-stock">重新核对</button></div><div id="order-stock-editor"></div>`);
    listen('#check-order-stock','click',refresh);
    listen('[data-restock]','click',async button => {
      const item = shortages.find(row => String(row.productId) === button.dataset.restock);
      if (!item) return;
      const editor = host.querySelector('#order-stock-editor');
      // 另一规格或另一订单被打开时，旧库存读取不能覆盖当前表单。
      const pane = document.createElement('div');
      editor.replaceChildren(pane);
      await inventory({...context,container:pane,current:() => context.current() && pane.isConnected},
        {productId:item.productId,orderId:order.id,back:refresh});
    });
    return;
  }
  if (order.status !== 2) return;
  if (typeof order.needCollect !== 'boolean' || !Array.isArray(order.items) || !order.items.length || order.items.some(item => typeof item.barrelItem !== 'boolean')) return;
  const amount = order.paymentMethod === 3 ? order.collectAmount : order.totalAmount;
  if (order.needCollect && money(amount) === '待核实') return;
  const barrels = order.firstBarrelOrder === true ? [] : order.items.filter(item => item.barrelItem === true);
  if (barrels.some(item => !positive(item.id) || !Number.isInteger(item.suggestedReturnQty) || item.suggestedReturnQty < 0)) return;
  host.innerHTML = `<form id="finish-delivery" class="panel"><h2>登记送达</h2>
    ${facts([['收货人',order.receiverName],['付款状态',order.payStateText]])}
    ${order.needCollect ? `<fieldset><legend>本单实收情况 · ${money(amount)}</legend><label><input type="radio" name="cash" value="received" required> 已收齐</label>
      ${order.hasOrderBarrelPurchase === true ? '<p>本单有新增押金，需收齐后交桶。</p>' : '<label><input type="radio" name="cash" value="pending"> 已送达，尚未收款</label>'}</fieldset>` : ''}
    ${barrels.length ? table(['商品','应回空桶','实回空桶','少回原因（选填）'],barrels.map(item => [esc(item.productNameSnapshot ?? item.productName),esc(item.suggestedReturnQty),
      `<input type="number" aria-label="${esc(item.productNameSnapshot ?? item.productName)}实回空桶" name="actual-${item.id}" min="0" max="2147483647" step="1" required>`,
      `<details><summary>填写原因</summary>${REASONS.map(([key,label]) => `<label class="field">${label}<input type="number" name="${item.id}-${key}" min="0" max="${item.suggestedReturnQty}" step="1"></label>`).join('')}</details>`])) : ''}
    <label class="field">实际配送楼层（选填）<input type="number" name="floor" min="1" max="200" step="1"></label>
    <label class="field">配送备注（选填）<textarea name="note" rows="2" maxlength="500"></textarea></label><button>登记本单送达</button></form>`;
  listen('#finish-delivery','submit',async form => {
    const body = completeRequest(order,new FormData(form));
    const cash = order.needCollect ? (body.collected ? `已收齐 ${money(amount)}` : `尚未收款 ${money(amount)}`) : order.payStateText;
    const returns = body.itemReturns.map(item => `${item.productName} 实回 ${item.actual} 桶`).join('\n');
    if (await submit(`${path}/complete`,'POST',body,`订单 #${order.id} 已送达？\n${cash ?? ''}\n${returns}`)) await refresh();
  });
}
