/** 只记录展示失败；不删除原 URL、凭证或商品，也不触发重复上传。 */
module.exports = Behavior({
  data: { displayImageErrors: {} },
  methods: {
    onDisplayImageError(e) {
      const src = e.currentTarget.dataset.src
      if (!src) return
      this.setData({ displayImageErrors: { ...this.data.displayImageErrors, [src]: true } })
    }
  }
})
