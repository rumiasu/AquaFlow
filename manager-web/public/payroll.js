import {esc, facts, money, original, params, positive, rows, screen, table} from './desktop.js';

const PATH = '/api/manager/payroll';
const RATE = '/api/manager/piece-rate', ITEMS = '/api/manager/earning-items';
const exact = value => /^\d+(\.\d{1,2})?$/.test(value);

export async function payroll(context) {
  const {api,user,container,current} = context;
  const {show,listen,submit} = screen(context);
  async function load(beforeId) {
    const [slips,people] = (await Promise.all([api.get(params(PATH,{limit:30,...(beforeId ? {beforeId} : {})})),api.get('/api/manager/staff')])).map(rows);
    const staff = people.filter(row => row.role === 'DELIVERY' && positive(row.id));
    const name = id => people.find(row => Number(row.id) === Number(id))?.name ?? `员工 #${id}`;
    show(`<div class="actions"><button id="payroll-rates">计件设置</button><button id="payroll-adjustments">人工加扣</button></div><form class="toolbar" id="payroll-preview"><label>配送员<select name="staffId" required><option value="">请选择</option>${staff.map(row => `<option value="${row.id}">${esc(row.name)}</option>`).join('')}</select></label>
      <label>开始日期<input name="periodStart" type="date" required></label><label>结束日期<input name="periodEnd" type="date" required></label><button>试算工资</button></form>
      <div id="payroll-result"></div><h2>结算单</h2>` + table(['编号','配送员','计件期间','金额','状态','操作'],slips.map(row => [esc(row.payrollNo ?? row.id),esc(name(row.staffId)),`${esc(row.periodStart)} 至 ${esc(row.periodEnd)}`,money(row.totalAmount),esc(row.statusText),
      positive(row.id) && positive(row.staffId) ? `<button data-wages="${row.id}">查看明细</button>${[1,2].includes(row.status) ? `<button data-payroll="${row.id}">核对后登记发薪</button>` : ''}` : '待核实'])) +
      `<div class="paging">${beforeId ? '<button id="newest-payroll">回到最新</button>' : ''}${slips.length === 30 && positive(slips.at(-1)?.id) ? '<button id="older-payroll">更早记录</button>' : ''}</div>`);
    if (!current()) return;
    const preview = container.querySelector('#payroll-result');
    const form = container.querySelector('#payroll-preview');
    let revision = 0;
    form.addEventListener('input',() => { revision++; preview.replaceChildren(); });
    listen('#newest-payroll','click',() => load());
    listen('#older-payroll','click',() => load(slips.at(-1).id));
    listen('#payroll-rates','click',() => rates());
    listen('#payroll-adjustments','click',() => adjustments(staff));
    listen('#payroll-preview','submit',async () => {
      const version = ++revision;
      const query = Object.fromEntries(new FormData(form));
      if (!positive(query.staffId) || !query.periodStart || !query.periodEnd || query.periodStart > query.periodEnd) throw new Error('请核对配送员与结算期间');
      const data = await api.get(params(`${PATH}/preview`,query));
      if (!current() || version !== revision || !preview.isConnected) return;
      const canSettle = data.complete === true && data.canSettle === true && typeof data.previewToken === 'string' && data.previewToken && money(data.settleAmount) !== '待核实';
      preview.innerHTML = `<section class="panel"><h2>工资试算</h2>${facts([['配送员',data.staffName],['结算期间',`${query.periodStart} 至 ${query.periodEnd}`],['待登记金额',money(data.settleAmount)],['已发金额',money(data.paidAmount)],['已出结算单未发',money(data.unpaidAmount)]])}
        ${data.warning ? `<p>${esc(data.warning)}</p>` : ''}${canSettle ? '<button id="pay-preview">登记已发工资</button>' : ''}</section>`;
      listen('#pay-preview','click',async () => {
        if (version !== revision) return;
        const body = {...query,staffId:Number(query.staffId),previewToken:data.previewToken,paidOffline:true};
        if (await submit(`${PATH}/settle`,'POST',body,`确认 ${data.staffName} ${query.periodStart} 至 ${query.periodEnd} 的 ${money(data.settleAmount)} 已在线下发放？`)) await load();
      });
    });
    listen('[data-payroll]','click',async button => {
      const data = await api.get(`${PATH}/${button.dataset.payroll}/settlement-preview`);
      if (!current() || !preview.isConnected) return;
      const row = data.payroll;
      if (data.canSettle !== true || !data.previewToken || String(row?.id) !== button.dataset.payroll || money(row.totalAmount) === '待核实') {
        throw new Error('工资金额或状态待核实，请刷新');
      }
      if (await submit(`${PATH}/${row.id}/settle`,'POST',{previewToken:data.previewToken,paidOffline:true},
        `确认 ${data.staffName} ${row.periodStart} 至 ${row.periodEnd} 的 ${money(row.totalAmount)} 已在线下发放？`)) await load();
    });
    listen('[data-wages]','click',async button => {
      const row = slips.find(item => String(item.id) === button.dataset.wages);
      const result = await api.get(params('/api/manager/earnings',{staffId:row.staffId,payrollId:row.id}));
      if (!current() || !preview.isConnected) return;
      preview.innerHTML = `<h2>结算单 ${esc(row.payrollNo ?? row.id)} 的明细</h2>` + table(['时间','类型','订单','数量','金额','说明'],
        rows(result.earnings).map(item => [esc(item.createTime),esc(item.kindText ?? item.kind),esc(item.orderId),esc(item.qty),money(item.amount),esc(item.note)]));
    });
  }
  async function rates(productId = 0) {
    const [data,products] = await Promise.all([api.get(params(RATE,{productId})),api.get('/api/manager/catalog')]);
    const options = rows(products).filter(row => row.selected === true && row.category === 1 && positive(row.id));
    const rate = data.rate;
    const value = key => esc(data.configured === true ? rate[key] ?? '' : '');
    show(`<button id="rate-back">返回工资</button><form class="toolbar" id="rate-choose"><label>计件范围<select name="product"><option value="0">本站默认</option>${options.map(row => `<option value="${row.id}" ${Number(row.id) === productId ? 'selected' : ''}>${esc(row.name)}</option>`).join('')}</select></label><button>查看</button></form>
      <form class="panel" id="rate-edit"><h2>${data.configured === true ? '现行设置' : '尚未配置'}</h2><label class="field">每桶计件金额<input name="amount" type="number" min="0" step="0.01" required value="${value('perBucketAmount')}"></label>
      ${productId === 0 ? `<label class="field">每层补贴（元）<input name="bonus" type="number" min="0" step="0.01" value="${value('floorBonusPerLevel')}"></label>
      <label class="field">几层以内不补<input name="free" type="number" min="0" step="1" value="${value('floorFreeLevel')}"></label>` : ''}<button>保存计件设置</button></form>`);
    listen('#rate-back','click',() => load());
    listen('#rate-choose','submit',form => {
      const id = Number(new FormData(form).get('product'));
      if (id !== 0 && !options.some(row => Number(row.id) === id)) throw new Error('请核对商品');
      return rates(id);
    });
    listen('#rate-edit','submit',async form => {
      const input = new FormData(form), amount = input.get('amount'), bonus = input.get('bonus'), free = input.get('free');
      if (!exact(amount) || bonus && !exact(bonus) || free && !/^\d+$/.test(free) || Number(bonus) > 0 && !free) throw new Error('请核对计件金额和楼层补贴');
      const body = {productId,perBucketAmount:amount,...(productId === 0 ? {floorBonusPerLevel:bonus || '0',floorFreeLevel:free ? Number(free) : null} : {})};
      if (await submit(RATE,'PUT',body,`保存每桶 ${money(amount)} 的计件设置？`)) await rates(productId);
    });
  }
  async function adjustments(staff) {
    const [items,directions] = (await Promise.all([api.get(ITEMS),api.get(`${ITEMS}/directions`)])).map(rows);
    const store = original(user,'payroll-adjust');
    const retained = store.read();
    const enabled = items.filter(row => row.status === 1 && positive(row.id));
    const directionText = value => directions.find(row => row.value === value)?.text ?? '待核实';
    show(`<button id="adjust-back">返回工资</button><section class="panel"><h2>登记工资加扣</h2>${retained ? `<p>上次登记回执待核对 · 员工 #${esc(retained.staffId)} · ${money(retained.amount)}</p><button id="adjust-retry">重试原登记</button>` :
      `<form class="toolbar" id="adjust-wages"><label>配送员<select name="staffId" required><option value="">请选择</option>${staff.map(row => `<option value="${row.id}">${esc(row.name)}</option>`).join('')}</select></label>
      <label>工资条目<select name="itemId"><option value="">手工加扣（正数加、负数扣）</option>${enabled.map(row => `<option value="${row.id}">${esc(row.name)} · ${esc(directionText(row.direction))}</option>`).join('')}</select></label>
      <label>金额<input name="amount" type="number" step="0.01" required></label><label>说明<input name="note" maxlength="200" required></label><button>登记</button></form>`}</section>
      <h2>自定义条目</h2><form class="toolbar" id="wage-item"><label>名称<input name="name" required maxlength="50"></label><label>方向<select name="direction" required>${directions.map(row => positive(row.value) ? `<option value="${row.value}">${esc(row.text)}</option>` : '').join('')}</select></label><label>顺序<input type="number" name="sort" value="0" required></label><button>保存条目</button></form>` +
      table(['名称','方向','状态','操作'],items.map(row => [esc(row.name),esc(directionText(row.direction)),row.status === 1 ? '启用' : '停用',positive(row.id) ? `<button data-wage-item="${row.id}:edit">编辑</button><button data-wage-item="${row.id}:status">${row.status === 1 ? '停用' : '启用'}</button><button data-wage-item="${row.id}:delete">删除</button>` : '—'])));
    listen('#adjust-back','click',() => load());
    listen('#adjust-retry','click',() => adjust(retained,true));
    listen('#adjust-wages','submit',async form => {
      const data = new FormData(form), person = staff.find(row => String(row.id) === data.get('staffId'));
      const itemId = data.get('itemId'), item = enabled.find(row => String(row.id) === itemId), amount = data.get('amount'), note = data.get('note').trim();
      if (!person || !note || !/^-?\d+(\.\d{1,2})?$/.test(amount) || Number(amount) === 0 || itemId && (!item || Number(amount) < 0)) throw new Error('请核对工资加扣条目与金额');
      if (store.read()) throw new Error('请先核对上次登记');
      if (!confirm(`登记 ${person.name} ${item ? directionText(item.direction)+' · '+item.name : Number(amount) > 0 ? '加项' : '扣项'} ${money(Math.abs(Number(amount)))}？\n${note}`)) return;
      const body = {staffId:person.id,itemId:item?.id ?? null,amount,note,idempotencyKey:crypto.randomUUID()};
      store.save(body);
      await adjust(body,false);
    });
    async function adjust(body,replay) {
      try {
        await api.command(`${PATH}/adjust`,'POST',body);
        store.clear();
      } catch (error) {
        if (!replay && error.businessRejected && !/幂等|重复/.test(error.message)) store.clear();
        if (current()) await adjustments(staff);
        throw error;
      }
      if (current()) await adjustments(staff);
    }
    listen('[data-wage-item]','click',async button => {
      const [id,action] = button.dataset.wageItem.split(':');
      const row = items.find(item => String(item.id) === id);
      const form = container.querySelector('#wage-item');
      if (action === 'edit') {
        form.dataset.editing = id; form.elements.name.value = row.name; form.elements.direction.value = row.direction; form.elements.sort.value = row.sort; form.elements.name.focus();
      } else if (action === 'status') {
        if (await submit(`${ITEMS}/${id}/status`,'POST',{status:row.status === 1 ? 0 : 1},`${button.textContent}「${row.name}」？`)) await adjustments(staff);
      } else if (await submit(`${ITEMS}/${id}`,'DELETE',undefined,`删除条目「${row.name}」？`)) await adjustments(staff);
    });
    listen('#wage-item','submit',async form => {
      const data = new FormData(form), name = data.get('name').trim(), direction = Number(data.get('direction')), sort = Number(data.get('sort'));
      if (!name || !directions.some(row => row.value === direction) || !Number.isInteger(sort)) throw new Error('请核对条目名称、方向与顺序');
      const id = form.dataset.editing;
      if (await submit(id ? `${ITEMS}/${id}` : ITEMS,id ? 'PUT' : 'POST',{name,direction,sort},`保存工资条目「${name}」？`)) await adjustments(staff);
    });
  }
  await load();
}
