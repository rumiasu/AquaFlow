package com.example.aquaflow.service;

import com.example.aquaflow.constant.WeChatApp;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.util.MaskUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 微信登录：code2Session（用 wx.login 的 code 换 openid + session_key）。
 *
 * <p>[2026-09-15] 本项目有**两个小程序**：客户端 miniapp-user 与员工端 miniapp-delivery，
 * 它们的 appid 不同。code 只能用**签发它的那一端**的 appid+secret 去换，
 * 换错端微信只回一句 {@code invalid appid}，现象是"某一端登录永远失败"。
 * 因此本方法强制调用方传 {@link WeChatApp}，不要再用单 appid 的写法。</p>
 *
 * <p>配置键：{@code wechat.miniapp.appid/secret} = 客户端；
 * {@code wechat.miniapp.staff-appid/staff-secret} = 员工端（环境变量 WX_STAFF_APP_ID / WX_STAFF_APP_SECRET）。
 * 员工端缺失**不阻塞启动**（见 {@code RequiredConfigChecker}），只在使用到员工端登录时抛明确业务错。</p>
 */
@Slf4j
@Service
public class WeChatLoginService {

    @Value("${wechat.miniapp.appid:}")
    private String customerAppid;

    @Value("${wechat.miniapp.secret:}")
    private String customerSecret;

    @Value("${wechat.miniapp.staff-appid:}")
    private String staffAppid;

    @Value("${wechat.miniapp.staff-secret:}")
    private String staffSecret;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 员工端未配置时的统一提示：说清"改哪里"，否则只看到一句无信息量的登录失败 */
    private static final String STAFF_NOT_CONFIGURED =
            "员工端微信登录未配置：请配置 wechat.miniapp.staff-appid / staff-secret"
                    + "（环境变量 WX_STAFF_APP_ID / WX_STAFF_APP_SECRET）";

    /** 员工换码的错误分类；仅调用微信，不写数据库，不返回可继续办理的失败状态。 */
    public Map<String, Object> staffCode2Session(String code) {
        try {
            return code2Session(WeChatApp.STAFF, code);
        } catch (BusinessException e) {
            // 保留原业务异常和面向用户的消息，避免重复添加“微信登录失败”前缀。
            throw e;
        } catch (RuntimeException e) {
            // 保留员工端原有的业务错误分类；认证事务收到异常后必须整笔回滚。
            throw new BusinessException("微信登录失败: " + e.getMessage());
        }
    }

