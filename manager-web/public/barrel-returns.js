import {esc, facts, money, original, params, positive, rows, screen, table} from './desktop.js';

const scopes = {ACTIVE:'办理中',REVIEW:'待审批',HANDOVER:'待交接',ALL:'全部记录'};

export async function barrelReturns(context) {
  const {api,user,container,current,openOrder} = context;
  const {show,listen,submit} = screen(context);
  const refund = original(user,'barrel-cash-payout');
  const recordPath = id => `/api/barrels/records/${id}`;
  async function originalRecord(id) {
    if (!positive(id)) throw new Error('原申请编号待核实');
    const row = await api.get(`/api/manager/pending-summary/return-record/${id}`);
    if (String(row.id) !== String(id) || row.type !== 2 || String(row.stationId) !== String(user.stationId)) throw new Error('原退款申请待核实');
    return row;
  }
  async function load(scope = 'ACTIVE',beforeId) {
    const page = await api.get(params('/api/manager/business-waiting/returns',{scope,...(beforeId ? {beforeId} : {})}));
    if (page.scope !== scope || String(page.stationId) !== String(user.stationId)) throw new Error('退桶申请的水站或范围待核实');
    const records = rows(page.items);
    const retained = refund.read();
    show(`<div class="actions">${Object.entries(scopes).map(([key,label]) => `<button data-return-scope="${key}" ${scope === key ? 'aria-current="true"' : ''}>${label}</button>`).join('')}${retained ? '<button id="refund-recover">核对上次现金退款</button>' : ''}</div>` +
      table(['申请','客户','商品','押金','状态','操作'],records.map(row => [esc(row.id),esc(row.customerName),esc(row.productName),money(row.depositRefund),esc(row.statusText),
        positive(row.id) && row.type === 2 && String(row.stationId) === String(user.stationId) ? `<button data-return="${row.id}">核对交接</button>` : '—'])) +
      (positive(page.nextBeforeId) ? '<div class="paging"><button id="older-returns">更早记录</button></div>' : ''));
    listen('[data-return-scope]','click',button => load(button.dataset.returnScope));
    listen('#older-returns','click',() => load(scope,page.nextBeforeId));
    listen('#refund-recover','click',async () => detail(await originalRecord(retained.id),() => load(scope,beforeId)));
    listen('[data-return]','click',button => detail(records.find(row => String(row.id) === button.dataset.return),() => load(scope,beforeId)));
  }
  async function detail(row,back) {
    const d = row.returnDetail;
    const qty = d ? d.requiredBarrels : row.quantity;
    const qtyKnown = Number.isInteger(qty) && qty >= 0;
    const combined = d?.pickupMode === 'COMBINED' && qty > 0 && ['APPLIED','APPROVED'].includes(d.status);
    const path = recordPath(row.id);
    const evidence = combined ? await api.get(`${path}/handover-evidence`) : null;
    if (!current()) return;
    const currentEvidence = evidence && String(evidence.recordId) === String(row.id) && String(evidence.companionOrderId) === String(d.companionOrderId) &&
      String(evidence.productId) === String(row.productId) && evidence.requiredBarrels === qty;
    const approval = d?.status === 'APPLIED' && qtyKnown && Number.isInteger(d.arrangementVersion);
    const feeNeeded = approval && d.pickupMode === 'PICKUP' && qty > 0;
    const customerReady = d && (d.customerConfirmationRequired === false || d.customerConfirmationCurrent === true);
    const feePending = d?.status === 'APPROVED' && customerReady && positive(d.feePaymentId) && d.feePaymentStatus === 1 && money(d.pickupFee) !== '待核实';
    const receive = qtyKnown && (d ? d.status === 'APPROVED' && customerReady && (!d.feePaymentId || d.feePaymentStatus === 2) : row.status === 1);
    const canReceive = receive && (!combined || currentEvidence && evidence.eligible === true && evidence.linked !== true);
    const link = canReceive && combined && evidence.requiresLinkedReceipt === true;
    const linkKnown = !link || positive(evidence.deliveryReceiptId);
    const reject = d ? ['APPLIED','APPROVED'].includes(d.status) && d.feePaymentStatus !== 2 : [1,2].includes(row.status);
    let retained = refund.read();
    if (retained && String(retained.id) === String(row.id) && payoutRecorded(row,retained)) { refund.clear(); retained = null; }
    const needsRefund = d ? d.status === 'RECEIVED' : row.status === 2;
    show(`<button id="return-back">返回退桶申请</button><section class="panel"><h2>退桶申请 #${row.id}</h2>${facts([
      ['客户',row.customerName],['商品',row.productName],['当前状态',row.statusText],['需交空桶',qtyKnown ? qty : '待核实'],['预计退押金',money(row.depositRefund)],
      ['当前欠桶',row.owedBuckets],['交接方式',d?.pickupModeText ?? d?.pickupMode],['服务费',money(d?.pickupFee)],['已退款',money(row.refundProgress?.paid)],['剩余退款',row.refundProgress?.verified === true ? money(row.refundProgress.remaining) : '待核实']
    ])}${currentEvidence ? `<p>${esc(evidence.reason)}</p>` : ''}${d?.status === 'APPROVED' && !customerReady ? '<p>等待客户确认安排。</p>' : ''}
    ${approval ? `<form id="return-approve">${feeNeeded ? '<label class="field">本次上门费（元，0 表示免费）<input type="number" name="fee" min="0" max="10000" step="0.01" required></label>' : ''}<button>批准原交接安排</button></form>` : ''}
    <div class="actions">${feePending ? '<button id="return-fee">登记服务费已收</button>' : ''}${canReceive && linkKnown ? `<button id="return-receive">${link ? '引用关联订单回桶凭据' : qty === 0 ? '登记无需交桶的交接' : '确认实收空桶'}</button>` : ''}
    ${currentEvidence && evidence.canOpenCompanion === true && positive(d?.companionOrderId) ? '<button id="return-companion">查看关联订单</button>' : ''}
    ${retained && String(retained.id) === String(row.id) ? '<button id="refund-retry">仅同步原现金退款</button>' : needsRefund && !retained ? '<button id="refund-eligibility">核对押金退款资格</button>' : ''}</div><div id="refund-review"></div>
    ${reject ? '<form id="return-reject"><label class="field">驳回说明<input name="note" maxlength="200" required></label><button>驳回申请</button></form>' : ''}</section>`);
    listen('#return-back','click',back);
    listen('#refund-retry','click',async () => {
      if (!confirm(`仅同步申请 #${row.id} 已交付的原现金 ${money(retained.body.expectedRefundAmount)}？请核实原交付记录。`)) return;
      await saveRefund(retained,back);
    });
    listen('#refund-eligibility','click',async () => {
      const eligibility = await api.get(`${path}/refund-eligibility?scopedRefunds=true`);
      if (!current() || !container.querySelector('#refund-review')) return;
      const amount = eligibility.refundAmount;
      const available = String(eligibility.recordId) === String(row.id) && eligibility.available === true && eligibility.channel === 'CASH' && /^\d+(\.\d{1,2})?$/.test(String(amount)) &&
        (d ? eligibility.detailStatus === d.status : eligibility.recordStatus === row.status);
      container.querySelector('#refund-review').innerHTML = `<section><h2>原款退款资格</h2>${facts([['本次可退',money(amount)],['累计应退',money(eligibility.totalRefundAmount)],['剩余待退',money(eligibility.remainingRefundAmount)]])}
        ${eligibility.expiredOriginalCash === true ? '<p>原微信款已超期，本次按核实的线下实退办理。</p>' : ''}
        ${available ? '<button id="refund-cash">登记现金退款</button>' : `<p>${esc(eligibility.reason || '请按已核对的原退款渠道办理。')}</p>`}</section>`;
      listen('#refund-cash','click',async () => {
        if (refund.read()) throw new Error('请先核对原现金退款');
        const body = {status:3,refundChannel:'CASH',cashDeliveryConfirmed:true,expectedRefundAmount:Number(amount),
          ...(eligibility.partialRefundsSupported === true && Number(amount) > 0 ? {payoutRequestKey:crypto.randomUUID()} : {})};
        const intent = {id:row.id,body};
        refund.save(intent);
        if (!confirm(Number(amount) === 0 ? `申请 #${row.id} 无需交款，登记零额退款？` : `确认申请 #${row.id} 的 ${money(amount)} 现金已经实际交给客户？`)) {
          refund.clear(); return;
        }
        await saveRefund(intent,back);
      });
    });
    listen('#return-companion','click',() => openOrder(d.companionOrderId,() => detail(row,back)));
    listen('#return-approve','submit',async form => {
      const value = feeNeeded ? new FormData(form).get('fee') : '0';
      if (!/^\d+(\.\d{1,2})?$/.test(value) || Number(value) > 10000) throw new Error('请核对上门费');
      if (await submit(`${path}/approve`,'PUT',{pickupFee:Number(value),note:'批准交接安排',expectedVersion:d.arrangementVersion},`批准退桶申请 #${row.id} 的原交接安排？\n需交空桶 ${qty} 个 · 上门费 ${money(value)}`)) await back();
    });
    listen('#return-fee','click',async () => {
      if (await submit(`/api/payments/${d.feePaymentId}/confirm`,'PUT',undefined,`已收到申请 #${row.id} 的收桶服务费 ${money(d.pickupFee)}？`)) await back();
    });
    listen('#return-receive','click',async () => {
      const message = link ? `将配送单 #${d.companionOrderId} 回桶凭据 #${evidence.deliveryReceiptId} 的 ${qty} 个空桶关联到原申请 #${row.id}？` : qty === 0 ? `确认申请 #${row.id} 无需实物交接？` : `确认本次实际收到申请 #${row.id} 的 ${qty} 个空桶？`;
      if (await submit(`${path}/status`,'PUT',{status:2,...(link ? {deliveryReceiptId:evidence.deliveryReceiptId} : {})},message)) await back();
    });
    listen('#return-reject','submit',async form => {
      const handleNote = new FormData(form).get('note').trim();
      if (!handleNote) throw new Error('请填写驳回说明');
      if (await submit(`${path}/status`,'PUT',{status:4,handleNote},`驳回退桶申请 #${row.id}？\n${handleNote}`)) await back();
    });
  }
  function payoutRecorded(row,intent) {
    if (intent.body.payoutRequestKey) return rows(row.refundProgress?.receipts ?? []).some(receipt =>
      receipt.requestKey === intent.body.payoutRequestKey && Number(receipt.amount) === intent.body.expectedRefundAmount);
    return row.status === 3 && !!row.refundPaidTime && Number(row.depositRefund) === intent.body.expectedRefundAmount;
  }
  async function saveRefund(intent,back) {
    const before = await originalRecord(intent.id);
    if (!payoutRecorded(before,intent)) {
      try {
        await api.command(`${recordPath(intent.id)}/status`,'PUT',intent.body);
      } catch (error) {
        if (current()) await detail(await originalRecord(intent.id),back);
        throw error;
      }
    }
    const latest = await originalRecord(intent.id);
    if (!payoutRecorded(latest,intent)) throw new Error('退款回执待核实，请核对原申请和实际交付');
    refund.clear();
    if (current()) await detail(latest,back);
  }
  await load();
}
