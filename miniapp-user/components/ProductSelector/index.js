/** Present parent-owned product facts; selection never quotes, loads, saves or changes quantity. */
Component({
  properties: {
    items: { type: Array, value: [] },
    selectedId: { type: String, optionalTypes: [Number], value: '' },
    disabled: { type: Boolean, value: false }
  },
  data: { viewItems: [] },
  observers: { 'items, selectedId, disabled': function () { this.syncItems() } },
  methods: {
    syncItems() {
      const text = value => value == null ? '' : String(value)
      const selected = text(this.data.selectedId)
      this.setData({ viewItems: (Array.isArray(this.data.items) ? this.data.items : []).map((item, index) => {
        const value = item || {}, id = text(value.id)
        const name = text(value.name) || '商品名称待确认', spec = text(value.spec)
        const priceText = text(value.priceText) || '价格待确认'
        return { index, id, name, spec, priceText, image: text(value.image), selected: selected !== '' && id === selected,
          disabled: this.data.disabled || !!value.disabled || id === '', ariaLabel: [name, spec, priceText].filter(Boolean).join('，') }
      }) })
    },
    onSelect(event) {
      const index = Number(event.currentTarget.dataset.index)
      if (!Number.isSafeInteger(index) || index < 0 || !this.data.viewItems[index] || this.data.viewItems[index].disabled) return
      const item = this.data.items[index]
      this.triggerEvent('change', { id: item.id, item })
    },
    onImageError(event) {
      const index = Number(event.currentTarget.dataset.index)
      if (Number.isSafeInteger(index) && index >= 0 && this.data.viewItems[index]) this.setData({ ['viewItems[' + index + '].image']: '' })
    }
  }
})
