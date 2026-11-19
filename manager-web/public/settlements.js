import {esc, facts, money, positive, rows, screen, table} from './desktop.js';

export async function settlements(context) {
  const {api,user,container,current} = context;
  const {show,listen,submit} = screen(context);
  const path = '/api/manager/inter-station-settlements';
  async function load() {
    const ledger = await api.get(path);
    const list = rows(ledger.items), dangling = rows(ledger.danglingSettled);
    const available = row => positive(row.orderId) && positive(row.fromStationId) && positive(row.toStationId) && money(row.amount) !== '待核实';
    const entries = (records,isDangling) => records.map(row => [esc(row.orderNo ?? row.orderId),
      `${esc(row.fromStationName)} → ${esc(row.toStationName)}<br>${esc(row.directionText)}`,
      money(row.amount),`${esc(row.basisText)} · ${esc(row.statusText)}${row.settledTime ? `<br>${esc(row.settledTime)} · ${esc(row.settleNote)}` : ''}`,
      available(row) ? (isDangling ? `<button data-ledger="reverse:${row.orderId}">登记冲销</button>` :
        `${row.canSettle === true && Number(user.stationId) === Number(row.fromStationId) ? `<button data-ledger="settle:${row.orderId}">登记结清</button>` : ''}
         ${row.canChangePrice === true ? `<button data-ledger="price-by-listed:${row.orderId}">改按挂牌价</button>` : ''}`) : '待核实']);
    const head = ['订单','款项方向','金额','口径与状态','操作'];
    show(`<section class="panel">${facts([['本站应收',money(ledger.receivableAmount)],['本站应付',money(ledger.payableAmount)],['净额',money(ledger.netAmount)]])}<p>${esc(ledger.scopeNote)}</p></section>` +
      table(head,entries(list,false)) + '<h2>需冲销的已结清记录</h2>' + table(head,entries(dangling,true)));
    listen('[data-ledger]','click',async button => {
      const [action,id] = button.dataset.ledger.split(':');
      const row = (action === 'reverse' ? dangling : list).find(item => String(item.orderId) === id);
      if (!row || !available(row)) throw new Error('金额或结算对象待核实');
      const label = button.textContent;
      const decision = container.querySelector('#settlement-decision');
      decision?.remove();
      const form = document.createElement('form');
      form.id = 'settlement-decision'; form.className = 'panel';
      form.innerHTML = `<h2>${esc(label)}</h2>${facts([['订单',id],['付款方',row.fromStationName],['收款方',row.toStationName],['当前金额',money(row.amount)]])}
        ${action === 'settle' ? '<label class="field">线下转账凭据或经手人（可选）<input name="note" maxlength="200"></label><p>请核实款项已在线下付给对方。</p>' : ''}<button>${esc(label)}</button>`;
      if (!current()) return;
      container.prepend(form); form.querySelector('input,button').focus();
      listen('#settlement-decision','submit',async () => {
        const body = action === 'settle' ? {note:new FormData(form).get('note').trim()} : undefined;
        if (await submit(`${path}/${id}/${action}`,'POST',body,`${label}订单 #${id}？\n${row.fromStationName} → ${row.toStationName}，当前 ${money(row.amount)}`)) await load();
      });
    });
  }
  await load();
}
