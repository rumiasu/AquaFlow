import {exceptionResolution, verifyCaseReceipt} from './business.js';
import {esc, facts, original, params, positive, rows, screen, table} from './desktop.js';

const OUTCOMES = [['VERIFY','canResolve','核实结案'], ['RESOLVED','canRecordOutcome','已解决'], ['NO_ACTION','canRecordNoAction','误报／无需处理']];

export async function cases(context) {
  const {api,user,view,container,current,openOrder,openAssets} = context;
  const {show,listen,submit} = screen(context);
  const isException = view === 'exceptions';
  const kind = isException ? 'EXCEPTION' : 'FEEDBACK';
  const root = isException ? '/api/manager/exceptions' : '/api/feedback';
  const pending = original(user, `case:${kind}`);
  let page = 1;

  async function list() {
    const retained = pending.read();
    const query = params(isException ? root : root + '/customers', {page, size:30});
    const result = await api.get(query);
    const entries = rows(isException ? result.records : result);
    show((retained ? '<button id="resume-case">核对上次办理</button>' : '') + table(['编号','事项','状态','操作'], entries.map(row => [
      esc(row.id), `${esc(row.categoryText ?? row.category)}<br>${esc(row.staffNote ?? row.content)}`, esc(row.statusText),
      positive(row.id) ? `<button data-case="${row.id}">查看处理</button>` : '待核实'
    ])) + `<div class="paging"><button id="case-prev" ${page === 1 ? 'disabled' : ''}>上一页</button><span>第 ${page} 页</span><button id="case-next" ${(isException ? page * 30 >= result.total : entries.length < 30) ? 'disabled' : ''}>下一页</button></div>`);
    listen('#resume-case','click',() => detail(retained.id));
    listen('[data-case]','click',button => detail(Number(button.dataset.case)));
    listen('#case-prev','click',async () => { page--; try { await list(); } catch (e) { page++; throw e; } });
    listen('#case-next','click',async () => { page++; try { await list(); } catch (e) { page--; throw e; } });
  }

  async function detail(id) {
    if (!positive(id)) throw new Error('处理编号待核实');
    const item = await api.get(`${root}/${id}/${isException ? 'resolution' : 'handling'}`);
    if (!current()) return;
    if (item?.kind !== kind || String(item.caseId) !== String(id) || !Number.isSafeInteger(item.version) || item.version < 0) {
      throw new Error('处理记录不完整，请刷新核对');
    }
    const history = rows(item.actions);
    const actions = isException ? OUTCOMES : [['REPLY','canReply','发送水站回复'], ['CLOSE','canClose','回复并结案']];
    const allowed = item.closed ? [] : actions.filter(([ ,flag]) => item[flag] === true);
    const retained = pending.read();
    const details = [['状态',item.statusText]];
    if (Number.isSafeInteger(item.barrelDifference)) details.push(['应回桶',item.expectedReturn],['实回桶',item.actualReturn],['少回（负数为多回）',item.barrelDifference]);
    if (Number.isSafeInteger(item.currentOwed)) details.push(['当前欠桶',item.currentOwed]);
    if (item.paymentStatusText) details.push(['支付状态',item.paymentStatusText]);
    if (item.receiverPhone) details.push(['联系收货人',item.receiverPhone]);
    show(`<button id="case-back">返回列表</button><section class="panel"><h2>${esc(item.subjectCategory)}</h2><p>${esc(item.subjectText)}</p>${facts(details)}
      ${item.handlingHint || item.blockReason ? `<p>${esc(item.handlingHint || item.blockReason)}</p>` : ''}
      <div class="actions">${positive(item.orderId) ? `<button id="case-order">${esc(item.orderActionText || '查看订单')}</button>` : ''}
      ${positive(item.customerId) ? `<button id="case-assets">${item.canReturnEmpty ? '核对桶账并登记实收空桶' : '查看客户桶账'}</button>` : ''}
      ${item.canContinueRefusal === true && positive(item.orderId) ? '<button id="case-refusal">继续核实拒付</button>' : ''}</div>
      ${retained ? `<p role="status">异常或反馈 #${esc(retained.id)} 的办理结果待核对。</p><button id="case-retry">${retained.id === id ? '重试原办理' : '打开待核对记录'}</button>` : allowed.length ?
      `<label for="case-reason">办理说明</label><textarea id="case-reason" rows="4" maxlength="${isException ? 500 : 1000}"></textarea><div class="actions">${allowed.map(([action,,label]) => `<button data-outcome="${action}">${esc(action === 'VERIFY' ? item.resolveActionText || label : label)}</button>`).join('')}</div>` : ''}</section>
      <h2>处理记录</h2>${table(['时间','办理结果','说明'], history.map(row => [esc(row.createdAt ?? row.createTime),esc(row.actionText),esc(row.reason)]))}`);
    listen('#case-back','click',list);
    listen('#case-order','click',() => openOrder(item.orderId, () => detail(id)));
    listen('#case-assets','click',() => openAssets(item.customerId, () => detail(id)));
    listen('#case-refusal','click',async () => {
      const [latest,order] = await Promise.all([api.get(`${root}/${id}/resolution`),api.get(`/api/delivery/orders/${item.orderId}`)]);
      if (!current()) return;
      if (latest?.kind !== 'EXCEPTION' || String(latest.caseId) !== String(id) || latest.canContinueRefusal !== true || String(latest.orderId) !== String(order.id) ||
          order.status !== 3 || order.needCollect !== true || typeof order.independentBusinessRules !== 'boolean') throw new Error('订单拒付资格已变化，请刷新核对');
      const independent = order.independentBusinessRules;
      const effect = independent ? '欠款继续追收，关闭线下付款并暂停退押金。跨站押金冻结由归属站核实。' : '按历史规则核销应收，撤销未归还桶权益并等量记欠桶。';
      if (await submit(`${root}/${id}/write-off`,'POST',{managerNote:independent ? '客户拒付，站长确认信用风险；欠款继续追收' : '客户拒付，站长核销认损'},`确认订单 #${order.id} 已交货且客户明确拒绝付款？\n${effect}`)) await detail(id);
    });
    listen('#case-retry','click',() => retained.id === id ? send(retained, true) : detail(retained.id));
    listen('[data-outcome]','click',async button => {
      const action = button.dataset.outcome;
      const reason = container.querySelector('#case-reason').value.trim();
      if (!reason || reason.length > (isException ? 500 : 1000)) throw new Error('请填写办理说明');
      if (pending.read()) throw new Error('请先核对原办理');
      if (!confirm(`${button.textContent}？\n${reason}`)) return;
      const key = crypto.randomUUID();
      const cmd = isException ? exceptionResolution(item,reason,key,action) : {
        path:`${root}/${id}/${action === 'REPLY' ? 'reply' : 'close'}`, method:'POST',
        body:{expectedVersion:item.version,idempotencyKey:key,reason}
      };
      const intent = {id, action, cmd};
      pending.save(intent);
      await send(intent,false);
    });
  }

  async function send(intent, replay) {
    const {cmd,id,action} = intent;
    try {
      const receipt = await api.command(cmd.path,cmd.method,cmd.body);
      verifyCaseReceipt(receipt,kind,id,cmd.body.expectedVersion,action);
      pending.clear();
    } catch (error) {
      if (!replay && error.businessRejected) pending.clear();
      if (current()) await detail(id);
      throw error;
    }
    if (current()) await detail(id);
  }
  await list();
}
