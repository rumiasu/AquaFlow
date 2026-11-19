import {editPromotion} from './promotion.js';
import {esc, money, original, params, positive, rows, screen, table} from './desktop.js';

const CATALOG = '/api/manager/catalog';
const exactCost = value => /^\d+(\.\d{1,2})?$/.test(value) && Number(value) <= 99999999.99;

export async function inventory(context, fromOrder) {
  const {api,user,current,container} = context;
  const {show,listen,submit} = screen(context);
  const saved = original(user,'stock-inbound');
  async function load() {
    const catalog = rows(await api.get(CATALOG));
    if (!current()) return;
    const pending = saved.read();
    if (fromOrder) {
      const row = catalog.find(item => String(item.id) === String(fromOrder.productId));
      const sameProduct = pending && pending.items?.length === 1 && String(pending.items[0].productId) === String(fromOrder.productId);
      show(`<button id="back-to-order">返回订单 #${esc(fromOrder.orderId)}</button><div id="stock-editor"></div>` +
        (sameProduct ? '<button id="stock-retry">核对上次入库</button>' : pending ? '<button id="stock-original">去商品库存核对另一笔入库</button>' : row?.selected !== true ? '<p>该规格当前未在本站选用，请核对商品。</p>' : ''));
      listen('#back-to-order','click',fromOrder.back);
      if (sameProduct) listen('#stock-retry','click',() => inbound(pending,true));
      if (pending && !sameProduct) listen('#stock-original','click',context.openInventory);
      if (!pending && row?.selected === true) await editProduct(row,'inbound','进货入库');
      return;
    }
    show(`<div class="actions"><button id="stock-history">库存流水</button>${pending ? '<button id="stock-retry">核对上次入库</button>' : ''}</div><div id="stock-editor"></div>` +
      table(['商品','实物库存','售价','状态','操作'], catalog.map(row => [esc([row.name,row.spec].filter(Boolean).join(' · ')),esc(row.quantity),money(row.effectivePrice),row.selected !== true ? '未选用' : row.enabled === 1 ? '已上架' : '未上架',
        !positive(row.id) ? '待核实' : row.selected === true ? `<button data-stock="${row.id}:inbound" ${pending ? 'disabled' : ''}>进货入库</button><button data-stock="${row.id}:stock">盘点</button><button data-stock="${row.id}:settings">本站销售设置</button><button data-stock="${row.id}:promotion">站内促销</button>` : `<button data-stock="${row.id}:select">选用</button>`])));
    listen('#stock-history','click',history);
    listen('#stock-retry','click',() => inbound(pending,true));
    listen('[data-stock]','click',async button => {
      const [id,action] = button.dataset.stock.split(':');
      const row = catalog.find(item => String(item.id) === id);
      if (row) await editProduct(row,action,button.textContent);
    });
  }
  async function editProduct(row,action,label) {
      const id = row.id;
      if (action === 'promotion') return editPromotion(context,row,load);
      if (action === 'select') {
        if (await submit(`${CATALOG}/${id}/select`,'POST',{},`选用 ${row.name}？选用后可设置本站售价与库存。`)) await load();
        return;
      }
      const settings = action === 'settings', adjust = action === 'stock';
      container.querySelector('#stock-editor').innerHTML = `<form class="panel" id="stock-edit"><h2>${esc([row.name,row.spec].filter(Boolean).join(' · '))} · ${esc(label)}</h2>${settings ?
        `<label class="field">上架状态<select name="enabled"><option value="0">未上架</option><option value="1" ${row.enabled === 1 ? 'selected' : ''}>上架</option></select></label>
         <label class="field">本站售价（留空保持，0 恢复参考价）<input type="number" name="price" min="0" step="0.01" value="${esc(row.salePrice ?? '')}"></label>` :
        `<label class="field">${adjust ? '实物盘点总数' : '本次入库数量'}<input type="number" name="qty" min="${adjust ? 0 : 1}" max="2147483647" step="1" required></label>` + (adjust ?
          '<label class="field">盘点说明<input name="note" maxlength="200"></label>' : '<label class="field">本批总成本（留空表示未记录）<input name="cost" type="number" min="0" step="0.01"></label>')}<button>确认</button></form>`;
      listen('#stock-edit','submit',async form => {
        const fields = new FormData(form);
        if (settings) {
          const price = fields.get('price');
          if (price && !exactCost(price)) throw new Error('请核对本站售价');
          if (await submit(`${CATALOG}/${id}`,'PUT',{enabled:Number(fields.get('enabled')),...(price ? {salePrice:price} : {})},`保存 ${row.name} 的本站销售设置？`)) await load();
          return;
        }
        const quantity = Number(fields.get('qty'));
        if (!Number.isInteger(quantity) || quantity < (adjust ? 0 : 1) || quantity > 2147483647) throw new Error('请核对数量');
        if (adjust) {
          if (await submit(`${CATALOG}/${id}/stock`,'POST',{target:quantity,note:fields.get('note').trim()},`将 ${row.name} 的实物库存盘点为 ${quantity}？`)) await load();
          return;
        }
        const cost = fields.get('cost');
        if (cost && !exactCost(cost)) throw new Error('请核对本批总成本');
        if (saved.read()) throw new Error('请先核对上次入库');
        if (!confirm(`登记 ${row.name} 入库 ${quantity} 件？\n${cost ? '本批总成本 '+money(cost) : '本批成本未记录'}`)) return;
        const body = {clientRequestId:crypto.randomUUID(),items:[{productId:Number(id),quantity,costAmount:cost || null}]};
        saved.save(body);
        await inbound(body,false);
      });
  }
  async function inbound(body,replay) {
    try {
      const receipt = await api.command(params('/api/inventory/inbound-with-cost-receipt',{stationId:user.stationId}),'POST',body);
      const expected = body.items[0], recorded = receipt?.items?.[0];
      if (receipt?.status !== 'APPLIED' || receipt.clientRequestId !== body.clientRequestId || String(receipt.stationId) !== String(user.stationId) ||
          receipt.items?.length !== 1 || recorded.productId !== expected.productId || recorded.quantity !== expected.quantity ||
          (expected.costAmount == null ? recorded.costAmount != null : recorded.costAmount == null || Number(recorded.costAmount) !== Number(expected.costAmount))) throw new Error('入库回执待核实');
      saved.clear();
    } catch (error) {
      if (!replay && error.businessRejected && !/编号|重复/.test(error.message)) saved.clear();
      if (current()) await load();
      throw error;
    }
    if (current()) {
      if (fromOrder && String(body.items[0].productId) === String(fromOrder.productId)) await fromOrder.back();
      else await load();
    }
  }
  async function history() {
    const items = rows(await api.get('/api/inventory/records?limit=200'));
    show('<button id="inventory-back">返回库存</button>' + table(['时间','商品','变动','数量','成本','说明'],items.map(row => [esc(row.createTime),esc(row.productName ?? row.productId),esc(row.typeText ?? row.type),esc(row.delta),money(row.costAmount),esc(row.note)])));
    listen('#inventory-back','click',load);
  }
  await load();
}
