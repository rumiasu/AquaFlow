// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get } = require('../../utils/request')

const EARNINGS = '/api/delivery/earnings'

/**
 * 员工端「我的工资」：配送员（以及自己也在送水的站长）看自己的计件收益与结算单。
 * 规格见 docs/design/18，接口是员工自助端点（身份取自登录态）。
 *
 * 三条口径（改这个页面时必须守住）：
 *   1. **这是自助页**：服务端只认登录态里的身份，路径里没有 staffId。别"顺手"加一个 ——
 *      加了服务端也只会返回你自己那份（`DeliveryMyEarningsIntegrationTest` 锁着这条）。
 *   2. **前端不算钱**：合计、未结合计、结算单金额一律用服务端下发的值；
 *      类型文案用 `kindText`、单据状态用 `statusText`（本仓前端禁止自带映射表）。
 *   3. **发钱在线下**：这里没有「提现 / 打款」按钮，只有站长登记过的发放状态。
 *
 * 金额与时间在本文件里格式化：wxml 不能调用 Page 方法，也不能用 Math / Date。
 */
Page({
  data: {
    loading: true,
    loadError: '',
    loaded: false,
    range: 'month',        // month（本月至今） | today
    from: '',
    to: '',
    periodTotalText: '待核实',
    unsettledTotalText: '待核实',
    items: [],
    payrolls: [],
    note: '', showSettlement: false, rangeExpanded: false, fromDraft: '', toDraft: '', rangeError: '',
    payrollHasMore: false, payrollLoadingMore: false, payrollError: '',
    selectedPayroll: null, detailItems: [], detailLoading: false, detailError: ''
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this._hidden = false
    const context = this.earningsContext()
    if (this._owner && context !== this._owner) {
      this.setData({ loading:false, loaded:false, items:[], payrolls:[], selectedPayroll:null, detailItems:[], periodTotalText:'待核实', unsettledTotalText:'待核实', loadError:'登录身份或水站已变化，请退出本页重新进入' }); return
    }
    this._owner = context
    return this.load()
  },

  earningsContext() { const a=getApp(),d=a.globalData||{},u=d.userInfo||{};return JSON.stringify([!!d.isLogin,a._loginGeneration||0,u.staffId,u.stationId,u.role,u.bindStatus]) },
  currentEarnings(context) { return !this._hidden && !this._disposed && context===this.earningsContext() && (!this._owner||context===this._owner) },
  onHide() { this._hidden=true;for(const k of ['_loadSeq','_payrollSeq','_detailSeq'])this[k]=(this[k]||0)+1 },
  onUnload() { this._disposed=true;this.onHide() },

  /** json 里开了 enablePullDownRefresh，就必须有对应的处理函数，否则下拉只转圈不停。 */
  async onPullDownRefresh() {
    try {
      await this.load()
    } finally {
      wx.stopPullDownRefresh()
    }
  },

  onRange(e) {
    const range = e.currentTarget.dataset.range
    if (range === this.data.range) return
    this.setData({ range })
    this.load()
  },

  onOpenRange() { this.setData({ rangeExpanded:!this.data.rangeExpanded,fromDraft:this.data.fromDraft||this.data.from,toDraft:this.data.toDraft||this.data.to }) },
  onFromChange(e) { this.setData({fromDraft:e.detail.value,rangeError:''}) },
  onToChange(e) { this.setData({toDraft:e.detail.value,rangeError:''}) },
  onApplyPeriod() {
    const from=this.data.fromDraft,to=this.data.toDraft
    if(!/^\d{4}-\d{2}-\d{2}$/.test(from)||!/^\d{4}-\d{2}-\d{2}$/.test(to)||from>to) { this.setData({rangeError:'请选择有效的起止日期，结束日期不能早于开始日期'}); return }
    this.setData({range:'custom',rangeError:'',rangeExpanded:false});return this.load()
  },

  onToggleSettlement() { this.setData({ showSettlement: !this.data.showSettlement }) },

  onRetry() { return this.load() },

  async load() {
    const context=this.earningsContext()
    if(!this.currentEarnings(context))return
    const seq = this._loadSeq = (this._loadSeq || 0) + 1
    const current=()=>seq===this._loadSeq&&this.currentEarnings(context)
    this._payrollSeq=(this._payrollSeq||0)+1;this._detailSeq=(this._detailSeq||0)+1
    this.setData({ loading: true, loadError: '', loaded: false, periodTotalText: '待核实', unsettledTotalText: '待核实', items: [], payrolls: [], payrollHasMore:false,payrollLoadingMore:false,payrollError:'',selectedPayroll:null,detailItems:[],detailLoading:false,detailError:'' })
    const today = dayText(new Date())
    const from = this.data.range === 'custom' ? this.data.fromDraft : this.data.range === 'today' ? today : monthStartText(new Date())
    const to=this.data.range==='custom'?this.data.toDraft:today
    try {
      const res = await get(EARNINGS, { from, to })
      if (!current()) return
      const d = res.data
      if (!d || !Array.isArray(d.items) || !Array.isArray(d.payrolls)) throw new Error('工资数据不完整，请重新读取')
      const items = (d.items || []).map(it => ({
        id: it.id,
        kindText: it.kindText,
        amountText: money(it.amount),
        // 只有计件类收益才有「数量 × 单价」，人工调整没有
        detailText: it.qty ? it.qty + ' × ' + money(it.unitAmount) : '',
        orderId: it.orderId,
        note: it.note || '',
        timeText: dateTimeText(it.createTime)
      }))
      const payrolls = (d.payrolls || []).map(p => ({
        id: p.id,
        payrollNo: p.payrollNo,
        periodText: text(p.periodStart) + ' ~ ' + text(p.periodEnd),
        totalText: money(p.totalAmount),
        // ⚠️ 状态文案由服务端下发，前端不写 1/2/3 映射表
        statusText: p.statusText,
        paidText: p.paidTime ? dateTimeText(p.paidTime) : ''
      }))
      this.setData({
        from: d.from || from,
        to: d.to || to,
        periodTotalText: money(d.periodTotal),
        unsettledTotalText: money(d.unsettledTotal),
        items,
        payrolls, payrollHasMore:payrolls.length>=20,
        note: d.note || '', loaded: true
      })
    } catch (err) {
      if (!current()) return
      this.setData({ loadError: '工资尚未读取，金额与收益明细待核实' })
      // 静默失败会让配送员以为"这个月没挣到钱"，必须出声
      wx.showToast({ title: err.message || '工资加载失败', icon: 'none' })
    } finally {
      if (current()) this.setData({ loading: false })
    }

  },

  /** 2026-11-02：过去20张之后原来不可达；只读本人历史，用严格递减id继续加载。 */
  async onMorePayrolls() {
    const context=this.earningsContext()
    if(!this.currentEarnings(context)||this.data.loading||this.data.payrollLoadingMore||!this.data.payrollHasMore)return
    const before=Number(this.data.payrolls[this.data.payrolls.length-1]?.id)
    if(!Number.isSafeInteger(before)||before<=0){this.setData({payrollError:'原工资记录位置尚未核实，请重新读取'});return}
    const seq=this._payrollSeq=(this._payrollSeq||0)+1,current=()=>seq===this._payrollSeq&&this.currentEarnings(context)
    this.setData({payrollLoadingMore:true,payrollError:''})
    try {
      const res=await get(EARNINGS+'/payrolls',{beforeId:before});if(!current())return
      const d=res&&res.data;let boundary=before
      if(!d||!Array.isArray(d.payrolls)||d.payrolls.length>20||typeof d.hasMore!=='boolean')throw Error('历史工资记录尚未完整读取')
      for(const row of d.payrolls){const id=Number(row?.id);if(!Number.isSafeInteger(id)||id<=0||id>=boundary)throw Error('历史工资记录顺序尚未核实');boundary=id}
      if(d.hasMore&&(!d.payrolls.length||Number(d.nextBeforeId)!==boundary))throw Error('后续工资记录位置尚未核实')
      this.setData({payrolls:this.data.payrolls.concat(d.payrolls.map(payrollView)),payrollHasMore:d.hasMore})
    } catch(e){if(current())this.setData({payrollError:e.message||'更早工资单暂未读取，已有记录仍保留'})}
    finally{if(current())this.setData({payrollLoadingMore:false})}
  },
  async onOpenPayroll(e) {
    const context=this.earningsContext(),id=Number(e.currentTarget.dataset.id)
    const original=this.data.payrolls.find(p=>Number(p.id)===id)
    if(!this.currentEarnings(context)||this.data.loading||!original)return
    const seq=this._detailSeq=(this._detailSeq||0)+1,current=()=>seq===this._detailSeq&&this.currentEarnings(context)
    this.setData({selectedPayroll:original,detailItems:[],detailLoading:true,detailError:''})
    try {
      const res=await get(EARNINGS+'/payrolls/'+id);if(!current())return
      const d=res&&res.data,u=(getApp().globalData||{}).userInfo||{}
      if(!d||!d.payroll||Number(d.payroll.id)!==id||String(d.payroll.staffId)!==String(u.staffId)||!Array.isArray(d.items))throw Error('本人工资单明细尚未核实')
      this.setData({selectedPayroll:payrollView(d.payroll),detailItems:d.items.map(it=>({id:it.id,kindText:it.displayText||it.kindText,amountText:money(it.amount),timeText:dateTimeText(it.createTime),note:it.note||''}))})
    } catch(e){if(current())this.setData({detailError:e.message||'工资单明细暂未读取'})}
    finally{if(current())this.setData({detailLoading:false})}
  },
  onRetryPayrollDetail() { if(this.data.selectedPayroll)return this.onOpenPayroll({currentTarget:{dataset:{id:this.data.selectedPayroll.id}}}) },
  onClosePayroll() { this._detailSeq=(this._detailSeq||0)+1;this.setData({selectedPayroll:null,detailItems:[],detailLoading:false,detailError:''}) }
})

function payrollView(p) { return {id:p.id,payrollNo:p.payrollNo,periodText:text(p.periodStart)+' ~ '+text(p.periodEnd),totalText:money(p.totalAmount),statusText:p.statusText,paidText:p.paidTime?dateTimeText(p.paidTime):''} }

function money(v) {
  const n = Number(v)
  return v === null || v === undefined || v === '' || !Number.isFinite(n) ? '待核实' : n.toFixed(2)
}

function text(v) {
  return v === null || v === undefined ? '' : String(v)
}

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

/** 本地日期串（后端按 LocalDate 解析，不能用 toISOString —— 那是 UTC，会差一天） */
function dayText(d) {
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

function monthStartText(d) {
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-01'
}

/** ISO-8601 串直接 new Date()，禁止 replace(/-/g,'/')（本仓有明文约定） */
function dateTimeText(s) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return ''
  return dayText(d) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}
