import {deliveryCommitmentFacts} from './sales-view.js';
import {Api} from './api.js';
import {operations} from './operations.js';
import {cases} from './cases.js';
import {customerAssets, owedBarrels} from './assets-ledger.js';
import {placeOrder} from './place-order.js';
import {settlements} from './settlements.js';
import {employees} from './employees.js';
import {payroll} from './payroll.js';
import {tickets} from './tickets.js';
import {assetClaims} from './claims.js';
import {coordination} from './coordination.js';
import {profit, receivables} from './reports.js';
import {pendingSummary, stationStatus} from './station.js';
import {inventory} from './inventory.js';
import {fulfillment} from './fulfillment.js';
import {barrelReturns} from './barrel-returns.js';
import {adjustments} from './adjustments.js';
import {account} from './account.js';
import {facts,positive,rows as requireRows,screen} from './desktop.js';
const api = new Api(), root = document.querySelector('#app');
let user=null, view='home', page=1, generation=0;
const esc = v => String(v ?? '—').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const money = v => v == null ? '—' : Number.isFinite(Number(v)) ? '¥'+Number(v).toFixed(2) : '—';
const labels={home:'经营看板',pending:'待办汇总',orders:'订单查询',placeOrder:'代客下单',dispatch:'本站派单',coordination:'配送协作',payments:'收款确认',receivables:'应收账款',settlements:'站间结算',payroll:'计件工资',profit:'利润报表',customers:'客户查询',owed:'欠桶台账',returns:'退桶申请',claims:'历史资产认领',adjustments:'客户资产调整',inventory:'商品库存',tickets:'水票档位',staff:'员工管理',exceptions:'异常处理',feedback:'客户反馈',station:'营业与公告',account:'我的账号'};
const modules={pending:pendingSummary,dispatch:operations,payments:operations,exceptions:cases,feedback:cases,placeOrder,owed:owedBarrels,returns:barrelReturns,settlements,payroll,tickets,claims:assetClaims,adjustments,staff:employees,coordination,profit,receivables,station:stationStatus,inventory,account};
function login(message='') { user=null; generation++; root.innerHTML=`<form class="login"><img src="assets/aiquan-mark.png" alt="艾泉"><h1>站长工作台</h1><p class="notice">使用已绑定水站的站长账号登录</p><label>账号<input name="username" autocomplete="username" required></label><label>密码<input name="password" type="password" autocomplete="current-password" required></label><p class="error" role="alert">${esc(message)}</p><button class="primary">登录</button></form>`;
 root.querySelector('form').onsubmit=async e=>{e.preventDefault();const form=e.currentTarget, button=form.querySelector('button');button.disabled=true;try {const data=new FormData(form); user=await api.login(data.get('username'),data.get('password'));view='home';shell();}catch(error){login(error.message);} }; }
function shell(){root.innerHTML=`<aside><a class="brand" href="#"><img class="brand-mark" src="assets/aiquan-mark.png" alt=""><span>艾泉<small>AquaFlow</small></span></a><div class="station">水站 ${esc(user.stationId)}</div><nav>${Object.entries(labels).map(([key,name])=>`<button data-view="${key}" class="${view===key?'active':''}">${name}</button>`).join('')}</nav></aside><div class="shell"><header><span>${labels[view]}</span><div>${esc(user.nickname)} <button id="logout">退出</button></div></header><main><h1>${labels[view]}</h1><div id="controls"></div><div id="content" aria-live="polite"></div></main><footer>当前水站 · 站长工作台</footer></div>`;
 root.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>{view=b.dataset.view;page=1;shell();});
 root.querySelector('#logout').onclick=async()=>{const task=api.logout();login();const alert=root.querySelector('[role=alert]');try{await task;}catch{if(alert.isConnected)alert.textContent='已退出当前页面，服务端退出待核实。';}};
 const controls=root.querySelector('#controls');
 if(view==='home') controls.innerHTML='<div class="toolbar"><label>统计期间<select id="range"><option value="today">今天</option><option value="7d">近 7 天</option><option value="30d">近 30 天</option></select></label><button id="refresh">刷新</button></div>';
 if(view==='orders')controls.innerHTML='<div class="toolbar"><label>范围<select id="order-scope"><option value="local">本站归属订单</option><option value="cross">本站接下的跨站单</option></select></label><label data-local-filter>状态<select id="status"><option value="">全部</option><option value="1">待配送</option><option value="2">配送中</option><option value="3">已送达</option><option value="4">已完成</option><option value="5">已取消</option></select></label><label data-local-filter>开始日期<input id="start" type="date"></label><label data-local-filter>结束日期<input id="end" type="date"></label><button id="refresh">查询</button></div>';
 if(view==='customers')controls.innerHTML='<form class="toolbar"><label>查找客户<input id="keyword" placeholder="姓名、电话或地址"></label><button id="refresh">查询</button></form>';
 if(!controls.querySelector('#refresh'))controls.innerHTML='<button id="refresh">刷新</button>';
 controls.querySelector('#order-scope')?.addEventListener('change',event=>{
   controls.querySelectorAll('[data-local-filter]').forEach(label=>{label.hidden=event.target.value==='cross';});page=1;load();
 });
 controls.querySelector('#refresh').onclick=e=>{e.preventDefault();page=1;load();};load(); }
