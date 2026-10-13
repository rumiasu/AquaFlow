const assert = require('assert')
const path = require('path')
const {createNavigator} = require(path.resolve(__dirname, '..', '..', 'miniapp-delivery/utils/navigation.js'))
let pass=0,fail=0
function test(name,fn){try{fn();pass++;console.log('PASS '+name)}catch(e){fail++;console.log('FAIL '+name+': '+e.message)}}
function setup(){
 const owner={route:'pages/station-mgmt/index'},before=[owner];let stack=before.slice(),identity='staff1',now=0,id=0
 const calls=[],messages=[],timers=new Map(),wx={}
 for(const api of ['navigateTo','navigateBack','switchTab','redirectTo','reLaunch'])wx[api]=o=>calls.push({api,o})
 const nav=createNavigator({wx,pages:()=>stack,session:()=>identity,setTimer(fn,ms){timers.set(++id,{fn,at:now+ms});return id},clearTimer(i){timers.delete(i)},feedback:f=>messages.push(f)})
 return {nav,owner,before,calls,messages,timers,setStack:s=>stack=s,session:s=>identity=s,
  arrive(options={},lifecycle=true){const target={route:'pages/station-mgmt/customers/index',options};stack=[...before,target];if(lifecycle){nav.hide(owner);nav.show(target)};return target},
  tick(){now+=12000;for(const [i,t] of [...timers])if(t.at<=now){timers.delete(i);t.fn()}}
 }
}
const dest='/pages/station-mgmt/customers/index'
test('arrived push with no callbacks produces no false modal and releases lock',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.nav.hide(f.owner);f.arrive();f.tick();assert.equal(f.messages.length,0);f.nav.open('/pages/mine/index');assert.equal(f.calls.length,2)})
test('no arrival still produces one real timeout feedback',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.tick();assert.equal(f.messages.length,1);assert.equal(f.calls.length,1)})
test('existing same route object cannot acknowledge pending push',()=>{const f=setup();f.setStack([...f.before,{route:dest.slice(1)}]);f.nav.open(dest+'?id=2',{owner:f.owner});f.tick();assert.equal(f.messages.length,1)})
test('wrong new route still reports timeout',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.setStack([...f.before,{route:'pages/mine/index'}]);f.tick();assert.equal(f.messages.length,1)})
test('same route with wrong query cannot acknowledge push',()=>{const f=setup();f.nav.open(dest+'?id=2',{owner:f.owner});f.arrive({id:'3'});f.tick();assert.equal(f.messages.length,1)})
test('same route with exact decoded query acknowledges push',()=>{const f=setup();f.nav.open(dest+'?id=2&name=a%20b',{owner:f.owner});f.arrive({id:'2',name:'a b'});f.tick();assert.equal(f.messages.length,0)})
test('duplicate query key fails closed',()=>{const f=setup();f.nav.open(dest+'?id=2&id=3',{owner:f.owner});f.arrive({id:'3'});f.tick();assert.equal(f.messages.length,1)})
test('invalid query encoding fails closed',()=>{const f=setup();f.nav.open(dest+'?id=%GG',{owner:f.owner});f.arrive({id:'%GG'});f.tick();assert.equal(f.messages.length,1)})
test('same route with replaced source stack cannot acknowledge push',()=>{const f=setup();f.nav.open(dest);f.setStack([{route:f.owner.route},{route:dest.slice(1)}]);f.tick();assert.equal(f.messages.length,1)})
test('extra unrelated push cannot acknowledge pending target',()=>{const f=setup();f.nav.open(dest);f.setStack([...f.before,{route:'pages/mine/index'},{route:dest.slice(1)}]);f.tick();assert.equal(f.messages.length,1)})
test('late callbacks after arrival cannot release newer pending navigation',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.arrive();f.tick();assert.equal(f.messages.length,0);f.nav.open('/pages/mine/index');f.calls[0].o.success();f.calls[0].o.complete({errMsg:'navigateTo:ok'});f.nav.open('/pages/home/index');assert.equal(f.calls.length,2);f.tick();assert.equal(f.messages.length,1)})
test('explicit native fail remains failure even if target route visible',()=>{const f=setup();f.nav.open(dest);f.arrive();f.calls[0].o.fail({errMsg:'navigateTo:fail'});assert.equal(f.messages.length,1);assert.equal(f.timers.size,0)})
test('native timeout after exact push arrival reconciles success',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.arrive();f.calls[0].o.fail({errMsg:'navigateTo:fail timeout'});assert.equal(f.messages.length,0);assert.equal(f.timers.size,0)})
test('native timeout without arrival remains failure',()=>{const f=setup();f.nav.open(dest);f.calls[0].o.fail({errMsg:'navigateTo:fail timeout'});assert.equal(f.messages.length,1)})
test('return to hidden source cancels old attempt without feedback',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.nav.hide(f.owner);f.arrive();f.setStack(f.before);f.nav.show(f.owner);f.tick();assert.equal(f.messages.length,0);f.nav.open(dest,{owner:f.owner});assert.equal(f.calls.length,2)})
test('session invalidation suppresses obsolete feedback',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.arrive();f.session('staff2');f.nav.invalidate();f.tick();assert.equal(f.messages.length,0)})
test('disposed source never shows stale modal',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.arrive();f.nav.dispose(f.owner);f.tick();assert.equal(f.messages.length,0)})
test('complete-only native success already clears timer',()=>{const f=setup();f.nav.open(dest);f.arrive();f.calls[0].o.complete({errMsg:'navigateTo:ok'});f.tick();assert.equal(f.messages.length,0);assert.equal(f.timers.size,0)})
test('replace operation missing callback is not treated as push success',()=>{const f=setup();f.nav.open(dest,{mode:'replace'});f.setStack([{route:dest.slice(1)}]);f.tick();assert.equal(f.messages.length,1)})
test('no query must not accept added customerId option',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.arrive({customerId:'42'});f.tick();assert.equal(f.messages.length,1)})
test('exact requested key must not accept extra stationId',()=>{const f=setup();f.nav.open(dest+'?customerId=42',{owner:f.owner});f.arrive({customerId:'42',stationId:'2'});f.tick();assert.equal(f.messages.length,1)})
test('missing option must not match literal undefined',()=>{const f=setup();f.nav.open(dest+'?customerId=undefined',{owner:f.owner});f.arrive({});f.tick();assert.equal(f.messages.length,1)})
test('undefined own value must not match literal undefined',()=>{const f=setup();f.nav.open(dest+'?customerId=undefined',{owner:f.owner});f.arrive({customerId:undefined});f.tick();assert.equal(f.messages.length,1)})
test('inherited option cannot satisfy expected own key',()=>{const f=setup();f.nav.open(dest+'?customerId=42',{owner:f.owner});f.arrive(Object.create({customerId:'42'}));f.tick();assert.equal(f.messages.length,1)})
test('exact own string options acknowledge shown destination',()=>{const f=setup();f.nav.open(dest+'?customerId=42',{owner:f.owner});f.arrive({customerId:'42'});f.tick();assert.equal(f.messages.length,0)})
test('own literal undefined string is valid exact match',()=>{const f=setup();f.nav.open(dest+'?customerId=undefined',{owner:f.owner});f.arrive({customerId:'undefined'});f.tick();assert.equal(f.messages.length,0)})
test('numeric coercion is not accepted as string option',()=>{const f=setup();f.nav.open(dest+'?customerId=42',{owner:f.owner});f.arrive({customerId:42});f.tick();assert.equal(f.messages.length,1)})
test('nonenumerable extra own option fails closed',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});const options={};Object.defineProperty(options,'customerId',{value:'42'});f.arrive(options);f.tick();assert.equal(f.messages.length,1)})
test('registered page before source hide remains unknown',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});const target=f.arrive({},false);f.nav.show(target);f.tick();assert.equal(f.messages.length,1)})
test('source hidden but destination never shown remains unknown',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.arrive({},false);f.nav.hide(f.owner);f.tick();assert.equal(f.messages.length,1)})
test('destination subsequently hidden cannot acknowledge arrival',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});const target=f.arrive();f.nav.hide(target);f.tick();assert.equal(f.messages.length,1)})
test('destination disposed cannot acknowledge arrival',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});const target=f.arrive();f.nav.dispose(target);f.tick();assert.equal(f.messages.length,1)})
test('replacement target object lacks original shown marker',()=>{const f=setup();f.nav.open(dest,{owner:f.owner});f.arrive();f.setStack([...f.before,{route:dest.slice(1),options:{}}]);f.tick();assert.equal(f.messages.length,1)})
console.log('ARRIVAL_REGRESSION '+JSON.stringify({pass,fail,total:pass+fail}));if(fail)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+pass)
