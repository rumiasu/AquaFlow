package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.AlertType;
import com.example.aquaflow.entity.AlertLog;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.mapper.AlertLogMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.service.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分级告警实现。设计要点（都是踩过的坑换来的）：
 *
 * <ol>
 *   <li><b>落库必须独立事务</b>（{@code REQUIRES_NEW}）：告警最常见的触发场景就是"业务要回滚"
 *       （补偿执行失败、未预期异常）。若与业务同一个事务，业务一回滚，告警记录也跟着没了 ——
 *       于是「系统出事了」这件事永远查不到。</li>
 *   <li><b>落库失败不能连累业务</b>：写告警是旁路。写不进去就只留日志（{@code log.error}），
 *       绝不把异常抛回调用方。</li>
 *   <li><b>外部渠道可缺省</b>：系统告警走可配置 webhook（企业微信/钉钉机器人一类），
 *       没配就只落库；站长侧的外部渠道（微信订阅消息）尚未接入，同样只落库。
 *       投递状态落在 {@code notify_status}，一眼能看出"是没渠道还是推失败"。</li>
 * </ol>
 */
@Service
@Slf4j
public class AlertServiceImpl implements AlertService {

    @Autowired
    private AlertLogMapper alertLogMapper;

    @Autowired
    private StaffMapper staffMapper;

    /** 渠道返回体里的业务错误码：企业微信/钉钉用 {@code errcode}，Server酱用 {@code code}。 */
    private static final java.util.regex.Pattern ERROR_CODE_PATTERN =
            java.util.regex.Pattern.compile("\"(?:errcode|errCode|code)\"\\s*:\\s*(-?\\d+)");

    /** 系统告警外部渠道（可空）。配了才推，推失败只记 FAILED，不影响业务。 */
    @Value("${alert.system-webhook:}")
    private String systemWebhook;

    /**
     * 外部渠道的**报文格式**。各服务要求的报文并不一样，**不能一套 JSON 打天下**：
     *
     * <ul>
     *   <li>{@code WECOM} / {@code DINGTALK}（默认）—— 企业微信群机器人 / 钉钉自定义机器人，
     *       两者同为 {@code {"msgtype":"text","text":{"content":"..."}}}。</li>
     *   <li>{@code SERVERCHAN} —— Server酱（{@code https://sctapi.ftqq.com/{SendKey}.send}），
     *       form-urlencoded 的 {@code title}/{@code desp}。消息落到**微信的「放糖服务号」**，
     *       是**不需要加群**就能到达微信的通道之一（微信扫码登录一次取 SendKey，免费额度 5 条/天）。</li>
     *   <li>{@code RAW} —— 原样发 JSON {@code {"title","source","content","level"}}，
     *       供自建接收端或其它中介（如能转微信的第三方）使用。</li>
     * </ul>
     *
     * 由配置决定、**不做自动嗅探**：猜错只会静默失败（对方回业务错误码），不如显式配。
     */
    @Value("${alert.webhook-format:WECOM}")
    private String webhookFormat;

    @Override
    public void systemFault(String source, String title, String content, String relatedType, Long relatedId) {
        AlertLog row = base(AlertType.SYSTEM, "ERROR", source, title, content, relatedType, relatedId);
        row.setStationId(null);   // 系统故障不属于任何水站（也是"不给站长看"的判据）
        row.setStaffId(null);
        log.error("[ALERT][SYSTEM→系统管理员] source={} title={} content={}", source, title, content);
        persist(row);
        pushSystemWebhook(row);
    }

    @Override
    public void stationFault(Long stationId, String level, String source, String title, String content,
                             String relatedType, Long relatedId) {
        if (stationId == null) {
            // 没有水站就投不出去；这属于调用方的口径错误，按系统故障上报（别静默丢掉）
            systemFault(source, "运营告警缺少水站，无法投递：" + title, content, relatedType, relatedId);
            return;
        }
        AlertLog row = base(AlertType.OPERATION, level == null ? "WARN" : level, source, title, content,
                relatedType, relatedId);
        row.setStationId(stationId);
        row.setStaffId(firstManagerId(stationId));
        log.warn("[ALERT][OPERATION→站长 station={}] source={} title={} content={}",
                stationId, source, title, content);
        persist(row);
        // 站长侧外部渠道（微信订阅消息）尚未接入：投递状态留在 LOGGED，语义是"已落库、站点可查"。
    }

    private AlertLog base(String alertType, String level, String source, String title, String content,
                          String relatedType, Long relatedId) {
        AlertLog row = new AlertLog();
        row.setAlertType(alertType);
        row.setLevel(level);
        row.setSource(source);
        row.setTitle(title);
        row.setContent(content);
        row.setRelatedType(relatedType);
        row.setRelatedId(relatedId);
        row.setNotifyStatus("LOGGED");
        row.setCreateTime(LocalDateTime.now());
        return row;
    }

    /** 取该站第一个站长作为收件人（没有站长也照样落库，只是 staff_id 为空）。 */
    private Long firstManagerId(Long stationId) {
        try {
            List<Staff> managers = staffMapper.listByStationIdAndRole(stationId, "STATION_MANAGER");
            return (managers == null || managers.isEmpty()) ? null : managers.get(0).getId();
        } catch (Exception e) {
            log.warn("[ALERT] 查询站长失败，告警仍会落库: stationId={}, err={}", stationId, e.getMessage());
            return null;
        }
    }

