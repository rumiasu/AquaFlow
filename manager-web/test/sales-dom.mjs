// Run with PLAYWRIGHT_MODULE pointing to an installed playwright module.
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright');
const root=resolve('public');
const server=createServer(async(req,res)=>{try{const name=new URL(req.url,'http://localhost').pathname;const file=resolve(root,'.'+name);if(!file.startsWith(root+'/')&&!file.startsWith(root+'\\'))throw Error();res.setHeader('Content-Type','text/javascript; charset=utf-8');res.end(await readFile(file));}catch{res.statusCode=404;res.end();}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true});
try {
 const page=await browser.newPage();
 await page.goto(`http://127.0.0.1:${server.address().port}/sales-view.js`);
 const result=await page.evaluate(async()=>{
  const {editPromotion}=await import('/promotion.js');
  const {salePriceLabel,deliveryCommitmentFacts}=await import('/sales-view.js');
  let count=0; const check=(v,msg)=>{if(!v)throw Error(msg);count++;};
  // Mocks yield a microtask, not an assumed amount of wall-clock time.
  const tick=()=>Promise.resolve();
  function settled(form,kind) {
   return new Promise((resolve,reject)=>{
    let timer;
    const observer=new MutationObserver(()=>{
     if(!form.dataset.running){cleanup();resolve();}
    });
    function cleanup(){observer.disconnect();clearTimeout(timer);}
    observer.observe(form,{attributes:true,attributeFilter:['data-running']});
    // Timeout is only a failure watchdog, never a condition for passing.
    timer=setTimeout(()=>{cleanup();reject(Error(kind+' submit did not settle; '+document.querySelector('[data-error]')?.textContent));},5000);
    if(!form.dataset.running){cleanup();resolve();}
   });
  }
  async function scenario(kind){
   document.body.innerHTML='<div id="root"><div id="stock-editor"></div></div>';
   let live=true,writes=0,reloads=0,reads=0;
   const row={id:9,name:'测试商品',selected:true,enabled:1,regularPrice:20,salePrice:20,promotionPrice:null,promotionActive:false,effectivePrice:20};
   const user={stationId:3,staffId:4};
   const api={tokens:{},get:async(path)=>{
    if(path.endsWith('/me'))return {stationId:kind==='switch'?8:3,staffId:4};
    reads++;await tick();
    if(kind==='navigate'){live=false;return [row];}
    if(kind==='stale')return [{...row,regularPrice:22}];
    if(writes && kind==='read-error')throw Error('offline');
    return [{...row,...(writes && kind!=='mismatch'?{promotionActive:kind!=='off',promotionPrice:kind==='off'?null:'15.50'}:{})}];
   },command:async(path,method,body)=>{writes++;check(path==='/api/manager/catalog/9','scoped endpoint');check(body.promotionActive===(kind!=='off'),'correct body');await tick();if(kind==='uncertain')throw Object.assign(Error('lost'),{uncertain:true});return {};}};
   window.confirm=()=>kind!=='cancel';
   await editPromotion({api,user,container:document.querySelector('#root'),current:()=>live},row,async()=>{reloads++;});
   const form=document.querySelector('form');form.elements.mode.value=kind==='off'?'off':'on';form.elements.promotion.value='15.50';
   form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
   form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
   check(form.dataset.running==='true',kind+' submission started');
   await settled(form,kind);
   const blocked=['switch','stale','navigate','cancel'].includes(kind);
   check(writes===(blocked?0:1),kind+' writes/double submit');
   check(reloads===(['ok','off'].includes(kind)?1:0),kind+' reload');
   if(['mismatch','read-error','uncertain'].includes(kind)){
    check(form.dataset.locked==='true',kind+' locked');
    check(!!document.querySelector('[data-error]'),kind+' visible error');
    form.dispatchEvent(new Event('submit',{cancelable:true}));
    check(!form.dataset.running,'locked draft does not start a submission');
    check(writes===1,'no replay');
   }
  }
  for(const kind of ['ok','off','switch','stale','navigate','cancel','mismatch','read-error','uncertain'])await scenario(kind);
  document.body.innerHTML='<div id="price"></div>';
  document.querySelector('#price').textContent=salePriceLabel({price:20,effectivePrice:0});
  check(document.querySelector('#price').textContent==='¥0.00','DOM zero');
  check(deliveryCommitmentFacts({}).length===0,'no invented deadline');
  return count;
 });
 assert.ok(result>30);console.log(`SALES_DOM_OK ${result} assertions / 9 scenarios`);
} finally {await browser.close();await new Promise(r=>server.close(r));}
