import {salePriceLabel} from './sales-view.js';
import {esc, facts, money, original, params, positive, rows, screen, table} from './desktop.js';

const ASSIST = '/api/manager/order-assist';
const amountKnown = value => value !== '' && value != null && Number.isFinite(Number(value)) && Number(value) >= 0;

export function makeOrderRequest(user, customerId, quoteInput, quote, key) {
  if (quote.blocked !== false || !amountKnown(quote.totalAmount) || !rows(quote.methods).some(method =>
    method.id === quoteInput.paymentMethod && method.enabled === true)) throw new Error('请核对可用付款方式与服务端报价');
  const purchases = rows(quote.barrelPurchases ?? (quote.independentRights === true ? null : []));
  const ids = new Set(purchases.map(row => row.productId));
  if (ids.size !== purchases.length || purchases.some(row => !positive(row.quantity) || !amountKnown(row.unitPrice) ||
    Number(row.unitPrice) === 0 || !amountKnown(row.amount) || Math.abs(Number(row.amount) - Number(row.unitPrice) * row.quantity) > 0.009 ||
    !quoteInput.items.some(line => line.productId === row.productId && line.quantity >= row.quantity)) ||
    quote.independentRights === true && (!amountKnown(quote.extraDeposit) || Math.abs(purchases.reduce((sum,row) => sum + Number(row.amount),0) - Number(quote.extraDeposit)) > 0.009)) {
    throw new Error('新增押金明细待核实，请重新试算');
  }
  const body = {...quoteInput, customerId:Number(customerId), stationId:Number(user.stationId), source:1, idempotencyKey:key,
    barrelPurchases:purchases.map(({productId,quantity,unitPrice}) => ({productId,quantity,unitPrice:Number(unitPrice)}))};
  if (body.paymentMethod === 3) {
    if (quote.ticketFeeAmount != null && (!amountKnown(quote.ticketFeeAmount) || !amountKnown(quote.ticketDeliveryCredit) ||
        Number(quote.ticketFeeAmount) > 0 && !rows(quote.ticketFeeMethods).some(method => method.id === quote.ticketFeePaymentMethod && method.enabled === true))) {
      throw new Error('水票余款的金额或付款方式待核实，请重新试算');
    }
    Object.assign(body,{ticketFeePaymentMethod:quote.ticketFeePaymentMethod,ticketFeeQuoteAmount:quote.ticketFeeAmount,ticketDeliveryCreditQuote:quote.ticketDeliveryCredit});
  }
  return body;
}

