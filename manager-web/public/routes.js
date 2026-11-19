const read = [
  'auth/me', 'dashboard/report', 'orders(?:/[1-9]\\d*)?', 'customers(?:/[1-9]\\d*/(?:profile|assets))?',
  'staff(?:/[1-9]\\d*/profile)?', 'payments(?:/pending)?', 'manager/exceptions(?:/[1-9]\\d*/resolution)?',
  'manager/order-assist/customers(?:/[1-9]\\d*/addresses)?', 'products/sale-by-station',
  'manager/(?:owed-barrels|inter-station-settlements|station-barrel-balances|inter-station-recoveries|business-waiting(?:/returns)?|gross-profit|receivables(?:/orders)?)',
  'barrels/records/[1-9]\\d*/(?:handover-evidence|refund-eligibility)', 'manager/pending-summary/return-record/[1-9]\\d*',
  'manager/(?:staff|piece-rate|earnings|earning-items(?:/directions)?|payroll(?:/preview|/[1-9]\\d*/settlement-preview)?)',
  'manager/(?:bind/applications|asset-claims(?:/[1-9]\\d*(?:/photo)?)?)',
  'manager/(?:catalog|ticket-delivery-offers)', 'ticket-packages/manage', 'ticket-discounts(?:/presets)?',
  'delivery/orders/(?:[1-9]\\d*(?:/cross-station-risk)?|station-pending|station-delivering|pool|directed-incoming|dispatch-tracking|directed-returns|pending-approvals|cross-station)',
  'stations/(?:search|mine)',
  'manager/(?:station-status|pending-summary|delivery-config|setup-guide)', 'notices/all',
  'inventory/records',
  'manager/adjustments(?:/[1-9]\\d*)?',
  'manager/customers/[1-9]\\d*/(?:risk|credit-terms)', 'customers/[1-9]\\d*/offline-payment/summary',
  'feedback/(?:customers|[1-9]\\d*/handling)'
];
const write = {
  POST: [
    'delivery/orders/assign(?:-quoted)?/[1-9]\\d*', 'manager/exceptions/[1-9]\\d*/(?:resolve|write-off)',
    'manager/order-assist/quote', 'orders/create', 'barrels/return-empty',
    'manager/inter-station-settlements/[1-9]\\d*/(?:settle|reverse|price-by-listed)',
    'manager/payroll(?:/settle|/[1-9]\\d*/settle|/adjust)', 'manager/earning-items(?:/[1-9]\\d*/status)?',
    'staff', 'manager/bind/(?:approve|reject|release|unbind-confirm|unbind-reject)', 'manager/staff/[1-9]\\d*/bind-code',
    'manager/asset-claims/[1-9]\\d*/review', 'manager/receivables/settle', 'ticket-packages', 'ticket-discounts',
    'delivery/orders/[1-9]\\d*/(?:accept|complete|claim-pool(?:-quoted)?|cancel-dispatch|directed-return(?:/(?:approve|reject))?)', 'delivery/orders/(?:return|cancel-request)/[1-9]\\d*/(?:approve|reject)',
    'delivery/orders/transfer/[1-9]\\d*/outsource',
    'feedback/[1-9]\\d*/(?:reply|close)', 'notices', 'manager/catalog/[1-9]\\d*/(?:select|stock)', 'inventory/inbound-with-cost-receipt',
    'manager/adjustments(?:/preview|/[1-9]\\d*/(?:execute|reverse))?', 'manager/customers/[1-9]\\d*/credit-terms/recalculate', 'auth/(?:change-password|update-profile)'
  ],
  PUT: ['payments/[1-9]\\d*/confirm', 'staff/[1-9]\\d*', 'manager/piece-rate', 'manager/earning-items/[1-9]\\d*', 'manager/ticket-delivery-offers', 'manager/(?:station-status|delivery-config)', 'stations/[1-9]\\d*/profile', 'notices/[1-9]\\d*', 'manager/catalog/[1-9]\\d*', 'barrels/records/[1-9]\\d*/(?:approve|status)', 'manager/customers/[1-9]\\d*/credit-terms', 'customers/[1-9]\\d*/offline-payment'],
  DELETE: ['ticket-packages/[1-9]\\d*', 'ticket-discounts/[1-9]\\d*', 'manager/earning-items/[1-9]\\d*', 'notices/[1-9]\\d*']
};

const matcher = paths => new RegExp(`^/api/(?:${paths.join('|')})(?:\\?[^#]*)?$`);
export const canRead = matcher(read);
const canWrite = Object.fromEntries(Object.entries(write).map(([method, paths]) => [method, matcher(paths)]));
export const allows = (method, path) => canWrite[method]?.test(path) === true;
