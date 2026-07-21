const { getTicketAccounts, getTicketRecords } = require('../../api/ticket')

Page({
  data: {
    loading: true,
    accounts: [],
    records: [],
    currentTab: 0,
    tabs: [
      { id: 0, name: '水票余额' },
      { id: 1, name: '消费记录' }
    ]
  },

  onShow() {
    this.loadData()
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const [accountsRes, recordsRes] = await Promise.all([
        getTicketAccounts(),
        getTicketRecords()
      ])
      if (accountsRes.data) {
        this.setData({ accounts: accountsRes.data })
      }
      if (recordsRes.data) {
        this.setData({ records: recordsRes.data })
      }
    } catch (error) {
      console.error('Load ticket data error:', error)
    } finally {
      this.setData({ loading: false })
    }
  },

  onTabChange(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ currentTab: id })
  }
})
