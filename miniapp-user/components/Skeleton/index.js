Component({
  properties: {
    loading: {
      type: Boolean,
      value: true
    },
    showHeader: {
      type: Boolean,
      value: false
    },
    showThumb: {
      type: Boolean,
      value: false
    },
    rows: {
      type: Array,
      value: [
        { width: '100%', showShort: true },
        { width: '90%', showShort: true },
        { width: '85%', showShort: true },
        { width: '100%', showShort: true }
      ]
    }
  }
})