    /**
     * 独立事务落库：业务回滚也要留下告警；落库失败只记日志，不抛回业务。
     *
     * <p>注：本方法由 {@link #persistSafely} 通过自注入代理调用才有事务语义，
     * 见下面的 {@code self} 字段。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistInNewTransaction(AlertLog row) {
        alertLogMapper.insert(row);
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    /**
     * 落库入口：走代理调用 {@link #persistInNewTransaction}，并把任何异常挡在这里。
     *
     * <p>为什么要拿代理：同类内部直接调 {@code this.persistInNewTransaction(...)} 不走 Spring AOP，
     * {@code REQUIRES_NEW} 直接失效（会跟着业务一起回滚）—— 这正是"看起来写了、其实白写"的经典坑。</p>
     */
    private void persist(AlertLog row) {
        try {
            applicationContext.getBean(AlertServiceImpl.class).persistInNewTransaction(row);
        } catch (Exception e) {
            log.error("[ALERT] 告警落库失败（不影响业务）: type={}, title={}, err={}",
                    row.getAlertType(), row.getTitle(), e.getMessage(), e);
        }
    }

    /**
     * 系统告警推外部渠道；未配置或失败都不影响业务，只更新 notify_status。
     *
     * <p>[2026-09-17] 修正两处，否则"把 URL 指过去就能用"并不成立：</p>
     * <ul>
     *   <li><b>报文要随渠道而定</b>：原先写死企业微信/钉钉的 {@code msgtype/text} 结构，
     *       导致 Server酱、自建接收端等格式不同的渠道**根本收不到**。</li>
     *   <li><b>必须判业务错误码</b>：企业微信 / 钉钉 / Server酱 失败时都返回
     *       <b>HTTP 200 + body 里的错误码</b>（{@code errcode=93000} / {@code code=400}）。
     *       只判 HTTP 状态会把"被渠道拒绝"记成 {@code PUSHED} —— 又是一次"以为发出去了其实没有"。</li>
     * </ul>
     */
    private void pushSystemWebhook(AlertLog row) {
        if (systemWebhook == null || systemWebhook.isBlank()) {
            return;   // 未配置外部渠道：只落库（notify_status 保持 LOGGED）
        }
        try {
            String text = "【系统故障】" + row.getTitle()
                    + "\nsource: " + str(row.getSource()) + "\n" + str(row.getContent());
            HttpRequest req = HttpRequest.newBuilder(URI.create(systemWebhook))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", webhookContentType())
                    .POST(HttpRequest.BodyPublishers.ofString(buildWebhookBody(row, text)))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());

            boolean httpOk = resp.statusCode() >= 200 && resp.statusCode() < 300;
            boolean bizOk = businessOk(resp.body());
            if (!httpOk || !bizOk) {
                log.error("[ALERT] 系统告警推送未成功（渠道回绝也算失败，别再记 PUSHED）: http={} body={}",
                        resp.statusCode(), abbreviate(resp.body()));
            }
            updateStatus(row, (httpOk && bizOk) ? "PUSHED" : "FAILED");
        } catch (Exception e) {
            log.error("[ALERT] 系统告警推送失败: {}", e.getMessage());
            updateStatus(row, "FAILED");
        }
    }

    private String webhookFormatName() {
        return (webhookFormat == null || webhookFormat.isBlank())
                ? "WECOM" : webhookFormat.trim().toUpperCase();
    }

    private String webhookContentType() {
        return "SERVERCHAN".equals(webhookFormatName())
                ? "application/x-www-form-urlencoded" : "application/json";
    }

    /** 按 {@link #webhookFormat} 生成报文；新增渠道只需在此加一个分支。 */
    private String buildWebhookBody(AlertLog row, String text) {
        switch (webhookFormatName()) {
            case "SERVERCHAN": {
                // title 必填、**不能含换行**、最长 32 字符；正文走 desp（支持 Markdown）。
                // 不 urlencode 的换行/中文/引号会让对方回 {"code":400,"message":"param error"}。
                String[] parts = text.split("\n", 2);
                String title = parts[0].length() > 32 ? parts[0].substring(0, 32) : parts[0];
                String desp = parts.length > 1 ? parts[1] : "";
                return "title=" + urlEncode(title) + "&desp=" + urlEncode(desp);
            }
            case "RAW":
                // 自建接收端/其它中介：给出结构化字段，由对方决定怎么转成微信消息
                return "{\"title\":\"" + esc(row.getTitle()) + "\",\"source\":\"" + esc(row.getSource())
                        + "\",\"content\":\"" + esc(str(row.getContent())) + "\",\"level\":\""
                        + esc(row.getLevel()) + "\"}";
            case "WECOM":
            case "DINGTALK":
            default:
                // 企业微信群机器人 / 钉钉自定义机器人：同一形状
                return "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + esc(text) + "\"}}";
        }
    }

    /**
     * 渠道返回体的业务码是否成功。
     *
     * <p>返回体里**没有**错误码字段时视为成功（自建 RAW 接收端、部分中介只回 200 空体）；
     * 拿到了错误码但解析不出来，则**保守判失败** —— 宁可记 FAILED 让人去查，也不要假报成功。</p>
     */
    private boolean businessOk(String body) {
        if (body == null || body.isBlank()) return true;
        java.util.regex.Matcher m = ERROR_CODE_PATTERN.matcher(body);
        if (!m.find()) return true;
        try {
            return Integer.parseInt(m.group(1)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(str(s), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String str(String s) {
        return s == null ? "" : s;
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ");
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    private void updateStatus(AlertLog row, String status) {
        row.setNotifyStatus(status);
        try {
            alertLogMapper.updateNotifyStatus(row.getId(), status);
        } catch (Exception e) {
            log.warn("[ALERT] 更新投递状态失败: {}", e.getMessage());
        }
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
