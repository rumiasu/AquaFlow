package com.example.aquaflow.constant;

/**
 * 微信小程序「端」——客户端与员工端是**两个不同的小程序（appid 不同）**。
 *
 * <p>为什么需要它：{@code wx.login} 拿到的 code 只能用**签发它的那个 appid** 去换 openid，
 * 用错端会拿到微信的 {@code 40013 invalid appid}——该错误在日志里没有任何指向性，
 * 表现只是"某一端登录怎么都不通"。所以 code2Session 必须显式指明端，不许靠猜。</p>
 *
 * <p>openid 按 appid 隔离：同一个微信用户在两端拿到的是两个不同 openid，
 * 分别落在 {@code customer.openid} 与 {@code staff.openid}，天然互不干扰。</p>
 */
public enum WeChatApp {

    /** 客户端小程序（miniapp-user，顾客）。配置键：wechat.miniapp.appid / secret */
    CUSTOMER,

    /** 员工端小程序（miniapp-delivery，站长 + 配送员）。配置键：wechat.miniapp.staff-appid / staff-secret */
    STAFF
}
