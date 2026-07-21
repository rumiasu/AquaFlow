// 格式化工具

// 格式化价格
const formatPrice = (price) => {
  if (price === null || price === undefined) return '0.00'
  return Number(price).toFixed(2)
}

// 格式化电话号码
const formatPhone = (phone) => {
  if (!phone) return ''
  return phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2')
}

// 格式化日期
const formatDate = (date, fmt = 'YYYY-MM-DD HH:mm') => {
  if (!date) return ''
  const d = new Date(date)
  const map = {
    'YYYY': d.getFullYear(),
    'MM': String(d.getMonth() + 1).padStart(2, '0'),
    'DD': String(d.getDate()).padStart(2, '0'),
    'HH': String(d.getHours()).padStart(2, '0'),
    'mm': String(d.getMinutes()).padStart(2, '0'),
    'ss': String(d.getSeconds()).padStart(2, '0')
  }
  let result = fmt
  for (const key in map) {
    result = result.replace(key, map[key])
  }
  return result
}

// 格式化订单状态
const formatOrderStatus = (status) => {
  const statusMap = {
    1: '待配送',
    2: '配送中',
    3: '已完成',
    4: '待配送'
  }
  return statusMap[status] || '未知状态'
}

// 格式化订单状态颜色
const formatOrderStatusColor = (status) => {
  const colorMap = {
    1: 'warning',
    2: 'primary',
    3: 'success',
    4: 'warning'
  }
  return colorMap[status] || 'default'
}

module.exports = {
  formatPrice,
  formatPhone,
  formatDate,
  formatOrderStatus,
  formatOrderStatusColor
}
