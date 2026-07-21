Component({
  properties: {
    price: {
      type: Number,
      value: 0
    },
    symbol: {
      type: String,
      value: '¥'
    }
  },
  data: {
    integer: '0',
    decimal: '00'
  },
  observers: {
    'price': function(val) {
      const num = Number(val) || 0
      const parts = num.toFixed(2).split('.')
      this.setData({
        integer: parts[0],
        decimal: parts[1]
      })
    }
  }
})
