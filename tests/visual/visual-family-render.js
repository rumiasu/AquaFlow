'use strict'
// Actual imported WXSS + explicit synthetic specimen markup. Not full-page/native modal screenshots.
const fs = require('fs'), path = require('path'), cp = require('child_process'), assert = require('assert')
const { chromium } = require('playwright')
const ROOT = path.resolve(__dirname, '../..'), OUT = process.argv[2] || '/tmp/af-visual-families', BASE = '74c1ce1'
const read = (file, old) => old ? cp.execFileSync('git', ['show', BASE + ':' + file], { cwd: ROOT, encoding: 'utf8' }) : fs.readFileSync(path.join(ROOT, file), 'utf8')
const css = (file, old) => read(file, old).replace(/@import\s+["']([^"']+)["'];/g, (_, imported) => css(path.posix.join(path.posix.dirname(file), imported), old))
const cases = [
  { name: 'employee-feedback', side: 'miniapp-delivery', files: [], body: '<div class="load-error">合成样例：部分订单暂未加载，已有结果仍保留。<button>重新加载</button></div><div class="ui-state"><span class="ui-state-title">上次读取的记录</span><span class="ui-state-copy">合成说明：重新读取后再查看完整信息。</span><div class="ui-state-actions"><button>重新读取</button><button disabled>正在处理</button></div></div>' },
  { name: 'employee-risk', side: 'miniapp-delivery', files: ['pages/station-mgmt/asset-claims/index.wxss'], body: '<div class="claim-card"><span class="claim-title">合成历史资产说明</span><div class="claim-notice">这笔记录还在办理，请先查看原结果。</div><div class="claim-error">资料暂未完整加载，请稍后重试。</div><input class="claim-input" placeholder="合成输入示例"><button class="claim-primary">查看原申请与办理结果</button><button class="claim-secondary">返回</button><button class="claim-primary" disabled>暂不可继续</button></div>' },
  { name: 'customer-feedback', side: 'miniapp-user', files: ['pages/login/index.wxss','pages/mine/edit.wxss'], body: '<div class="load-error">合成样例：内容暂未加载，请重试。<button>重新加载</button></div><div class="login-error">登录暂未完成，请稍后重试。</div><div class="save-error">资料暂未保存，请重试。</div>' },
  { name: 'customer-money-risk', side: 'miniapp-user', files: ['pages/order/create.wxss','pages/ticket/index.wxss'], body: '<div class="submit-unknown">合成样例：这次付款是否成功还没查到，请先查看原结果。</div><div class="fee-warn">费用有变化，请核对本次金额后继续。</div><div class="recovery-card">原购买记录已保留，不要重复付款。</div>' }
]
;(async () => {
  fs.mkdirSync(OUT, { recursive: true }); const browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] }), page = await browser.newPage(), results = []
  await page.route('**/*', route => route.abort())
  try {
    for (const item of [{ width:375,height:720,scale:1,name:'phone' }, {width:320,height:568,scale:1.3,name:'small-large-font'}]) for (const scenario of cases) for (const old of [true,false]) {
      const raw = [scenario.side + '/app.wxss',...scenario.files.map(f=>scenario.side+'/'+f)].map(f=>css(f,old)).join('\n')
      const style = raw.replace(/(?<![.\w-])page\s*\{/g,'body {').replace(/([\d.]+)rpx/g,(_,n)=>Number(n)*item.width/750+'px').replace(/font-size:\s*([\d.]+)px/g,(_,n)=>'font-size:'+Number(n)*item.scale+'px').replace(/env\(safe-area-inset-bottom\)/g,'16px')
      await page.setViewportSize({width:item.width,height:item.height})
      await page.setContent('<meta charset="utf-8"><style>html,body{margin:0;width:100%;box-sizing:border-box;font-family:"Noto Sans CJK SC",sans-serif}body{padding:12px}div,span,input,button{box-sizing:border-box}button,input{font:inherit}button{display:block;width:100%;max-width:100%;border:0}input{display:block}main{width:100%;min-width:0}main>div{margin:12px 0!important}main .specimen-actions{display:flex;flex-wrap:wrap;gap:12px}main .specimen-actions button{flex:1;min-width:120px}</style><style>'+style+'</style><main>'+scenario.body+'<div class="specimen-actions"><button class="btn-primary">继续原操作</button><button class="btn-outline">返回查看</button><button class="btn-primary" disabled>暂不可操作</button></div></main>')
      const result=await page.evaluate(()=>{const get=s=>{const e=document.querySelector(s);if(!e)return null;const c=getComputedStyle(e),r=e.getBoundingClientRect();return {background:c.backgroundColor,color:c.color,borderLeft:c.borderLeftColor,height:r.height,radius:c.borderRadius}};return {width:innerWidth,scrollWidth:document.documentElement.scrollWidth,readError:get('.load-error'),primary:get('.specimen-actions .btn-primary'),secondary:get('.btn-outline'),disabled:get('.specimen-actions [disabled]'),warning:get('.claim-notice')||get('.fee-warn'),error:get('.claim-error')||get('.login-error')}})
      const name=(old?'before-':'after-')+scenario.name+'-'+item.name
      await page.screenshot({path:path.join(OUT,name+'.png')})
      if(!old){assert(result.scrollWidth<=item.width+1,name+' overflow');assert(result.primary.height>=48,name+' primary target');assert(result.secondary.height>=44,name+' secondary target');assert.notEqual(result.primary.background,result.disabled.background,name+' disabled');if(result.readError)assert.equal(result.readError.background,'rgb(240, 242, 245)',name+' neutral feedback');}
      results.push({...item,name,before:old,scenario:scenario.name,...result})
    }
    fs.writeFileSync(path.join(OUT,'layout.json'),JSON.stringify({baseline:BASE,scope:'Actual imported WXSS + synthetic specimen markup; no native modal or full-page proof',results},null,2));console.log('AQUAFLOW_LAYOUT_OK '+results.length)
  } finally {await browser.close()}
})().catch(e=>{console.error(e.stack);process.exitCode=1})
