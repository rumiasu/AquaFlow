/**
 * 地址展示工具
 *
 * 背景：旧版小程序自动定位时，把「省+市+区+街道+POI」整串塞进了 detail 字段，
 * 导致 province/city/district 全为 NULL（见 migration_v21_address_detail_cleanup.py）。
 * 本模块做两件事：
 *   1) 兜底：即使数据未清洗，也能从 detail 里正确拆出省市区；
 *   2) 统一展示粒度，避免各页面各自拼接导致不一致。
 *
 * 安全原则：所有剥离都**锚定开头**按已知值精确匹配，绝不全局替换，
 * 因此不会误伤地址后面重复出现的区名（如"济阳区…济阳区济北小学"）。
 */

/**
 * 从一整串地址里拆出省/市/区/剩余详情。
 * 正则与 pages/address/edit.js 的 parseRegion() 保持一致，已覆盖：
 * 直辖市 / 省+市+区 / 省+市 / 市+区 / 仅市，均锚定 ^。
 * @param {string} addressStr
 * @returns {{province: string, city: string, district: string, detail: string}}
 */
function parseRegion(addressStr) {
  if (!addressStr) return { province: '', city: '', district: '', detail: '' }

  // 直辖市：优先按「区/县」切分，避免 (.*?) 非贪婪一路吃到句尾
  // 例：北京市朝阳区建国路88号 → 区=朝阳区, detail=建国路88号
  const m4a = addressStr.match(/^(北京市|天津市|上海市|重庆市)(.{2,10}?(?:区|县))(.*)/)
  if (m4a) {
    return { province: m4a[1], city: m4a[1], district: m4a[2], detail: m4a[3] }
  }

  // 直辖市兜底（无区县时）
  const m4 = addressStr.match(/^(北京市|天津市|上海市|重庆市)(.*?)(省|市|区|县|$)/)
  if (m4) {
    return {
      province: m4[1],
      city: m4[1],
      district: m4[2] || '',
      detail: addressStr.substring(m4[0].length)
    }
  }

  const m1 = addressStr.match(/^(.{2,8}省)(.{2,10}?市)(.{2,10}?(?:区|县))(.*)/)
  if (m1) return { province: m1[1], city: m1[2], district: m1[3], detail: m1[4] }

  const m2 = addressStr.match(/^(.{2,8}省)(.{2,10}?市)(.*)/)
  if (m2) return { province: m2[1], city: m2[2], district: '', detail: m2[3] }

  const m3 = addressStr.match(/^(.{2,10}?市)(.{2,10}?(?:区|县))(.*)/)
  if (m3) return { province: '', city: m3[1], district: m3[2], detail: m3[3] }

  const m5 = addressStr.match(/^(.{2,10}?市)(.*)/)
  if (m5) return { province: '', city: m5[1], district: '', detail: m5[2] }

  return { province: '', city: '', district: '', detail: addressStr }
}

/**
 * 若 detail 开头带有省/市/区，逐个剥离，返回剩余部分。
 * 按已知值精确匹配开头，不做全局替换 → 天然规避"区名重复"误删。
 * @returns {string}
 */
function stripRegionPrefix(detail, province, city, district) {
  let rest = detail || ''
  const parts = [province, city, district].filter(function (p) { return !!p })

  // 反复剥离，直到没有前缀可剥（防止省市区有嵌套/重复前缀）
  let changed = true
  while (changed) {
    changed = false
    for (let i = 0; i < parts.length; i++) {
      const p = parts[i]
      if (p && rest.indexOf(p) === 0 && rest.length > p.length) {
        rest = rest.substring(p.length)
        changed = true
      }
    }
  }
  return rest
}

/**
 * 首页等场景的简洁展示：「区 + 街道门牌」
 * 例：济阳区 · 济北街道正安路28号济阳区济北小学
 *
 * 优先用已入库的 district；若 district 为空（历史数据未清洗），
 * 则兜底从 detail 里拆出区再拼。
 * @param {{province:string, city:string, district:string, detail:string}} addr
 * @returns {string}
 */
function formatAddress(addr) {
  if (!addr) return ''

  const detail = addr.detail || ''
  if (!detail) return ''

  let district = addr.district || ''
  let rest = ''

  if (district) {
    rest = stripRegionPrefix(detail, addr.province, addr.city, addr.district)
  } else {
    // 未清洗的历史数据：兜底从 detail 拆
    const parsed = parseRegion(detail)
    district = parsed.district
    rest = parsed.detail
  }

  if (!rest) return district || detail
  return district ? district + ' · ' + rest : rest
}

/**
 * 完整地址展示：「省 + 市 + 区 + 街道门牌」（供需要完整地址的场景）
 * 同样对未清洗数据做兜底拆分，并避免省市区被重复拼接。
 * @param {{province:string, city:string, district:string, detail:string}} addr
 * @returns {string}
 */
function formatFullAddress(addr) {
  if (!addr) return ''

  let province = addr.province || ''
  let city = addr.city || ''
  let district = addr.district || ''
  let detail = addr.detail || ''

  if (!province && !city && !district) {
    const parsed = parseRegion(detail)
    province = parsed.province
    city = parsed.city
    district = parsed.district
    detail = parsed.detail
  } else {
    detail = stripRegionPrefix(detail, addr.province, addr.city, addr.district)
  }

  // 直辖市 province === city，避免拼成"北京市北京市"
  if (province && city && province === city) city = ''

  const region = [province, city, district].filter(Boolean).join('')
  return region ? region + ' ' + detail : detail
}

module.exports = {
  parseRegion: parseRegion,
  stripRegionPrefix: stripRegionPrefix,
  formatAddress: formatAddress,
  formatFullAddress: formatFullAddress
}
