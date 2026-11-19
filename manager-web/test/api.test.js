import test from 'node:test';
import assert from 'node:assert/strict';
import {Api} from '../public/api.js';
const reply=(data,code=0,status=200)=>({ok:status<400,status,json:async()=>({code,data})});
const manager={role:'STATION_MANAGER',stationId:3,userType:'staff'};
test('login validates current server identity and sends credentials only in body',async()=>{const calls=[];const api=new Api(async(path,options)=>{calls.push([path,options]);return reply(path.endsWith('/login')?{accessToken:'a',refreshToken:'r'}:manager);});assert.deepEqual(await api.login('station','pw'),manager);assert.equal(calls[0][0],'/api/auth/login');assert.equal(calls[1][1].headers.Authorization,'Bearer a');assert.equal(calls[0][1].redirect,'error');});
test('reject delivery account and discard tokens',async()=>{const api=new Api(async p=>reply(p.endsWith('/login')?{accessToken:'a'}:{...manager,role:'DELIVERY'}));await assert.rejects(api.login('x','y'),/站长/);assert.equal(api.tokens,null);});
test('concurrent expired reads share one refresh and rotate both tokens',async()=>{let refresh=0;const api=new Api(async(p,o)=>{if(p.endsWith('/refresh')){refresh++;await new Promise(r=>setTimeout(r,10));return reply({accessToken:'new',refreshToken:'new-r'});}return o.headers.Authorization==='Bearer old'?reply(null,401,401):reply([1]);});api.tokens={accessToken:'old',refreshToken:'old-r'};assert.deepEqual(await Promise.all([api.get('/api/orders'),api.get('/api/customers')]),[[1],[1]]);assert.equal(refresh,1);assert.equal(api.tokens.refreshToken,'new-r');});
test('late responses cannot enter a new login session',async()=>{let resolve;const api=new Api(()=>new Promise(r=>{resolve=r}));api.tokens={accessToken:'a'};const pending=api.get('/api/orders');api.clear();resolve(reply([{id:1}]));await assert.rejects(pending,/会话已变更/);});
test('business errors and unknown transport response do not fabricate data',async()=>{const api=new Api(async()=>reply(null,1));api.tokens={accessToken:'a'};await assert.rejects(api.get('/api/orders'));});
test('refresh failure clears session',async()=>{const api=new Api(async()=>reply(null,401,401));api.tokens={accessToken:'a',refreshToken:'r'};await assert.rejects(api.get('/api/orders'));assert.equal(api.tokens,null);});
test('no arbitrary API destinations',async()=>{const api=new Api(()=>{throw Error('must not send')});await assert.rejects(api.get('https://example.com/api/orders'),/不支持/);});
test('logout clears before network finishes',async()=>{const api=new Api(async()=>reply(null));api.tokens={accessToken:'a'};const pending=api.logout();assert.equal(api.tokens,null);await pending;});
test('write preflights current identity and never replays after unknown network result',async()=>{let writes=0;const api=new Api(async(p)=>{if(p==='/api/auth/me')return reply(manager);writes++;throw Error('network lost');});api.tokens={accessToken:'a'};await assert.rejects(api.command('/api/payments/7/confirm','PUT'),/核对/);assert.equal(writes,1);});
test('duplicate in-flight write is blocked',async()=>{let release;const api=new Api(async p=>p==='/api/auth/me'?reply(manager):new Promise(r=>{release=r}));api.tokens={accessToken:'a'};const first=api.command('/api/payments/7/confirm','PUT');await assert.rejects(api.command('/api/payments/7/confirm','PUT'),/正在提交/);await new Promise(r=>setTimeout(r,0));release(reply(null));await first;});
test('generic state edits are rejected',async()=>{const api=new Api(()=>{throw Error('must not send')});await assert.rejects(api.command('/api/orders/7/status','PUT',{status:4}),/不支持/);});

test('backend manager alias is accepted and a changed station blocks the next write',async()=>{
  const identity={...manager,role:'manager',staffId:8};
  let station=identity.stationId;
  const api=new Api(async path=>{
    if(path==='/api/auth/login')return reply({accessToken:'a',refreshToken:'r'});
    assert.equal(path,'/api/auth/me');
    return reply({...identity,stationId:station});
  });
  assert.deepEqual(await api.login('station','pw'),identity);
  station=4;
  await assert.rejects(api.command('/api/barrels/return-empty','POST',{}),/水站或账号已变化/);
  assert.equal(api.tokens,null);
});
