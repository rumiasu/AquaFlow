const {
  getStationStatus,
  updateStationStatus,
  getNotices,
  createNotice,
  updateNotice,
  deleteNotice
} = require('../../../api/station-mgmt')

/**
 * 营业状态 + 公告（站长）。
 *
 * 产品口径（2026-09-17 与站长确认）：
 *   · 营业状态是**软状态** —— 顾客照常下单，只是会在商城/下单页看到横幅、下单响应里带提示；
 *     "真的不接单"用的是另一套（station.status=2 停业，下单会被拒），两者别混。
 *   · 留言是配合状态的一句话说明（≤100 字），会原样展示给顾客。
 *   · 公告是本站通知（顾客端只读已发布的），支持草稿。
 *
 * 状态文案由后端下发，前端只用 value 提交。⚠️ 下面那张 `STATUS_OPTIONS` 是**兜底**
 * （后端拉不到时用），能拉到 `GET /api/manager/station-status` 的 `options` 就以它为准
 * （value/text/desc 同源于 `constant/StationOperatingStatus`）—— 手写表最容易在改名/
 * 增删取值时静默过期。
 *
 * [2026-09-24] **兜底表里没有「待上线」**：它是**系统状态**（只有刚注册的站才是它，
 * 配齐资料后转正、且再也回不去），后端下发的 `options` 已不含它，手动提交 3 也会被拒。
 * ⚠️ 别因为"兜底表少了 3"就把它加回来 —— 出现了就等于告诉站长他能设，而后端会拒。
 *
 * [2026-09-19] 本页原先还管**水站坐标**（地图选点），已整块搬到
 * `pages/station-mgmt/station-info/` —— 坐标是"水站资料"（固定信息），不是"营业状态"（临时状态）。
 * 别把它加回来：两处各写一份必然分叉（本仓"计价双轨"的同形风险）。
 */

const STATUS_OPTIONS = [
  { value: 1, name: '正常运营', desc: '照常接单配送' },
  { value: 2, name: '休息中', desc: '打烊/午休，稍后恢复' },
  { value: 4, name: '暂停配送，可预约', desc: '今天不送，订单明天统一处理' }
]

