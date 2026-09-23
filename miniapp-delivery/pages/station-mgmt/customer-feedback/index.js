// 站长端「客户反馈」：顾客端一键上报的系统错误 + 顾客自己提的反馈，落到本站汇总（只读）。
//
// 为什么这个页面必须存在（别删）：
//   顾客端 `miniapp-user/utils/request.js` 在接口 `code=500` 时会弹「要把这个问题告诉水站吗？」，
//   用户点「上报」就真的 `POST /api/feedback` 落库。在后端 `GET /api/feedback/customers` 被接进本页
//   之前，水站侧**没有任何页面能读到它** —— 顾客以为"告诉水站了"，水站永远看不到。
//   「我的反馈」(`pages/feedback/index`) 是另一回事，那是员工自己提的反馈，与本页人群不重叠。
//
// 三条口径（改这个页面时必须守住）：
//   1. **只读**。后端目前没有回复/处置端点（`FeedbackController` 只有 POST 提交、GET /my、GET /customers），
//      所以这里不做"标记已处理"之类的假按钮 —— 做不出来，还会让站长以为处理过了。
//   2. **加载失败必须与"没有反馈"区分开**（本仓 §8.22：零覆盖端点的真实形态是"界面空白"，
//      与"确实没有"无法区分）。失败时把后端 `message` 原样打在页面上，并**清空 list**，
//      绝不能让"请求挂了"渲染成"本站暂无客户反馈"。
//   3. **所有展示文案都取自后端下发的字段，或由后端字段直接拼出；本文件不建任何
//      「分类→中文」映射表**（本仓明令禁止：两端各写一套映射出过下单 100% 失败的事故）。
//      后端 `feedback.category` 存的就是提交方写的原文（顾客端一键上报写死 '错误报告'），
//      **原样透传**即可，没有可映射的枚举。
//   4. **匿名由后端保证，不是由本页保证**（2026-09-18 产品裁定「客户反馈支持匿名提交」）。
//      匿名记录在 `GET /api/feedback/customers` 的响应里**根本没有** customerId / customerName
//      —— 后端 `FeedbackMapper.listCustomerFeedbackByStation` 用 `CASE WHEN f.anonymous = 1
//      THEN NULL` 在 SQL 层就置空了，前端无从显示。本页只按"有没有姓名/客户号"决定显示
//      「匿名顾客」这一句展示文案，**绝不读 `anonymous` 标志位自己拼身份文案**
//      （响应里该字段恒为 null，后端刻意不下发；详见 decorate 的注释）。
//
// 已知的数据边界（属后端问题、本页不掩盖）：
//   · `contact` 字段顾客端一键上报**根本不发**（`request.js` 的 data 只有 category 与 content），
//     因此这列对自动上报恒为空，只有顾客手动填过联系方式的反馈才有值。
//   · 后端目前没有回复/处置端点，所以这是**只读页**（做不出"标记已处理"）。
//   · 自动上报（顾客端一键上报）**刻意保持实名**（顾客端 `utils/request.js` 的
//     `offerErrorReport` 不传 anonymous）：报障的价值在于站长能复现/追问；
//     只有顾客在「客服/反馈」页手动提交时才可能匿名。
//
// [2026-09-18 同日修复，两处已在后端改掉，改本页时别再把旧行为写回来]
//   · `FeedbackMapper.listCustomerFeedbackByStation` 原来只 `select f.*`、**不 JOIN customer**，
//     `customerName` 恒为 null，页面只能显示"客户 #<id>"。现在已补 `left join customer`，
//     姓名正常下发（下面的代码仍保留"没有名字就退回客户号"的兜底）。
//   · 原 SQL 用 `inner join customer_station_config` 判本站（只看"绑定行"），
//     于是**只在小程序下过单、没有绑定行的老顾客**提交的反馈不进这个列表 ——
//     界面显示"暂无客户反馈"，库里却有记录。现在归属口径改为与
//     `CustomerMapper.countCustomerOfStation` 一致的并集：绑定行 **或** 本站订单。
//
// 权限：后端 `FeedbackController.customerFeedback` 带 `@RequireRole({"STATION_MANAGER"})` ——
// 配送员调只会拿到一条 permission denied 的业务错误，所以本页进页就拦掉非站长。

const { getCustomerFeedbacks } = require('../../../api/feedback')

