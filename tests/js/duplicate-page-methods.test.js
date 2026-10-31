'use strict'
// JS permits duplicate object methods: a later business action can silently replace a navigation handler.
const fs=require('fs'),path=require('path'),assert=require('assert')
const root=path.resolve(__dirname,'../..')
function duplicates(source){const seen=new Map(),bad=[]
 for(const m of source.matchAll(/^ {2}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^\n]*?\)\s*\{/gm)){
  if(['if','for','while','switch','catch','with','return'].includes(m[1]))continue
  const line=source.slice(0,m.index).split('\n').length
  if(seen.has(m[1]))bad.push({method:m[1],first:seen.get(m[1]),line});else seen.set(m[1],line)
 }return bad
}
assert.equal(duplicates('Page({\n  onBack() {},\n  async onBack() {}\n})').length,1)
assert.equal(duplicates('Page({\n  onBack() {},\n  onSave() {},\n    onBack() {}\n})').length,0)
const failures=[];let pages=0
for(const app of ['miniapp-user','miniapp-delivery'])for(const route of JSON.parse(fs.readFileSync(path.join(root,app,'app.json'),'utf8')).pages){
 pages++;const file=app+'/'+route+'.js'
 for(const item of duplicates(fs.readFileSync(path.join(root,file),'utf8')))failures.push({file,...item})
}
for(const failure of failures)console.error('DUPLICATE_PAGE_METHOD '+failure.file+':'+failure.line+' '+failure.method+' first='+failure.first)
assert.equal(failures.length,0,'A later Page method replaces an earlier action; navigation must have its own handler')
console.log('AQUAFLOW_SUITE_OK '+pages)