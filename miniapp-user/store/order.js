// 订单状态管理
const orderStore = {
  state: {
    currentOrder: null,
    orderList: [],
    filters: {
      status: 0, // 0: 全部, 1: 待配送, 2: 配送中, 3: 已送达, 4: 已完成, 5: 已取消
      page: 1,
      pageSize: 10
    }
  },

  // 设置订单列表
  setOrderList(list) {
    this.state.orderList = list
  },

  // 添加订单
  addOrder(order) {
    this.state.orderList.unshift(order)
  },

  // 更新订单状态
  updateOrderStatus(orderId, status) {
    const order = this.state.orderList.find(o => o.id === orderId)
    if (order) {
      order.status = status
    }
  },

  // 设置当前订单
  setCurrentOrder(order) {
    this.state.currentOrder = order
  },

  // 清除当前订单
  clearCurrentOrder() {
    this.state.currentOrder = null
  },

  // 设置筛选条件
  setFilters(filters) {
    this.state.filters = { ...this.state.filters, ...filters }
  },

  // 重置筛选条件
  resetFilters() {
    this.state.filters = {
      status: 0,
      page: 1,
      pageSize: 10
    }
  },

  // 获取筛选后的订单列表
  getFilteredOrders() {
    let filtered = [...this.state.orderList]

    if (this.state.filters.status > 0) {
      filtered = filtered.filter(
        order => order.status === this.state.filters.status
      )
    }

    return filtered
  }
}

module.exports = orderStore
