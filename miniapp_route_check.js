#!/usr/bin/env node
/**
 * miniapp_route_check.js —— 配送端小程序「身份路由决策」离线回归检查
 *
 * 为什么需要它
 * ------------
 * `miniapp-delivery/app.js` 的 `_targetRoute` / `routeByRole` / `refreshIdentityAndRoute`
 * 决定了「登录后把人送到哪一页」。这段逻辑一旦出错，表现是**用户卡在注册流程里出不来**
 * （2026-09-17 报过的故障），而且只能靠真机反复试才发现 —— 微信开发者工具跑不了单测。
 *
 * 本脚本把 app.js 的全局依赖（wx / App / getApp）与网络层（utils/request）桩化后，
 * 直接在 Node 里加载真实的 app.js，用断言覆盖各条分支。**不需要开发者工具、不需要后端。**
 *
 * 用法
 * ----
 *     node miniapp_route_check.js
 *
 * 退出码：0 = 全部通过；1 = 有断言失败。
 *
 * 覆盖的关键场景
 * --------------
 *   · 各身份/绑定状态 → 目标页（未选身份 / 站长无站 / 站长有站 / 配送员未绑定 /
 *     审批中 / 解绑审批中 / 已绑定 / BOUND 但缺 stationId）
 *   · **本地状态陈旧、服务器已放行**（"在别处注册完却出不去"）→ 必须跳首页
 *   · 本地与服务器一致且仍需注册 → **不得跳转**（否则 reLaunch 当前页会自我循环）
 *   · 同步接口失败 → 保持旧状态，不把人卡住
 *   · UNSELECTED 虚拟会话 → 跳过同步（后端查不到 staff，查了只会得到"员工不存在"）
 *
 * 维护提示
 * --------
 * 新增身份/绑定状态时，先改 app.js 的 `_targetRoute`，再在此处补一条断言 ——
 * 两处必须同步演进，否则路由会悄悄漂移。
 */
'use strict'

const path = require('path')

const APP_DIR = path.join(__dirname, 'miniapp-delivery')

// ---------------- 桩化全局 ----------------
const storage = {}
const log = []

global.wx = {
  getStorageSync: k => storage[k],
  setStorageSync: (k, v) => { storage[k] = v },
  removeStorageSync: k => { delete storage[k] },
  redirectTo: o => log.push('redirectTo ' + o.url),
  reLaunch: o => log.push('reLaunch ' + o.url),
  switchTab: o => log.push('switchTab ' + o.url),
  navigateTo: o => log.push('navigateTo ' + o.url),
  request: () => {},
  showModal: () => {},
  showToast: () => {},
}

let APP = null
global.App = o => { APP = o }
global.getApp = () => APP

// 用桩替换真实网络层：预置 require 缓存，app.js 里的 require('./utils/request') 会命中它。
// 这样 app.js 的代码**零改动**即可在 Node 下运行。
let SERVER = null
const reqPath = require.resolve(path.join(APP_DIR, 'utils', 'request.js'))
require.cache[reqPath] = {
  id: reqPath,
  filename: reqPath,
  loaded: true,
  exports: {
    get: async () => ({ data: SERVER }),
    post: async () => ({ data: SERVER }),
    put: async () => ({ data: SERVER }),
    del: async () => ({ data: SERVER }),
    request: async () => ({ data: SERVER }),
  },
}

require(path.join(APP_DIR, 'app.js'))

const app = APP
if (!app || typeof app._targetRoute !== 'function') {
  console.error('[FATAL] 未能从 app.js 取到 _targetRoute —— app.js 结构变了，请同步更新本脚本的桩。')
  process.exit(1)
}

let pass = 0
let fail = 0
function eq(actual, expected, msg) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  if (ok) pass++
  else fail++
  console.log((ok ? '  [OK] ' : '  [XX] ') + msg
    + '  实际=' + JSON.stringify(actual) + ' 期望=' + JSON.stringify(expected))
}

const U = 'UNSELECTED'
const M = 'STATION_MANAGER'
const D = 'DELIVERY'

const APPLY = 'pages/station-mgmt/apply-bind/index'
const CREATE = 'pages/station-mgmt/create-station/index'
const WAIT = 'pages/bind-wait/index'
const HOME = '/pages/home/index'