function table(headers,rows){return `<div class="panel"><table><thead><tr>${headers.map(h=>`<th>${h}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.map(r=>`<tr>${r.map(c=>`<td>${c}</td>`).join('')}</tr>`).join(''):`<tr><td colspan="${headers.length}">暂无记录</td></tr>`}</tbody></table></div>`;}
async function load(){const seq=++generation,content=root.querySelector('#content');content.innerHTML='<p>加载中…</p>';const value=id=>root.querySelector('#'+id)?.value;try {
 let html;
 const context={api,view,user,container:content,current:()=>seq===generation,onSessionLost:login,
   openInventory:()=>{view='inventory';page=1;shell();},openOrder:(id,back)=>detail(id,back,context),openAssets:(id,back)=>customerAssets(context,id,back)};
 if(modules[view]){await modules[view](context);return;}
 if(view==='orders' && value('order-scope')==='cross'){
   const cross=await api.get('/api/delivery/orders/cross-station');
   const {show,listen}=screen(context);
   show(`<section class="panel">${facts([['跨站履约订单',cross.count],['订单金额',money(cross.amountTotal)],['待配送',cross.pending],['配送中或已送达',cross.delivering],['已完成',cross.completed]])}</section>`+
     table(['订单','收货人／地址','状态','金额',''],requireRows(cross.orders).map(row=>[esc(row.orderNo??row.id),`${esc(row.receiverName)}<br>${esc(row.addressDetail??row.addressSnapshot)}`,esc(row.statusText),money(row.totalAmount),positive(row.id)?`<button data-cross-order="${row.id}">查看订单</button>`:'—'])));
   listen('[data-cross-order]','click',button=>context.openOrder(button.dataset.crossOrder,load));return;
 }
 if(view==='home'){
   const report=await api.get('/api/dashboard/report?range='+value('range')),s=report.summary||{};
   html=`<div class="cards">${[['订单数',esc(s.orderCount)],['订单金额',money(s.grossAmount)],['订单已付款',money(s.paidAmount)],['下单桶数',s.deliveryBucketQtyKnown===true?esc(s.deliveryBucketQty):'待核实']].map(([name,n])=>`<div class="metric">${name}<strong>${n}</strong></div>`).join('')}</div>
   ${[report.orderAmountNote,report.paidAmountNote,report.scopeNote].filter(Boolean).map(note=>`<p class="notice">${esc(note)}</p>`).join('')}`;
   if(Array.isArray(report.trend))html+='<h2>每日订单</h2>'+table(['日期','订单数','金额'],report.trend.map(row=>[esc(row.date??row.label),esc(row.orders),money(row.amount)]));
   if(Array.isArray(report.topProducts))html+='<h2>商品排行</h2>'+table(['商品','数量','订单水费'],report.topProducts.map(row=>[esc(row.productName??row.name),esc(row.qty),money(row.revenue)]));
 }
 if(view==='orders'){if(value('start')&&value('end')&&value('start')>value('end'))throw new Error('结束日期不能早于开始日期');const q=new URLSearchParams({page:String(page),pageSize:'30'});if(value('status'))q.set('status',value('status'));if(value('start'))q.set('createTimeStart',value('start')+' 00:00:00');if(value('end'))q.set('createTimeEnd',value('end')+' 23:59:59');const rows=await api.get('/api/orders?'+q);html=table(['订单','收货人 / 地址','状态','订单金额','配送员',''],rows.map(r=>[esc(r.orderNo||r.id),`${esc(r.receiverName)}<br>${esc(r.addressDetail)}`,esc(r.statusText),money(r.totalAmount),esc(r.deliveryStaffName),`<button data-order="${esc(r.id)}">查看</button>`]))+`<div class="paging"><button id="prev" ${page===1?'disabled':''}>上一页</button><span>第 ${page} 页</span><button id="next" ${rows.length<30?'disabled':''}>下一页</button></div>`;}
 if(view==='customers'){const rows=await api.get('/api/customers?'+new URLSearchParams({keyword:value('keyword')||''}));html=table(['客户','电话','地址','累计订单','累计消费',''],rows.map(r=>[esc(r.name),esc(r.phone),esc(r.matchedAddressText||r.addressText),esc(r.totalOrders),money(r.totalConsumption),`<button data-customer-assets="${esc(r.id)}">查看资产</button>`]));}
 if(seq!==generation)return;content.innerHTML=html;content.querySelector('#prev')?.addEventListener('click',()=>{page--;load();});content.querySelector('#next')?.addEventListener('click',()=>{page++;load();});content.querySelectorAll('[data-order]').forEach(b=>b.onclick=()=>context.openOrder(b.dataset.order,load).catch(e=>{if(context.current()){const note=document.createElement('p');note.className='error';note.textContent=e.message;content.prepend(note);}}));
 content.querySelectorAll('[data-customer-assets]').forEach(b=>b.onclick=()=>context.openAssets(b.dataset.customerAssets,load).catch(e=>{if(context.current()){const error=document.createElement('p');error.className='error';error.setAttribute('role','alert');error.textContent=e.message;content.prepend(error);}}));
 }catch(e){if(seq!==generation)return;if(!api.tokens){login(e.message);return;} content.innerHTML=`<p role="alert" class="error">${esc(e.message)}</p><button id="retry">重试</button>`;content.querySelector('#retry').onclick=load;}}
async function detail(id,back,context){
 const {container,current}=context;
 const r=await api.get('/api/delivery/orders/'+encodeURIComponent(id));
 if(!current())return;
 const items=Array.isArray(r.items)?r.items:null;
 container.innerHTML=`<button id="back">返回</button><section class="panel"><h2>订单 ${esc(r.orderNo||r.id)}</h2>${facts([
   ...deliveryCommitmentFacts(r), ['订单状态',r.statusText],['付款方式',r.payMethodText],['付款状态',r.payStateText],['订单金额',money(r.totalAmount)],
   ['水费',money(r.waterAmount)],['押金',money(r.depositAmount)],['配送费',money(r.deliveryFee)],['楼层费',money(r.floorFee)],
   ['订单联系人',r.receiverName],['电话',r.receiverPhone],['订单地址',r.addressSnapshot || '本单地址待核实'],['配送员',r.deliveryStaffName],['顾客备注',r.customerNote]
 ])}</section>${items?table(['商品','数量','应回桶'],items.map(row=>[esc(row.productNameSnapshot??row.productName),esc(row.quantity),row.barrelItem===true?esc(row.suggestedReturnQty):'—'])):'<p>商品明细待核实。</p>'}<div id="fulfillment"></div>`;
 fulfillment(context,r,()=>detail(id,back,context));
 container.querySelector('#back').onclick=()=>Promise.resolve(back()).catch(e=>{if(current()){const note=document.createElement('p');note.className='error';note.textContent=e.message;container.prepend(note);}});
}
login();