    /**
     * code2Session：用 wx.login 拿到的 code 换 openid + session_key
     * 文档: https://developers.weixin.qq.com/miniprogram/dev/api-backend/open-api/login/auth.code2Session.html
     *
     * @param app  签发该 code 的小程序端（客户端 / 员工端），必须与小程序实际 appid 一致
     * @param code wx.login 返回的 code
     */
    public Map<String, Object> code2Session(WeChatApp app, String code) {
        String appid = appidOf(app);
        String secret = secretOf(app);

        String url = String.format(
                "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                appid, secret, code);

        String response;
        try {
            response = restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            // [F-08 2026-09-30] ⚠️ **这里绝不记录异常消息，也绝不记录 URL / js_code / secret**：
            // Spring 的 ResourceAccessException / RestClientException 的 message 里含**完整请求 URL**
            // （即 `secret=…&js_code=…` 原样在内），一旦带上它，就会顺着 GlobalExceptionHandler 的
            // `log.error(..., e)` 落进日志文件 = 微信凭据泄露。只留 app 与异常类型，够定位"网络/微信侧故障"。
            log.error("微信code2Session调用失败（URL 已省略，避免泄露 secret / js_code）: app={}, 异常类型={}",
                    app, e.getClass().getName());
            // 转成**可预期的业务错误**（code=1）：外部依赖/网络故障属于可预期故障，
            // 不该升级成 500 兜底 + 一条 SYSTEM 告警（同 AGENTS §8.21 的判据）。
            // ⚠️ 不 attach cause —— BusinessException 也会被 GlobalExceptionHandler 打堆栈，
            //    带上 cause 等于把含 URL 的原始异常又写回日志。
            throw new BusinessException("微信登录服务暂时不可用，请稍后重试");
        }
        // #56: 不打印完整响应（含 session_key / openid 等敏感信息），仅打印脱敏后的部分
        log.info("微信code2Session响应[{}]: {}", app, MaskUtil.maskCode2SessionResponse(response));

        Map<String, Object> result;
        try {
            result = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // 同样必须脱敏：解析失败的响应里 session_key / openid 是原样出现的，
            // 而这一支恰恰只在异常时触发，最容易被忽略而泄漏。
            log.error("解析微信响应失败: {}", MaskUtil.maskCode2SessionResponse(response), e);
            throw new BusinessException("微信登录响应解析失败");
        }

        if (result.containsKey("errcode")) {
            Object errcodeObj = result.get("errcode");
            int errcode = errcodeObj instanceof Number ? ((Number) errcodeObj).intValue() : Integer.parseInt(errcodeObj.toString());
            if (errcode != 0) {
                String errmsg = result.getOrDefault("errmsg", "未知错误").toString();
                // 带上端名：40013(invalid appid) 的根因几乎总是"端与 appid 配错"，不写端名根本无从判断。
                // 再带上**实际用的 appid**（appid 是公开信息，不是密钥）：这类故障现场最需要的一句话就是
                // "后端拿的是哪个 appid"，好和开发者工具详情页里显示的那个逐字对比（2026-09-24 加）。
                log.error("微信code2Session失败: app={}, appid={}, errcode={}, errmsg={}", app, appid, errcode, errmsg);
                if (errcode == 40029) {
                    probeOtherApp(app, code);
                }
                // ⚠️ **不要把 errmsg 抛给用户**（2026-09-24 改）：它是微信的内部话术，
                //    形如 "invalid code, rid: 6ab491dd-7e32e260-0eb5aef1" —— 顾客/站长看不懂，
                //    而且小程序那边是 `wx.showToast`，两行就截断，用户实际只看到半句。
                //    **errcode 与原文都在上面那行 log 里**，排障看日志；抛给用户的是"下一步能做什么"。
                throw new BusinessException(userMessageOf(errcode));
            }
        }

        if (!result.containsKey("openid")) {
            // 走到这里说明微信没回 errcode 却也没给 openid（协议异常），不是用户能处理的
            log.error("微信code2Session响应缺少openid且无errcode: {}", MaskUtil.maskCode2SessionResponse(response));
            throw new BusinessException("微信登录失败，请稍后再试");
        }

        return result;
    }

    /**
     * 40029（invalid code）的**定点诊断**（2026-09-24 加）。
     *
     * <p>微信既然认下了 appid+secret（否则回 40125 且**不带 rid**），却说不认识这个 code，
     * 那就只剩一个解释：**这个 code 是另一个 appid 签发的**。本地最常见的情形是开发者工具里
     * 打开的项目其实挂着另一端，或者项目还停留在改 appid **之前**的旧绑定 ——
     * ⚠️ 后者的关键是**点「编译」不会重新绑定，必须关掉项目重新打开**。</p>
     *
     * <p>所以这里拿**同一个 code** 去试另一端的 appid+secret：换到了就说明 code 属于那一端，
     * 日志直接给结论，省掉"到底哪一端配错了"的来回猜。</p>
     *
     * <p>⚠️ **只诊断、绝不改变行为**：换回来的 openid 一律丢弃，失败路径上也绝不据此放行登录；
     * 诊断自身出任何错都被吞掉（只留一行 warn），不能连累主流程。</p>
     */
    private void probeOtherApp(WeChatApp failed, String code) {
        WeChatApp other = failed == WeChatApp.CUSTOMER ? WeChatApp.STAFF : WeChatApp.CUSTOMER;
        try {
            String otherAppid = appidOf(other);
            String url = String.format(
                    "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                    otherAppid, secretOf(other), code);
            String response = restTemplate.getForObject(url, String.class);
            // ⚠️ 只看有没有 openid，**绝不打印响应体**（里面是 session_key）
            boolean matched = response != null && response.contains("\"openid\"");
            if (matched) {
                log.error("[登录诊断] 这个 code 用【{}】的 appid({}) 换到了 openid —— 说明它是**那一端**签发的。"
                        + "请检查开发者工具当前项目的 appid：点「编译」不会重新绑定，**要关掉项目重新打开**。",
                        other, otherAppid);
            } else {
                log.error("[登录诊断] 这个 code 用【{}】的 appid({}) 也换不到。说明它不是本仓库任何一端签发的："
                        + "请核对①工具「详情」里显示的 appid；②当前登录的微信号是不是**那个 appid** 的开发者/体验者"
                        + "（是员工端的开发者 ≠ 是顾客端的开发者，两个小程序各有一份成员名单）。", other, otherAppid);
            }
        } catch (Exception e) {
            // [F-08 2026-09-30] ⚠️ 这里**不能**打 e.getMessage()：这一支调的是另一端的 appid+secret，
            // RestTemplate 的网络异常 message 里含完整 URL（即另一端的 secret=）。
            // 只对**本仓自己抛的** BusinessException 记文案（例如"员工端未配置"，是给人看的、不含 URL），
            // 其余一律只记异常类型。
            if (e instanceof BusinessException) {
                log.warn("[登录诊断] 试另一端 appid 时未完成（忽略，不影响登录结果）: {}", e.getMessage());
            } else {
                log.warn("[登录诊断] 试另一端 appid 时出错（忽略，不影响登录结果）: app={}, 异常类型={}",
                        other, e.getClass().getName());
            }
        }
    }

    /**
     * 把微信的 errcode 翻成**用户能照着做**的一句话（2026-09-24 加）。
     *
     * <p>⚠️ 判据：给出的必须是**下一步动作**，不能只是"失败了"。原始 errmsg 与 errcode
     * 一律只进日志（见 {@link #code2Session}）。</p>
     *
     * <p>⚠️ {@code 40029 invalid code} 在本地开发时几乎只有一个原因：**开发者工具当前登录的
     * 微信号，不是这个小程序的开发者 / 体验者** —— 于是 {@code wx.login} 发出的 code
     * 根本不是该 appid 签发的（微信能认 appid+secret，所以回的是 invalid code 而不是
     * invalid appsecret，响应里还带 rid）。这一条**没法从提示语里指导用户解决**（顾客也做不到），
     * 所以提示语只说"重开再试"，真正的原因看日志。</p>
     */
    private static String userMessageOf(int errcode) {
        switch (errcode) {
            case 40029:
                return "微信登录失败：凭证已失效，请重开小程序再试";
            case 40013:
            case 40125:
                // 配置问题（appid/secret 与端配错），用户改不了 → 如实告诉他找谁，别让他反复重试
                return "微信登录配置有误，请联系管理员";
            case -1:
                return "微信服务繁忙，请稍后再试";
            case 45011:
                return "操作太频繁，请稍后再试";
            default:
                return "微信登录失败，请稍后再试";
        }
    }

    // [2026-09-30 修 F-35] 本文件原先自己维护 maskSensitive / maskOpenid 两个**副本**
    // （靠注释要求与 AuthTokenService 同步）。现已抽到 {@code util/MaskUtil} 的**唯一实现**，
    // 调用点一律写 {@code MaskUtil.maskCode2SessionResponse(...)}。
    // ⚠️ 不要再在本文件里重造这两个方法 —— F-35（本文件明文 openid）正是"两份口径分叉"的产物。

    private String appidOf(WeChatApp app) {
        if (app == WeChatApp.STAFF) {
            if (isBlank(staffAppid)) {
                throw new BusinessException(STAFF_NOT_CONFIGURED);
            }
            return staffAppid;
        }
        if (isBlank(customerAppid)) {
            // RequiredConfigChecker 已把这条挡在启动前；留着是为了"有人绕过校验"时也给出可读错误
            throw new BusinessException("客户端微信登录未配置：缺少 WX_APP_ID");
        }
        return customerAppid;
    }

    private String secretOf(WeChatApp app) {
        if (app == WeChatApp.STAFF) {
            if (isBlank(staffSecret)) {
                throw new BusinessException(STAFF_NOT_CONFIGURED);
            }
            return staffSecret;
        }
        if (isBlank(customerSecret)) {
            throw new BusinessException("客户端微信登录未配置：缺少 WX_APP_SECRET");
        }
        return customerSecret;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
