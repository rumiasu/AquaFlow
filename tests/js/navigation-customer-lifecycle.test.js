const assert=require('assert'),path=require('path')
const root=path.resolve(__dirname,'..','..')
const {loadPage}=require(path.join(root,'tests/js/harness.js'))
let passed=0
function fixture(allowed=true){const events=[];const f=loadPage('miniapp-delivery/pages/station-mgmt/customers/index.js',{app:{canAccessStationBusiness:()=>allowed,routeByRole:()=>events.push('guard')},stubs:{'utils/navigation':{show:()=>events.push('show'),hide:()=>events.push('hide'),dispose:()=>events.push('dispose')}}});const page=f.page||f;page.loadData=()=>events.push('load');page._closeCod=()=>events.push('close');return {page,events}}
function test(name,fn){fn();passed++;console.log('PASS '+name)}
test('authorized real onShow marks destination before loading',()=>{const f=fixture();f.page.onShow();assert.deepEqual(f.events,['show','load'])})
test('denied real onShow cannot mark visible destination',()=>{const f=fixture(false);f.page.onShow();assert.deepEqual(f.events,['guard'])})
test('real onHide removes visible marker before cleanup',()=>{const f=fixture();f.page._codContext={ready:false};f.page.onHide();assert.deepEqual(f.events,['hide','close']);assert.equal(f.page._customersHidden,true)})
test('real onUnload closes a ready editor and disposes page instance',()=>{const f=fixture();f.page._codContext={ready:true};f.page.onUnload();assert.deepEqual(f.events,['hide','close','dispose'])})
test('a ready editor survives hide without discarding its draft',()=>{const f=fixture();f.page._codContext={ready:true};f.page.onHide();assert.deepEqual(f.events,['hide']);assert.equal(f.page._customersHidden,true)})
console.log('AQUAFLOW_SUITE_OK '+passed)
