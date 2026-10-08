/**
 * 顾客端「我的 · 押金水票与桶」的加载态与文案（2026-09-27 走查 C05 / C06 / C07）。
 *
 * 跑法：node tests/js/customer-assets-state.test.js
 *
 * 真实执行 `miniapp-user/pages/mine/index.js` 的处理函数，断言的是**页面数据与渲染分支**，
 * 不是"某个字符串在不在文件里"：
 *   · C05 —— 资产加载失败**不得**留下看起来像真的 0；换水站不得把上一站的数字当成本站资产；
 *   · C06 —— 「水桶权益」这个标签绑定的是 heldBuckets（持有 = 权益 + 配送中），
 *            配送中的桶既不能抵扣也不能退，标签不能叫"权益"；
 *   · C07 —— 桶页标题不得再出现"长期桶资产（按商品聚合）"这类建模语言。
 *
 * ⚠️ 反向验证（把修复改回去，本套件应变红）：
 *   · 把 `assetsView` 里 `loaded.balance ? … : ASSET_UNLOADED` 改回无条件 `fmtMoney(v.balance)`
 *     ⇒ 「接口失败时不许写 ¥0」那条红；
 *   · 把 `onShow` 里 `prevStationId !== stationId` 那段清空逻辑删掉
 *     ⇒ 「换水站先清空」那条红；
 *   · 把 wxml 的 `我的桶` 改回 `水桶权益`、把桶页标题改回 `长期桶资产（按商品聚合）`
 *     ⇒ 两条静态断言红。
 */

const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')

const MARK_ASCII = 'AQUAFLOW_SUITE_OK'

let passed = 0
const failures = []
async function test(name, fn) {
  try {
    await fn()
    passed++
    console.log('  ✓ ' + name)
  } catch (e) {
    failures.push({ name, error: e })
    console.log('  ✗ ' + name + '\n      ' + (e && e.message))
  }
}

/** 读一份小程序文件（静态文案断言用；路径相对仓库根） */
function readFile(rel) {
  return fs.readFileSync(path.join(ROOT, rel), 'utf8')
}

/**
 * 去掉 WXML 注释后再做文案断言。
 *
 * ⚠️ 判据必须落在**渲染出来的文本**上：本仓的注释里大段写着"原来叫什么、为什么改"
 * （例如 `水桶权益` / `长期桶资产` 都会以"旧文案"的身份出现在注释里）。
 * 直接对整份文件 indexOf，会把"注释里提过旧词"误判成"页面还在渲染旧词"。
 */
function renderedWxml(rel) {
  return readFile(rel).replace(/<!--[\s\S]*?-->/g, '')
}

/**
 * 加载「我的」页：三个实际接口各自可控。
 * @param {{summary:Function, tickets:Function, company:Function}} api
 *        每个键是 `() => Promise`（桶/水票那两个收 stationId）；抛异常 = 该接口失败。
 * @param {{stationId?:number, stationName?:string}} station 本地缓存的服务水站
 */
function loadMine(api, station) {
  const st = station || {}
  const wx = createWx()
  if (st.stationId) {
    wx.__storage.set('selectedStation', { id: st.stationId, name: st.stationName || ('水站' + st.stationId) })
  }
  const stubs = {
    'api/barrel': { getBarrelSummary: (id) => api.summary(id), getBarrelRecords: async () => ({ code: 0, data: [] }) },
    'api/ticket': { getTicketAccounts: (id) => api.tickets(id) },
    'api/company': { getCompanyInfo: () => api.company() }
  }
  const app = createApp()
  const page = loadPage('miniapp-user/pages/mine/index.js', { stubs, wx, app })
  // 未登录时 onShow 不会去拉资产，这里统一置成已登录
  page.__app.globalData.isLogin = true
  page.__app.globalData.userInfo = { nickName: '测试客户' }
  return { page, wx }
}

console.log('顾客「我的 · 押金水票与桶」加载态与文案（真实执行页面处理函数）')

