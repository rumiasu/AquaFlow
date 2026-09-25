const { getAllBarrelRecords, updateBarrelRecordStatus } = require('../../../api/station-mgmt')

Page({
  data: { list: [] },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  async loadData() {
    try {
      const res = await getAllBarrelRecords()
      // owedBuckets 后端给的是 over（可为负）。负数=顾客多还的桶寄存在水站，是合法状态，
      // 不能当成 0 显示——那是顾客打电话来问"我的桶呢"的直接来源。
      // wxml 里不能做取负运算，所以在 JS 里预先拆成两个非负字段。
      const list = (res.data || []).map(item => {
        const over = item.owedBuckets || 0
        return Object.assign({}, item, {
          owedQty: over > 0 ? over : 0,
          storageQty: over < 0 ? -over : 0
        })
      })
      this.setData({ list })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  onApprove(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '确认收到空桶',
      content: '确定客户已退回空桶？',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await updateBarrelRecordStatus(id, 2)
            wx.hideLoading()
            wx.showToast({ title: '已确认', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  /**
   * 已确认收到空桶(status=2) → 退押金(status=3)。
   * 这一步以前根本没做按钮：站长点完"确认收到"就只能干瞪眼，
   * 申请永远停在"已确认"，顾客押金退不出来。
   */
  onRefund(e) {
    const id = e.currentTarget.dataset.id
    const amount = e.currentTarget.dataset.amount || 0
    wx.showModal({
      title: '确认退押金',
      content: `确定已收到空桶并退还押金 ¥${amount}？退款将按该客户的押金条批次核销，不可撤销。`,
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await updateBarrelRecordStatus(id, 3)
            wx.hideLoading()
            wx.showToast({ title: '已退押金', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none', duration: 3000 })
          }
        }
      }
    })
  },

  onReject(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '驳回退桶申请',
      content: '确定驳回该申请？',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await updateBarrelRecordStatus(id, 4)
            wx.hideLoading()
            wx.showToast({ title: '已驳回', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  }
})
