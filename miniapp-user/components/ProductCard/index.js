Component({
  properties: {
    product: {
      type: Object,
      value: {}
    },
    showButton: {
      type: Boolean,
      value: true
    }
  },

  data: {},

  methods: {
    onProductTap() {
      const { product } = this.data
      wx.navigateTo({
        url: `/pages/product/detail?id=${product.id}`
      })
    }
  }
})
