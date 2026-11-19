import test from 'node:test';
import assert from 'node:assert/strict';
import {fillExpectedReturns, fulfillment, completeRequest} from '../public/fulfillment.js';
const order=()=>({id:9,status:2,stationId:3,needCollect:true,totalAmount:30,paymentMethod:2,stockPrep:{ready:true},items:[{id:11,barrelItem:true,quantity:8,suggestedReturnQty:2,productName:'示意水'},{id:12,barrelItem:false,quantity:4},{id:13,barrelItem:true,quantity:3,suggestedReturnQty:0}]});
function form(){const fields=new Map(['actual-11','actual-13','11-lost','13-other','cash','floor','note'].map(n=>[n,{value:'',validity:{badInput:false}}]));return {fields,elements:{namedItem:n=>fields.get(n)}};}
test('explicit mixed-row fill uses return plan not delivery quantity; cash remains independent',()=>{
 const f=form();assert.equal(fillExpectedReturns(order(),f),2);assert.equal(f.fields.get('actual-11').value,'2');assert.equal(f.fields.get('actual-13').value,'0');assert.equal(f.fields.get('cash').value,'');assert.equal(fillExpectedReturns(order(),f),0);
 const values=new FormData();for(const [k,v]of f.fields)values.set(k,v.value);assert.throws(()=>completeRequest(order(),values));values.set('cash','pending');assert.equal(completeRequest(order(),values).collected,false);values.set('actual-11','1');assert.equal(completeRequest(order(),values).itemReturns[0].actual,1);
});
test('hand values, discrepancy reasons and invalid numeric typing are not overwritten',()=>{
 for(const value of ['0','1','bad',' ']){const f=form();f.fields.get('actual-11').value=value;fillExpectedReturns(order(),f);assert.equal(f.fields.get('actual-11').value,value);}
 for(const field of ['11-lost','actual-11']){const f=form();f.fields.get(field).validity.badInput=true;fillExpectedReturns(order(),f);assert.equal(f.fields.get('actual-11').value,'');}
 const f=form();f.fields.get('11-lost').value='1';fillExpectedReturns(order(),f);assert.equal(f.fields.get('actual-11').value,'');
});
test('unknown incomplete invalid and duplicate plans do not become zero or partially fill',()=>{
 for(const value of [undefined,null,'2',-1,1.5,NaN,Infinity,2147483648]){const o=order(),f=form();o.items[2].suggestedReturnQty=value;assert.equal(fillExpectedReturns(o,f),0);assert.equal(f.fields.get('actual-11').value,'');}
 for(const mutate of [o=>o.items.push(null),o=>delete o.items[1].barrelItem,o=>o.items[2].id=11,o=>o.firstBarrelOrder=true,o=>o.status=1]){const o=order(),f=form();mutate(o);assert.equal(fillExpectedReturns(o,f),0);}
 const f=form();f.fields.delete('actual-13');assert.equal(fillExpectedReturns(order(),f),0);assert.equal(f.fields.get('actual-11').value,'');
});
test('actual render wires explicit non-submit action, no automatic API call or cash selection',async()=>{
 const f=form(),handlers={};const button={tagName:'BUTTON',dataset:{},isConnected:true,disabled:false,addEventListener:(ev,fn)=>handlers.click=fn};Object.assign(f,{tagName:'FORM',dataset:{},isConnected:true,addEventListener:(ev,fn)=>handlers.submit=fn});
 const host={innerHTML:'',querySelector:s=>s==='#finish-delivery'?f:null};const container={querySelector:s=>s==='#fulfillment'?host:null,querySelectorAll:s=>s==='#fill-expected-returns'?[button]:s==='#finish-delivery'?[f]:[]};
 let requests=0;fulfillment({user:{stationId:3},container,current:()=>true,api:{command(){requests++;throw Error('unexpected write')}}},order(),()=>{throw Error('unexpected refresh')});
 assert.match(host.innerHTML,/<button type="button" id="fill-expected-returns">/);assert.match(host.innerHTML,/name="actual-11"/);assert.doesNotMatch(host.innerHTML,/name="actual-12"/);assert.equal(f.fields.get('actual-11').value,'');await handlers.click({preventDefault(){}});assert.equal(f.fields.get('actual-11').value,'2');assert.equal(f.fields.get('cash').value,'');assert.equal(requests,0);
});
