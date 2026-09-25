// 押金流水（顾客查自己在本站的押金变动）。
//
// 后端契约（DepositRecordController.java:56-63，2026-09-18 核对）：
//   · stationId **必传**；缺失时返回 code=1 + message「请选择水站」，**不是**空列表、也不是全部数据。
//   · 返回的是**裸数组**（Result<List<DepositRecord>>），没有分页对象 —— 别去找 records 字段。
//   · 排序由后端给出（DepositRecordMapper.listByCustomerAndStation：order by create_time desc），前端不再排。
//
// 金额一律**原样展示正负**：扣减类流水在后端以负数落库（DepositRecordServiceImpl.java:56-64），
// 这是对账等式1「余额 == SUM(deposit_record.amount)」的前提。
// **禁止 Math.abs** —— 那会让「退押金 -60」和「入账 +60」在界面上长得一模一样，
// 而本页存在的唯一理由就是回答"余额为什么变"。
//
// 类型中文文案由后端下发（entity/DepositRecord.java 的派生 getter getTypeText() → constant/DepositType.textOf，
// 与 Orders.getStatusText / PaymentRecord.getMethodText 同一路数）。**前端绝不自建 type→中文 映射表**：
// 历史上两端各写一套，后端调口径前端不跟随（PayMethod 的 2=水票/2=现金 写反曾导致新客下单 100% 失败，见 AGENTS.md §6）。
// 该 getter 是 2026-09-18 随本页一起补的；**后端必须重启后才下发生效**（部署时前后端一起上）。
// 拿不到 typeText 时本页只是不显示类型名，**不会**退化成自己编的中文。
//
// 路径常量按本仓惯例写在页面顶部：本批不允许改 config/api.js 与 api/*.js，故直接走 utils/request。
// 取水站的方式与余额页完全同源（pages/barrel/index.js:55、pages/mine/index.js:43 的
// stationStorage.getId()）—— 必须同源，否则「余额」与「解释余额的流水」会来自两个水站，
// 客户看到的就是"余额没变但流水在动"。

const { get } = require('../../../utils/request')
const { stationStorage } = require('../../../utils/storage')
const { formatMoney, formatTime } = require('../../../utils/format')

const DEPOSIT_RECORDS = '/api/deposit-records'

Page({
  data: {
    loading: true,
    records: [],
    // 加载失败时**原样**展示后端 message。必须与"确实没有流水"分开渲染：
    // 「界面空白」与「确实没有」无法区分是本仓惯犯（AGENTS.md §8.22）——
    // 这里失败走 error-card，空列表走 empty，两条路径在 wxml 里互斥。
    errorText: '',
    // 本地判断的"没选水站"，只用来决定是否给「去选水站」按钮；
    // 提示文案仍然由后端下发（前端不复制「请选择水站」这句话，后端改文案这里自动跟随）。
    needStation: false,
    stationName: ''
  },

  onShow() {
    this.loadRecords()
  },

  onPullDownRefresh() {
    this.loadRecords().then(() => wx.stopPullDownRefresh())
  },

  async loadRecords() {
    const stationId = stationStorage.getId()
    const station = stationStorage.get()
    this.setData({
      loading: true,
      errorText: '',
      needStation: !stationId,
      stationName: (station && station.name) || ''
    })
    try {
      // stationId 缺失时**故意照发**（utils/request 会过滤掉 null 的 query 参数）：
      // 让后端自己说「请选择水站」，前端不自造文案。
      const res = await get(DEPOSIT_RECORDS, stationId ? { stationId } : {})
      const list = (res && res.data) || []
      this.setData({ records: list.map(r => this.decorate(r)) })
    } catch (err) {
      // 失败必须是失败：显示后端的 message，绝不留成空列表（否则客户以为"我没有押金记录"）
      this.setData({ records: [], errorText: (err && err.message) || '加载失败' })
    } finally {
      this.setData({ loading: false })
    }
  },

  // 纯展示预处理：wxml 不能调 Page 方法，也不能用 Math./Date.（见 AGENTS.md §6），
  // 所以金额文案、正负号、时间文案都在这里算好。
  decorate(record) {
    const amount = record.amount
    const num = (amount === null || amount === undefined) ? 0 : Number(amount)
    return {
      id: record.id,
      // 正负号来自后端落库值（增加为正、扣减为负），这里只给正数补一个显示用的 '+'，
      // 没有任何算术、更没有取绝对值。
      amountText: (num > 0 ? '+' : '') + formatMoney(amount),
      amountState: num > 0 ? 'in' : (num < 0 ? 'out' : 'flat'),
      // 后端下发的派生文案；拿不到就留空，不 fallback 成自己编的中文
      typeText: record.typeText || '',
      timeText: formatTime(record.createTime),
      note: record.note || '',
      orderText: record.relatedOrderId ? ('订单 #' + record.relatedOrderId) : ''
    }
  },

  onGoSelectStation() {
    // 顾客端选水站的唯一入口是首页（pages/home/index.wxml 的 state==='noStation' 卡片）。
    // 必须用 switchTab：首页是 tabBar 页，wx.navigateTo 对 tabBar 页会直接失败
    //（navigateTo:fail can not navigateTo a tabbar page）→ 用户点了什么都不会发生。
    // 同一批已修掉 pages/order/create.js 里的两处同类写法（「去选站」按钮）。
    wx.switchTab({ url: '/pages/home/index' })
  },

  onRetry() {
    this.loadRecords()
  }
})
