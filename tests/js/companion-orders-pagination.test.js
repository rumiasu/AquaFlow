const assert = require('assert')
const { readCompanionOrders } = require('../../miniapp-user/utils/companion-orders')
let passed = 0
async function test(name, fn) { await fn(); passed++; console.log('PASS ' + name) }
const rows = (start,n,status=1,stationId=11)=>Array.from({length:n},(_,i)=>({id:start+i,status,stationId}))
;(async()=>{
 await test('drains active pages using pageSize',async()=>{const calls=[];const result=await readCompanionOrders(async q=>{calls.push(q);return {code:0,data:q.status===1?(q.page===1?rows(1,100):rows(101,2)):rows(201,1,2)}},11,()=>true);assert.equal(result.length,103);assert.deepEqual(calls.map(q=>[q.status,q.page,q.pageSize]),[[1,1,100],[1,2,100],[2,1,100]])})
 await test('filters foreign stations and terminal orders',async()=>{const result=await readCompanionOrders(async()=>({code:0,data:[...rows(1,1),...rows(2,1,4),...rows(3,1,1,12)]}),11,()=>true);assert.deepEqual(result.map(r=>r.id),[1])})
 await test('late identity change discards results',async()=>{let current=true;assert.equal(await readCompanionOrders(async()=>{current=false;return {code:0,data:rows(1,1)}},11,()=>current),null)})
 await test('repeated full pages fail visibly',async()=>{await assert.rejects(()=>readCompanionOrders(async()=>({code:0,data:rows(1,100)}),11,()=>true))})
 await test('business error and malformed response fail',async()=>{for(const response of [{code:1,data:[]},{code:0,data:null},{code:0,data:[{id:1}]}])await assert.rejects(()=>readCompanionOrders(async()=>response,11,()=>true))})
 await test('records envelope honors hasMore and deduplicates',async()=>{const result=await readCompanionOrders(async q=>({code:0,data:{records:rows(q.page===1?1:2,1,q.status),hasMore:q.page===1}}),11,()=>true);assert.equal(result.length,2)})
 console.log('AQUAFLOW_SUITE_OK '+passed)
})().catch(e=>{console.error(e);process.exitCode=1})
