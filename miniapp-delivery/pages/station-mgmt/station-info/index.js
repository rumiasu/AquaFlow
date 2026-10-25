const { jumpToSettingSection } = require('../../../utils/settings-sections')
const { initialModuleData, refreshModuleTasks } = require('../../../utils/setup-progress')
// 站长端「水站资料」：站名 / 电话 / 地址 / 坐标。
//
// [2026-09-19 新建] 在此之前这几个字段是**死的**：宫格 F 分区那格「水站资料」点了只是
// 跳到「我的」tab（纯只读的展示卡），而**后端端点早就存在**
// （`PUT /api/stations/{id}`，带 `getByIdAndCreator` 归属校验）—— 小程序从来没过接线。
// 于是站长建完站以后，站名/电话/地址再也改不了，而客户在下单页要靠它们认水站。
//
// 坐标是**从 station-status 页搬过来的**（那边一并删掉）：坐标属于"水站资料"，
// 放在"营业状态与公告"页里语义不对；两处各写一份 = 迟早分叉（本仓"计价双轨"的同形风险）。
//
// ⚠️ 两条不能忘的约束（详见 api/station-mgmt.js 的 updateStation javadoc）：
//   1. `PUT /api/stations/{id}` 是**整行覆盖**，payload 必须把 name/phone/address/status 四项给全；
//      所以本页先读一次 getMyStation()，改哪项覆盖哪项，其余原值带回。
//   2. `status`（1 营业 / 2 停业）是**硬状态**，停业会真拒单 —— 本页**不提供修改入口**，
//      只原样带回。别顺手把它做成开关。
const { getMyStation, updateStation, updateStationCoordinates } = require('../../../api/station-mgmt')
function stationSession() {
  const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
  return { app, generation: app._loginGeneration || 0, stationId: Number(u.stationId),
    identity: JSON.stringify([!!g.isLogin, u.staffId, u.role, u.stationId, u.bindStatus]) }
}
function sameStationSession(proof) {
  const now = stationSession()
  return proof && proof.app === now.app && proof.generation === now.generation && proof.identity === now.identity && now.app.canAccessStationBusiness()
}

