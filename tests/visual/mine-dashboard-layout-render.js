// Browser-only static projection. No application lifecycle, event handler, login or business request runs.
const fs = require('fs'), path = require('path'), vm = require('vm'), crypto = require('crypto')
const { loadPage, createWx, createApp } = require('../js/harness')
const { parseWxml, validateConditionalSiblings } = require('../js/wxml-tree')
const state = { isLogin:true, userInfo:{nickname:'测试顾客'}, stationName:'艾泉演示水站·历下店（虚构）', viewStationId:1, orderStationName:'艾泉演示水站·历下店（虚构）', viewStationStatusText:'正常站点', depositLoaded:true, ticketLoaded:true, barrelLoaded:true, depositText:'150', ticketText:'7', barrelText:'3', unloadedCount:0 }
const cases = [320,360,375,390,430].map(width => ({ id:'mine-'+width, route:'mine/index', width, state, label:'¥150 与常用服务间距' }))
cases.push({id:'mine-decimals',route:'mine/index',width:320,state:{...state,depositText:'150.50'},label:'完整小数'})
cases.push({id:'mine-long',route:'mine/index',width:320,state:{...state,assetLongValues:true,depositText:'1234567.89'},label:'长金额'})
cases.push({id:'mine-large-text',route:'mine/index',width:320,state:{...state,assetLargeText:true},label:'大字模式'})
for (const scopeExpanded of [false,true]) cases.push({id:'dashboard-'+(scopeExpanded?'expanded':'collapsed'),app:'miniapp-delivery',route:'station-mgmt/dashboard/index',width:375,state:{loading:false,scopeExpanded,report:{hasData:false,label:'今日',metricCards:[{key:'paidAmount',valueText:'¥150.00',label:'订单已付款',deltaDir:'flat',deltaText:'持平',previousText:'¥150.00'}],orderAmountNote:'订单金额按订单创建时间、结算站统计未取消订单的金额；不是实收净额，不扣售后退款。时间按上海时区。',scopeNote:'这里按营收归属统计：外派给别站配送的单，钱算接单站的，不计入本站。',paidAmountNote:'「订单已付款」是已付款订单金额合计，不等于本站收到的现金；客户先买票、还没兑水的钱也不在内。'}},label:'统计口径按需展开'})
const HTML_ONLY = process.argv.includes('--html-only')
const ROOT = path.resolve(__dirname, '../..'), OUT = process.argv.slice(2).find(arg => arg !== '--html-only') || path.join(require('os').tmpdir(), 'aquaflow-mine-dashboard-layout')
const escape = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))
const trees = new Map(), components = new Map(), errors = []
function read(file) { return fs.readFileSync(path.join(ROOT, file), 'utf8') }
function tree(file) {
  if (!trees.has(file)) { const value = parseWxml(read(file)); validateConditionalSiblings(value); trees.set(file, value) }
  return trees.get(file)
}
// WeChat treats absent nested bindings as empty. Optional chaining emulates that display rule only.
function evaluate(source, data) {
  const expr = source.split(/('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")/g).map((piece, i) => i % 2 ? piece : piece.replace(/(?<=[\w\]\)])\.(?=[A-Za-z_$])/g, '?.')).join('')
  try { return vm.runInNewContext(expr, new Proxy({...data}, {get:(target,key)=>target[key]}), { timeout: 100 }) }
  catch (error) { errors.push({expression:source,error:error.message}); return '' }
}
function value(text, data) {
  const one = /^{{([\s\S]*)}}$/.exec(text)
  return one ? evaluate(one[1], data) : text.replace(/{{([\s\S]*?)}}/g, (_, expr) => evaluate(expr, data) ?? '')
}
function image(src) {
  if (typeof src !== 'string' || !src.startsWith('/')) return ''
  const file = path.join(ROOT, 'miniapp-user', src)
  if (!fs.existsSync(file)) return ''
  const ext = path.extname(file).slice(1), mime = ext === 'jpg' ? 'jpeg' : ext
  return 'data:image/' + mime + ';base64,' + fs.readFileSync(file).toString('base64')
}
function component(name, props) {
  const names = {'asset-station-view':'AssetStationView','order-card':'OrderCard','loading':'Loading'}
  const folder = names[name]
  if (!folder) return null
  const file = 'miniapp-user/components/' + folder + '/index'
  if (!components.has(folder)) {
    let cfg
    global.Component = c => {cfg=c}
    delete require.cache[require.resolve(path.join(ROOT,file+'.js'))]
    require(path.join(ROOT,file+'.js'))
    components.set(folder,cfg)
  }
  const cfg = components.get(folder), data = JSON.parse(JSON.stringify(cfg.data || {}))
  for (const [key, item] of Object.entries(cfg.properties || {})) data[key] = item.value
  Object.assign(data,props)
  if (folder === 'OrderCard') cfg.observers.order.call({setData:patch=>Object.assign(data,patch)},props.order)
  return {file,folder,data}
}
const styles = new Set()
function render(nodes, data) {
  let output='', taken=false
  for (const node of nodes) {
    if (node.tag === '#text') { output+=escape(value(node.value,data)); continue }
    const attrs=node.attrs || {}
    if ('wx:for' in attrs) {
      const rows=value(attrs['wx:for'],data) || []
      if (!Array.isArray(rows)) throw Error('Non-array fixture loop')
      rows.forEach((row,index)=>{
        const a={...attrs}; delete a['wx:for']
        output+=render([{...node,attrs:a}],{...data,[attrs['wx:for-item']||'item']:row,[attrs['wx:for-index']||'index']:index})
      })
      taken=false;continue
    }
    let visible=true
    if ('wx:if' in attrs) {visible=!!value(attrs['wx:if'],data);taken=visible}
    else if ('wx:elif' in attrs) {visible=!taken&&!!value(attrs['wx:elif'],data);taken ||= visible}
    else if ('wx:else' in attrs) {visible=!taken;taken=true}
    else taken=false
    if(!visible || node.tag==='page-meta')continue
    if(node.tag==='block'){output+=render(node.children,data);continue}
    const props={}
    for(const [key,val] of Object.entries(attrs))props[key.replace(/-([a-z])/g,(_,c)=>c.toUpperCase())]=value(val,data)
    const cmp=component(node.tag,props)
    if(cmp){styles.add(cmp.file+'.wxss');output+='<view class="projection-component">'+render(tree(cmp.file+'.wxml').children,cmp.data)+'</view>';continue}
    if(node.tag==='icon'){
      const glyph={check:'✓',arrow:'›',warn:'!',alert:'!',location:'⌖',ticket:'票',barrel:'桶',wallet:'¥',bill:'¥',zap:'✓',order:'单',home:'⌂',search:'⌕',droplet:'水'}[props.name]||'·'
      output+='<span class="projection-icon" style="color:'+escape(props.color||'#4B596A')+';font-size:'+escape(props.size||'28rpx')+'">'+glyph+'</span>';continue
    }
    let tag=node.tag==='image'?'img':node.tag==='picker'?'view':node.tag
    let htmlAttrs=''
    for(const key of ['class','style','placeholder','type','maxlength'])if(key in attrs)htmlAttrs+=' '+key+'="'+escape(value(attrs[key],data))+'"'
    if('disabled'in attrs&&value(attrs.disabled,data))htmlAttrs+=' disabled'
    if(tag==='input')htmlAttrs+=' value="'+escape(value(attrs.value||'',data))+'"'
    if(tag==='img'){htmlAttrs+=' src="'+image(value(attrs.src||'',data))+'"';output+='<img'+htmlAttrs+' />';continue}
    if(tag==='scroll-view')htmlAttrs+=' style="display:block;overflow-y:auto"'
    output+='<'+tag+htmlAttrs+'>'+(tag==='textarea'?escape(value(attrs.value||'',data)):render(node.children,data))+'</'+tag+'>'
  }
  return output
}
function css(source,width){return source.replace(/@import[^;]+;/g,'').replace(/(^|[}\s])page\s*\{/g,'$1.wechat-page {').replace(/([\d.]+)rpx/g,(_,n)=>(Number(n)*width/750).toFixed(3)+'px').replace(/env\(safe-area-inset-bottom\)/g,'24px')}
;(async()=>{
  fs.mkdirSync(OUT,{recursive:true})
  let browser, page
  if (HTML_ONLY) console.log('HTML-only: 不执行浏览器、截图或布局断言；仅输出离线投影 HTML。')
  else {
    const { chromium } = require('playwright')
    browser=await chromium.launch({executablePath:process.env.AQUAFLOW_BROWSER_EXECUTABLE || '/usr/bin/chromium',headless:true,args:['--no-sandbox']})
    page=await browser.newPage({viewport:{width:375,height:812},deviceScaleFactor:1})
    await page.route('**/*',r=>r.abort())
  }
  const manifest=[]
  try {
    for(const item of cases){
      styles.clear();errors.length=0
      const wx=createWx(),app=createApp()
      const appRoot=item.app||'miniapp-user'
      const instance=loadPage(appRoot+'/pages/'+item.route+'.js',{wx,app})
      const data={...instance.data,...item.state},file=appRoot+'/pages/'+item.route
      const body=render(tree(file+'.wxml').children,data)
      if(errors.length) throw Error(item.id+' projection expressions: '+JSON.stringify(errors))
      const title=JSON.parse(fs.existsSync(path.join(ROOT,file+'.json'))?read(file+'.json'):'{}').navigationBarTitleText||'AquaFlow'
      const width=item.width
      if (page) await page.setViewportSize({width,height:812})
      const sources=[]
      function addCss(file) { const source=read(file); for(const m of source.matchAll(/@import\s+['\"]([^'\"]+)['\"]/g)) addCss(path.posix.join(path.posix.dirname(file),m[1])); sources.push(file) }
      addCss(appRoot+'/app.wxss'); for(const f of styles) addCss(f); addCss(file+'.wxss')
      const rawCss=sources.map(read).join('\n')
      const html='<!doctype html><meta charset="utf-8"><style>html,body{margin:0}body{font-family:"Noto Sans CJK SC",sans-serif;background:#F5F6F7}view,scroll-view{display:block;box-sizing:border-box}text{box-sizing:border-box}button,input,textarea{font:inherit;box-sizing:border-box}button{cursor:default;border:0}input{border:0;background:transparent}img{display:block;object-fit:contain}picker{display:block}.projection-icon{display:inline-flex;align-items:center;justify-content:center;min-width:1em;flex-shrink:0}.projection-note{font:11px sans-serif;padding:8px 12px;background:#182230;color:white}.projection-nav{height:44px;display:flex;align-items:center;justify-content:center;background:#F5F6F7;font-size:16px;font-weight:600}.wechat-page{min-height:100vh}</style><style>'+css(rawCss,width)+'</style><div class="projection-note">离线合成静态投影 · 非微信原生 · '+escape(item.label)+'</div><div class="projection-nav">'+escape(title)+'</div><main class="wechat-page">'+body+'</main>'
      const htmlFile=path.join(OUT,item.id+'.html')
      fs.writeFileSync(htmlFile,html)
      const evidence={id:item.id,route:item.route,label:item.label,width,projectionErrors:[...errors],files:sources.concat(file+'.wxml'),sourceSha256:crypto.createHash('sha256').update(read(file+'.wxml')+rawCss).digest('hex'),nativeVerified:false,eventsExecuted:false,browserExecuted:!HTML_ONLY,layoutAssertionsExecuted:!HTML_ONLY}
      if (HTML_ONLY) { manifest.push(evidence); console.log(item.id+' '+width+'px HTML written; layout NOT verified'); continue }
      await page.setContent(html,{waitUntil:'load'})
      const dialog = data.showDetailPopup || data.showUnifiedConfirm || data.showReturnModal || data.showPurchase || data.showEditModal || data.showStationPicker
      await page.screenshot({path:path.join(OUT,item.id+'.png'),fullPage:!dialog})
      const geometry=await page.evaluate(()=>{ const q=s=>document.querySelector(s), rect=e=>e&&({x:e.getBoundingClientRect().x,y:e.getBoundingClientRect().y,width:e.getBoundingClientRect().width,height:e.getBoundingClientRect().height,bottom:e.getBoundingClientRect().bottom}); const money=q('.asset-money'), card=q('.assets-card'), heading=q('.assets-card + .group-title');return {money:rect(money),lineHeight:money&&parseFloat(getComputedStyle(money).lineHeight),gap:card&&heading?heading.getBoundingClientRect().top-card.getBoundingClientRect().bottom:null,entries:[...document.querySelectorAll('.asset-entry')].map(rect),values:[...document.querySelectorAll('.asset-value')].map(rect)}})
      if(item.route==='mine/index') { if(geometry.money.height>geometry.lineHeight+1) throw Error(item.id+' money wrapped'); if(geometry.gap>13) throw Error(item.id+' excessive service gap'); for(let i=0;i<geometry.entries.length;i++) if(geometry.entries[i].y<geometry.values[i].bottom-1) throw Error(item.id+' entry overlaps value') }
      const overflow=await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth}))
      if(overflow.scrollWidth>width) throw Error(item.id+' horizontal overflow')
      manifest.push({...evidence,geometry,overflow})
      console.log(item.id+' '+width+'px; errors='+errors.length+'; overflow='+overflow.scrollWidth)
    }
    fs.writeFileSync(path.join(OUT,'manifest.json'),JSON.stringify({kind:HTML_ONLY?'offline synthetic HTML only; no browser or layout assertions':'offline synthetic browser projection',safeAreaSimulatedPx:24,cases:manifest},null,2)+'\n')
    fs.writeFileSync(path.join(OUT,'index.html'),'<!doctype html><meta charset="utf-8"><title>顾客端离线投影</title><h1>离线合成静态投影</h1><p>来自当前 WXML/WXSS；没有执行应用生命周期、网络或业务事件。原生胶囊、键盘、系统弹窗及触控未验。</p>'+manifest.map(x=>'<article><h2>'+escape(x.label)+' ('+x.width+'px)</h2><a href="'+x.id+'.html">'+escape(x.route)+'</a><br>'+(HTML_ONLY?'<p>仅 HTML；未执行浏览器与布局断言。</p><iframe title="'+escape(x.label)+'" width="'+x.width+'" height="812" src="'+x.id+'.html"></iframe>':'<img width="300" src="'+x.id+'.png">')+'</article>').join(''))
  } finally {if(browser) await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})
