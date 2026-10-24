'use strict'
// Actual WXML/WXSS, with offline state matching parent-observed simulator wording.
// 375 CSS px is the iPhone layout assumption; original attachment pixel dimensions are not claimed.
const fs=require('fs'),path=require('path'),vm=require('vm'),cp=require('child_process'),assert=require('assert')
const {chromium}=require('playwright'),{parseWxml,validateConditionalSiblings}=require('../js/wxml-tree')
const {fixture:customer}=require('../js/customer-home-recovery-view.test'),{loadPage,createWx,createApp}=require('../js/harness')
const {stationName}=require('../../miniapp-user/utils/station-view')
const ROOT=path.resolve(__dirname,'../..'),OUT=process.argv[2]||'/tmp/af-home-failure',BASE=process.argv[3]||'d088919'
const read=(file,old)=>old?cp.execFileSync('git',['show',BASE+':'+file],{cwd:ROOT,encoding:'utf8'}):fs.readFileSync(path.join(ROOT,file),'utf8')
const css=(file,old)=>read(file,old).replace(/\/\*[\s\S]*?\*\//g,'').replace(/@import\s+["']([^"']+)["'];/g,(_,f)=>css(path.posix.join(path.posix.dirname(file),f),old))
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))
const evaluate=(text,data)=>vm.runInNewContext(text,new Proxy({...data},{get:(target,key)=>target[key]}),{timeout:100})
const value=(text,data)=>text.replace(/{{([\s\S]*?)}}/g,(_,e)=>evaluate(e,data)??'')
function render(children,data,nav){let html='',taken=false
 for(const n of children){if(n.tag==='#text'){html+=esc(value(n.value,data));continue}const a=n.attrs
  if(['page-meta','import','privacy-popup'].includes(n.tag))continue
  if(n.tag==='template'){if(a.is==='stationNavbar')html+=render(nav.children,data,nav);continue}
  if('wx:for' in a){const rows=evaluate(a['wx:for'].slice(2,-2),data),copy={...a};delete copy['wx:for'];for(let i=0;i<rows.length;i++)html+=render([{...n,attrs:copy}],{...data,item:rows[i],index:i},nav);taken=false;continue}
  let show=true;if('wx:if'in a){show=!!evaluate(a['wx:if'].slice(2,-2),data);taken=show}else if('wx:elif'in a){show=!taken&&!!evaluate(a['wx:elif'].slice(2,-2),data);taken||=show}else if('wx:else'in a){show=!taken;taken=true}else taken=false
  if(!show)continue;if(n.tag==='block'){html+=render(n.children,data,nav);continue}
  const tag=n.tag==='text'?'span':n.tag==='button'?'button':'div';let attrs=''
  for(const key of ['class','style'])if(key in a)attrs+=' '+key+'="'+esc(value(a[key],data))+'"'
  if(a.size==='mini')attrs+=' data-mini="true"'
  if(a.bindtap||a.catchtap)attrs+=' data-handler="'+(a.bindtap||a.catchtap)+'"'
  html+='<'+tag+attrs+'>'+render(n.children,data,nav)+'</'+tag+'>'
 }return html
}
function state(side,old){
 if(side==='miniapp-user'){const f=customer(),name='SYNTH Task5 Scenario Station';f.page.setData({isLogin:true,state:'ready',loading:false,catalogState:'error',currentStation:{id:1,name},currentStationName:old?name:stationName({name}),homeWaterBackdropFailed:true,statusBarHeight:24,navBarHeight:44,navContentWidth:200})
  f.page._noteLoadError('商品、订单信息、桶资产、配送地址暂未加载，请重新读取。','overview');f.page._noteLoadError('提醒暂未加载，请重试。','notifications')
  if(old)f.page.setData({loadImpactSummary:'商品、订单状态、桶资产、配送地址与提醒暂未核实'})
  return f.page.data
 }
 const p=loadPage('miniapp-delivery/pages/home/index.js',{wx:createWx(),app:createApp(),stubs:{'api/delivery':{},'utils/request':{get:async()=>({code:0,data:{}})}}})
 p.setData({title:'配送',rightPad:true,statusBarHeight:24,navBarHeight:44,navTitleLeft:160,navTitleRight:104,navLeftMax:92,stationStatusError:'合成读取失败',stationStatusText:'',stationStatusNote:'',stationStatusHint:'',stationPendingCount:0,homeStationLabel:'当前水站：名称未核实',loading:false,loadError:old?'有 5 项尚未读取，列表可能不完整，请重新加载':'5项信息暂未加载',readReady:{assigned:false,delivering:false,completed:false,unpaid:false,transfers:false}})
 return p.data
}
;(async()=>{fs.mkdirSync(OUT,{recursive:true});const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox']}),page=await browser.newPage(),results=[];await page.route('**/*',r=>r.abort())
 try{for(const size of [{name:'iphone',width:375,height:720,scale:1},{name:'small-large-font',width:320,height:568,scale:1.3}])for(const side of ['miniapp-user','miniapp-delivery'])for(const old of [true,false]){
  const file=side+'/pages/home/index',root=parseWxml(read(file+'.wxml',old));validateConditionalSiblings(root)
  const navRoot=parseWxml(read('miniapp-delivery/templates/station-navbar.wxml',old)),nav=navRoot.children.find(n=>n.tag==='template')
  const style=(css(side+'/app.wxss',old)+css(file+'.wxss',old)).replace(/(?<![.\w-])page\s*\{/g,'body {').replace(/(?<![.\w-])text(?=\s*\{)/g,'span').replace(/([\d.]+)rpx/g,(_,n)=>Number(n)*size.width/750+'px').replace(/font-size:\s*([\d.]+)px/g,(_,n)=>'font-size:'+Number(n)*size.scale+'px').replace(/env\(safe-area-inset-bottom\)/g,'16px')
  await page.setViewportSize({width:size.width,height:size.height})
  await page.setContent('<meta charset="utf-8"><style>html,body{margin:0;width:100%;font-family:"Noto Sans CJK SC",sans-serif}div,span,button{box-sizing:border-box}button{display:block;font:inherit;border:0}button[data-mini]{display:inline-block;width:auto}</style><style>'+style+'</style>'+render(root.children,state(side,old),nav))
  const name=(old?'before-':'after-')+(side==='miniapp-user'?'customer':'delivery')+'-'+size.name
  const g=await page.evaluate(()=>{const rect=s=>{const e=document.querySelector(s);if(!e)return null;const r=e.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,height:r.height}};return {width:innerWidth,scrollWidth:document.documentElement.scrollWidth,text:document.body.innerText,legacyBanners:document.querySelectorAll('.load-error').length,mergedBanners:document.querySelectorAll('.home-read-feedback').length,customerRetry:document.querySelectorAll('button[data-handler="onRetryHome"]').length,copy:rect('.home-read-feedback-copy'),retry:rect('.home-read-feedback-retry'),feedback:rect('.home-read-feedback')||rect('.home-feedback'),tabs:rect('.tab-bar'),catalogError:document.querySelectorAll('.catalog-state').length}})
  await page.screenshot({path:path.join(OUT,name+'.png')})
  if(!old){assert(g.scrollWidth<=size.width+1,name+' horizontal overflow');if(side==='miniapp-delivery'){assert.equal(g.mergedBanners,1);assert.equal(g.legacyBanners,0);assert(g.copy.right+2<=g.retry.left,name+' independent action');assert(g.retry.height>=44);assert(g.text.includes('不代表结清'));assert(!g.text.includes('核对待收款'));}else{assert.equal(g.customerRetry,1);assert.equal(g.catalogError,0);assert(!g.text.includes('SYNTH'));assert(g.text.includes('示例水站（测试）'));}}
  results.push({...size,name,side,before:old,...g})
 }fs.writeFileSync(path.join(OUT,'layout.json'),JSON.stringify({baseline:BASE,scope:'Actual WXML/WXSS; synthetic state matching parent-observed simulator text. 375 CSS px iPhone assumption + 320 stress; not native WeChat or exact original attachment dimensions.',results},null,2));console.log('AQUAFLOW_LAYOUT_OK '+results.length)
 }finally{await browser.close()}
})().catch(e=>{console.error(e.stack);process.exitCode=1})
