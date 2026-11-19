import {esc, facts, money, original, params, positive, rows, screen, table} from './desktop.js';

export async function owedBarrels(context) {
  const {api,container,openAssets} = context;
  const {show,listen} = screen(context);
  async function load(minDays) {
    const list = rows(await api.get(params('/api/manager/owed-barrels',{minDays})));
    show(`<form class="toolbar" id="owed-filter"><label>欠桶时间<select name="days"><option value="0">全部</option><option value="7" ${minDays === '7' ? 'selected' : ''}>至少 7 天</option></select></label><button>查询</button></form>` +
      table(['客户','电话','商品','欠桶','起始日期','天数','操作'],list.map(row => [esc(row.customerName),esc(row.phone),esc(row.productName),esc(row.overQty),esc(row.owedSinceText),esc(row.owedDaysText),
        positive(row.customerId) ? `<button data-asset="${row.customerId}">查看桶账</button>` : '待核实'])));
    listen('#owed-filter','submit',() => load(container.querySelector('[name=days]').value));
    listen('[data-asset]','click',button => openAssets(button.dataset.asset,() => load(minDays)));
  }
  await load('0');
}

export async function customerAssets(context, customerId, back) {
  if (!positive(customerId)) throw new Error('客户编号待核实');
  const {api,user,container,current} = context;
  const {show,listen,submit} = screen(context);
  const ledger = original(user,`return-empty:${customerId}`);
  const asset = await api.get(`/api/customers/${customerId}/assets`);
  const barrels = rows(asset.barrels);
  const tickets = rows(asset.tickets);
  const records = rows(asset.records);
  const pending = ledger.read();
  const returnable = barrels.filter(row => positive(row.productId) && positive(row.occupiedQty));
  show(`<button id="assets-back">返回</button><button id="assets-profile">客户资料和货到付款</button><div id="assets-profile-content"></div><section class="panel"><h2>客户 #${esc(customerId)} 的本站桶账</h2>${facts([
    ['押金余额',money(asset.depositBalance)],['客户实物桶数',asset.actualBuckets],['配送中桶数',asset.inTransitBuckets],['欠桶',asset.owedBuckets],['水票张数',asset.ticketQuantity]
  ])}</section>${table(['商品','桶权益','占用','欠桶／暂存','押金'], barrels.map(row => [esc(row.productName),esc(row.heldQty),esc(row.occupiedQty),esc(row.overQty),money(row.depositAmount)]))}
    ${pending ? `<section class="panel"><p>上次实收空桶登记待核对。${pending.items.map(row => `商品 #${esc(row.productId)} × ${esc(row.qty)}`).join('、')}</p><button id="retry-return">重试原登记</button></section>` : returnable.length ? `<form id="return-empty" class="panel"><h2>登记已收到的空桶</h2><p>按实际交回数量填写。此项登记只更新桶账。</p>
    ${returnable.map(row => `<label class="field">${esc(row.productName)}（最多 ${row.occupiedQty} 桶）<input name="item-${row.productId}" type="number" min="0" max="${row.occupiedQty}" step="1" value="0" required></label>`).join('')}<button>登记实收空桶</button></form>` : ''}
    <h2>水票</h2>${table(['商品','剩余张数','单价','金额'],tickets.map(row => [esc(row.productName),esc(row.remainQuantity),money(row.unitPrice),money(row.totalValue)]))}
    <h2>资产流水</h2>${table(['时间','类型','商品','数量','金额','备注'],records.map(row => [esc(row.createTime),esc(row.typeText ?? row.recordTypeText),esc(row.productName),esc(row.quantity),money(row.amount),esc(row.note)]))}`);
  listen('#assets-back','click',back);
  listen('#assets-profile','click',async button => {
    const root = `/api/customers/${customerId}`;
    const [profile,payment] = await Promise.all([api.get(`${root}/profile`),api.get(`${root}/offline-payment/summary`)]);
    if (!current() || !button.isConnected) return;
    const known = [0,1].includes(payment.offlinePaymentEnabled);
    const enabled = payment.offlinePaymentEnabled === 1;
    container.querySelector('#assets-profile-content').innerHTML = `<section class="panel"><h2>本站客户资料</h2>${facts([['姓名',profile.name],['电话',profile.phone],['默认地址',profile.defaultAddress],['本站订单',payment.orderCount],
      ['货到付款授权',known ? (enabled ? '已开通' : '未开通') : '待核实'],['逾期订单',payment.overdueCount],['逾期金额',money(payment.overdueAmount)],['当前可用性',payment.usable === true ? '可用' : payment.blockReason]])}
      ${known ? `<button id="toggle-cod">${enabled ? '关闭' : '开通'}货到付款</button>` : ''}</section>`;
    listen('#toggle-cod','click',async () => {
      if (await submit(`${root}/offline-payment`,'PUT',{offlinePaymentEnabled:enabled ? 0 : 1},`${enabled ? '关闭' : '开通'} ${profile.name} 在本站的货到付款授权？${payment.blockReason ? `\n${payment.blockReason}` : ''}`)) await customerAssets(context,customerId,back);
    });
  });
  listen('#retry-return','click',() => send(pending,true));
  listen('#return-empty','submit',async form => {
    if (ledger.read()) throw new Error('请先核对原登记');
    const data = new FormData(form);
    const items = returnable.map(row => ({productId:Number(row.productId),qty:Number(data.get(`item-${row.productId}`)),max:Number(row.occupiedQty)}));
    if (items.some(row => !Number.isSafeInteger(row.qty) || row.qty < 0 || row.qty > row.max)) throw new Error('请核对实收桶数');
    const accepted = items.filter(row => row.qty > 0).map(({productId,qty}) => ({productId,qty}));
    if (!accepted.length) throw new Error('请填写已收到的空桶数量');
    if (!confirm('确认已收到以下空桶？\n' + accepted.map(row => `${returnable.find(p => Number(p.productId) === row.productId).productName} × ${row.qty}`).join('\n'))) return;
    const intent = {customerId:Number(customerId),items:accepted,clientToken:`RE-${crypto.randomUUID()}`,note:'站长代客还空桶'};
    ledger.save(intent);
    await send(intent,false);
  });
  async function send(intent, replay) {
    try {
      const receipt = await api.command('/api/barrels/return-empty','POST',intent);
      if (receipt?.duplicate !== true && !(Array.isArray(receipt?.changes) && receipt.changes.length === intent.items.length &&
          intent.items.every(row => receipt.changes.some(change => Number(change.productId) === row.productId && Number(change.qty) === row.qty)))) {
        throw new Error('还桶登记回执待核实，请重试原登记');
      }
      ledger.clear();
    } catch (error) {
      if (!replay && error.businessRejected) ledger.clear();
      if (current()) await customerAssets(context,customerId,back);
      throw error;
    }
    if (current()) await customerAssets(context,customerId,back);
  }
}