Page({
  onSettingsSection(e) { jumpToSettingSection(this, e) },
  data: {
    settingsSections: [{"target": "info-basics", "label": "联系资料"}, {"target": "setup-station-position", "label": "地图坐标"}],
    ...initialModuleData(),
    loading: true, loadError: '',
    // 原值（含后端下发但本页不编辑的 status），保存时原样带回
    origin: null,
    form: { name: '', phone: '', address: '' },
    saving: false,
    // lat 为 null = 还没选点，此时配送范围校验会跳过（不是拒单）
    coord: { lat: null, lng: null },
    coordSaving: false
  },

  refreshModuleSetup() { return refreshModuleTasks(this, '/pages/station-mgmt/station-info/index') },
  onModuleSetupTask(e) {
    const key = e.currentTarget.dataset.key
    if (!(this.data.moduleSetupTasks || []).some(it => it.key === key)) return
    const target = key === 'stationPhone' ? '#setup-station-phone' : '#setup-station-position'
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ selector: target, duration: 180 })
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    if (!sameStationSession(this._stationSession)) {
      this._coordinateVersion = (this._coordinateVersion || 0) + 1
      this._coordinatePick = null
      this.setData({ coordSaving: false })
    }
    this._stationSession = stationSession()
    // 原生地图会正常 hide/show；同一选点周期返回时不重读覆盖输入，也不使合法回调失效。
    if (!this._coordinatePick || !this.coordinateCurrent(this._coordinatePick)) return this.loadData()
  },

  onUnload() {
    this._moduleSetupClosed = true
    this._stationUnloaded = true
    this._coordinateVersion = (this._coordinateVersion || 0) + 1
    this._stationReadVersion = (this._stationReadVersion || 0) + 1
    this._coordinatePick = null
  },

  coordinateCurrent(proof) {
    return !this._stationUnloaded && this._coordinatePick === proof && proof.version === this._coordinateVersion
      && sameStationSession(proof.session) && Number(this.data.origin && this.data.origin.id) === proof.session.stationId
  },

  onRetry() { return this.loadData() },

  async loadData() {
    this.refreshModuleSetup()
    if (this._stationUnloaded) return
    const session = stationSession(), version = this._stationReadVersion = (this._stationReadVersion || 0) + 1
    const current = () => !this._stationUnloaded && sameStationSession(session) && version === this._stationReadVersion
    this.setData({ loading: true, loadError: '' })
    try {
      const res = await getMyStation()
      if (!current()) return
      const d = res.data
      if (!d || !d.id) throw new Error('水站资料尚未读取')
      this.setData({
        origin: d,
        form: {
          name: d.name || '',
          phone: d.phone || '',
          address: d.address || ''
        },
        coord: {
          // 三态别压扁：null 表示未设置，不能被 `|| null` 之外的写法顺手变成 0
          lat: (d.lat === null || d.lat === undefined) ? null : d.lat,
          lng: (d.lng === null || d.lng === undefined) ? null : d.lng
        }
      })
    } catch (err) {
      if (!current()) return
      // 静默失败会让页面显示成空白表单，站长一保存就把站名冲没了 —— 必须出声。
      this.setData({ loadError: '水站资料尚未读取，请重新读取后修改' })
      console.error('[StationInfo] 读取水站资料失败:', err)
      wx.showToast({ title: (err && err.message) || '读取水站资料失败', icon: 'none' })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  onNameInput(e) { this.setData({ 'form.name': e.detail.value }) },
  onPhoneInput(e) { this.setData({ 'form.phone': e.detail.value }) },
  onAddressInput(e) { this.setData({ 'form.address': e.detail.value }) },

  /**
   * 保存资料。四项给全（见文件头约束 1）。
   * `status` 取原值 —— 后端 SQL 会写这一列，漏传就是 NULL（`station.status` 可空但语义是"营业状态未知"）。
   */
  async onSave() {
    if (this.data.saving) return
    const origin = this.data.origin || {}
    const name = (this.data.form.name || '').trim()
    if (!name) {
      wx.showToast({ title: '水站名称不能为空', icon: 'none' })
      return
    }
    if (!origin.id) {
      wx.showToast({ title: '没读到水站信息，请下拉刷新重试', icon: 'none' })
      return
    }

    this.setData({ saving: true })
    try {
      const res = await updateStation(origin.id, {
        name,
        phone: (this.data.form.phone || '').trim(),
        address: (this.data.form.address || '').trim(),
        status: origin.status
      })
      if (res.code !== 0) {
        wx.showToast({ title: res.message || '保存失败', icon: 'none' })
        return
      }
      wx.showToast({ title: '已保存', icon: 'success' })
      this.loadData()
    } catch (err) {
      wx.showToast({ title: (err && err.message) || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  /**
   * 地图选点并保存。
   *
   * 选完**立即保存**而不是"改了再点保存"：坐标这一项走的是独立端点
   * （`PUT /api/stations/mine/coordinates`），没有别的可改字段，
   * 多一步确认只会让站长以为还需要按别处的按钮。
   *
   * [2026-09-23 与建站页统一] 选点会**同时带回地址文本**，建站页就是这么用的
   * （`create-station/index.js` 一次选点同时产出 address + lat/lng）。本页原来只存 lat/lng，
   * 于是站长看到"地址注册时已经填过了，为什么还让我再选一次点"（用户原话）—— 两页口径分叉。
   * 现在：地址框**为空**时用选点结果回填并顺手一起保存（一次动作把「位置」这条引导项清掉）；
   * 地址框**已有内容**时只存坐标、**绝不覆盖** —— 站长可能特意写得更细（"院内 3 号楼东侧"），
   * 选点给的地址通常更粗，覆盖等于把他的信息删了。
   */
  onPickCoordinates() {
    const session = stationSession()
    if (this._stationUnloaded || this.data.coordSaving || this.data.saving || !sameStationSession(session)
      || !Number.isSafeInteger(session.stationId) || session.stationId <= 0 || Number(this.data.origin && this.data.origin.id) !== session.stationId) return
    const proof = { session, version: this._coordinateVersion = (this._coordinateVersion || 0) + 1, used: false }
    this._stationSession = session
    this._coordinatePick = proof
    this.setData({ coordSaving: true })
    wx.chooseLocation({
      success: async (res) => {
        if (!this.coordinateCurrent(proof) || proof.used) return
        proof.used = true
        try {
          if (typeof res.latitude !== 'number' || typeof res.longitude !== 'number' || !Number.isFinite(res.latitude) || !Number.isFinite(res.longitude)
            || Math.abs(res.latitude) > 90 || Math.abs(res.longitude) > 180) throw Error('选点坐标未能核实，请重新选择')
          // 写接口按当前登录站判权；必须在发出之前核对发起身份，不能仅拦迟到渲染。
          await updateStationCoordinates(res.latitude, res.longitude)
          if (!this.coordinateCurrent(proof)) return

          const typed = (this.data.form.address || '').trim()
          const picked = ((res.name || '') + (res.address || '')).trim()
          const fillAddress = !typed && !!picked

          const patch = { 'coord.lat': res.latitude, 'coord.lng': res.longitude }
          if (fillAddress) {
            patch['form.address'] = picked
          }
          this.setData(patch)

          if (fillAddress) {
            // 地址框本来是空的 → 不存在覆盖风险，顺手一起存掉，
            // 省掉"再点一次保存资料"（站长会以为又要填两遍）
            const form = this.data.form, origin = this.data.origin
            if (!this.coordinateCurrent(proof)) return
            if (!(form.name || '').trim()) { wx.showToast({ title: '坐标已保存，请补全站名后保存地址', icon: 'none' }); return }
            await updateStation(origin.id, { name: form.name.trim(), phone: (form.phone || '').trim(), address: picked, status: origin.status })
            if (this.coordinateCurrent(proof)) { this.refreshModuleSetup(); wx.showToast({ title: '坐标与地址已保存', icon: 'success' }) }
          } else {
            this.refreshModuleSetup(); wx.showToast({ title: '坐标已保存', icon: 'success' })
          }
        } catch (err) {
          if (this.coordinateCurrent(proof)) wx.showToast({ title: err.message || '坐标保存失败', icon: 'none' })
        } finally {
          if (this.coordinateCurrent(proof)) { this._coordinatePick = null; this.setData({ coordSaving: false }) }
        }
      },
      fail: (err) => {
        if (!this.coordinateCurrent(proof)) return
        this._coordinatePick = null; this.setData({ coordSaving: false })
        const msg = (err && err.errMsg) || ''
        if (msg.indexOf('auth') >= 0 || msg.indexOf('deny') >= 0) {
          wx.showToast({ title: '需要位置权限，请在设置中开启', icon: 'none' })
        }
      }
    })
  },

  /** 清除坐标：清除后范围校验跳过（不会拒单），所以文案要说清后果 */
  onClearCoordinates() {
    if (this.data.coordSaving || this.data.saving) return
    const session = stationSession(), version = this._coordinateVersion = (this._coordinateVersion || 0) + 1
    this._coordinatePick = null
    const current = () => !this._stationUnloaded && sameStationSession(session) && version === this._coordinateVersion
      && Number(this.data.origin && this.data.origin.id) === session.stationId
    if (!current()) return
    let used = false
    wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',  confirmText: '确认清除',
      title: '清除水站坐标',
      content: '清除后系统无法判断订单是否超出配送范围，会跳过范围校验（不会拒单）。确认清除？',
      success: async (res) => {
        if (!res.confirm || !current() || used) return
        used = true
        this.setData({ coordSaving: true })
        try {
          await updateStationCoordinates(null, null)
          if (!current()) return
          this.setData({ 'coord.lat': null, 'coord.lng': null }); this.refreshModuleSetup()
          wx.showToast({ title: '已清除', icon: 'success' })
        } catch (err) {
          if (current()) wx.showToast({ title: err.message || '清除失败', icon: 'none' })
        } finally { if (current()) this.setData({ coordSaving: false }) }
      }
    })
  }
})
