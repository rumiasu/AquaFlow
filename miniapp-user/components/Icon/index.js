/**
 * AquaFlow 图标组件
 * 线性 SVG 图标系统，统一替换 emoji
 * 用法：<icon name="home" size="48rpx" color="#2E4A68" />
 */
const ICONS = {
  // 水滴 Logo
  droplet: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2.69l5.66 5.66a8 8 0 1 1-11.31 0z"/></svg>',
  // 首页
  home: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M9 22V12h6v10"/></svg>',
  // 商城
  shop: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z"/><path d="M3 6h18"/><path d="M16 10a4 4 0 0 1-8 0"/></svg>',
  // 订单/列表
  order: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 6h13M8 13h13M8 20h13M3 6h.01M3 13h.01M3 20h.01"/></svg>',
  // 我的/用户
  user: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>',
  // 购物车
  cart: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"/></svg>',
  // 水票
  ticket: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 9a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 0 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2z"/></svg>',
  // 水桶
  barrel: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 8h14l-1.5 12a2 2 0 0 1-2 1.8H8.5a2 2 0 0 1-2-1.8z"/><path d="M7 8a5 5 0 0 1 10 0"/><path d="M5 13h14"/></svg>',
  // 地址/定位
  location: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>',
  // 公告/铃铛
  notice: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>',
  // 账单/收据
  bill: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1z"/><path d="M8 7h8M8 11h8M8 15h4"/></svg>',
  // 企业/楼宇
  building: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 21h18M5 21V7l8-4v18M19 21V11l-6-4"/><path d="M9 9v.01M9 12v.01M9 15v.01M9 18v.01"/></svg>',
  // 客服/消息
  chat: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
  // 搜索
  search: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>',
  // 右箭头
  arrow: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>',
  // 右箭头(长)
  arrowRight: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M12 5l7 7-7 7"/></svg>',
  // 钱包
  wallet: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12V7H5a2 2 0 0 1 0-4h14v4"/><path d="M3 5v14a2 2 0 0 0 2 2h16v-5"/><path d="M18 12a2 2 0 0 0 0 4h4v-4z"/></svg>',
  // 充值/加
  plus: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>',
  // 退出
  logout: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="M16 17l5-5-5-5M21 12H9"/></svg>',
  // 礼物/邀请
  gift: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 12v10H4V12M2 7h20v5H2zM12 22V7M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7zM12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z"/></svg>',
  // 电话
  phone: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.127.96.36 1.903.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.907.34 1.85.573 2.81.7A2 2 0 0 1 22 16.92z"/></svg>',
  // 编辑
  edit: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>',
  // 空状态
  empty: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15.5l-9 5.25L3 15.5"/><path d="M21 11.5l-9 5.25L3 11.5"/><path d="M12 3.25l9 4.75-9 4.75-9-4.75z"/></svg>',
  // 警告
  alert: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><path d="M12 9v4M12 17h.01"/></svg>',
  // 对勾
  check: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>',
  // 对勾圆
  checkCircle: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="M22 4L12 14.01l-3-3"/></svg>',
  // 闪电(快速)
  zap: '<svg viewBox="0 0 24 24" fill="none" stroke="__COLOR__" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg>'
}

Component({
  properties: {
    name: {
      type: String,
      value: ''
    },
    size: {
      type: String,
      value: '48rpx'
    },
    color: {
      type: String,
      value: '#2E4A68'
    }
  },
  data: {
    src: ''
  },
  observers: {
    'name, color': function (name, color) {
      const svg = ICONS[name] || ICONS.empty
      const colored = svg.replace(/__COLOR__/g, color)
      this.setData({
        src: 'data:image/svg+xml,' + encodeURIComponent(colored)
      })
    }
  },
  lifetimes: {
    attached() {
      const svg = ICONS[this.data.name] || ICONS.empty
      const colored = svg.replace(/__COLOR__/g, this.data.color)
      this.setData({
        src: 'data:image/svg+xml,' + encodeURIComponent(colored)
      })
    }
  }
})
