// 2026-10-21：资料模板不携带经营价/押金/库存，也不直接写公共库或自动上架。
// 名称/水种来源见 docs/design/14；历史容量不作为当前商品默认规格。
const TEMPLATE_IMAGE = '/assets/product/barrel-neutral-template.webp'
const IMAGE_NOTICE = '商品示意图，包装以实物为准'
const WATER_PRODUCT_TEMPLATES = [
  { id: 'pulisi-purified-185', imagePresetKey: 'pulisi-purified-photo', waterType: '饮用纯净水', name: '普利思 饮用纯净水', alias: '普利思 纯净水', sourceUrl: 'https://www.pulisi.com/product/128.html' },
  { id: 'pulisi-spring-185', imagePresetKey: 'pulisi-spring-photo', waterType: '饮用天然泉水', name: '普利思 饮用天然泉水', alias: '普利思 天然泉水', sourceUrl: 'https://www.pulisi.com/product/130.html' },
  { id: 'pulisi-mineral-185', imagePresetKey: 'pulisi-mineral-photo', waterType: '饮用天然矿泉水', name: '普利思 饮用天然矿泉水', alias: '普利思 天然矿泉水', sourceUrl: 'https://www.pulisi.com/product/129.html' }
].map(item => Object.freeze({ ...item, brand: '普利思', spec: '', category: 1,
  unit: '桶', imageUrl: TEMPLATE_IMAGE, imageObjectName: '', imageNotice: IMAGE_NOTICE,
  description: '资料图片仅作识别参考，实际包装和规格以实物标签为准；售价和桶押金由本站确认。' }))
Object.freeze(WATER_PRODUCT_TEMPLATES)

/** 图片地址仅供预览，另保留后端下发的对象键；绝不从签名URL反推或保存URL。 */
function templatesWithImages(presets) {
  return WATER_PRODUCT_TEMPLATES.map(template => {
    const preset = (Array.isArray(presets) ? presets : []).find(item => item.key === template.imagePresetKey)
    if (!preset || typeof preset.objectName !== 'string' || !preset.objectName
        || preset.objectName.startsWith('/') || preset.objectName.includes('://')) return { ...template }
    const readablePhoto = typeof preset.path === 'string' && preset.path.startsWith('https://')
    return { ...template, imageObjectName: preset.objectName,
      imageUrl: readablePhoto ? preset.path : TEMPLATE_IMAGE,
      imageNotice: readablePhoto ? '品牌资料图，实际包装和规格以实物标签为准' : IMAGE_NOTICE }
  })
}

const compact = text => String(text || '').replace(/\s+/g, '').toLowerCase()
/** 只复用同品牌、同水种、同容量的可用公共商品；不将18.9L或其它水种猜成同款。 */
function matchingCatalogProducts(template, list) {
  // 未确认容量的参考模板不能自动认定任何公共SKU为同款。
  if (!compact(template.spec)) return []
  const names = []
  for (const name of [template.name, template.alias]) names.push(...[name, name + template.spec, name + template.spec + '桶装水'].map(compact))
  return (list || []).filter(item => !item.ownerStationId && item.status === 1 && item.category === template.category
    && compact(item.brand) === compact(template.brand) && compact(item.spec) === compact(template.spec)
    && names.includes(compact(item.name)))
}

/** 模板的价格/押金/实物数量必须由站长输入；空值不能悄悄当0入库。 */
function templateBusinessError(form) {
  if (!String(form.spec || '').trim()) return '请按实物标签填写规格'
  const amount = value => typeof value === 'string' && /^\d+(?:\.\d{1,2})?$/.test(value.trim()) && Number(value) > 0
  if (!amount(form.price)) return '请设置本站售价（大于0，最多两位小数）'
  if (form.category === 1 && !amount(form.deposit)) return '请确认本站桶押金（大于0，最多两位小数）'
  if (!/^\d+$/.test(String(form.quantity)) || !Number.isSafeInteger(Number(form.quantity))) return '请填写实际库存；暂未入库可填0'
  return ''
}

module.exports = { WATER_PRODUCT_TEMPLATES, TEMPLATE_IMAGE, IMAGE_NOTICE, templatesWithImages, matchingCatalogProducts, templateBusinessError }
