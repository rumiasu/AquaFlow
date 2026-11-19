import {esc, facts, money, params, positive, rows, screen, table} from './desktop.js';

export async function profit(context) {
  const {api,container} = context;
  const {show,listen} = screen(context);
  async function load(query = {}) {
    const report = await api.get(params('/api/manager/gross-profit',query));
    const comparable = report.costComplete === true && report.profitComparable === true && report.netProfit != null;
    show(`<form class="toolbar" id="profit-filter"><label>开始日期<input name="from" type="date" value="${esc(report.from)}" required></label><label>结束日期<input name="to" type="date" value="${esc(report.to)}" required></label><button>查询</button></form>
      <section class="panel">${facts([['订单销售收入',money(report.orderIncome)],['购票实收',money(report.ticketRevenue)],['实际出库成本',money(report.totalCost)],['计件工资',money(report.wage)],['净利润',comparable ? money(report.netProfit) : '暂不展示']])}
      ${[report.incomeBasisNote,report.periodBasisNote,comparable ? report.profitBasisNote : report.profitUnavailableReason || report.missingCostHint].filter(Boolean).map(note => `<p>${esc(note)}</p>`).join('')}</section>` +
      table(['商品','订单水费','购票实收','出库成本','利润'],rows(report.items).map(row => [esc(row.productName),money(row.revenue),money(row.ticketRevenue),money(row.costAmount),comparable ? esc(row.profitText) : '—'])));
    listen('#profit-filter','submit',() => {
      const fields = Object.fromEntries(new FormData(container.querySelector('#profit-filter')));
      if (fields.from > fields.to) throw new Error('结束日期不能早于开始日期');
      return load(fields);
    });
  }
  await load();
}

export async function receivables(context) {
  const {api,openOrder,current,container} = context;
  const {show,listen,submit} = screen(context);
  const path = '/api/manager/receivables';
  async function load() {
    const summary = await api.get(path);
    const customers = rows(summary.customers);
    show(`<section class="panel">${facts([['待收合计',money(summary.outstandingAmount)],['逾期金额',money(summary.overdueAmount)]])}</section>` +
      table(['客户','待收','逾期','最早应付日','操作'],customers.map(row => [esc(row.customerName),money(row.outstandingAmount),money(row.overdueAmount),esc(row.earliestDueDate?.slice(0,10)),
        positive(row.customerId) ? `<button data-receivable="${row.customerId}">核对订单</button>` : '待核实'])));
    listen('[data-receivable]','click',button => detail(customers.find(row => String(row.customerId) === button.dataset.receivable)));
  }
  async function detail(customer) {
    const orders = rows(await api.get(params(`${path}/orders`,{customerId:customer.customerId})));
    const canCollect = row => positive(row.orderId) && Number(row.customerId) === Number(customer.customerId) && row.canRegisterCash === true &&
      row.paymentStatus === 1 && row.settlementStatus === 1 && [1,2,3,4].includes(row.status) && /^\d+(\.\d{1,2})?$/.test(String(row.totalAmount));
    show(`<button id="receivable-back">返回台账</button><button id="receivable-terms">账期与信用风险</button><div id="credit-terms"></div><h2>${esc(customer.customerName)}的待收订单</h2><form id="receivable-collect">` +
      table(['选择','订单','金额','应付日','收款方式','操作'],orders.map(row => [canCollect(row) ? `<input type="checkbox" aria-label="选择订单 ${row.orderId}" name="orders" value="${row.orderId}">` : '—',
        esc(row.orderNo ?? row.orderId),money(row.totalAmount),esc(row.dueDate?.slice(0,10)),`${esc(row.collectionMethodText)}${canCollect(row) ? '' : `<br>${esc(row.collectionBlockReason)}`}`,
        row.canViewOrder === true && positive(row.orderId) ? `<button type="button" data-receivable-order="${row.orderId}">查看订单</button>` : '—'])) +
      (orders.some(canCollect) ? '<div class="actions"><button>登记所选现金已收</button></div>' : '') + '</form>');
    listen('#receivable-back','click',load);
    listen('#receivable-terms','click',async button => {
      const root = `/api/manager/customers/${customer.customerId}`;
      const [terms,risk] = await Promise.all([api.get(`${root}/credit-terms`),api.get(`${root}/risk`)]);
      if (!current() || !button.isConnected) return;
      container.querySelector('#credit-terms').innerHTML = `<section class="panel">${facts([['当前风险',risk.levelText],['情况',risk.note],['当前账期（天）',terms.dueDays]])}
        <form id="credit-save" class="toolbar"><label>账期天数（0 为现结）<input name="days" type="number" min="0" step="1" required value="${esc(terms.dueDays ?? 0)}"></label><button>保存新订单账期</button></form>
        <button id="credit-recalc">重算已有未结挂账单日期</button></section>`;
      listen('#credit-save','submit',async form => {
        const days = Number(new FormData(form).get('days'));
        if (!Number.isSafeInteger(days) || days < 0) throw new Error('请填写有效账期');
        if (await submit(`${root}/credit-terms`,'PUT',{dueDays:days},`将 ${customer.customerName} 后续订单的账期设为 ${days ? days+' 天' : '现结'}？`)) await detail(customer);
      });
      listen('#credit-recalc','click',async () => {
        if (await submit(`${root}/credit-terms/recalculate`,'POST',undefined,`按当前账期重算 ${customer.customerName} 的未结挂账单应付日？`)) await detail(customer);
      });
    });
    listen('[data-receivable-order]','click',button => openOrder(button.dataset.receivableOrder,() => detail(customer)));
    listen('#receivable-collect','submit',async form => {
      const ids = [...new Set(new FormData(form).getAll('orders'))];
      const selected = ids.map(id => orders.find(row => String(row.orderId) === id));
      if (!selected.length || selected.some(row => !row || !canCollect(row))) throw new Error('请核对要登记的订单');
      if (await submit(`${path}/settle`,'POST',{customerId:customer.customerId,orderIds:ids.map(Number)},`确认 ${customer.customerName} 以下订单的现金均已实收？\n${selected.map(row => `#${row.orderNo ?? row.orderId} ${money(row.totalAmount)}`).join('\n')}`)) await load();
    });
  }
  await load();
}
