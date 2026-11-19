import test from 'node:test';
import assert from 'node:assert/strict';
import {singleAddressId,needsOrderConfirmation,makeOrderRequest} from '../public/place-order.js';
const address={id:4,name:'合成收货人',phone:'13800000000',detail:'合成地址 1 号'};
test('only one complete address may be preselected',()=>{
 assert.equal(singleAddressId([address]),'4');
 for(const list of [[],[address,{...address,id:5}],[{...address,detail:' '}],[{...address,phone:null}],[{...address,id:0}],[address,null]]) assert.equal(singleAddressId(list),'');
});
test('ordinary quotes avoid an extra dialog but risk information retains confirmation',()=>{
 assert.equal(needsOrderConfirmation({warnings:['','  '],barrelPurchases:[],extraDeposit:0}),false);
 for(const quote of [{warnings:['超出配送范围']},{extraDeposit:40},{barrelPurchases:[{}]}]) assert.equal(needsOrderConfirmation(quote),true);
 assert.throws(()=>needsOrderConfirmation({warnings:[{}]}));
 const input={paymentMethod:2,items:[{productId:9,quantity:1}]};
 for(const totalAmount of [false,' ',null]) assert.throws(()=>makeOrderRequest({stationId:3},7,input,{blocked:false,totalAmount,methods:[{id:2,enabled:true}]},'key'));
});

