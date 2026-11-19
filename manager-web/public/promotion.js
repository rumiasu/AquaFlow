import {esc,money,rows,screen} from './desktop.js';
const path = '/api/manager/catalog';
const fields = ['id','selected','enabled','regularPrice','salePrice','promotionPrice','promotionActive'];
const snapshot = row => JSON.stringify(fields.map(key => row?.[key] ?? null));
export function promotionBody(row, mode, value) {
  if (row.selected !== true) throw new Error('请先选用商品');
  if (mode === 'off') return {promotionActive:false};
  if (mode !== 'on' || !/^\d+(\.\d{1,2})?$/.test(value) || Number(value) <= 0 || Number(value) > 99999999.99) throw new Error('促销价须为大于 0 的金额，最多两位小数');
  const regular = row.regularPrice;
  if (regular == null || regular === '' || !Number.isFinite(Number(regular)) || Number(value) >= Number(regular)) throw new Error('促销价须低于服务端日常售价；日常售价未知时请重新读取');
  return {promotionActive:true,promotionPrice:value};
}
export async function editPromotion(context, initial, reload) {
  const {api,user,container,current} = context;
  const station = String(user.stationId), actor = String(user.staffId ?? user.id);
  const {listen} = screen(context);
  const active = () => current() && station === String(user.stationId) && actor === String(user.staffId ?? user.id);
  let revision = 0;
  const editor = container.querySelector('#stock-editor');
  editor.innerHTML = `<form id="promotion-edit" class="panel"><h2>${esc(initial.name)} · 站内促销</h2><p>日常售价 ${esc(money(initial.regularPrice))}；当前成交价 ${esc(money(initial.effectivePrice))}。仅影响本站新订单，不改历史订单价格。</p>
    <label>促销状态<select name="mode"><option value="off">结束／不启用促销</option><option value="on" ${initial.promotionActive === true ? 'selected' : ''}>开启／修改促销</option></select></label>
    <label>促销价<input name="promotion" type="number" min="0.01" step="0.01" value="${esc(initial.promotionPrice ?? '')}"></label><button>核对并保存促销</button></form><button id="promotion-refresh">重新读取商品</button>`;
  const form = editor.querySelector('#promotion-edit');
  form.addEventListener('input',() => revision++);
  form.addEventListener('change',() => revision++);
  listen('#promotion-refresh','click',reload);
  listen('#promotion-edit','submit',async () => {
    const version = revision, data = new FormData(form);
    const body = promotionBody(initial,data.get('mode'),data.get('promotion').trim());
    const me = await api.get('/api/auth/me');
    if (!active() || String(me.stationId) !== station || String(me.staffId ?? me.id) !== actor) throw new Error('水站或账号已变化，请重新登录');
    const fresh = rows(await api.get(path)).find(row => String(row.id) === String(initial.id));
    if (!active() || !form.isConnected || version !== revision) throw new Error('页面或输入已变化，请重新核对');
    if (!fresh || snapshot(fresh) !== snapshot(initial)) throw new Error('商品价格或状态已变化，请重新读取商品后核对');
    if (!confirm(`${initial.name}：${body.promotionActive ? '将本站促销价设为 '+money(body.promotionPrice) : '结束本站促销，恢复日常售价 '+money(fresh.regularPrice)}？仅影响新订单。`)) return;
    let failure;
    try { await api.command(`${path}/${initial.id}`,'PUT',body); }
    catch (error) { if (error.businessRejected) throw error; failure = error; }
    // Once dispatched, lock this draft. Never replay an uncertain write automatically.
    form.dataset.locked = 'true';
    form.querySelector('button').disabled = true;
    if (!active()) return;
    let recorded;
    try { recorded = rows(await api.get(path)).find(row => String(row.id) === String(initial.id)); }
    catch { throw new Error('提交结果待核实，请重新读取商品；不要重复提交'); }
    if (!active() || !form.isConnected) return;
    const matches = recorded?.selected === true && recorded.enabled === fresh.enabled && Number(recorded.regularPrice) === Number(fresh.regularPrice) && (body.promotionActive
      ? recorded.promotionActive === true && Number(recorded.promotionPrice) === Number(body.promotionPrice)
      : recorded.promotionActive === false && recorded.promotionPrice == null);
    if (!matches) throw new Error('回读促销与提交不一致，请重新读取商品核对；不要重复提交');
    if (failure) throw new Error('提交回执异常；回读已是目标促销状态，请重新读取商品确认');
    await reload();
  });
}