async function main() {
  console.log('== 1. _targetRoute 纯决策 ==')
  eq(app._targetRoute({ role: U }), '/pages/role-select/index', '未选身份 -> 身份选择')
  eq(app._targetRoute({ role: D, needSelectRole: true }), '/pages/role-select/index', 'needSelectRole 残留 -> 身份选择')
  eq(app._targetRoute({ role: M, stationId: null }), '/' + CREATE, '站长无水站 -> 建站')
  eq(app._targetRoute({ role: M, stationId: 7 }), null, '站长有水站 -> 业务首页')
  eq(app._targetRoute({ role: D, bindStatus: 'UNBOUND', stationId: null }), '/' + APPLY, '配送员未绑定 -> 申请绑定')
  eq(app._targetRoute({ role: D, bindStatus: 'PENDING', stationId: null }), '/' + WAIT, '审批中 -> 等待页')
  eq(app._targetRoute({ role: D, bindStatus: 'PENDING_UNBIND', stationId: 3 }), '/' + WAIT, '解绑审批中 -> 等待页')
  eq(app._targetRoute({ role: D, bindStatus: 'BOUND', stationId: 3 }), null, '配送员已绑定 -> 业务首页')
  eq(app._targetRoute({ role: D, bindStatus: 'BOUND', stationId: null }), '/' + APPLY, 'BOUND 但无水站 -> 仍需绑定')

  console.log('\n== 2. 本地 UNBOUND、服务器已 BOUND（在别处被审批通过） ==')
  log.length = 0
  app.globalData.accessToken = 'tok'
  app.globalData.userInfo = app._normalizeUserInfo({ role: D, bindStatus: 'UNBOUND', stationId: null, staffId: 9 })
  storage['aq_delivery_accessToken'] = 'tok'
  storage['aq_delivery_userInfo'] = app.globalData.userInfo
  SERVER = { role: D, bindingStatus: 'BOUND', stationId: 42 }
  const left = await app.refreshIdentityAndRoute(APPLY)
  eq(left, true, '应判定为已跳走')
  eq(log, ['reLaunch ' + HOME], '应放行到首页（"在别处注册了却出不去"的修复点）')
  eq(app.globalData.userInfo.stationId, 42, '本地 stationId 应被服务器值覆盖')

  console.log('\n== 3. 本地与服务器都是 UNBOUND（确实还没注册） ==')
  log.length = 0
  app.globalData.userInfo = app._normalizeUserInfo({ role: D, bindStatus: 'UNBOUND', stationId: null, staffId: 9 })
  SERVER = { role: D, bindingStatus: 'UNBOUND', stationId: null }
  const left2 = await app.refreshIdentityAndRoute(APPLY)
  eq(left2, false, '应留在本页')
  eq(log, [], '不得发生跳转（否则 reLaunch 到当前页会自我循环）')

  console.log('\n== 4. 服务器返回审批中 -> 应转到等待页 ==')
  log.length = 0
  SERVER = { role: D, bindingStatus: 'PENDING', stationId: null }
  const left3 = await app.refreshIdentityAndRoute(APPLY)
  eq(left3, true, '应跳走')
  eq(log, ['reLaunch ' + '/' + WAIT], '应转到等待页')

  console.log('\n== 5. 同步接口失败 -> 不得把人卡住 ==')
  app.globalData.userInfo = app._normalizeUserInfo({ role: D, bindStatus: 'UNBOUND', stationId: null, staffId: 9 })
  SERVER = null
  eq(await app.syncIdentity(), null, '同步失败返回 null')
  eq(app.globalData.userInfo.bindStatus, 'UNBOUND', '失败时保持旧状态，不被清空')

  console.log('\n== 6. 站长：别处已建站 -> 放行 ==')
  log.length = 0
  app.globalData.userInfo = app._normalizeUserInfo({ role: M, stationId: null, staffId: 5 })
  SERVER = { role: M, bindingStatus: 'UNBOUND', stationId: 11 }
  const left4 = await app.refreshIdentityAndRoute(CREATE)
  eq(left4, true, '应跳走')
  eq(log, ['reLaunch ' + HOME], '应放行到首页')

  console.log('\n== 7. UNSELECTED 虚拟会话不发同步请求 ==')
  app.globalData.userInfo = app._normalizeUserInfo({ role: U, needSelectRole: true })
  eq(await app.syncIdentity(), null, 'UNSELECTED 跳过同步（避免"员工不存在"噪声）')

  console.log('\n== 8. isIdentityEffective —— 决定能否"返回重选身份" ==')
  // 与后端 LoginController#selectRole 同一判据：stationId 非空 = 已生效（= 禁止改选）
  app.globalData.userInfo = app._normalizeUserInfo({ role: D, bindStatus: 'UNBOUND', stationId: null })
  eq(app.isIdentityEffective(), false, '配送员未绑定 → 未生效，可返回重选')
  app.globalData.userInfo = app._normalizeUserInfo({ role: D, bindStatus: 'PENDING', stationId: null })
  eq(app.isIdentityEffective(), false, '配送员审批中（未获批）→ 未生效，可返回重选')
  app.globalData.userInfo = app._normalizeUserInfo({ role: D, bindStatus: 'BOUND', stationId: 3 })
  eq(app.isIdentityEffective(), true, '配送员已绑定 → 已生效，禁止改选')
  app.globalData.userInfo = app._normalizeUserInfo({ role: M, stationId: null })
  eq(app.isIdentityEffective(), false, '站长未建站 → 未生效，可返回重选')
  app.globalData.userInfo = app._normalizeUserInfo({ role: M, stationId: 9 })
  eq(app.isIdentityEffective(), true, '站长已建站 → 已生效，禁止改选')
  app.globalData.userInfo = app._normalizeUserInfo({ role: U, needSelectRole: true })
  eq(app.isIdentityEffective(), false, '未选身份 → 未生效')

  console.log('\n结果：通过 ' + pass + '，失败 ' + fail)
  process.exit(fail ? 1 : 0)
}

main()