test('real DOM order friction and recovery flows', {skip:!process.env.PLAYWRIGHT_MODULE},async()=>{
 const {pathToFileURL,fileURLToPath}=await import('node:url');
 const {createServer}=await import('node:http');
 const {readFile}=await import('node:fs/promises');
 const {resolve,sep}=await import('node:path');
 const {chromium}=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
 const root=resolve(fileURLToPath(new URL('../public/',import.meta.url)));
 const server=createServer(async(req,res)=>{try{const file=resolve(root,'.'+new URL(req.url,'http://localhost').pathname);if(!file.startsWith(root+sep))throw Error();res.setHeader('Content-Type','text/javascript; charset=utf-8');res.end(await readFile(file));}catch{res.statusCode=404;res.end();}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 let browser;
 try{
  browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true});
  const page=await browser.newPage();await page.goto(`http://127.0.0.1:${server.address().port}/place-order.js`);
  const result=await page.evaluate(async()=>{
   const {placeOrder}=await import('/place-order.js');
   let assertions=0;const check=(value,label)=>{if(!value)throw Error(label);assertions++;};
   async function event(node,type,double=false){
    node.dispatchEvent(new Event(type,{bubbles:true,cancelable:true}));
    if(double)node.dispatchEvent(new Event(type,{bubbles:true,cancelable:true}));
    if(!node.dataset.running)return;
    await new Promise((resolve,reject)=>{const obs=new MutationObserver(()=>{if(!node.dataset.running){clearTimeout(timer);obs.disconnect();resolve();}});obs.observe(node,{attributes:true,attributeFilter:['data-running']});const timer=setTimeout(()=>{obs.disconnect();reject(Error('event did not settle'));},5000);});
   }
   for(const kind of ['normal','warnings','deposit','cancel','shortage','unknown','changed','multiple','incomplete','requote','latequote','manual-missing-phone','manual-missing-name','manual-missing-detail']){
    sessionStorage.clear();document.body.innerHTML='<main id="root"></main>';
    let creates=[],quotes=0,confirms=0,failOnce=true,releaseQuote,quoteStarted;
    const entered=new Promise(resolve=>{quoteStarted=resolve;});
    window.confirm=()=>{confirms++;return kind!=='cancel';};
    const address={id:4,name:'Synthetic receiver',phone:'13800000000',detail:'Synthetic road 1'};
    const api={tokens:{},get:async path=>path.includes('/addresses')?(kind==='multiple'?[address,{...address,id:5}]:kind==='incomplete'?[{...address,detail:''}]:kind.startsWith('manual-missing-')?[{...address,[kind.slice('manual-missing-'.length)]:''}]:[address]):path.includes('sale-by-station')?[{id:9,name:'Water',effectivePrice:15}]:[{id:7,name:'Synthetic customer'}],command:async(path,method,body)=>{
     await Promise.resolve();
     if(path.includes('/quote')){quotes++;if(kind==='latequote'){await new Promise(resolve=>{releaseQuote=resolve;quoteStarted();});}return {blocked:false,totalAmount:kind==='deposit'?55:15,waterAmount:15,deliveryFee:0,floorFee:0,extraDeposit:kind==='deposit'?40:0,methods:[{id:2,enabled:true}],warnings:kind==='warnings'||kind==='cancel'?['Synthetic business warning']:[],barrelPurchases:kind==='deposit'?[{productId:9,quantity:1,unitPrice:40,amount:40}]:[]};}
     creates.push(structuredClone(body));
     if(kind==='shortage'&&!body.confirmShortage)return {needConfirm:true,shortages:[{productName:'Water',requested:1,stock:0}]};
     if(kind==='unknown'&&failOnce){failOnce=false;return {};}
     return {orderId:99};
    }};
    await placeOrder({api,user:{staffId:2,stationId:3},container:document.querySelector('#root'),current:()=>true,openOrder:()=>{}});
    await event(document.querySelector('[data-customer]'),'click');
    const draft=document.querySelector('#order-draft');
    check(draft.elements.address.value===(['multiple','incomplete'].includes(kind)||kind.startsWith('manual-missing-')?'':'4'),kind+' default address');
    if(['multiple','incomplete'].includes(kind)){check(creates.length===0,'no auto create');continue;}
    if(kind.startsWith('manual-missing-'))draft.elements.address.value='4';
    draft.elements['product-9'].value='1';
    if(kind==='latequote'){const pending=event(draft,'submit');await entered;draft.elements['product-9'].value='2';draft.dispatchEvent(new Event('input',{bubbles:true}));releaseQuote();await pending;check(!document.querySelector('#create-order'),'late quote ignored after edit');check(creates.length===0,'late quote cannot create');continue;}
    await event(draft,'submit');
    check(document.querySelector('#order-quote').textContent.includes(kind==='manual-missing-detail'?'所选历史地址':'Synthetic road 1'),'selected address or missing-field warning visible');
    if(kind.startsWith('manual-missing-'))check(document.querySelector('#order-quote').textContent.includes('所选历史地址'),'incomplete address risk is visible');
    check(document.querySelector('#order-quote').textContent.includes(kind==='deposit'?'55.00':'15.00'),'exact quoted total');
    if(kind==='changed'){
     draft.elements['product-9'].value='2';draft.dispatchEvent(new Event('change',{bubbles:true}));check(!document.querySelector('#create-order'),'change invalidates quote');check(!creates.length,'no stale order');continue;
    }
    if(kind==='requote'){await event(draft,'submit');check(quotes===2,'fresh quote');}
    await event(document.querySelector('#create-order'),'click',true);
    if(kind==='cancel'){check(creates.length===0,'cancel risk quote');continue;}
    check(creates.length===1,'double click creates once');
    check(confirms===(['warnings','deposit'].includes(kind)||kind.startsWith('manual-missing-')?1:0),kind+' confirmation count');
    if(kind==='shortage'){check(!!document.querySelector('#allow-shortage'),'shortage recovery');await event(document.querySelector('#allow-shortage'),'click',true);check(confirms===1,'shortage confirmation');check(creates[1].confirmShortage===true,'shortage explicit');check(creates[0].idempotencyKey===creates[1].idempotencyKey,'shortage same key');}
    if(kind==='unknown'){check(!!document.querySelector('#retry-order'),'unknown receipt recovery');await event(document.querySelector('#retry-order'),'click',true);check(creates.length===2,'only explicit retry');check(creates[0].idempotencyKey===creates[1].idempotencyKey,'retry same key');}
   }
   return assertions;
  });
  assert.ok(result>=40);console.log(`ORDER_FRICTION_DOM_OK ${result} assertions / 14 scenarios`);
 }finally{if(browser)await browser.close();await new Promise(r=>server.close(r));}
});