export async function placeOrder(context) {
  const {api,user,current,container,openOrder} = context;
  const {show,listen} = screen(context);
  const storage = original(user,'assisted-order');
  const options = '<form class="toolbar" id="customer-search"><label>查找代下单客户<input name="keyword" placeholder="姓名、电话或地址"></label><button>查询</button></form>';
  async function search(keyword = '') {
    const pending = storage.read();
    if (pending) return recovery(pending);
    const customers = rows(await api.get(params(`${ASSIST}/customers`,{keyword})));
    show(options + table(['客户','电话','地址',''],customers.map(row => [esc(row.name),esc(row.phone),esc(row.matchedAddressText ?? row.addressText),
      positive(row.id) ? `<button data-customer="${row.id}">选此客户</button>` : '待核实'])));
    if (!current()) return;
    container.querySelector('[name=keyword]').value = keyword;
    listen('#customer-search','submit',form => search(new FormData(form).get('keyword').trim()));
    listen('[data-customer]','click',button => select(customers.find(row => String(row.id) === button.dataset.customer)));
  }
  async function select(customer) {
    const [addresses,products] = (await Promise.all([
      api.get(`${ASSIST}/customers/${customer.id}/addresses`),api.get(params('/api/products/sale-by-station',{stationId:user.stationId}))
    ])).map(rows);
    if (!current()) return;
    show(`<button id="back-search">重选客户</button><form class="panel" id="order-draft"><h2>${esc(customer.name)}的订单</h2>
      <label class="field">收货地址<select name="address" required><option value="">请选择</option>${addresses.filter(row => positive(row.id)).map(row => `<option value="${row.id}">${esc([row.name,row.phone,row.detail].filter(Boolean).join(' · '))}</option>`).join('')}</select></label>
      ${table(['商品','参考价','数量'],products.filter(row => positive(row.id)).map(row => [`${esc(row.name)} ${esc(row.spec ?? '')}`,salePriceLabel(row),`<input aria-label="${esc(row.name)}数量" type="number" name="product-${row.id}" min="0" step="1" max="2147483647" value="0" required>`]))}
      <div class="actions" id="order-methods"></div><button>试算费用</button></form><div id="order-quote"></div>`);
    const draft = container.querySelector('#order-draft');
    const quoteBox = container.querySelector('#order-quote');
    let revision = 0, selectedMethod = 2, feeMethod, ready;
    function invalidate() { revision++; ready = null; quoteBox.replaceChildren(); }
    draft.addEventListener('input',invalidate);
    listen('#back-search','click',() => search());
    async function quoteOrder(allowDefault = true) {
      if (storage.read()) throw new Error('请先核对上次订单');
      invalidate();
      const version = revision;
      const data = new FormData(draft);
      const items = products.filter(row => positive(row.id)).map(row => ({productId:Number(row.id),quantity:Number(data.get(`product-${row.id}`))})).filter(row => row.quantity > 0);
      if (!positive(data.get('address')) || !items.length || !draft.reportValidity()) throw new Error('请选择地址和商品，核对数量');
      const input = {addressId:Number(data.get('address')),items,paymentMethod:selectedMethod,...(selectedMethod === 3 ? {ticketFeePaymentMethod:feeMethod} : {})};
      const quote = await api.command(params(`${ASSIST}/quote`,{customerId:customer.id}),'POST',{...input,stationId:Number(user.stationId)});
      if (!current() || version !== revision || !draft.isConnected) return;
      const methods = rows(quote.methods);
      if (allowDefault && !methods.some(row => row.id === selectedMethod && row.enabled === true)) {
        const replacement = methods.find(row => row.id === quote.defaultMethod && row.enabled === true) ?? methods.find(row => row.enabled === true);
        if (replacement && replacement.id !== selectedMethod) { selectedMethod = replacement.id; return quoteOrder(false); }
      }
      feeMethod = quote.ticketFeePaymentMethod;
      const selector = (list, attr, selected) => list.map(row => positive(row.id) ? `<button type="button" ${attr}="${row.id}" ${row.enabled !== true || row.id === selected ? 'disabled' : ''}>${esc(row.name ?? row.text ?? row.label ?? row.id)}${row.id === selected ? '（当前）' : ''}</button>` : '').join('');
      container.querySelector('#order-methods').innerHTML = selector(methods,'data-method',selectedMethod) + selector(quote.ticketFeeMethods ?? [],'data-fee-method',feeMethod);
      listen('[data-method]','click',button => {selectedMethod = Number(button.dataset.method); feeMethod = undefined; return quoteOrder(false);});
      listen('[data-fee-method]','click',button => {feeMethod = Number(button.dataset.feeMethod); return quoteOrder(false);});
      const purchases = rows(quote.barrelPurchases ?? []);
      let request;
      try { request = makeOrderRequest(user,customer.id,input,quote,crypto.randomUUID()); }
      catch (error) { if (!quote.blocked && methods.some(row => row.id === selectedMethod && row.enabled === true)) throw error; }
      quoteBox.innerHTML = `<section class="panel"><h2>服务端报价</h2>${facts([['水费',money(quote.waterAmount)],['配送费',money(quote.deliveryFee)],['楼层费',money(quote.floorFee)],['新增押金',money(quote.extraDeposit)],['总额',money(quote.totalAmount)],...(quote.ticketFeeAmount == null ? [] : [['用票后应付余款',money(quote.ticketFeeAmount)]])])}
        ${(quote.warnings ?? []).map(warning => `<p>${esc(warning)}</p>`).join('')}${quote.blockReason ? `<p role="alert">${esc(quote.blockReason)}</p>` : ''}
        ${purchases.map(row => `<p>${esc(row.productName)} · 新增押金 ${esc(row.quantity)} 份，${money(row.amount)}</p>`).join('')}
        ${request ? '<button id="create-order">确认下单</button>' : '<p>当前报价无法提交，请核对付款方式或配送条件。</p>'}</section>`;
      ready = request;
      listen('#create-order','click',async () => {
        if (ready !== request || storage.read()) throw new Error('请重新核对报价或原订单');
        if (!confirm(`为 ${customer.name} 提交 ${money(quote.totalAmount)} 的订单？${purchases.length ? '\n请确认客户同意逐项新增押金。' : ''}`)) return;
        storage.save(request);
        await send(request,false);
      });
    }
    listen('#order-draft','submit',() => quoteOrder());
  }

  function recovery(body, shortages) {
    const lines = rows(body.items).map(row => `商品 #${esc(row.productId)} × ${esc(row.quantity)}`).join('、');
    show(`<section class="panel"><h2>${shortages ? '库存不足' : '核对原订单'}</h2><p>客户 #${esc(body.customerId)} · ${lines}</p>
      ${shortages ? `<ul>${shortages.map(row => `<li>${esc(row.productName)} · 需要 ${esc(row.requested)}，可用 ${esc(row.stock)}</li>`).join('')}</ul><button id="allow-shortage">确认按缺货订单提交</button><button id="abandon-shortage">返回重选</button>` : '<p>上次下单回执待确认。重试会使用原请求编号。</p><button id="retry-order">重试原订单</button>'}</section>`);
    listen('#retry-order','click',() => send(body,true));
    listen('#allow-shortage','click',() => {
      if (!confirm('确认客户同意按缺货状态下单？')) return;
      const confirmed = {...body,confirmShortage:true}; storage.save(confirmed); return send(confirmed,false);
    });
    listen('#abandon-shortage','click',() => { storage.clear(); return search(); });
  }

  async function send(body,replay) {
    let result;
    try {
      result = await api.command('/api/orders/create','POST',body);
      if (result?.needConfirm === true && !body.confirmShortage) {
        const shortages = rows(result.shortages);
        if (current()) recovery(body,shortages);
        return;
      }
      if (!positive(result?.orderId)) throw new Error('订单编号待核实，请重试原请求');
      storage.clear();
    } catch (error) {
      if (!replay && error.businessRejected && !/幂等|重复|已提交过|请求摘要|内容不同/.test(error.message)) storage.clear();
      if (current() && storage.read()) recovery(body);
      throw error;
    }
    if (!current()) return;
    show(`<section class="panel"><h2>订单 #${esc(result.orderId)} 已创建</h2>${(result.warnings ?? []).map(warning => `<p>${esc(warning)}</p>`).join('')}
      <button id="created-order">查看订单与付款状态</button><button id="another-order">继续下单</button></section>`);
    listen('#created-order','click',() => openOrder(result.orderId,search));
    listen('#another-order','click',() => search());
  }
  await search();
}
