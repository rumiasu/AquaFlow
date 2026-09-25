const { getBarrelRecords } = require('../../api/delivery')

Page({
  data: {
    records: [],
    totalDeliveries: 0,
    totalReturn: 0,
    totalDiscrepancy: 0
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
    try {
      const res = await getBarrelRecords()
      const records = (res.data || []).map(r => {
        const disc = r.barrelDiscrepancy || 0
        return {
          ...r,
          timeText: this.formatDate(r.updateTime),
          discAbsText: Math.abs(disc) + '',
          discLabel: disc > 0 ? '欠桶' : '多还',
          discTagClass: disc > 0 ? 'owe' : 'extra'
        }
      })
      const totalReturn = records.reduce((s, r) => s + (r.returnBucketQty || 0), 0)
      const totalDiscrepancy = records.reduce((s, r) => s + (r.barrelDiscrepancy || 0), 0)
      this.setData({
        records,
        totalDeliveries: records.length,
        totalReturn,
        totalDiscrepancy
      })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  formatDate(d) {
    if (!d) return ''
    const t = new Date(d)
    const pad = n => n.toString().padStart(2, '0')
    return `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())} ${pad(t.getHours())}:${pad(t.getMinutes())}`
  }
})
