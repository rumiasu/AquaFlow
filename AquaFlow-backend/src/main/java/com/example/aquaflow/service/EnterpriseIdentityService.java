package com.example.aquaflow.service;

import com.example.aquaflow.constant.SettlementCycle;
import com.example.aquaflow.entity.CompanyInfo;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.CustomerEnterpriseApply;
import com.example.aquaflow.entity.StationEnterpriseConfig;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CompanyInfoMapper;
import com.example.aquaflow.mapper.CustomerEnterpriseApplyMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.mapper.StationEnterpriseConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 企业身份申请的最小闭环（v50）：**客户申请 → 站长审核 → 转企业身份 + 写企业资料**。
 *
 * <p>产品口径：「企业和普通用户分离…我不建议做成入口，我觉得在订水时，检测到大额订单，
 * 会弹出确认是否是企业，可申请企业身份这种」。</p>
 *
 * <p><b>什么算"大额"（v51 收敛，只算水）</b>：桶装水数量与<b>水费</b>两条口径，命中任一即提示；
 * 押金 / 配送费 / 楼层费一律不计。阈值可按站配（{@code station_enterprise_config}），
 * 没配过的站用平台默认 30 桶。详见 {@link #largeOrderHint}。</p>
 *
 * <p><b>整个功能受 {@code app.enterprise.enabled} 控制（默认关闭）</b>：关掉时本类的每个方法
 * 一律抛业务错误、报价也不再下发提示 —— 甲方不满意可以立刻关，而且**关掉不影响已经是企业身份的历史客户**
 * （那只是不再有新申请与新提示）。⚠️ 这个开关**不做前端可视化**（产品 2026-09-19 明确：「平台级的
 * 暂时不做前端可视化了」）—— 只有环境变量一条路径，界面上的开关是**站级阈值**那个，别混淆。</p>
 *
 * <p>⚠️ 审核通过要做两件事，必须在同一个事务里：① 把 {@code customer.customer_type} 置 2；
 * ② 把企业资料写进既有的 {@code company_info}（唯一键 {@code uk_company_customer}）。</p>
 */
@Service
@Slf4j
public class EnterpriseIdentityService {

    /** 大额订单的提示文案（前端原样展示，不得自编同义文案）。 */
    private static final String HINT_TEMPLATE =
            "%s。如果这是企业订水，可以申请企业身份（对公结算、可设账期），"
                    + "由水站站长审核；不确定就先不申请，不影响本次下单。";

    @Autowired
    private CustomerEnterpriseApplyMapper applyMapper;

    @Autowired
    private CompanyInfoMapper companyInfoMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private StationEnterpriseConfigMapper configMapper;

    /**
     * 账期的写入口（v60 起账期是**站级**配置，落在 {@code customer_station_config}）。
     *
     * <p>⚠️ 这里注入 mapper 而不是 {@code ReceivableService}：后者依赖 {@code PaymentService}，
     * 而 {@code PaymentServiceImpl} 又依赖本类（`largeOrderHint`）—— 走 service 会形成循环依赖。
     * 平台默认值本身是常量（{@link SettlementCycle}），不存在两处各写一套数字的问题。</p>
     */
    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    /** 总开关：默认关闭。关掉后本功能的端点一律拒绝、报价不再下发提示。 */
    @Value("${app.enterprise.enabled:false}")
    private boolean enabled;

    /**
     * 平台默认的**桶数**阈值：该站没配过时用它（默认 30 桶）。
     *
     * <p>⚠️ 与 v50 的 {@code large-order-threshold}（订单总额 500 元）不是同一个口径，
     * 那次按"只算水"重做了 —— 押金不再计入，且桶数是主口径（见 {@link #largeOrderHint}）。</p>
     */
    @Value("${app.enterprise.large-order-barrels:30}")
    private Integer defaultBarrels;

    /** 平台默认的**水费金额**阈值；{@code <=0} = 默认不启用金额口径（水站可在本站单独开启）。 */
    @Value("${app.enterprise.large-order-water-amount:0}")
    private java.math.BigDecimal defaultWaterAmount;

    /** 开关是否打开（报价侧也要问这一句）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 大额提示（报价响应里下发）。**返回 null 表示什么都不提示**，四种情况：
     * 开关关着、该客户已经是企业身份、两条口径都没命中、该站被站长显式关掉了提示。
     *
     * <p><b>口径（2026-09-19 第五批裁定，取代 v50 的"订单总额 ≥ 阈值"）</b>：只算水 ——
     * 产品原话「企业的只看水，押金不算，水超过 30 桶就可以吧。也可以由水站设置」。
     * 于是两条口径各自独立，命中任一即提示：</p>
     * <ul>
     *   <li><b>桶数</b>：本单桶装水（{@code product.category=1}）数量合计 ≥ 阈值；</li>
     *   <li><b>水费</b>：本单水费（不含押金 / 配送费 / 楼层费）≥ 阈值。</li>
     * </ul>
     * <p>阈值来源：该站在 {@code station_enterprise_config} 里配过就用它（两项都配 = 任一满足）；
     * <b>没配过</b>才套平台默认（{@code app.enterprise.large-order-barrels}，默认 30 桶）。
     * 「有一行但两项都留空」= 站长表示本站不提示，**不要**当成"没配过"再套默认值。</p>
     *
     * @param barrelCount 本单桶装水数量合计（只数 category=1）
     * @param waterAmount 本单水费（不含押金与各项费用）
     */
    public String largeOrderHint(Long customerId, Long stationId, int barrelCount, java.math.BigDecimal waterAmount) {
        if (!enabled || customerId == null) {
            return null;
        }
        Customer customer = customerMapper.getById(customerId);
        if (customer != null && Integer.valueOf(2).equals(customer.getCustomerType())) {
            return null;
        }

        if (stationId == null) {
            return null;
        }
        StationEnterpriseConfig config = configMapper.getByStationId(stationId);
        Integer barrels = null;
        java.math.BigDecimal amount = null;
        if (config == null) {
            // 还没配过 → 平台默认（金额口径默认关闭：0/负 一律视为"不启用"）
            barrels = defaultBarrels;
            amount = defaultWaterAmount;
        } else {
            barrels = config.getBarrelThreshold();
            amount = config.getWaterAmountThreshold();
        }

        boolean byBarrels = barrels != null && barrels > 0 && barrelCount >= barrels;
        boolean byAmount = amount != null && amount.signum() > 0
                && waterAmount != null && waterAmount.compareTo(amount) >= 0;
        if (!byBarrels && !byAmount) {
            return null;
        }
        // 文案里报的是**本单自己的数**，不是阈值 —— 阈值属于水站的经营参数，没必要透露给客户
        String what;
        if (byBarrels && byAmount) {
            what = String.format("本单共 %d 桶水、水费 ¥%s", barrelCount, waterAmount.toPlainString());
        } else if (byBarrels) {
            what = String.format("本单共 %d 桶水", barrelCount);
        } else {
            what = String.format("本单水费 ¥%s", waterAmount.toPlainString());
        }
        return String.format(HINT_TEMPLATE, what);
    }

    /** 本站的阈值配置（含平台默认与"是否在用默认"），供站长端设置界面回填。 */
    public java.util.Map<String, Object> stationConfig(Long stationId) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("defaultBarrels", defaultBarrels);
        result.put("defaultWaterAmount",
                (defaultWaterAmount != null && defaultWaterAmount.signum() > 0) ? defaultWaterAmount : null);
        if (!enabled) {
            // 开关关着：入口整体不该出现，只回 enabled=false + 默认值，前端据此整块隐藏
            result.put("usingDefault", true);
            result.put("barrelThreshold", null);
            result.put("waterAmountThreshold", null);
            return result;
        }
        StationEnterpriseConfig config = configMapper.getByStationId(stationId);
        result.put("usingDefault", config == null);
        result.put("barrelThreshold", config == null ? defaultBarrels : config.getBarrelThreshold());
        // ⚠️ 平台默认的金额阈值 0 表示"口径未启用"，这里必须下发 **null 而不是 0**：
        // 前端拿到 0 会把它回填进输入框（站长看到"水费达到 0 元"），一保存又被"必须为正"拒掉。
        // 「未启用」对外只有一种表示法 —— null。
        java.math.BigDecimal amount;
        if (config == null) {
            amount = (defaultWaterAmount != null && defaultWaterAmount.signum() > 0) ? defaultWaterAmount : null;
        } else {
            amount = config.getWaterAmountThreshold();
        }
        result.put("waterAmountThreshold", amount);
        return result;
    }

    /**
     * 站长改本站阈值。两项都传 null 是合法的（= 本站不提示）。
     *
     * <p>校验：桶数与金额要么留空、要么为正 —— <b>不接受 0 或负数</b>。
     * 因为"0 桶就提示"等于每单都弹，几乎必然是站长填错，宁可报错也不要静默变成骚扰。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStationConfig(Long stationId, Integer barrelThreshold,
                                    java.math.BigDecimal waterAmountThreshold, Long operatorId) {
        requireEnabled();
        if (barrelThreshold != null && barrelThreshold <= 0) {
            throw new BusinessException("桶数阈值要么留空（不用这条口径），要么填大于 0 的整数");
        }
        if (waterAmountThreshold != null && waterAmountThreshold.signum() <= 0) {
            throw new BusinessException("水费金额阈值要么留空（不用这条口径），要么填大于 0 的金额");
        }
        StationEnterpriseConfig config = new StationEnterpriseConfig();
        config.setStationId(stationId);
        config.setBarrelThreshold(barrelThreshold);
        config.setWaterAmountThreshold(waterAmountThreshold);
        config.setOperatorId(operatorId);
        configMapper.upsert(config);
        log.info("[企业身份] 站 {} 的提示阈值已更新：桶数={} 水费={}（留空=该项不启用；两项都空=本站不提示）",
                stationId, barrelThreshold, waterAmountThreshold);
    }

    /** 客户提交申请：同一站已有待审时**幂等返回那一条**（不产生第二条）。 */
    @Transactional(rollbackFor = Exception.class)
    public CustomerEnterpriseApply submit(Long customerId, Long stationId, String companyName,
                                          String contactPerson, String contactPhone, String taxNo) {
        requireEnabled();
        if (companyName == null || companyName.trim().isEmpty()) {
            throw new BusinessException("请填写企业名称");
        }
        CustomerEnterpriseApply pending = applyMapper.getPending(customerId, stationId);
        if (pending != null) {
            return pending;
        }
        CustomerEnterpriseApply apply = new CustomerEnterpriseApply();
        apply.setCustomerId(customerId);
        apply.setStationId(stationId);
        apply.setCompanyName(companyName.trim());
        apply.setContactPerson(contactPerson);
        apply.setContactPhone(contactPhone);
        apply.setTaxNo(taxNo);
        applyMapper.insert(apply);
        return apply;
    }

    public List<CustomerEnterpriseApply> listMine(Long customerId, Long stationId) {
        requireEnabled();
        return applyMapper.listByCustomerAndStation(customerId, stationId);
    }

    public List<CustomerEnterpriseApply> listPending(Long stationId) {
        requireEnabled();
        return applyMapper.listPendingByStation(stationId);
    }

    /**
     * 站长审核：{@code approve=true} → 申请置已通过 + 客户转企业身份 + 写企业资料；
     * {@code false} → 只置已驳回（不动客户身份）。
     *
     * <p>⚠️ 顺序：**先 CAS 申请状态**（拿不到行数就直接抛，说明已被审过），再改客户 —— 反过来的话，
     * 并发两次"通过"会各写一遍企业资料。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void review(Long applyId, Long stationId, boolean approve, String note, Long reviewerId) {
        requireEnabled();
        CustomerEnterpriseApply apply = applyMapper.getById(applyId);
        if (apply == null || !stationId.equals(apply.getStationId())) {
            // 不区分"不存在"与"不是本站的"，避免把申请 id 变成可枚举的探针
            throw new BusinessException("申请不存在或不属于本水站");
        }
        int changed = applyMapper.reviewIfPending(applyId,
                approve ? CustomerEnterpriseApply.APPROVED : CustomerEnterpriseApply.REJECTED, note, reviewerId);
        if (changed == 0) {
            throw new BusinessException("该申请已被处理，请刷新后重试");
        }
        if (!approve) {
            return;
        }
        customerMapper.updateCustomerType(apply.getCustomerId(), 2);
        CompanyInfo info = companyInfoMapper.getByCustomerId(apply.getCustomerId());
        if (info == null) {
            info = new CompanyInfo();
            info.setCustomerId(apply.getCustomerId());
            info.setCompanyName(apply.getCompanyName());
            info.setContactPerson(apply.getContactPerson());
            info.setContactPhone(apply.getContactPhone());
            info.setCreateTime(LocalDateTime.now());
            info.setUpdateTime(LocalDateTime.now());
            companyInfoMapper.insert(info);
        } else {
            info.setCompanyName(apply.getCompanyName());
            info.setContactPerson(apply.getContactPerson());
            info.setContactPhone(apply.getContactPhone());
            info.setUpdateTime(LocalDateTime.now());
            companyInfoMapper.updateByCustomerId(info);
        }
        log.info("[企业身份] 客户 {} 的申请 {} 已通过（站 {}）", apply.getCustomerId(), applyId, stationId);

        // [v60] 一键套用平台默认账期 —— 「点开启企业账户时可以一键使用，然后后续可以重设」。
        //
        // 为什么默认是「月结 30 天」：企业主流是"本月消费、下月结账"（见 constant/SettlementCycle）。
        // 站长的动作因此只是"点一下通过"，不需要理解什么是结算周期、也不需要填数
        // —— 站长主要是力工，每多一个要填的框就多一份误判风险。
        //
        // ⚠️ 这里**直接调 mapper 而不调 ReceivableService**：后者依赖 PaymentService，
        // 而 PaymentServiceImpl 又依赖本类（`largeOrderHint`），绕过去会形成循环依赖。
        // 平台默认值本身是常量（SettlementCycle），不存在两处各写一套数字的问题。
        //
        // ⚠️ 只写**站级**配置：客户在别的站有没有账期不受影响（v60 起账期按 (customer, station) 隔离）。
        customerStationConfigMapper.ensureExists(apply.getCustomerId(), stationId);
        customerStationConfigMapper.updateCreditTerms(apply.getCustomerId(), stationId,
                SettlementCycle.PLATFORM_DEFAULT_DUE_DAYS, SettlementCycle.PLATFORM_DEFAULT);
        log.info("[企业身份] 已为客户 {} 在站 {} 套用平台默认账期：{} {} 天",
                apply.getCustomerId(), stationId,
                SettlementCycle.textOf(SettlementCycle.PLATFORM_DEFAULT),
                SettlementCycle.PLATFORM_DEFAULT_DUE_DAYS);
    }

    private void requireEnabled() {
        if (!enabled) {
            // 关掉时**不能只是隐藏入口**：接口必须真拒绝，否则"关了还能调"
            throw new BusinessException("企业身份功能当前未开启");
        }
    }
}
