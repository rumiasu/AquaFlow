'use strict'
// Checks the actual simple-selector cascade behind the narrow saves/lost form gaps.
// Source contract only; this is not a pixel renderer or native visual acceptance.
const fs=require('fs'),path=require('path'),assert=require('assert')
const ROOT=process.env.AQUAFLOW_LAYOUT_TEST_ROOT||path.resolve(__dirname,'../..')
const {parseWxml}=require(path.join(ROOT,'tests/js/wxml-tree'))
const read=rel=>fs.readFileSync(path.join(ROOT,rel),'utf8');let count=0
function declarations(css){return [...css.replace(/\/\*[\s\S]*?\*\//g,'').replace(/@import[^;]+;/g,'').matchAll(/([^{}]+)\{([^{}]*)\}/g)].flatMap(m=>m[1].split(',').map(selector=>({selector:selector.trim(),props:m[2].split(';').filter(s=>s.includes(':')).map(s=>{const i=s.indexOf(':');return[s.slice(0,i).trim(),s.slice(i+1).trim()]})})))}
function effective(app,route,node){
 const classes=new Set((node.attrs.class||'').split(/\s+/)),out={},priority={}
 const rules=declarations(read(app+'/styles/actions.wxss')+(route?read(app+'/'+route+'.wxss'):''))
 rules.forEach((rule,order)=>{const sel=rule.selector;if(!/^(?:button|view|text)?(?:\.[\w-]+)*$/.test(sel)||!sel)return
  const tag=/^(button|view|text)/.exec(sel)?.[0],names=[...sel.matchAll(/\.([\w-]+)/g)].map(x=>x[1]);if(tag&&tag!==node.tag||names.some(n=>!classes.has(n)))return
  const score=names.length*100+(tag?1:0)
  for(const [key,value] of rule.props){let pairs=[[key,value]];if(key==='margin'){const v=value.split(/\s+/);pairs=['top','right','bottom','left'].map((side,i)=>['margin-'+side,v[i]||v[i%2]||v[0]])}
   for(const [k,v]of pairs){const old=priority[k];if(!old||score>old.score||score===old.score&&order>=old.order){out[k]=v;priority[k]={score,order,selector:sel}}}
  }
 });return out
}
function nodes(route,predicate){const out=[];function visit(n){if(n.attrs&&predicate(n))out.push(n);for(const c of n.children||[])visit(c)}visit(parseWxml(read('miniapp-delivery/'+route+'.wxml')));assert(out.length,'target present: '+route);return out}
const saves=[['station-info','onSave'],['station-status','onSaveStatus'],['ticket-packages','onSave'],['customer-feedback','onCloseDispute'],['customer-feedback','onSubmitRefundNote']]
for(const [name,handler]of saves){const route='pages/station-mgmt/'+name+'/index';for(const n of nodes(route,n=>n.tag==='button'&&n.attrs.bindtap===handler)){const css=effective('miniapp-delivery',route,n);assert.equal(css.width,'100%',name+' single-column submit fills the page-defined row');assert.equal(css['min-height'],'48px','primary touch hierarchy retained')}count++}
const payroll='pages/station-mgmt/payroll/index'
for(const [cls,gap]of [['form-submit','20px'],['form-cancel','8px']]){for(const n of nodes(payroll,n=>(n.attrs.class||'').split(/\s+/).includes(cls))){assert.equal(effective('miniapp-delivery',payroll,n)['margin-top'],gap,'page gap survives native button family default')}count++}
for(const [route,handler,gap]of [['pages/station-mgmt/station-status/index','onSaveStatus','16px'],['pages/station-mgmt/customer-feedback/index','onCloseDispute','12px']]){for(const n of nodes(route,n=>n.tag==='button'&&n.attrs.bindtap===handler))assert.equal(effective('miniapp-delivery',route,n)['margin-top'],gap);count++}
for(const app of ['miniapp-user','miniapp-delivery']){
 const css=effective(app,null,{tag:'button',attrs:{class:'af-action af-action--quiet'}});assert.equal(css.width,'auto','short quiet action stays intrinsic');assert.equal(css['min-height'],'44px');assert.equal(css['max-width'],'100%');count++
 const main=effective(app,null,{tag:'button',attrs:{class:'af-action af-action--primary af-action--block'}});assert.equal(main.width,'100%');assert.equal(main['min-height'],'48px');count++
}
console.log('AQUAFLOW_SUITE_OK '+count)
