// 表单验证工具

const validator = {
  // 手机号验证
  isPhone: (phone) => {
    return /^1[3-9]\d{9}$/.test(phone)
  },

  // 邮箱验证
  isEmail: (email) => {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
  },

  // 身份证验证
  isIdCard: (idCard) => {
    return /(^\d{15}$)|(^\d{18}$)|(^\d{17}(\d|X|x)$)/.test(idCard)
  },

  // 非空验证
  isNotEmpty: (value) => {
    if (value === null || value === undefined) return false
    if (typeof value === 'string') return value.trim().length > 0
    if (Array.isArray(value)) return value.length > 0
    return true
  },

  // 最小长度
  minLength: (value, min) => {
    if (!value) return false
    return value.length >= min
  },

  // 最大长度
  maxLength: (value, max) => {
    if (!value) return true
    return value.length <= max
  },

  // 数字验证
  isNumber: (value) => {
    return /^\d+(\.\d+)?$/.test(value)
  },

  // 正整数验证
  isPositiveInt: (value) => {
    return /^\d+$/.test(value) && parseInt(value) > 0
  }
}

// 验证规则
const rules = {
  phone: [
    { validator: validator.isNotEmpty, message: '请输入手机号' },
    { validator: validator.isPhone, message: '手机号格式不正确' }
  ],
  name: [
    { validator: validator.isNotEmpty, message: '请输入姓名' },
    { validator: (v) => validator.maxLength(v, 20), message: '姓名不能超过20个字符' }
  ],
  address: [
    { validator: validator.isNotEmpty, message: '请输入详细地址' },
    { validator: (v) => validator.maxLength(v, 100), message: '地址不能超过100个字符' }
  ],
  quantity: [
    { validator: validator.isNotEmpty, message: '请输入数量' },
    { validator: validator.isPositiveInt, message: '数量必须为正整数' }
  ]
}

// 验证单个字段
const validate = (value, fieldRules) => {
  for (const rule of fieldRules) {
    if (!rule.validator(value)) {
      return {
        valid: false,
        message: rule.message
      }
    }
  }
  return {
    valid: true,
    message: ''
  }
}

// 验证多个字段
const validateForm = (formData, formRules) => {
  const errors = {}
  for (const field in formRules) {
    const result = validate(formData[field], formRules[field])
    if (!result.valid) {
      errors[field] = result.message
    }
  }
  return {
    valid: Object.keys(errors).length === 0,
    errors
  }
}

module.exports = {
  validator,
  rules,
  validate,
  validateForm
}
