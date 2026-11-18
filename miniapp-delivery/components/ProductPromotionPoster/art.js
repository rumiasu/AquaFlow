// The review gallery and the WeChat canvas draw from the same artwork.
const WIDTH = 720
const HEIGHT = 900
const sans = '"PingFang SC","Microsoft YaHei",sans-serif'
const serif = '"Songti SC","STSong","SimSun",serif'
const palettes = {
  '普利思': ['#224F76', '#CBE8F3', 'spring'],
  '怡宝': ['#11634F', '#CCEBDB', 'spring'],
  '景田': ['#4A447D', '#E0DAF2', 'strata'],
  '崂山': ['#30594D', '#D8E9D6', 'strata'],
  '农夫山泉': ['#913731', '#F4DCD2', 'strata'],
  '娃哈哈': ['#92364B', '#F2D9DC', 'rings'],
  '冰露': ['#294F79', '#D0E5F1', 'rings']
}
const clean = value => String(value == null ? '' : value).trim()

function priceText(value) {
  const text = clean(value)
  if (!/^\d{1,10}(?:\.\d{1,2})?$/.test(text) || Number(text) <= 0) return ''
  return text.replace(/^0+(?=\d)/, '').replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')
}

function posterData(product, stationName, demo = false) {
  const name = clean(product.name)
  const brand = clean(product.brand)
  const spec = clean(product.spec)
  const image = clean(product.imageUrl)
  const group = clean(product.group)
  const theme = Object.prototype.hasOwnProperty.call(palettes, brand) ? palettes[brand] : ['#2B5269', '#D7E8E8', 'spring']
  let ink = theme[0], tint = theme[1], pattern = theme[2]
  if (brand === '普利思' && /矿泉/.test(name)) { ink = '#514666'; tint = '#E6DFF0'; pattern = 'strata' }
  if (brand === '普利思' && /纯净/.test(name)) { ink = '#225C69'; tint = '#CDE8E8'; pattern = 'rings' }
  const unit = product.unit || (/组合装/.test(name) ? '组' : group === '箱装' || /[×x*]\s*\d+|箱装/i.test(spec) ? '箱'
    : group === '一次性桶' || group === '循环桶' || Number(product.category) === 1 ? '桶'
    : group === '单瓶' || Number(product.category) === 2 ? '瓶' : Number(product.category) === 3 ? '台' : '件')
  return { brand, name: name.startsWith(brand) ? name.slice(brand.length).trim() || name : name,
    spec, image, unit: clean(unit), station: clean(stationName), demo, ink, tint, pattern,
    price: priceText(product.effectivePrice), active: product.promotionActive === true }
}

function font(ctx, weight, size, family = sans) {
  ctx.font = weight + ' ' + size + 'px ' + family
}

function fit(ctx, value, width, start, min, weight, family) {
  let size = start
  font(ctx, weight, size, family)
  while (size > min && ctx.measureText(value).width > width) font(ctx, weight, --size, family)
  return size
}

function ellipsis(ctx, value, width) {
  if (ctx.measureText(value).width <= width) return value
  const chars = Array.from(value)
  while (chars.length && ctx.measureText(chars.join('') + '…').width > width) chars.pop()
  return chars.join('') + '…'
}

function title(ctx, value, x, y, maxWidth) {
  const size = fit(ctx, value, maxWidth, 55, 39, '600', serif)
  const chars = Array.from(value)
  let end = chars.length
  while (end > 1 && ctx.measureText(chars.slice(0, end).join('')).width > maxWidth) end--
  if (end < chars.length && chars.length - end <= end) {
    const suffix = ['矿泉水', '纯净水', '山泉水', '饮用水', '大瓶水', '桶装水'].find(word => value.endsWith(word))
    const split = suffix ? chars.length - Array.from(suffix).length : Math.ceil(chars.length / 2)
    if (split > 0 && split <= end && ctx.measureText(chars.slice(split).join('')).width <= maxWidth) end = split
  }
  const first = chars.splice(0, end).join('')
  ctx.fillText(first, x, y)
  if (chars.length) ctx.fillText(ellipsis(ctx, chars.join(''), maxWidth), x, y + size * 1.32)
}

function line(ctx, x, y, endX, endY) {
  ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(endX, endY); ctx.stroke()
}

