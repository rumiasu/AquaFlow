/**
 * 地址解析工具（员工端）
 *
 * 与 miniapp-user/utils/address.js 的 parseRegion() **正则完全同一套**：
 * 客户端「收货地址」与员工端「水站地址」必须按同一规则拆省市区，否则同一串
 * 定位结果在两端会拆出不同结果，站长看到的站址与客户看到的收货地址对不上。
 *
 * ⚠️ 两个小程序是**独立工程**（appid 不同、无法互相 require），所以这份实现是
 * 有意重复的第二份；**任何一侧改正则，另一侧必须同步改**（正本注释在客户端那份）。
 *
 * 安全原则：所有匹配都**锚定开头**，不做全局替换，因此不会误伤地址后半段
 * 重复出现的区名（如"济阳区…济阳区济北小学"）。
 */

/**
 * 从一整串地址里拆出省/市/区/剩余详情。
 * 覆盖：直辖市 / 省+市+区 / 省+市 / 市+区 / 仅市，均锚定 ^。
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
 * 「楼层 / 电梯」展示文案 —— 给配送员看：这一单要不要上楼。
 *
 * P0-2 给 address 加了 floor / has_elevator，但此前只有计价在用（向客户收楼层费、
 * 给配送员补楼层补贴），**真正要爬楼的人看不到**，所以两端都补上这一行。
 *
 * ⚠️ hasElevator 是**三态**：null = 客户没确认过 / 0 = 无电梯 / 1 = 有电梯。
 * **只有确认过"无电梯"才说无电梯** —— 把 null 说成无电梯既误导配送员，
 * 也与后端收费口径相反（拿不准时后端不收楼层费）。
 * ⚠️ 取的是**当前地址**的值、不是下单快照（配送员要知道客户现在在哪层）。
 * ⚠️ 什么都不确定时返回空串，调用方据此**整行不显示** —— 不放假数据。
 */
function buildFloorText(order) {
  if (!order) return ''
  const parts = []
  const floor = order.addressFloor
  if (floor !== null && floor !== undefined && floor !== '') {
    parts.push('楼层 ' + floor)
  }
  const lift = order.addressHasElevator
  if (lift === 0 || lift === '0') {
    parts.push('无电梯')
  } else if (lift === 1 || lift === '1') {
    parts.push('有电梯')
  } else if (parts.length) {
    // 知道楼层但不知道电梯：如实说"未知"，别替客户回答
    parts.push('电梯未知')
  }
  return parts.join(' · ')
}

module.exports = {
  parseRegion: parseRegion,
  buildFloorText: buildFloorText
}
