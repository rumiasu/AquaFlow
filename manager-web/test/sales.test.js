import test from 'node:test';
import assert from 'node:assert/strict';
import {salePriceLabel,deliveryCommitmentFacts} from '../public/sales-view.js';
import {promotionBody} from '../public/promotion.js';
test('server payable price only, including zero and legacy unknown',()=>{
 assert.equal(salePriceLabel({price:20,salePrice:18,effectivePrice:15}),'¥15.00');
 assert.equal(salePriceLabel({effectivePrice:0}),'¥0.00');
 for(const value of [null,undefined,'', ' ',false,-1,'oops']) assert.equal(salePriceLabel({price:20,effectivePrice:value}),'成交价待试算');
});
test('persisted deadline is verbatim, absent deadline is not invented',()=>{
 assert.deepEqual(deliveryCommitmentFacts({deliveryDeadlineText:'明日 01:15'}),[['最迟送达（订单承诺）','明日 01:15']]);
 assert.deepEqual(deliveryCommitmentFacts({createTime:'today',openMinutes:30}),[]);
});
test('promotion commands preserve backend rules',()=>{
 const row={selected:true,regularPrice:20};
 assert.deepEqual(promotionBody(row,'on','15.50'),{promotionActive:true,promotionPrice:'15.50'});
 assert.deepEqual(promotionBody(row,'off',''),{promotionActive:false});
 for(const price of ['0','20','21','1.001','-1','','1e1']) assert.throws(()=>promotionBody(row,'on',price));
 assert.throws(()=>promotionBody({...row,regularPrice:null},'on','1'));
 assert.throws(()=>promotionBody({...row,selected:false},'off',''));
});
