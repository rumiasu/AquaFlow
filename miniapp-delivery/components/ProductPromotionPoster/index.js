const { WIDTH, HEIGHT, posterData, drawPoster } = require('./art')

// Keep the source packaging and its label intact. Only the surrounding offer is typeset.
function loadPhoto(canvas, src) {
  return new Promise((resolve, reject) => {
    const img = canvas.createImage()
    img.onload = () => resolve(img)
    img.onerror = reject
    if (/^https:\/\//i.test(src)) {
      wx.getImageInfo({ src, success: result => { img.src = result.path }, fail: reject })
    } else {
      img.src = src
    }
  })
}

Component({
  properties: {
    product: Object,
    stationName: String,
    saved: { type: Boolean, value: false }
  },
  data: { ready: false, busy: false, canRetry: false, description: '商品海报', message: '' },
  observers: { 'product, stationName, saved'() { if (this._mounted) this.refreshPoster() } },
  lifetimes: {
    ready() { this._mounted = true; this.refreshPoster() },
    detached() { this._mounted = false; this._generation++; this._canvas = null }
  },
  pageLifetimes: {
    hide() { this._hidden = true; this._generation++; this._canvas = null },
    show() { if (this._hidden && this._mounted) { this._hidden = false; this.refreshPoster() } }
  },
  methods: {
    closePoster() { this.triggerEvent('close') },
    current(generation) { return this._mounted && !this._hidden && generation === this._generation },
    refreshPoster() {
      const generation = this._generation = (this._generation || 0) + 1
      this._canvas = null
      const product = this.properties.product || {}
      const data = posterData(product, this.properties.stationName)
      this.setData({ ready: false, busy: false, canRetry: false, message: '正在生成海报…',
        description: [data.brand, data.name, data.spec, data.price && data.price + '元'].filter(Boolean).join(' ') })
      this.createSelectorQuery().select('#poster').fields({ node: true, size: true }).exec(async rows => {
        if (!this.current(generation)) return
        const canvas = rows && rows[0] && rows[0].node
        try {
          if (!canvas) throw Error('canvas')
          canvas.width = WIDTH; canvas.height = HEIGHT
          const ctx = canvas.getContext('2d')
          if (!ctx) throw Error('context')
          drawPoster(ctx, data, null)
          const image = data.image ? await loadPhoto(canvas, data.image) : null
          if (!this.current(generation)) return
          drawPoster(ctx, data, image)
          this._canvas = canvas
          const message = !this.properties.saved ? '保存商品和售价后可以导出。'
            : !data.price ? '先设置本站售价，再生成海报。' : !image ? '可在商品资料中补一张包装图。' : ''
          this.setData({ ready: !!data.price, message })
        } catch (_) {
          if (this.current(generation)) this.setData({ message: '海报未生成，请重新加载。', canRetry: true })
        }
      })
    },
    exportPoster() {
      if (!this._canvas || !this.properties.saved || !this.data.ready || this.data.busy) return
      const generation = this._generation
      this.setData({ busy: true })
      const fail = () => {
        if (this.current(generation)) this.setData({ busy: false, message: '大图未打开，请重试。' })
      }
      wx.canvasToTempFilePath({
        canvas: this._canvas, destWidth: WIDTH, destHeight: HEIGHT, fileType: 'png',
        success: result => {
          if (!this.current(generation)) return
          this.setData({ busy: false })
          wx.previewImage({ current: result.tempFilePath, urls: [result.tempFilePath], fail })
        },
        fail
      }, this)
    }
  }
})