Page({
  data: {
    loading: true,
    statusOptions: STATUS_OPTIONS,
    current: { operatingStatus: 1, statusText: '正常运营', note: '', customerHint: '' },
    // 「顾客那边会怎样」：跟着**当前显示的那个状态**走 —— 打开页面显示当前状态的，
    // 点了别的选项就换成那个选项的，**再点一次同一个就撤回当前状态**（见 onPickStatus）。
    // 文案全部来自后端（options[].effect / currentEffect）。
    effectLabel: '当前状态，顾客那边：',
    effectText: '',
    // 当前状态的效果（单独存一份）：点选项再撤回时要还原成它。
    // ⚠️ 不能从 `current.customerHint` 里取 —— 那是给**顾客**看的一句话，口径与 effect 不同。
    currentEffect: '',
    picked: 1,
    noteInput: '',
    saving: false,

    notices: [],
    noticesLoading: false,
    showEdit: false,
    isAdd: true,
    editId: null,
    // 编辑时的**原状态**（0 草稿 / 1 已发布）—— 保存时原样带回，见 openEditNotice。
    // ⚠️ 这里原来还有个 `published: true`（配合已删掉的「立即发布」开关），现在没人读了，已清掉。
    editStatus: 0,
    editForm: { title: '', content: '' },
    // 按钮上方那句"保存会发生什么"（按新建 / 编辑已发布 / 编辑草稿三种情形分别给，
    // 值在 openAddNotice / openEditNotice 里设）。**不能写死一句**：
    // 编辑已发布的公告保存后仍然可见，说成"要发布才看得见"就是假话。
    saveHint: ''
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadStatus()
    this.loadNotices()
  },

  onPullDownRefresh() {
    Promise.all([this.loadStatus(), this.loadNotices()]).then(() => wx.stopPullDownRefresh())
  },

  async loadStatus() {
    this.setData({ loading: true })
    try {
      const res = await getStationStatus()
      const d = res.data || {}
      const patch = {
        current: {
          operatingStatus: d.operatingStatus || 1,
          statusText: d.statusText || '正常运营',
          note: d.note || '',
          customerHint: d.customerHint || '',
          statusUpdateTime: d.statusUpdateTime || ''
        },
        picked: d.operatingStatus || 1,
        noteInput: d.note || ''
      }
      // 选项**以后端下发为准**（value/text/desc/effect 同源于 constant/StationOperatingStatus）；
      // 后端还没这些字段时（旧后端）才用文件头那张兜底表，别让它成为唯一来源。
      const opts = (d.options || []).map(o => ({ value: o.value, name: o.text, desc: o.desc, effect: o.effect || '' }))
      if (opts.length) patch.statusOptions = opts
      // 打开页面先显示**当前状态**的效果；点了别的选项再换成那个选项的。
      // ⚠️ 待上线**不在可选项里**，所以它的效果只能从 currentEffect 拿（见后端 toView）。
      patch.effectLabel = '当前状态，顾客那边：'
      patch.effectText = d.currentEffect || ''
      patch.currentEffect = d.currentEffect || ''
      this.setData(patch)
    } catch (err) {
      wx.showToast({ title: err.message || '营业状态加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  /**
   * 选一个状态 → **就地显示"选了它顾客那边会怎样"**（2026-09-24 产品要求）。
   * **再点一次已选中的那个 = 取消锁定**，回到"当前状态"那一句（2026-09-24 追加）。
   *
   * ⚠️ 文案来自后端下发的 `options[].effect` / `currentEffect`，**前端不拼句子**：原来这里是前端用
   * `statusOptions[picked-1].name + '（仍可下单，站长会按留言安排配送）'` 拼的，
   * 而它对待上线拼出的是**假话**（待上线的站对顾客不可见，压根下不了单）。
   */
  onPickStatus(e) {
    const value = Number(e.currentTarget.dataset.value)
    const current = this.data.current.operatingStatus

    // 「取消锁定」：点的正是**已经选中**的那个，且它跟当前状态不同 → 撤回当前状态，不提交任何改动。
    // 判据带上 `picked !== current` 是为了让"本来就没改过"时再点一下**什么都不发生**
    // （否则会闪一下没意义的 setData；而且那时也没有"锁定"可取消）。
    if (value === this.data.picked && this.data.picked !== current) {
      this.setData({
        picked: current,
        effectLabel: '当前状态，顾客那边：',
        effectText: this.data.currentEffect || ''
      })
      return
    }

    const opt = (this.data.statusOptions || []).find(o => o.value === value)
    this.setData({
      picked: value,
      effectLabel: '选了它，顾客那边：',
      effectText: (opt && opt.effect) || ''
    })
  },

  onNoteInput(e) {
    this.setData({ noteInput: e.detail.value })
  },

  async onSaveStatus() {
    this.setData({ saving: true })
    try {
      const res = await updateStationStatus(this.data.picked, this.data.noteInput)
      const d = res.data || {}
      this.setData({
        current: {
          operatingStatus: d.operatingStatus || 1,
          statusText: d.statusText || '正常运营',
          note: d.note || '',
          customerHint: d.customerHint || '',
          statusUpdateTime: d.statusUpdateTime || ''
        }
      })
      wx.showToast({ title: '已保存', icon: 'success' })
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  /* ==================== 公告 ==================== */

  async loadNotices() {
    this.setData({ noticesLoading: true })
    try {
      const res = await getNotices()
      const notices = (res.data || []).map(n => ({
        ...n,
        // [2026-09-18] statusText 一律用后端下发的（Notice.getStatusText，真相源 constant/NoticeStatus.java）。
        // 这里原来写的是 `n.status === 1 ? '已发布' : '草稿'` —— 正是本仓禁止的"前端自带映射表"：
        // 后端一旦改状态口径，前端不会跟随、也不会报错，只会一直显示错的那句。
        timeText: (n.createTime || '').replace('T', ' ').slice(0, 16)
      }))
      this.setData({ notices })
    } catch (err) {
      wx.showToast({ title: err.message || '公告加载失败', icon: 'none' })
    } finally {
      this.setData({ noticesLoading: false })
    }
  },

  /**
   * 新建公告 → 表单**只有标题与内容**，两个按钮一次决定结果（2026-09-26 产品裁定）：
   *   · 右键「发布」= 直接上线（status 1，顾客立刻可见）；
   *   · 左键「存为草稿」= 先留着（status 0，顾客看不到，之后可在列表里发布）。
   *
   * ⚠️ 历史沿革（别改回去）：
   *   ① 最早有个「立即发布（关掉则存为草稿）」开关，但它跟按钮文案对不上 ——
   *      按钮写「发布」、toast 又写死「已发布」，关掉开关存草稿照样说"已发布"；
   *   ② 后来改成"新建即草稿 + 发布是列表里那个动作"，又变成**两步**（产品反馈：
   *      "不拆分多步"）；
   *   ③ 现在 = 一步两键，**默认方向是右键发布**，左键才是草稿。
   */
  openAddNotice() {
    this.setData({
      showEdit: true,
      isAdd: true,
      editId: null,
      editStatus: 0,
      editForm: { title: '', content: '' },
      // 按钮上方那句"点下去会怎样"必须跟这次的两个按钮一致（见 _submitNotice 的规矩②）
      saveHint: '点「发布」顾客立刻能看到；点「存为草稿」先留着，之后在列表里还能发布。'
    })
  },

  /**
   * 编辑公告。
   *
   * ⚠️ 两个按钮都在弹窗里（右键发布 / 左键存草稿），所以**默认方向很重要**：
   *    右键「发布」= 保存并保持可见（改错别字的常规路径，不会把公告弄下线）；
   *    左键「存为草稿」= **把这条下架**（顾客立刻看不到）—— 会先弹二次确认，见 _submitNotice。
   *    `editStatus` 只用来判断"要不要问那一句"，不再直接决定保存后的状态。
   */
  openEditNotice(e) {
    const item = e.currentTarget.dataset.item
    const published = item.status === 1
    this.setData({
      showEdit: true,
      isAdd: false,
      editId: item.id,
      editStatus: item.status,
      editForm: { title: item.title || '', content: item.content || '' },
      saveHint: published
        ? '点「发布」保存并保持可见；点「存为草稿」会把它下架，顾客立刻看不到。'
        : '点「发布」顾客立刻能看到；点「存为草稿」继续留着。'
    })
  },

  closeEditNotice() {
    this.setData({ showEdit: false })
  },

  stopPropagation() {},

  /** 弹窗遮罩上吞掉 touchmove，防止滚动穿透到页面（wxml 用 catchtouchmove） */
  preventMove() {},

  onNoticeFieldInput(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ ['editForm.' + field]: e.detail.value })
  },

  /** 表单校验（两个按钮都要过）：标题与内容都不能空。 */
  _noticeFormInvalid() {
    const { editForm } = this.data
    if (!editForm.title || !editForm.title.trim()) {
      wx.showToast({ title: '请填写公告标题', icon: 'none' })
      return true
    }
    if (!editForm.content || !editForm.content.trim()) {
      wx.showToast({ title: '请填写公告内容', icon: 'none' })
      return true
    }
    return false
  },

  /**
   * 弹窗右键：**发布**（一点就上线，顾客立刻可见）。
   *
   * [2026-09-26 产品裁定] 改回"一步到位"：不再要求站长"先存草稿 → 再去列表里点发布"。
   *   新建 = 直接发布（status 1）；编辑 = 保存并保持发布（已发布的仍是已发布）。
   *   想先放着不发就走左边的「存为草稿」。
   */
  async onSaveNotice() {
    this._submitNotice(1)
  },

  /**
   * 弹窗左键：**存为草稿**（顾客看不到，之后可在列表里点「发布」）。
   *
   * ⚠️ 编辑一条**已发布**的公告时，这一步等于**把它下架**（顾客立刻看不到）——
   *    这正是"静默消失"的高发处，所以先弹二次确认（见 _submitNotice）。
   *    原来的实现是"编辑保持原状态"，于是想下架只能去列表点；现在两键都在弹窗里，
   *    但**默认方向反过来了**：右键是发布、左键是草稿，误点左键会下线一条正在展示的公告。
   */
  async onSaveDraft() {
    this._submitNotice(0)
  },

  /**
   * 保存公告。
   *
   * @param {number} nextStatus 1 = 发布（顾客立刻可见）；0 = 草稿（顾客看不到）
   *
   * ⚠️ 两条硬规矩（写错都是"界面说做成了、实际没做"）：
   *   ① **toast 必须跟真实 status 一致** —— 曾写死「已发布」而实际存的 0（站长以为顾客看到了）；
   *   ② **把已发布的打成草稿必须先确认** —— 那是"顾客端立刻消失"，不能靠手滑决定。
   */
  _submitNotice(nextStatus) {
    const { isAdd, editId, editForm, editStatus } = this.data
    if (this._noticeFormInvalid()) {
      return
    }
    // 只有"编辑一条原本已发布的公告、并选了存草稿"才需要二次确认
    const willUnpublish = !isAdd && editStatus === 1 && nextStatus === 0
    if (willUnpublish) {
      wx.showModal({
        title: '转为草稿并下架？',
        content: '保存后这条公告会立刻从顾客端消失（内容不会丢，之后还能在列表里重新发布）。',
        // ⚠️ **按钮文案最多 4 个字符**（微信 showModal 的硬限制）。写过 5 个字的
        // 「下架为草稿」⇒ 这次调用被平台直接拒掉、又没接 fail ⇒ 表现是"点存为草稿毫无反应"
        //（2026-09-26 真机反馈）。改文案时别加长；要改就一起改这条注释。
        confirmText: '转为草稿',
        cancelText: '再想想',
        success: (r) => {
          if (r.confirm) this._doSaveNotice(nextStatus)
        },
        // 弹窗没弹出来（平台拒绝 / 版本差异）**绝不能静默**：什么都不做 + 告诉人重试。
        // 这里刻意**不**把降级动作照做 —— "下架一条正在展示的公告"不该由一次失败的弹窗决定。
        fail: (err) => {
          console.error('[StationStatus] 确认弹窗调用失败:', err)
          wx.showToast({ title: '确认弹窗没能打开，请重试', icon: 'none' })
        }
      })
      return
    }
    this._doSaveNotice(nextStatus)
  },

  async _doSaveNotice(status) {
    const { isAdd, editId, editForm } = this.data
    const payload = {
      title: editForm.title.trim(),
      content: editForm.content.trim(),
      // type=2 水站通知（站长发的是站点通知，不是系统公告/活动）
      type: 2,
      status
    }
    this.setData({ saving: true })
    try {
      if (isAdd) {
        await createNotice(payload)
      } else {
        await updateNotice(editId, payload)
      }
      // ⚠️ 文案跟着**真实 status** 走，不许一律说"已发布"（见 _submitNotice 的规矩①）
      wx.showToast({
        title: status === 1 ? '已发布，顾客现在能看到' : '已存为草稿，顾客看不到',
        icon: 'none',
        duration: 2500
      })
      this.setData({ showEdit: false })
      this.loadNotices()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  onToggleNoticeStatus(e) {
    const item = e.currentTarget.dataset.item
    const next = item.status === 1 ? 0 : 1
    updateNotice(item.id, {
      title: item.title,
      content: item.content,
      type: item.type || 2,
      status: next
    })
      .then(() => {
        wx.showToast({ title: next === 1 ? '已发布' : '已下架', icon: 'success' })
        this.loadNotices()
      })
      .catch(err => wx.showToast({ title: err.message || '操作失败', icon: 'none' }))
  },

  onDeleteNotice(e) {
    const item = e.currentTarget.dataset.item
    wx.showModal({
      title: '删除公告',
      content: '删除后客户立刻看不到该公告，确认删除？',
      confirmText: '删除',
      confirmColor: '#B5442C',
      success: (res) => {
        if (!res.confirm) return
        deleteNotice(item.id)
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' })
            this.loadNotices()
          })
          .catch(err => wx.showToast({ title: err.message || '删除失败', icon: 'none' }))
      }
    })
  }
})
