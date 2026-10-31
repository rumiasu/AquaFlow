// Browser-only static projection. No application lifecycle, event handler, login or business request runs.
const fs = require('fs'), path = require('path'), vm = require('vm'), crypto = require('crypto')
const { chromium } = require('playwright')
const { loadPage, createWx, createApp } = require('../js/harness')
const { parseWxml, validateConditionalSiblings } = require('../js/wxml-tree')
const cases = require('./customer-night-fixtures')
const ROOT = path.resolve(__dirname, '../..'), OUT = process.argv[2] || '/workspace/customer-night-visual'
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
  const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox']})
  const page=await browser.newPage({viewport:{width:375,height:812},deviceScaleFactor:1})
  await page.route('**/*',r=>r.abort())
  const manifest=[]
  try {
    for(const item of cases){
      styles.clear();errors.length=0
      const wx=createWx(),app=createApp()
      const instance=loadPage('miniapp-user/pages/'+item.route+'.js',{wx,app})
      const data={...instance.data,...item.state},file='miniapp-user/pages/'+item.route
      const body=render(tree(file+'.wxml').children,data)
      const title=JSON.parse(fs.existsSync(path.join(ROOT,file+'.json'))?read(file+'.json'):'{}').navigationBarTitleText||'AquaFlow'
      const width=item.id.includes('fees')||item.id.includes('return')||item.id.includes('form')?320:375
      await page.setViewportSize({width,height:812})
      const sources=['miniapp-user/styles/variable.wxss','miniapp-user/styles/common.wxss','miniapp-user/styles/theme.wxss','miniapp-user/app.wxss',...styles,file+'.wxss']
      const rawCss=sources.map(read).join('\n')
      const html='<!doctype html><meta charset="utf-8"><style>html,body{margin:0}body{font-family:"Noto Sans CJK SC",sans-serif;background:#F5F6F7}view,scroll-view{display:block;box-sizing:border-box}text{box-sizing:border-box}button,input,textarea{font:inherit;box-sizing:border-box}button{cursor:default;border:0}input{border:0;background:transparent}img{display:block;object-fit:contain}picker{display:block}.projection-icon{display:inline-flex;align-items:center;justify-content:center;min-width:1em;flex-shrink:0}.projection-note{font:11px sans-serif;padding:8px 12px;background:#182230;color:white}.projection-nav{height:44px;display:flex;align-items:center;justify-content:center;background:#F5F6F7;font-size:16px;font-weight:600}.wechat-page{min-height:100vh}</style><style>'+css(rawCss,width)+'</style><div class="projection-note">离线合成静态投影 · 非微信原生 · '+escape(item.label)+'</div><div class="projection-nav">'+escape(title)+'</div><main class="wechat-page">'+body+'</main>'
      const htmlFile=path.join(OUT,item.id+'.html')
      fs.writeFileSync(htmlFile,html)
      await page.setContent(html,{waitUntil:'load'})
      const dialog = data.showDetailPopup || data.showUnifiedConfirm || data.showReturnModal || data.showPurchase || data.showEditModal || data.showStationPicker
      await page.screenshot({path:path.join(OUT,item.id+'.png'),fullPage:!dialog})
      const overflow=await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth}))
      manifest.push({id:item.id,route:item.route,label:item.label,width,projectionErrors:[...errors],overflow,files:sources.concat(file+'.wxml'),sourceSha256:crypto.createHash('sha256').update(read(file+'.wxml')+rawCss).digest('hex'),nativeVerified:false,eventsExecuted:false})
      console.log(item.id+' '+width+'px; errors='+errors.length+'; overflow='+overflow.scrollWidth)
    }
    fs.writeFileSync(path.join(OUT,'manifest.json'),JSON.stringify({kind:'offline synthetic browser projection',safeAreaSimulatedPx:24,cases:manifest},null,2)+'\n')
    fs.writeFileSync(path.join(OUT,'index.html'),'<!doctype html><meta charset="utf-8"><title>顾客端离线投影</title><h1>离线合成静态投影</h1><p>来自当前 WXML/WXSS；没有执行应用生命周期、网络或业务事件。原生胶囊、键盘、系统弹窗及触控未验。</p>'+manifest.map(x=>'<article><h2>'+escape(x.label)+'</h2><a href="'+x.id+'.html">'+escape(x.route)+'</a><br><img width="300" src="'+x.id+'.png"></article>').join(''))
  } finally {await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})