// Water lines occupy their own clipped strip, clear of the price and pack label.
function waterLines(ctx, data) {
  ctx.save()
  ctx.beginPath(); ctx.rect(490, 609, 230, 197); ctx.clip()
  ctx.strokeStyle = data.tint
  ctx.globalAlpha = 0.46
  ctx.lineWidth = 1.6
  for (let i = 0; i < 10; i++) {
    const shift = i * 12
    ctx.beginPath()
    if (data.pattern === 'rings') {
      ctx.arc(723, 826, 57 + shift, 0, Math.PI * 2)
    } else if (data.pattern === 'strata') {
      ctx.moveTo(466, 824 + shift)
      ctx.bezierCurveTo(515, 816 + shift, 577, 681 + shift, 607, 672 + shift)
      ctx.bezierCurveTo(645, 660 + shift, 676, 746 + shift, 740, 650 + shift)
    } else {
      ctx.moveTo(476, 758 + shift)
      ctx.bezierCurveTo(531, 812 + shift, 564, 754 + shift, 618, 730 + shift)
      ctx.bezierCurveTo(651, 715 + shift, 692, 724 + shift, 742, 664 + shift)
    }
    ctx.stroke()
  }
  ctx.restore()
}

function drawPoster(ctx, data, image) {
  ctx.save()
  ctx.fillStyle = '#FFFFFF'; ctx.fillRect(0, 0, WIDTH, HEIGHT)
  ctx.fillStyle = data.ink; ctx.fillRect(0, 0, WIDTH, 12)
  ctx.textBaseline = 'alphabetic'
  ctx.textAlign = 'left'
  // A compact water mark and the actual seller share the masthead.
  ctx.lineWidth = 3; ctx.strokeStyle = data.ink
  for (let i = 0; i < 3; i++) {
    ctx.beginPath(); ctx.moveTo(43, 50 + i * 8); ctx.quadraticCurveTo(50, 45 + i * 8, 58, 50 + i * 8)
    ctx.quadraticCurveTo(66, 55 + i * 8, 74, 50 + i * 8); ctx.stroke()
  }
  font(ctx, '500', 23)
  ctx.fillText(ellipsis(ctx, data.station || '本站商品', 420), 92, 67)
  ctx.textAlign = 'right'; font(ctx, '500', 20)
  ctx.fillText(data.demo ? '设计样张' : data.active ? '本站促销' : '本站零售', 672, 66)
  ctx.textAlign = 'left'; ctx.strokeStyle = '#D5DFDF'; ctx.lineWidth = 1
  line(ctx, 46, 96, 674, 96)
  if (image && image.width > 0 && image.height > 0) {
    const scale = Math.min(389 / image.width, 429 / image.height)
    const w = image.width * scale, h = image.height * scale
    ctx.drawImage(image, 484 - w / 2, 357 - h / 2, w, h)
  }
  ctx.fillStyle = data.ink; font(ctx, '600', 28)
  if (data.brand) ctx.fillText(ellipsis(ctx, data.brand, 245), 47, 203)
  title(ctx, data.name || '本站商品', 44, 284, image ? 254 : 614)
  if (data.spec) {
    ctx.fillStyle = data.tint; ctx.fillRect(47, 437, 40, 5)
    ctx.fillStyle = data.ink
    fit(ctx, data.spec, 240, 40, 22, '500')
    ctx.fillText(ellipsis(ctx, data.spec, 240), 45, 497)
  } else {
    font(ctx, '400', 23)
    ctx.fillText('规格见包装', 47, 497)
  }
  if (image && data.image === '/assets/product/barrel-neutral-template.webp') {
    font(ctx, '400', 21); ctx.textAlign = 'center'
    ctx.fillText('包装示意', 484, 575); ctx.textAlign = 'left'
  }
  ctx.fillStyle = data.ink; ctx.fillRect(0, 603, WIDTH, 203)
  waterLines(ctx, data)
  ctx.fillStyle = '#FFFFFF'; font(ctx, '500', 23)
  ctx.fillText(data.demo ? '促销示例价' : data.active ? '本站促销价' : '本站零售价', 47, 655)
  if (data.price) {
    font(ctx, '500', 42); ctx.fillText('¥', 45, 760)
    fit(ctx, data.price, 355, 107, 39, '600', 'Arial,sans-serif')
    ctx.fillText(data.price, 78, 763)
    const priceEnd = 78 + ctx.measureText(data.price).width
    font(ctx, '500', 26)
    ctx.fillText('/ ' + ellipsis(ctx, data.unit, 62), priceEnd + 14, 759)
  } else {
    font(ctx, '500', 48); ctx.fillText('售价待填写', 45, 755)
  }
  ctx.fillStyle = '#F4F5F0'; ctx.fillRect(0, 806, WIDTH, 94)
  ctx.fillStyle = '#364746'; font(ctx, '400', 22)
  ctx.fillText(data.demo ? '示例金额仅用于预览排版' : '商品单价，配送等费用以订单为准', 46, 863)
  ctx.restore()
}
const posterArt = { WIDTH, HEIGHT, posterData, drawPoster, priceText }
if (typeof module !== 'undefined' && module.exports) module.exports = posterArt
else globalThis.AquaFlowPoster = posterArt
