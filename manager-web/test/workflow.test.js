import test from 'node:test';
import assert from 'node:assert/strict';
import {exceptionResolution,verifyCaseReceipt} from '../public/business.js';
import {makeOrderRequest} from '../public/place-order.js';
import {claimFacts} from '../public/claims.js';
import {allows} from '../public/routes.js';
import {completeRequest} from '../public/fulfillment.js';

test('exception outcomes follow distinct backend capabilities and validate recovery receipts', () => {
  const item = {caseId:8,version:2,canResolve:false,canRecordOutcome:true,canRecordNoAction:false};
  assert.equal(exceptionResolution(item,'多回的桶已经入账','key-1','RESOLVED').body.outcome,'RESOLVED');
  assert.throws(() => exceptionResolution(item,'核对','key-2','NO_ACTION'));
  assert.throws(() => exceptionResolution(item,'事'.repeat(501),'key-3','RESOLVED'));
  const receipt = {kind:'EXCEPTION',caseId:8,version:3,action:'RETURN_EMPTY'};
  assert.doesNotThrow(() => verifyCaseReceipt(receipt,'EXCEPTION',8,2,'VERIFY'));
  assert.throws(() => verifyCaseReceipt(receipt,'EXCEPTION',8,2,'RESOLVED'));
});

test('assisted order keeps quoted deposit lines, ticket fee and original key', () => {
  const user = {stationId:3};
  const input = {addressId:4,items:[{productId:9,quantity:2}],paymentMethod:2};
  const quote = {blocked:false,totalAmount:61,methods:[{id:2,enabled:true}],independentRights:true,extraDeposit:40,
    barrelPurchases:[{productId:9,quantity:1,unitPrice:40,amount:40}]};
  const order = makeOrderRequest(user,7,input,quote,'test-original');
  assert.deepEqual(order.barrelPurchases,[{productId:9,quantity:1,unitPrice:40}]);
  assert.equal(order.stationId,3);
  assert.equal(order.idempotencyKey,'test-original');
  assert.throws(() => makeOrderRequest(user,7,input,{...quote,extraDeposit:50},'key'));
  const ticket = makeOrderRequest(user,7,{...input,paymentMethod:3},{blocked:false,totalAmount:23,methods:[{id:3,enabled:true}],
    ticketFeeAmount:3,ticketDeliveryCredit:4,ticketFeeMethods:[{id:2,enabled:true}],ticketFeePaymentMethod:2},'ticket');
  assert.equal(ticket.ticketFeePaymentMethod,2);
  assert.equal(ticket.ticketFeeQuoteAmount,3);
  assert.equal(ticket.ticketDeliveryCreditQuote,4);
});

test('asset claims verify the original station, identity, and closed review evidence', () => {
  const record = {id:2,customerId:8,stationId:3,status:'PENDING',clientToken:'original',requestJson:JSON.stringify({stationId:3,
    clientToken:'original',confirmed:true,depositBalance:'40.00',note:'核对历史押金',items:[{productId:6,rights:1,tickets:0}]})};
  assert.equal(claimFacts(record,3).depositBalance,'40.00');
  assert.throws(() => claimFacts(record,4));
  assert.throws(() => claimFacts({...record,status:'APPROVED'},3));
  assert.throws(() => claimFacts({...record,clientToken:'different'},3));
});

test('money and inventory commands stay on named business endpoints', () => {
  for(const path of ['/api/manager/receivables/settle','/api/barrels/return-empty','/api/manager/payroll/42/settle']) assert.equal(allows('POST',path),true);
  for(const path of ['/api/orders/42/status','/api/manager/reconciliation/run','//example.com/api/barrels/return-empty']) assert.equal(allows('POST',path),false);
});

test('delivery uses actual item returns and requires explicit cash confirmation for a new deposit', () => {
  const order = {status:2,needCollect:true,hasOrderBarrelPurchase:true,stockPrep:{ready:true},items:[
    {id:4,barrelItem:true,quantity:3,suggestedReturnQty:1,productName:'桶装水'}, {id:6,barrelItem:false,quantity:2}
  ]};
  const form = new FormData();
  form.set('actual-4','0');
  form.set('4-customer_kept','1');
  assert.throws(() => completeRequest(order,form),/收款情况/);
  form.set('cash','pending');
  assert.throws(() => completeRequest(order,form),/收齐/);
  form.set('cash','received');
  assert.deepEqual(completeRequest(order,form).itemReturns,[{orderItemId:4,productName:'桶装水',expected:1,actual:0,reasons:[{key:'customer_kept',qty:1}]}]);
  form.set('4-customer_kept','2');
  assert.throws(() => completeRequest(order,form),/差额/);
});
