const { get, post } = require('../../utils/request')
const intent = require('../../utils/exception-action-intent')
const { showBusinessModal } = require('../../utils/business-dialog')
const STAFF_APP = true
const ids = v => /^[1-9][0-9]*$/.test(String(v))
function owner() {
  const a=getApp(),d=a.globalData||{},u=d.userInfo||{}
  return JSON.stringify([STAFF_APP,!!d.isLogin,u.staffId||d.customerId||wx.getStorageSync('customerId'),u.stationId,u.role])
}
Page({
  data:{kind:'',id:null,ready:false,loading:false,error:'',history:[],version:0,canReply:false,canClose:false,canResolve:false,reason:'',busy:false,pending:false,result:''},
  onLoad(options){
    const kind=options.kind,id=options.id
    if(!ids(id)||!['FEEDBACK','EXCEPTION'].includes(kind)||(!STAFF_APP&&kind!=='FEEDBACK')) {this._invalid=true;this.setData({error:'处理对象无效，请返回原记录'});return}
    this.setData({kind,id:String(id)})
    this._owner=owner();this._generation=getApp()._loginGeneration||0;this._object=kind+':'+id
  },
  onShow(){
    this._hidden=false
    if(this._invalid)return
    if(!this.current()){this.setData({ready:false,history:[],reason:'',pending:false,canReply:false,canClose:false,canResolve:false,busy:false,error:'登录身份已变化，请返回原记录重新进入'});return}
    if(!this._flight)this.setData({busy:false})
    return this.load()
  },
  onHide(){this._hidden=true;this._seq=(this._seq||0)+1},
  onUnload(){this._disposed=true;this.onHide()},
  current(){return !this._hidden&&!this._disposed&&this._owner===owner()&&this._generation===(getApp()._loginGeneration||0)},
  url(){return this.data.kind==='EXCEPTION'?'/api/manager/exceptions/'+this.data.id+'/resolution':'/api/feedback/'+this.data.id+'/handling'},
  recover(){
    if(!STAFF_APP)return null
    const rows=['VERIFY','REPLY','CLOSE'].map(a=>intent.read(this._owner,this._object,a)).filter(Boolean)
    if(rows.length>1)throw new Error('有多条原办理记录待核对，请保留记录联系负责人')
    this._pending=rows[0]||null;this.setData({pending:!!this._pending,reason:this._pending?this._pending.reason:this.data.reason})
    return this._pending
  },
  async load(){
    if(!this.current()||this.data.busy)return
    const seq=this._seq=(this._seq||0)+1
    this.setData({loading:true,error:'',ready:false})
    try{
      this.recover()
      const r=await get(this.url()),d=r&&r.data
      if(!this.current()||seq!==this._seq)return
      if(!r||r.code!==0||!d||d.kind!==this.data.kind||String(d.caseId)!==this.data.id||!Number.isSafeInteger(d.version)||d.version<0||!Array.isArray(d.actions))throw new Error('处理记录尚未核对，请刷新重试')
      const history=d.actions.map(a=>({...a,label:a.actionText||'处理记录'}))
      this.setData({ready:true,history,version:d.version,canReply:STAFF_APP&&d.canReply===true,canClose:STAFF_APP&&d.canClose===true,canResolve:STAFF_APP&&d.canResolve===true})
    }catch(e){if(this.current()&&seq===this._seq)this.setData({error:e.message||'暂时无法核对处理记录'})}
    finally{if(this.current()&&seq===this._seq)this.setData({loading:false})}
  },
  onReason(e){if(!this.data.busy&&!this.data.pending)this.setData({reason:e.detail.value})},
  onReply(){return this.act('REPLY')},
  onClose(){return this.act('CLOSE')},
  onResolve(){return this.act('VERIFY')},
  onRetry(){return this._pending?this.act(this._pending.action):this.load()},
  async act(action){
    if(!STAFF_APP||!this.current()||this._flight||this.data.busy)return
    const user=(getApp().globalData||{}).userInfo||{}
    if(user.role!=='STATION_MANAGER'){this.setData({error:'仅当前水站站长可以办理'});return}
    const flight=this._flight={};this.setData({busy:true,error:'',result:''})
    const epoch=this._seq,current=()=>this.current()&&epoch===this._seq
    let saved,hadPending=false,failure=''
    try{
      saved=this.recover();hadPending=!!saved
      if(!saved){
        if(!this.data.ready||!(action==='VERIFY'?this.data.canResolve:action==='CLOSE'?this.data.canClose:this.data.canReply))throw new Error('当前记录不能执行此操作，请刷新核对')
        const reason=String(this.data.reason||'').trim(),max=action==='VERIFY'?500:1000
        if(!reason||reason.length>max)throw new Error('请填写处理理由或给顾客的回复，最多'+max+'字')
        const confirm=await new Promise(resolve=>showBusinessModal({title:action==='VERIFY'?'核实无需补偿并结案':action==='CLOSE'?'回复顾客并结案':'发送水站回复',content:reason,confirmText:'确认提交',success:r=>resolve(r.confirm),fail:()=>resolve(false)}))
        if(!confirm||!current())return
        saved=intent.prepare(this._owner,this._object,action,reason,this.data.version)
        const check=intent.read(this._owner,this._object,action)
        if(!check||JSON.stringify(check)!==JSON.stringify(saved))throw new Error('原办理记录未能保存，请勿继续提交')
      }
      if(!current())return
      this._pending=saved;this.setData({pending:true})
      const url=this.data.kind==='EXCEPTION'?'/api/manager/exceptions/'+this.data.id+'/resolve':'/api/feedback/'+this.data.id+(saved.action==='CLOSE'?'/close':'/reply')
      const r=await post(url,intent.payload(saved)),d=r&&r.data
      if(!current())return
      if(!r||r.code!==0||!d||d.kind!==this.data.kind||String(d.caseId)!==this.data.id||d.action!==saved.action||Number(d.version)!==saved.expectedVersion+1)throw new Error('办理结果尚未确认，请核对原请求')
      intent.clear(saved);if(intent.read(this._owner,this._object,saved.action))throw new Error('结果已收到，原记录清理尚未完成，请重试核对')
      this._pending=null;this.setData({pending:false,reason:'',result:saved.action==='REPLY'?'回复已保存，顾客可在我的反馈中查看':'已登记结果并结案'})
    }catch(e){
      failure=e.message||'结果尚未确认，请重试原请求'
      if(current()) {
        if(e.businessRejected&&!hadPending&&saved){try{intent.clear(saved);this._pending=null}catch(storage){failure=storage.message}}
        this.setData({error:failure,pending:!!this._pending})
      }
    }
    finally{
      if(this._flight===flight)this._flight=null
      if(this.current()){
        this.setData({busy:false})
        if(current()&&!this._pending){
          const read=this.load(),refresh=this._seq;await read
          if(failure&&this.current()&&this._seq===refresh)this.setData({error:failure})
        }else if(failure&&current())this.setData({error:failure})
      }
    }
  }
})
