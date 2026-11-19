import {esc, facts, money, original, params, positive, rows, screen, table} from './desktop.js';

const PATH = '/api/manager/adjustments';
const options = {BARREL_GRANT:'补录桶数',BARREL_REVOKE:'扣减桶数',OVER_ADJUST:'订正欠桶',DEPOSIT_GRANT:'补录押金',DEPOSIT_DEDUCT:'扣减押金',TICKET_GRANT:'补录水票',TICKET_DEDUCT:'扣减水票'};
const fields = {right:'桶权益',over:'欠桶／暂存',occupied:'占用',deposit:'押金',ticket:'水票'};
const amount = value => /^\d+(\.\d{1,2})?$/.test(value) && Number(value) > 0;

function snapshots(before,after) {
  const parse = value => typeof value === 'string' ? JSON.parse(value) : value;
  const first = parse(before), second = parse(after);
  if (!first || !second) return '<p>资产快照待核实</p>';
  return table(['项目','调整前','调整后'],Object.entries(fields).map(([key,label]) => [esc(label),key === 'deposit' ? money(first[key]) : esc(first[key]),key === 'deposit' ? money(second[key]) : esc(second[key])]));
}

export async function adjustments(context) {
  const {api,user,container,current} = context;
  const {show,listen,submit} = screen(context);
  const storage = original(user,'asset-adjustment');
  async function list(page = 1) {
    const result = await api.get(params(PATH,{page,size:30}));
    const pending = storage.read();
    show(`<div class="actions">${pending ? '<button id="adjust-resume">核对原调整单提交</button>' : '<button id="adjust-new">新建调整单</button>'}</div>` + table(['单号','客户','类型','状态','操作'],rows(result.list).map(row => [esc(row.adjustNo),esc(row.customerName),esc(row.adjustTypeText),esc(row.statusText),positive(row.id) ? `<button data-adjustment="${row.id}">查看</button>` : '—'])) +
      `<div class="paging">${page > 1 ? '<button id="adjust-prev">上一页</button>' : ''}<span>第 ${page} 页</span>${page * 30 < result.total ? '<button id="adjust-next">下一页</button>' : ''}</div>`);
    listen('#adjust-new','click',() => search());
    listen('#adjust-resume','click',() => send(pending,true));
    listen('#adjust-prev','click',() => list(page - 1));
    listen('#adjust-next','click',() => list(page + 1));
    listen('[data-adjustment]','click',button => detail(button.dataset.adjustment));
  }
  async function search(keyword = '') {
    const customers = rows(await api.get(params('/api/manager/order-assist/customers',{keyword})));
    show(`<button id="adjust-list">返回调整单</button><form id="adjust-customer" class="toolbar"><label>查找客户<input name="keyword" value="${esc(keyword)}"></label><button>查询</button></form>` +
      table(['客户','电话','操作'],customers.map(row => [esc(row.name),esc(row.phone),positive(row.id) ? `<button data-adjust-customer="${row.id}">核对资产</button>` : '—'])));
    listen('#adjust-list','click',() => list());
    listen('#adjust-customer','submit',form => search(new FormData(form).get('keyword').trim()));
    listen('[data-adjust-customer]','click',button => edit(customers.find(row => String(row.id) === button.dataset.adjustCustomer)));
  }
  async function edit(customer) {
    const [assets,catalog] = await Promise.all([api.get(`/api/customers/${customer.id}/assets`),api.get('/api/manager/catalog')]);
    if (assets.adjustmentEligible !== true) throw new Error('客户资产调整资格待核实');
    const products = rows(catalog).filter(row => positive(row.id));
    show(`<button id="adjust-back">重选客户</button><form id="adjust-draft" class="panel"><h2>${esc(customer.name)}的资产调整</h2>
      <label class="field">类型<select name="type">${Object.entries(options).map(([key,label]) => `<option value="${key}">${label}</option>`).join('')}</select></label><div id="adjust-fields"></div>
      <label class="field">调整说明<textarea name="reason" maxlength="200" rows="3" required></textarea></label><button>试算资产变化</button></form><div id="adjust-preview"></div>`);
    if (!current()) return;
    const form = container.querySelector('#adjust-draft'), target = container.querySelector('#adjust-preview');
    let revision = 0;
    function renderFields() {
      const type = form.elements.type.value;
      container.querySelector('#adjust-fields').innerHTML = type.startsWith('DEPOSIT_') ? '<label class="field">金额<input name="amount" type="number" min="0.01" step="0.01" required></label>' :
        `<label class="field">商品<select name="product" required><option value="">请选择</option>${products.map(row => `<option value="${row.id}">${esc(row.name)} ${esc(row.spec ?? '')}</option>`).join('')}</select></label>
         <label class="field">数量<input name="qty" type="number" min="1" max="2147483647" step="1" required></label>
         ${type === 'OVER_ADJUST' ? '<label class="field">方向<select name="sign" required><option value="">请选择</option><option value="1">补记欠桶</option><option value="-1">核销欠桶</option></select></label>' : ''}
         ${type === 'BARREL_GRANT' ? '<label class="field">历史每桶押金（可选）<input name="unitPrice" type="number" min="0.01" step="0.01"></label>' : ''}`;
    }
    renderFields();
    form.elements.type.addEventListener('change',renderFields);
    form.addEventListener('input',() => {revision++;target.replaceChildren();});
    listen('#adjust-back','click',() => search());
    listen('#adjust-draft','submit',async () => {
      if (storage.read()) throw new Error('请先核对原调整单');
      const data = new FormData(form), adjustType = data.get('type'), reason = data.get('reason').trim(), product = products.find(row => String(row.id) === data.get('product'));
      const deposit = adjustType.startsWith('DEPOSIT_'), qty = Number(data.get('qty')), unitPrice = data.get('unitPrice');
      if (!Object.hasOwn(options,adjustType) || !reason || (deposit ? !amount(data.get('amount')) : !product || !positive(qty)) || unitPrice && !amount(unitPrice)) throw new Error('请核对调整内容');
      const sign = adjustType === 'OVER_ADJUST' ? Number(data.get('sign')) : 1;
      if (sign !== 1 && sign !== -1) throw new Error('请选择欠桶调整方向');
      const body = {customerId:customer.id,adjustType,reason,...(deposit ? {amount:data.get('amount')} : {productId:product.id,qty:qty * sign}),...(unitPrice ? {unitPrice} : {})};
      const version = ++revision;
      const preview = await api.command(`${PATH}/preview`,'POST',body);
      if (!current() || version !== revision || !target.isConnected) return;
      if (preview.adjustType !== adjustType || !preview.before || !preview.after) throw new Error('试算结果待核实');
      target.innerHTML = `<h2>${esc(preview.adjustTypeText)} · 服务端试算</h2>${snapshots(preview.before,preview.after)}<button id="adjust-create">保存待执行单</button>`;
      listen('#adjust-create','click',async () => {
        if (storage.read() || version !== revision) throw new Error('请重新核对试算或原调整单');
        if (!confirm(`为 ${customer.name} 创建待执行调整单「${preview.adjustTypeText}」？`)) return;
        const intent = {path:PATH,body:{...body,clientToken:crypto.randomUUID()}};
        storage.save(intent); await send(intent,false);
      });
    });
  }
  async function detail(id) {
    const row = await api.get(`${PATH}/${id}`);
    if (String(row.id) !== String(id)) throw new Error('调整单编号待核实');
    const pending = storage.read();
    show(`<button id="adjust-detail-back">返回调整单</button><section class="panel"><h2>${esc(row.adjustNo)}</h2>${facts([['客户',row.customerName],['商品',row.productName],['类型',row.adjustTypeText],['状态',row.statusText],['数量',row.qty],['金额',money(row.amount)],['原因',row.reason]])}</section>
      ${snapshots(row.beforeSnapshot,row.afterSnapshot)}<div class="actions">${!pending && row.status === 'PENDING' ? '<button id="adjust-execute">执行调整</button>' : ''}</div>
      ${!pending && row.canReverse === true ? '<form id="adjust-reverse" class="panel"><label class="field">撤销原因<input name="reason" maxlength="200" required></label><button>撤销并生成反向单</button></form>' : ''}`);
    listen('#adjust-detail-back','click',() => list());
    listen('#adjust-execute','click',async () => {
      if (await submit(`${PATH}/${id}/execute`,'POST',undefined,`确认执行 ${row.customerName} 的调整单 ${row.adjustNo} 并更新资产？`)) await detail(id);
    });
    listen('#adjust-reverse','submit',async form => {
      const reason = new FormData(form).get('reason').trim();
      if (!reason || storage.read()) throw new Error('请填写原因并核对原调整单');
      if (!confirm(`撤销调整单 ${row.adjustNo} 并执行反向调整？\n${reason}`)) return;
      const intent = {path:`${PATH}/${id}/reverse`,body:{reason,clientToken:crypto.randomUUID()}};
      storage.save(intent); await send(intent,false);
    });
  }
  async function send(intent,replay) {
    let saved;
    try {
      saved = await api.command(intent.path,'POST',intent.body);
      if (!positive(saved?.id)) throw new Error('调整单回执待核实');
      storage.clear();
    } catch (error) {
      if (!replay && error.businessRejected && !/幂等|编号|重复/.test(error.message)) storage.clear();
      if (current()) await list();
      throw error;
    }
    if (current()) await detail(saved.id);
  }
  await list();
}
