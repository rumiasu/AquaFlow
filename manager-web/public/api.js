import {allows, canRead} from './routes.js';

const manager = me => me?.userType === 'staff' && ['manager','STATION_MANAGER'].includes(me.role) && /^[1-9]\d*$/.test(String(me.stationId));

// Same-origin only. Credentials live in memory, never in browser storage or URLs.
export class Api {
  constructor(fetcher = globalThis.fetch.bind(globalThis)) { this.fetcher = fetcher; this.epoch = 0; this.tokens = null; this.refreshing = null; this.writes = new Set(); }
  clear() { this.epoch++; this.tokens = null; this.refreshing = null; this.identity = null; }
  async wire(path, method, body, token) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await this.fetcher(path, {method, credentials:'omit', cache:'no-store', redirect:'error', signal:controller.signal,
        headers:{'Content-Type':'application/json', ...(token ? {Authorization:`Bearer ${token}`} : {})},
        ...(body === undefined ? {} : {body:JSON.stringify(body)})});
      let result; try { result = await response.json(); } catch { throw new Error('服务暂时不可用，请重试'); }
      if (!response.ok || ![0,200].includes(result.code)) {
        const error = new Error(response.status === 401 || result.code === 401 ? '登录已过期，请重新登录' : result.message || result.msg || '读取失败，请重试');
        error.status = response.status === 401 || result.code === 401 ? 401 : response.status;
        error.businessRejected = response.ok && result.code === 1;
        throw error;
      }
      return result.data;
    } finally { clearTimeout(timer); }
  }
  async login(username,password) {
    this.clear(); const epoch = this.epoch;
    const tokens = await this.wire('/api/auth/login','POST',{username,password});
    if(epoch !== this.epoch) throw new Error('登录已取消');
    this.tokens = tokens;
    try {
      const me = await this.get('/api/auth/me');
      if(!manager(me)) throw new Error('请使用已绑定水站的站长账号');
      this.identity = me;
      return me;
    } catch(error) { if(epoch === this.epoch) this.clear(); throw error; }
  }
  async get(path) {
    if(!canRead.test(path)) throw new Error('不支持的查询');
    const epoch = this.epoch; const access = this.tokens?.accessToken;
    if(!access) throw new Error('请先登录');
    const check = () => { if(epoch !== this.epoch) throw new Error('会话已变更'); };
    try { const data = await this.wire(path,'GET',undefined,access); check(); return data; }
    catch(error) {
      check(); if(error.status !== 401) throw error;
      if(this.tokens.accessToken === access) {
        if(!this.refreshing) {
          const pending = this.wire('/api/auth/refresh','POST',{refreshToken:this.tokens.refreshToken})
            .then(tokens => { check(); this.tokens = tokens; })
            .catch(err => { if(epoch === this.epoch) this.clear(); throw err; })
            .finally(() => { if(this.refreshing === pending) this.refreshing = null; });
          this.refreshing = pending;
        }
        await this.refreshing;
      }
      check(); const data = await this.wire(path,'GET',undefined,this.tokens.accessToken); check(); return data;
    }
  }
  async command(path, method, body) {
    if(!allows(method,path)) throw new Error('不支持的操作');
    // Preflight refresh only. Never replay a write after an uncertain response.
    const epoch=this.epoch;
    const key = `${epoch}:${method}:${path.split('?')[0]}`;
    if(this.writes.has(key)) throw new Error('正在提交，请稍候');
    this.writes.add(key);
    let dispatched = false;
    try {
      const me=await this.get('/api/auth/me');
      if(!manager(me)) throw new Error('请使用站长账号');
      if(this.identity && (String(me.stationId)!==String(this.identity.stationId) || String(me.staffId)!==String(this.identity.staffId))) {
        this.clear();
        throw new Error('水站或账号已变化，请重新登录');
      }
      if(epoch!==this.epoch) throw new Error('会话已变更');
      dispatched = true;
      const result=await this.wire(path,method,body,this.tokens.accessToken);
      if(epoch!==this.epoch) throw new Error('会话已变更，请重新查询结果');
      return result;
    } catch(error) {
      if(dispatched && !error.businessRejected && error.status !== 401) {
        throw Object.assign(new Error('提交结果尚未确认，请刷新记录核对后再操作'), {uncertain:true});
      }
      throw error;
    } finally { this.writes.delete(key); }
  }
  async logout() {
    const token = this.tokens?.accessToken; this.clear();
    if(token) await this.wire('/api/auth/logout','POST',{},token);
  }
}