;(async () => {
  const doneWatchdog = armWatchdog()

  // ---------------------------------------------------------------- C05：失败不许冒充 0
  await test('C05 桶/押金接口失败：不写 ¥0 / 0 个，写「暂未加载」并标出未加载项', async () => {
    const { page, wx } = loadMine({
      summary: async () => { throw new Error('网络连接失败，请检查网络后重试') },
      tickets: async () => ({ code: 0, data: [{ remainQuantity: 5 }] }),
      company: async () => ({ code: 0, data: null })
    }, { stationId: 11 })
    page.setData({ isLogin: true })
    await page.loadAssets()

    assert.strictEqual(Object.hasOwn(page.data, 'balance'), false, '接口不存在的钱包状态已经退役')
    assert.strictEqual(page.data.depositLoaded, false, '押金没拿到 = 未加载')
    assert.strictEqual(page.data.depositText, '暂未加载', '**不许**把没拿到的押金写成 0/¥0')
    assert.strictEqual(page.data.barrelLoaded, false)
    assert.strictEqual(page.data.barrelText, '暂未加载')
    assert.strictEqual(page.data.ticketText, '5', '成功的那几项照常显示')
    assert.strictEqual(page.data.unloadedCount, 2, '押金与我的桶来自同一接口，一起缺席')
    // 出声：toast 必须说清"不是 0"，而不是原文那句"显示的可能是 0"
    const toast = wx.__calls.toast.map(t => t.title || '').join(' | ')
    assert.ok(toast.indexOf('不是 0') > -1, '失败提示必须点明"不是 0"：' + toast)
    assert.ok(toast.indexOf('可能是 0') === -1, '原文案会让人把未加载读成 0：' + toast)
  })

  await test('C05 全部接口失败：三项全是「暂未加载」，没有一个数字', async () => {
    const { page } = loadMine({
      summary: async () => { throw new Error('超时') },
      tickets: async () => { throw new Error('超时') },
      company: async () => { throw new Error('超时') }
    }, { stationId: 11 })
    page.setData({ isLogin: true })
    await page.loadAssets()
    assert.strictEqual(page.data.unloadedCount, 3)
    ;['depositText', 'ticketText', 'barrelText'].forEach(k => {
      assert.strictEqual(page.data[k], '暂未加载', k + ' 不该有任何数字')
    })
  })

  await test('C05 真的是 0 时照实写 0（"没有资产"与"没加载出来"必须长得不一样）', async () => {
    const { page } = loadMine({
      summary: async () => ({ code: 0, data: { depositBalance: 0, heldBuckets: 0 } }),
      tickets: async () => ({ code: 0, data: [] }),
      company: async () => ({ code: 0, data: null })
    }, { stationId: 11 })
    page.setData({ isLogin: true })
    await page.loadAssets()
    assert.strictEqual(page.data.unloadedCount, 0, '全部成功就不该有未加载提示')
    assert.strictEqual(page.data.depositText, '0', '真的是 0 就写 0')
    assert.strictEqual(page.data.barrelText, '0')
    assert.strictEqual(page.data.ticketText, '0')
  })

  await test('C05 换水站：先把上一站的数字清空，不能拿 A 站的押金当 B 站的资产', async () => {
    let stationServed = null
    const { page } = loadMine({
      summary: async (id) => {
        stationServed = id
        return { code: 0, data: { depositBalance: id === 11 ? 450 : 30, heldBuckets: 2 } }
      },
      tickets: async () => ({ code: 0, data: [] }),
      company: async () => ({ code: 0, data: null })
    }, { stationId: 11 })
    page.setData({ isLogin: true })
    await page.onShow()
    await new Promise(r => setTimeout(r, 10))
    assert.strictEqual(stationServed, 11)
    assert.strictEqual(page.data.depositText, '450', 'A 站的押金先显示出来')

    // 切到 B 站：onShow 必须以**新站**的 id 重新取数，且在取到之前不显示 A 站的 450
    page.__wx.__storage.set('selectedStation', { id: 22, name: '水站22' })
    const p = page.onShow()
    assert.strictEqual(page.data.depositText, '暂未加载',
      '换站后、新数据回来之前，屏幕上不许还挂着上一站的数字')
    await p
    await new Promise(r => setTimeout(r, 10))
    assert.strictEqual(stationServed, 22, '必须按新站 id 取数')
    assert.strictEqual(page.data.depositText, '30', 'B 站的数字落地')
  })

  await test('C05 未加载的那几格有「重新加载」处理函数（点了必须真的重新拉）', async () => {
    let calls = 0
    const { page } = loadMine({
      summary: async () => { calls++; throw new Error('超时') },
      tickets: async () => { calls++; throw new Error('超时') },
      company: async () => { calls++; return { code: 0, data: null } }
    }, { stationId: 11 })
    page.setData({ isLogin: true })
    assert.strictEqual(typeof page.onRetryAssets, 'function',
      'wxml 绑了 bindtap="onRetryAssets"，js 里不存在 = 点击静默无反应（AGENTS §6）')
    await page.onRetryAssets()
    await new Promise(r => setTimeout(r, 10))
    assert.ok(calls >= 3, '重试必须真的再发请求，实际 ' + calls)
  })

  // ---------------------------------------------------------------- C06 / C07：文案口径
  await test('C06 桶标签是「我的桶」（持有 ≠ 权益：配送中的桶不能退也不能抵扣）', async () => {
    const wxml = renderedWxml('miniapp-user/pages/mine/index.wxml')
    assert.ok(wxml.indexOf('我的桶') > -1, '标签应为「我的桶」')
    assert.strictEqual(wxml.indexOf('水桶权益'), -1,
      '「水桶权益」绑的是 heldBuckets（持有 = 权益 + 配送中），配送中的桶不可退/不可抵扣，不能叫权益')
  })

  await test('C06 数据源仍是后端 heldBuckets（口径没被顺手改掉）', async () => {
    const js = readFile('miniapp-user/pages/mine/index.js')
    assert.ok(js.indexOf('summaryRes.data.heldBuckets') > -1,
      '数值口径必须保持后端 heldBuckets；要改口径先回去读 docs/architecture/02-领域模型.md §4')
    assert.strictEqual(js.indexOf('rightBuckets'), -1, '不许把"权益"（rightBuckets）拿来顶替持有量')
  })

  await test('C07 桶页标题不得是建模语言「长期桶资产（按商品聚合）」', async () => {
    const wxml = renderedWxml('miniapp-user/pages/barrel/index.wxml')
    assert.strictEqual(wxml.indexOf('长期桶资产'), -1, '「长期桶资产」是建模语言，顾客界面不许出现')
    assert.strictEqual(wxml.indexOf('按商品聚合'), -1, '「按商品聚合」是实现方式，不该渲染给顾客')
    assert.ok(wxml.indexOf('我的水桶') > -1, '改成顾客能懂的「我的水桶」')
  })

  console.log('')
  doneWatchdog()
  if (failures.length) {
    console.log('失败 ' + failures.length + ' 项 / 通过 ' + passed + ' 项')
    process.exitCode = 1
  } else {
    console.log('全部通过：' + passed + ' 项（流程测试，真实执行页面处理函数）')
    console.log(MARK_ASCII + ' ' + passed)
  }
})()
