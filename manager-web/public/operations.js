import {assignment,paymentConfirmation,execute} from './business.js';
const esc=v=>String(v??'—').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const money=v=>v==null?'—':'¥'+Number(v).toFixed(2);
export async function operations({api,view,user,container,current}) {
 const show=html=>{if(current())container.innerHTML=html;};
 const table=(head,rows)=>`<div class="panel"><table><thead><tr>${head.map(x=>`<th>${x}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.map(row=>`<tr>${row.map(x=>`<td>${x}</td>`).join('')}</tr>`).join(''):`<tr><td colspan="${head.length}">暂无记录</td></tr>`}</tbody></table></div>`;
 const rerun=()=>operations({api,view,user,container,current}).catch(e=>show(`<p role="alert">${esc(e.message)}</p>`));
 async function submit(command,button){button.disabled=true;try {await execute(api,command);if(current())await rerun();}catch(e){if(current()){const note=document.createElement('p');note.setAttribute('role','alert');note.textContent=e.message;container.prepend(note);button.disabled=true;}}}
 if(view==='dispatch') {
  const [orders,staff]=await Promise.all([api.get('/api/delivery/orders/station-pending'),api.get('/api/staff')]);
  const people=staff.filter(s=>s.role==='DELIVERY'&&s.status===1&&String(s.stationId)===String(user.stationId));
  show(table(['订单','收货信息','状态','配送员','办理'],orders.map(r=>{let eligible=false;try{assignment(r,people[0],user.stationId);eligible=true;}catch{}
   return [esc(r.id),`${esc(r.receiverName)}<br>${esc(r.addressDetail)}`,esc(r.statusText),eligible?`<select aria-label="订单 ${esc(r.id)} 的配送员" data-staff="${esc(r.id)}">${people.map(s=>`<option value="${esc(s.id)}">${esc(s.name)}</option>`).join('')}</select>`:'—',eligible?`<button data-assign="${esc(r.id)}">分配</button>`:'请在小程序调度页办理'];})));
  if(!current())return;container.querySelectorAll('[data-assign]').forEach(b=>b.onclick=()=>{const row=orders.find(r=>String(r.id)===b.dataset.assign),staffId=container.querySelector(`[data-staff="${b.dataset.assign}"]`).value,person=people.find(s=>String(s.id)===staffId);if(!current())return;const cmd=assignment(row,person,user.stationId);if(confirm(`将订单 #${row.id} 分配给 ${person.name}？`))submit(cmd,b);});
 }
 if(view==='payments'){
  let cursor=null; const rows=[];
  async function history(){
   const result=await api.get('/api/payments?limit=200');
   if(!Array.isArray(result))throw Error('收款流水格式不符，请重试');
   show('<button id="pending-payments">返回待确认收款</button>'+table(['记录编号','订单','金额','方式','状态','记录时间'],result.map(row=>[esc(row.id),esc(row.orderId),money(row.amount),esc(row.methodText||row.paymentMethodText),esc(row.statusText),esc(row.payTime||row.createTime)])));
   if(current())container.querySelector('#pending-payments').onclick=rerun;
  }
  async function next(){const result=await api.get('/api/payments/pending?'+new URLSearchParams({pageSize:'50',...(cursor?{cursor}:{})}));if(!Array.isArray(result.items))throw Error('收款记录格式不符，请重试');if(!current())return;const known=new Set(rows.map(r=>String(r.id)));rows.push(...result.items.filter(r=>!known.has(String(r.id))));cursor=result.nextCursor;
   show('<button id="payment-history">最近收款流水</button>'+table(['收款记录','用途','客户 / 订单','金额','操作'],rows.map(r=>{let allowed=true;try{paymentConfirmation(r)}catch{allowed=false}return [esc(r.id),esc(r.purposeText),`${esc(r.customerName)} / #${esc(r.orderId)}`,money(r.amount),allowed?`<button data-payment="${esc(r.id)}">确认到账</button>`:'金额待核实']}))+(cursor?'<button id="more-payments">加载更多</button>':''));
   container.querySelector('#payment-history').onclick=()=>history().catch(error=>{if(current())alert(error.message)});
   container.querySelector('#more-payments')?.addEventListener('click',e=>{e.target.disabled=true;next().catch(error=>{if(current()){e.target.disabled=false;alert(error.message);}})});
   container.querySelectorAll('[data-payment]').forEach(b=>b.onclick=()=>{if(!current())return;const row=rows.find(r=>String(r.id)===b.dataset.payment);const message=`确认已收到收款记录 #${row.id} 的 ${money(row.amount)}？\n${row.purposeText||''}${row.orderId?' · 订单 #'+row.orderId:''}${!row.orderId&&Number(row.ticketQty)>0?'\n确认后水票入账。':''}`;if(confirm(message))submit(paymentConfirmation(row),b);});
  }await next();
 }
}
