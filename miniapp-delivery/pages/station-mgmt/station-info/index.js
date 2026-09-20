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

Page({
  data: {
    loading: true,
    // 原值（含后端下发但本页不编辑的 status），保存时原样带回
    origin: null,
    form: { name: '', phone: '', address: '' },
    saving: false,
    // lat 为 null = 还没选点，此时配送范围校验会跳过（不是拒单）
    coord: { lat: null, lng: null },
    coordSaving: false
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const res = await getMyStation()
      const d = res.data || {}
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
      // 静默失败会让页面显示成空白表单，站长一保存就把站名冲没了 —— 必须出声。
      console.error('[StationInfo] 读取水站资料失败:', err)
      wx.showToast({ title: (err && err.message) || '读取水站资料失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
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
   */
  onPickCoordinates() {
    wx.chooseLocation({
      success: async (res) => {
        this.setData({ coordSaving: true })
        try {
          await updateStationCoordinates(res.latitude, res.longitude)
          this.setData({ 'coord.lat': res.latitude, 'coord.lng': res.longitude })
          wx.showToast({ title: '坐标已保存', icon: 'success' })
        } catch (err) {
          wx.showToast({ title: err.message || '坐标保存失败', icon: 'none' })
        } finally {
          this.setData({ coordSaving: false })
        }
      },
      fail: (err) => {
        const msg = (err && err.errMsg) || ''
        if (msg.indexOf('auth') >= 0 || msg.indexOf('deny') >= 0) {
          wx.showToast({ title: '需要位置权限，请在设置中开启', icon: 'none' })
        }
      }
    })
  },

  /** 清除坐标：清除后范围校验跳过（不会拒单），所以文案要说清后果 */
  onClearCoordinates() {
    wx.showModal({
      title: '清除水站坐标',
      content: '清除后系统无法判断订单是否超出配送范围，会跳过范围校验（不会拒单）。确认清除？',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await updateStationCoordinates(null, null)
          this.setData({ 'coord.lat': null, 'coord.lng': null })
          wx.showToast({ title: '已清除', icon: 'success' })
        } catch (err) {
          wx.showToast({ title: err.message || '清除失败', icon: 'none' })
        }
      }
    })
  }
})
