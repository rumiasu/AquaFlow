import {money} from './desktop.js';
// Never infer a payable amount from reference prices or client arithmetic.
export function salePriceLabel(row) {
  const value = row.effectivePrice;
  if ((typeof value !== 'number' && typeof value !== 'string') || String(value).trim() === '' || !Number.isFinite(Number(value)) || Number(value) < 0) return '成交价待试算';
  return money(value);
}
export function deliveryCommitmentFacts(order) {
  return typeof order.deliveryDeadlineText === 'string' && order.deliveryDeadlineText.trim()
    ? [['最迟送达（订单承诺）',order.deliveryDeadlineText]] : [];
}
