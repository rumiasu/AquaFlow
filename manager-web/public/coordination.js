import {esc, money, positive, rows, screen, table} from './desktop.js';

const PATH = '/api/delivery/orders';
const views = {'station-delivering':'本站配送中','station-pending':'本站待分配','pool':'抢单池','directed-incoming':'指定给本站','dispatch-tracking':'本站外派','directed-returns':'跨站退回审批','pending-approvals':'取消与站内审批'};

export async function coordination(context) {
  const {api,user,openOrder,current,container} = context;
  const {show,listen,submit} = screen(context);
  async function load(view = 'station-delivering') {
    const result = await api.get(`${PATH}/${view}`);
    const approvals = view === 'pending-approvals';
    const list = approvals ? [...rows(result.customer).map(row => ({...row,approval:'customer'})), ...rows(result.station).map(row => ({...row,approval:'station'}))] : rows(result);
    function actions(row,index) {
      if (!positive(row.id)) return '待核实';
      const approve = view === 'directed-returns' && positive(row.transferPendingRequestId) || approvals && (row.approval === 'customer' || ['RETURN_STATION','CANCEL_REQUEST'].includes(row.transferPendingSubKind));
      const recall = view === 'dispatch-tracking' && row.status === 1 && String(row.deliveryStationId) !== String(user.stationId);
      const details = !['pool','directed-returns'].includes(view) && (view !== 'dispatch-tracking' || String(row.deliveryStationId) === String(user.stationId));
      const assign = view === 'directed-incoming' && row.status === 1 && !row.deliveryStaffId;
      const claim = view === 'pool' && Object.hasOwn(row,'crossStationRiskNote') && row.crossStationRiskNote === null;
      return (details ? `<button data-dispatch-detail="${row.id}">查看订单</button>` : '') +
        (assign ? `<button data-directed="${index}">接收并分配</button>` : '') +
        (claim ? `<button data-directed="${index}">抢单并分配</button>` : '') +
        (view === 'pool' && row.crossStationRiskNote ? `<p>${esc(row.crossStationRiskNote)}</p>` : '') +
        (view === 'station-pending' && row.status === 1 && !row.deliveryStaffId && Number(row.stationId) === Number(user.stationId) ? `<button data-outsource="${index}">外派</button>` : '') +
        (view === 'directed-incoming' && row.status === 1 ? `<button data-dispatch-action="${index}:return">申请退回原站</button>` : '') +
        (approve ? `<button data-dispatch-action="${index}:approve">同意</button><button data-dispatch-action="${index}:reject">驳回</button>` : '') +
        (recall ? `<button data-dispatch-action="${index}:recall">召回</button>` : '');
    }
    show(`<div class="actions" aria-label="配送协作队列">${Object.entries(views).map(([key,label]) => `<button data-queue="${key}" ${key === view ? 'aria-current="true"' : ''}>${label}</button>`).join('')}</div><div id="dispatch-command"></div>` +
      table(['订单','状态','定价站与结算说明','订单金额','操作'],list.map((row,index) => [esc(row.orderNo ?? row.id),esc(row.statusText),
        `${esc(row.feeStationName)}<br>${esc(row.settleNote)}`,money(row.totalAmount),actions(row,index)])));
    listen('[data-queue]','click',button => load(button.dataset.queue));
    listen('[data-dispatch-detail]','click',button => openOrder(button.dataset.dispatchDetail,() => load(view)));
    listen('[data-outsource]','click',async button => {
      const row = list[Number(button.dataset.outsource)];
      const [stations,risk] = await Promise.all([api.get('/api/stations/search'),api.get(`${PATH}/${row.id}/cross-station-risk`)]);
      if (!current() || !button.isConnected) return;
      if (typeof risk.depositBarrelRisk !== 'boolean' || risk.depositBarrelRisk && !risk.riskNote) throw new Error('订单风险待核实');
      const choices = rows(stations).filter(item => positive(item.id) && item.status === 1 && Number(item.id) !== Number(user.stationId));
      container.querySelector('#dispatch-command').innerHTML = `<form class="panel" id="dispatch-outsource"><h2>外派订单 #${row.id}</h2>
        ${risk.depositBarrelRisk ? `<p>${esc(risk.riskNote)}</p><label><input type="checkbox" name="risk" required> 已核对押金和桶权益风险</label>` : ''}
        <label class="field">外派去向<select name="target" required><option value="">请选择</option>${risk.depositBarrelRisk ? '' : '<option value="pool">进入抢单池</option>'}
        ${choices.map(item => `<option value="${item.id}">${esc(item.name)}</option>`).join('')}</select></label><button>确认外派</button></form>`;
      listen('#dispatch-outsource','submit',async form => {
        const data = new FormData(form), target = data.get('target'), station = choices.find(item => String(item.id) === target);
        if (!station && (target !== 'pool' || risk.depositBarrelRisk)) throw new Error('请核对外派去向');
        if (risk.depositBarrelRisk && !data.has('risk')) throw new Error('请先确认押金和桶权益风险');
        if (await submit(`${PATH}/transfer/${row.id}/outsource`,'POST',{...(station ? {targetStationId:station.id} : {}),...(risk.depositBarrelRisk ? {riskAcknowledged:true} : {})},
          `订单 #${row.id} 外派至${station?.name ?? '抢单池'}？`)) await load(view);
      });
    });
    listen('[data-directed]','click',async button => {
      const row = list[Number(button.dataset.directed)];
      const pool = view === 'pool';
      const [staff,risk] = await Promise.all([api.get('/api/staff'),pool ? {crossStation:true,depositBarrelRisk:false} : api.get(`${PATH}/${row.id}/cross-station-risk`)]);
      if (!current() || !button.isConnected) return;
      if (pool && row.crossStationRiskNote !== null) throw new Error('请核对抢单池订单的桶权益风险');
      if (risk.crossStation !== true || typeof risk.depositBarrelRisk !== 'boolean' || risk.depositBarrelRisk && !risk.riskNote) throw new Error('入站订单风险待核实');
      const people = rows(staff).filter(item => positive(item.id) && item.role === 'DELIVERY' && item.status === 1 && Number(item.stationId) === Number(user.stationId));
      const offer = row.dispatchAgreement;
      const quoted = offer?.status === 'OFFERED';
      if (quoted && (!/^[a-f0-9]{64}$/.test(offer.quoteToken ?? '') || money(offer.serviceAmount) === '待核实')) throw new Error('外派报价待核实');
      const label = pool ? '抢单并分配' : '接收并分配';
      container.querySelector('#dispatch-command').innerHTML = `<form class="panel" id="dispatch-incoming"><h2>${label} · 订单 #${row.id}</h2>
        ${quoted ? `<p>本单配送报酬 ${money(offer.serviceAmount)}</p><p>${esc(offer.barrelNote)}${offer.barrelAmount != null ? ` · 桶款 ${money(offer.barrelAmount)}` : ''}</p>` : ''}
        ${risk.depositBarrelRisk ? `<p>${esc(risk.riskNote)}</p><label><input type="checkbox" name="risk" required> 已核对押金和桶权益风险</label>` : ''}
        <label class="field">配送员<select name="staff" required><option value="">请选择</option>${people.map(item => `<option value="${item.id}">${esc(item.name)}</option>`).join('')}</select></label><button>${label}</button></form>`;
      listen('#dispatch-incoming','submit',async form => {
        const data = new FormData(form), person = people.find(item => String(item.id) === data.get('staff'));
        if (!person || risk.depositBarrelRisk && !data.has('risk')) throw new Error('请核对配送员及接收风险');
        const body = {deliveryStaffId:person.id,...(quoted ? {quoteToken:offer.quoteToken} : {}),...(risk.depositBarrelRisk ? {riskAcknowledged:true} : {})};
        const command = pool ? `${PATH}/${row.id}/claim-pool${quoted ? '-quoted' : ''}` : `${PATH}/assign${quoted ? '-quoted' : ''}/${row.id}`;
        if (await submit(command,'POST',body,`${label} · 订单 #${row.id} · ${person.name}？${quoted ? `\n配送报酬 ${money(offer.serviceAmount)}\n${offer.barrelNote ?? ''}` : ''}`)) await load(view);
      });
    });
    listen('[data-dispatch-action]','click',async button => {
      const [index,action] = button.dataset.dispatchAction.split(':');
      const row = list[Number(index)];
      const direction = view === 'directed-returns';
      const staffReturn = approvals && row.approval === 'station' && row.transferPendingSubKind === 'RETURN_STATION';
      const url = action === 'recall' ? `${PATH}/${row.id}/cancel-dispatch` : action === 'return' ? `${PATH}/${row.id}/directed-return` : direction ? `${PATH}/${row.id}/directed-return/${action}` : `${PATH}/${staffReturn ? 'return' : 'cancel-request'}/${row.id}/${action}`;
      const message = action === 'recall' ? '召回后回到本站待分配' : action === 'return' ? '提交退回申请，由原站确认' : direction || staffReturn ? (action === 'approve' ? '同意后由本站重新分配' : '驳回后保留原配送安排') :
        (action === 'approve' ? '同意后取消订单，由系统按原单规则处理款项与库存预留' : '驳回后保留原订单状态');
      if (await submit(url,'POST',direction ? {requestId:row.transferPendingRequestId} : undefined,`订单 #${row.id}\n${message}。继续办理？`)) await load(view);
    });
  }
  await load();
}