Page({
  data: {
    loading: true,
    list: [],
    // type: loading | error | empty | list（四个状态互斥，wxml 不回退成"看起来是空的"）
    type: 'loading',
    // 接口失败时原样展示的后端 message（绝不翻译、绝不替换成"暂无"）
    errMsg: '',
    denied: false,
    deniedText: ''
  },

  onLoad() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this.setData({ denied: true, deniedText: '当前账号没有水站业务权限' })
      app.routeByRole(true)
      return
    }
    // `canAccessStationBusiness()` 对已绑定水站的配送员同样返回 true，但客户反馈汇总
    // 是站长专属（后端 @RequireRole("STATION_MANAGER")）。这里前置拦掉，
    // 免得配送员进来只收到一条与本页无关的权限错误。（同 pages/station-mgmt/customers/adjust/index.js）
    if (!app.isStationManager()) {
      this.setData({ denied: true, deniedText: '客户反馈仅站长可查看，请联系站长处理' })
    }
  },

  onShow() {
    if (this.data.denied) return
    this.loadData()
  },

  async onPullDownRefresh() {
    if (this.data.denied) {
      wx.stopPullDownRefresh()
      return
    }
    await this.loadData()
    wx.stopPullDownRefresh()
  },

  /** 失败态上的「重试」按钮（wxml 里不能调方法，事件必须落在真实处理器上）。 */
  onRetry() {
    this.loadData()
  },

  async loadData() {
    this.setData({ loading: true, type: 'loading' })
    try {
      const res = await getCustomerFeedbacks()
      const list = (res.data || []).map(item => this.decorate(item))
      this.setData({
        list,
        type: list.length ? 'list' : 'empty',
        errMsg: ''
      })
    } catch (err) {
      // 静默失败会让站长以为"本站没有客户反馈"，从而把顾客真报上来的问题漏掉 —— 必须出声。
      // 同时清空 list：半截的旧数据 + 报错混在一起，比只显示错误更难判断。
      console.error('[CustomerFeedback] 加载失败:', err)
      this.setData({
        list: [],
        type: 'error',
        errMsg: (err && err.message) || '加载失败'
      })
    } finally {
      this.setData({ loading: false })
    }
  },

  /**
   * 逐条算好展示字段（wxml 里不能调用 Page 方法，也不能 new Date）。
   *
   * ⚠️ 这里只做"把后端字段搬进模板"的事，**不产生任何业务文案映射**：
   * category / content 原样透传，contact 只做"有 / 没有"的判空后原样显示。
   */
  decorate(item) {
    const customerId = item && item.customerId
    const customerName = (item && item.customerName) || ''
    // [2026-09-18 匿名提交] whoText 只看**后端有没有下发姓名/客户号**，不看任何标志位：
    //   · 有姓名 → 姓名（可识别，能闭环）；
    //   · 没姓名但有客户号 → "客户 #<id>"（可识别，只是客户没填名字）；
    //   · 两个都没有 → "匿名顾客"（**这一句是展示文案，允许写死在页面里**）。
    // ⚠️ **不要改成读 `item.anonymous` 来拼身份文案**：后端在站长端列表里
    // 刻意不下发该标志（响应里恒为 null），身份脱敏是后端 SQL 层做的
    // （FeedbackMapper.listCustomerFeedbackByStation 对 anonymous=1 的记录把
    // customer_id 与姓名都置 NULL）。前端据标志位自造文案 = 把"后端保证拿不到身份"
    // 降级成"前端决定不显示"，那是假的匿名（改个 setData 就看见了）。
    // ⚠️ 也别把匿名记录渲染成"客户 #null"—— 那既难听又暴露"这里本该有个 id"。
    const who = customerName || (customerId ? ('客户 #' + customerId) : '匿名顾客')
    return {
      id: item.id,
      whoText: who,
      customerIdText: customerId ? ('客户ID ' + customerId) : '',
      categoryText: (item && item.category) ? item.category : '',
      content: (item && item.content) ? item.content : '',
      contactText: (item && item.contact) ? ('联系方式：' + item.contact) : '',
      // 后端没有下发"能不能联系上他"，所以不猜，只如实告诉站长这一条没有联系方式
      noContactText: (item && item.contact) ? '' : '未留联系方式',
      timeText: this.formatTime(item && item.createTime)
    }
  },

  formatTime(d) {
    if (!d) return ''
    // 后端下发 ISO 字符串（Web 层 Jackson3 未开 WRITE_DATES_AS_TIMESTAMPS）。
    // wxml 里不能 new Date / 调方法，所以统一在这里格式化；解析失败则退化为截断显示，
    // 绝不显示 "Invalid Date"。
    const t = new Date(String(d).replace(' ', 'T'))
    if (isNaN(t.getTime())) return String(d).replace('T', ' ').slice(0, 16)
    const pad = n => (n < 10 ? '0' + n : '' + n)
    return t.getFullYear() + '-' + pad(t.getMonth() + 1) + '-' + pad(t.getDate())
      + ' ' + pad(t.getHours()) + ':' + pad(t.getMinutes())
  }
})
