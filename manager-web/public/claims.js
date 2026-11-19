import {esc, facts, money, original, positive, rows, screen, table} from './desktop.js';

const PATH = '/api/manager/asset-claims';
const count = value => /^(0|[1-9]\d*)$/.test(String(value)) && Number.isSafeInteger(Number(value)) && Number(value) <= 2147483647;
const price = value => /^(0|[1-9]\d*)(\.\d{1,2})?$/.test(String(value)) && Number(value) <= 99999999.99;

export function claimFacts(row, station, id = row?.id) {
  if (!positive(id) || String(row?.id) !== String(id) || !positive(row.customerId) || String(row.stationId) !== String(station) ||
      !['PENDING','APPROVED','REJECTED'].includes(row.status)) throw new Error('原认领记录待核实');
  const record = typeof row.requestJson === 'string' ? JSON.parse(row.requestJson) : row.requestJson;
  if (!record || String(record.stationId) !== String(station) || !record.clientToken || record.clientToken !== row.clientToken ||
      record.confirmed !== true || !price(record.depositBalance) || typeof record.note !== 'string' || !record.note.trim()) throw new Error('原认领清单待核实');
  const items = rows(record.items);
  const keys = new Set(items.map(item => Number(item?.productId)));
  if (keys.size !== items.length || items.some(item => !positive(item?.productId) || !count(item.rights) || !count(item.tickets) ||
      item.unitPrice != null && (!price(item.unitPrice) || Number(item.unitPrice) <= 0))) throw new Error('认领资产数量待核实');
  if (row.status !== 'PENDING' && (!positive(row.reviewerId) || !row.reviewTime || row.status === 'REJECTED' && !row.reviewNote?.trim())) {
    throw new Error('认领审批回执待核实');
  }
  return record;
}

export async function assetClaims(context) {
  const {api,user,container,current} = context;
  const {show,listen,error} = screen(context);
  const pending = original(user,'asset-claim-review');
  async function list() {
    const retained = pending.read();
    const claims = rows(await api.get(PATH));
    show((retained ? '<button id="claim-recover">核对上次审批</button>' : '') + table(['申请','客户','申请时间','状态','操作'],claims.map(row => [esc(row.id),esc(row.customerName ?? row.customerId),esc(row.createTime),esc(row.statusText),
      positive(row.id) ? `<button data-claim="${row.id}">核对认领清单</button>` : '待核实'])));
    listen('#claim-recover','click',() => detail(retained.id));
    listen('[data-claim]','click',button => detail(Number(button.dataset.claim)));
  }

  async function detail(id) {
    const [row,products,photo] = await Promise.all([api.get(`${PATH}/${id}`),api.get('/api/manager/catalog'),api.get(`${PATH}/${id}/photo`).catch(() => null)]);
    if (!current()) return;
    const record = claimFacts(row,user.stationId,id);
    const names = new Map(rows(products).map(product => [Number(product.id),product.name]));
    const retained = pending.read();
    if (retained?.id === id && row.status !== 'PENDING') pending.clear();
    const intent = pending.read();
    const photoUrl = photo?.exists && /^https:\/\/[^\s]+$/i.test(photo.url ?? '') ? new URL(photo.url) : null;
    const canOpen = photoUrl && !photoUrl.username && !photoUrl.password;
    const positiveAssets = Number(record.depositBalance) > 0 || record.items.some(item => Number(item.rights) > 0 || Number(item.tickets) > 0);
    show(`<button id="claim-back">返回列表</button><section class="panel"><h2>认领申请 #${id}</h2>${facts([['客户',row.customerName ?? row.customerId],['历史押金',money(record.depositBalance)],['申请说明',record.note],['状态',row.statusText],...(row.status === 'PENDING' ? [] : [['核对时间',row.reviewTime],['审批说明',row.reviewNote]])])}
      ${canOpen ? `<p><a id="claim-photo" href="${esc(photoUrl.href)}" target="_blank" rel="noopener noreferrer">打开客户凭据照片</a></p>` : `<p>${photo?.exists === false ? '未附照片' : '凭据照片待核实'}</p>`}</section>` +
      table(['商品','桶权益','水票','历史单价'],record.items.map(item => [`${esc(names.get(Number(item.productId)) ?? '历史商品')} #${esc(item.productId)}`,esc(item.rights),esc(item.tickets),item.unitPrice == null ? '按补录规则推断' : money(item.unitPrice)])) +
      (row.status === 'PENDING' ? intent ? `<section class="panel"><p>申请 #${esc(intent.id)} 的原审批待核对。</p><button id="claim-retry">${intent.id === id ? '重试原审批' : '查看待核对申请'}</button></section>` :
      `<section class="panel"><label class="field">核对说明<textarea id="claim-note" rows="3" maxlength="500"></textarea></label>
      ${canOpen ? '<label><input id="claim-photo-checked" type="checkbox"> 已核对客户凭据照片</label>' : ''}<div class="actions"><button id="claim-accept" ${!positiveAssets || photo?.exists !== false && !canOpen ? 'disabled' : ''}>通过并入账</button><button id="claim-reject">拒绝申请</button></div></section>` : ''));
    listen('#claim-back','click',list);
    listen('#claim-retry','click',() => intent.id === id ? send(intent,true) : detail(intent.id));
    for (const [selector,approve] of [['#claim-accept',true],['#claim-reject',false]]) {
      listen(selector,'click',async () => {
        const note = container.querySelector('#claim-note').value.trim();
        if (!approve && !note) throw new Error('拒绝时请填写核对差异');
        if (approve && canOpen && !container.querySelector('#claim-photo-checked').checked) throw new Error('请先核对客户凭据照片');
        if (pending.read()) throw new Error('请先核对上次审批');
        if (!confirm(approve ? `确认已核对申请 #${id}，按原清单登记历史资产？` : `拒绝申请 #${id}？\n${note}`)) return;
        const decision = {id,body:{approve,note:note || null}};
        pending.save(decision);
        await send(decision,false);
      });
    }
  }

  async function send(intent,replay) {
    try {
      const receipt = await api.command(`${PATH}/${intent.id}/review`,'POST',intent.body);
      claimFacts(receipt,user.stationId,intent.id);
      if (receipt.status !== (intent.body.approve ? 'APPROVED' : 'REJECTED')) throw new Error('审批结果待核实，请重新读取申请');
      pending.clear();
    } catch (failure) {
      if (!replay && failure.businessRejected) pending.clear();
      if (current()) {
        try { await detail(intent.id); } catch (readFailure) { error(readFailure.message); }
      }
      throw failure;
    }
    if (current()) await detail(intent.id);
  }
  await list();
}
