// Only named workflow commands. Never patch a generic order status or local balance.
const id = value => { if(!/^[1-9]\d*$/.test(String(value))) throw new Error('记录编号无效'); return String(value); };
export function assignment(order,staff,stationId) {
  if(!order || order.status!==1 || order.deliveryStaffId || String(order.stationId)!==String(stationId) ||
    (order.deliveryStationId && String(order.deliveryStationId)!==String(stationId)) || order.dispatchAgreement ||
    staff?.role!=='DELIVERY' || staff.status!==1 || String(staff.stationId)!==String(stationId)) throw new Error('本单请在小程序调度页办理');
  return {path:'/api/delivery/orders/assign/'+id(order.id),method:'POST',body:{deliveryStaffId:Number(id(staff.id))}};
}
export function paymentConfirmation(payment) {
  if(!payment || payment.amount==null || String(payment.amount).trim()==='' || !Number.isFinite(Number(payment.amount)) || Number(payment.amount)<=0) throw new Error('收款金额不完整，请刷新');
  return {path:'/api/payments/'+id(payment.id)+'/confirm',method:'PUT'};
}
export function exceptionResolution(item,reason,key,outcome) {
  const allowed = {VERIFY:item?.canResolve, RESOLVED:item?.canRecordOutcome, NO_ACTION:item?.canRecordNoAction};
  if(allowed[outcome ?? 'VERIFY']!==true || item.closed===true || !Number.isSafeInteger(item.version) || item.version<0) throw new Error('当前异常不能执行此操作，请刷新核对');
  if(!reason?.trim() || reason.trim().length>500 || !/^[A-Za-z0-9_-]{1,64}$/.test(key ?? ''))throw new Error('请填写办理结果，最多500字');
  return {path:'/api/manager/exceptions/'+id(item.caseId)+'/resolve',method:'POST',body:{expectedVersion:item.version,idempotencyKey:key,reason:reason.trim(),...(outcome?{outcome}:{})}};
}

export function verifyCaseReceipt(receipt, kind, caseId, version, action) {
  if (receipt?.kind !== kind || String(receipt.caseId) !== String(caseId) || receipt.version !== version + 1 ||
      !(receipt.action === action || kind === 'EXCEPTION' && action === 'VERIFY' && receipt.action === 'RETURN_EMPTY')) {
    throw new Error('办理回执待核实，请刷新原记录');
  }
}
export async function execute(api,command) {return api.command(command.path,command.method,command.body);}
