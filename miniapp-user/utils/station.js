// 顾客端「当前服务水站」的统一解析入口。
//
// 优先级（与首页 checkStation 保持一致）：
//   1. stationStorage          —— 用户在首页显式选择的水站
//   2. /api/orders/my-station  —— 上次下单的水站；无订单时回退「水站给该客户的绑定配置」
//
// 为什么需要这个工具：顾客端多处都需要「当前水站」，此前各写各的，其中两处误用了
// **员工专属**接口 /api/stations/mine（@RequireRole STATION_MANAGER/DELIVERY）：
//   · pages/template/index.js —— 顾客恒 403 → 模板存不进、列表恒空
//    · pages/order/create.js   —— 顾客恒 403 →「水站不一致」提醒沦为死分支
// 两处都是「请求失败但被静默吞掉」，表面看不出错，最难排查。统一到这里，避免再犯。
//
// 注意：本方法只读，**不写** stationStorage —— 页面内的临时切换不应污染首页选择。

const { stationStorage } = require('./storage')
const { getMyLatestStation } = require('../api/order')

/**
 * 解析当前生效的服务水站 id。
 * @returns {Promise<number|null>} 解析不到时返回 null（调用方负责给出提示或引导）
 */
async function resolveStationId() {
  const localId = stationStorage.getId()
  if (localId) return localId
  try {
    const res = await getMyLatestStation()
    if (res && res.data && res.data.stationId) return res.data.stationId
  } catch (e) {
    // [2026-09-20 真机联调] 这里原来是**空 catch**：水站身份静默丢失且不留任何线索 ——
    // 调用方拿到 null 后只会提示「请先选择水站」，与"接口 500 / 断网"长得一模一样，
    // 排查时分不清是"真没选站"还是"请求挂了"。
    // 保持返回 null 的契约不变（调用方的兜底提示仍然有效），但必须留下可查的记录。
    console.warn('[station] 解析当前水站失败，已回退为 null:', e && (e.errMsg || e.message))
  }
  return null
}

module.exports = { resolveStationId }
