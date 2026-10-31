/** 离散整数草稿输入；只发 change，不保存、不计算金额，也不静默截断非法值。 */
Component({
  properties: {
    value: { type: String, optionalTypes: [Number], value: '' },
    min: { type: Number, value: 1 },
    max: { type: Number, value: 5000 },
    step: { type: Number, value: 1 },
    disabled: { type: Boolean, value: false },
    label: { type: String, value: '数量' },
    placeholder: { type: String, value: '' }
  },
  data: { inputValue: '', minusDisabled: true, plusDisabled: false, inputError: '' },
  observers: {
    'value, min, max, step, disabled': function () { this.syncInput(this.data.value) }
  },
  methods: {
    syncInput(value) {
      const raw = value == null ? '' : String(value)
      const { min, max, step, disabled } = this.data
      const number = Number(raw)
      const valid = /^\d+$/.test(raw) && Number.isSafeInteger(number) && number >= min && number <= max
      this.setData({ inputValue: raw,
        minusDisabled: disabled || !valid || number - step < min,
        plusDisabled: disabled || (raw !== '' && (!valid || number + step > max)),
        inputError: raw !== '' && !valid ? '请输入 ' + min + '–' + max + ' 的整数' : '' })
    },
    onInput(e) {
      if (this.data.disabled) return
      const value = String(e.detail.value)
      this.syncInput(value)
      this.triggerEvent('change', { value })
    },
    onStep(e) {
      if (this.data.disabled) return
      const add = e.currentTarget.dataset.direction === 'add'
      if (add ? this.data.plusDisabled : this.data.minusDisabled) return
      const value = String(this.data.inputValue === '' ? this.data.min
        : Number(this.data.inputValue) + (add ? this.data.step : -this.data.step))
      this.syncInput(value)
      this.triggerEvent('change', { value })
    },
    onConfirm() { if (typeof wx.hideKeyboard === 'function') wx.hideKeyboard() }
  }
})
