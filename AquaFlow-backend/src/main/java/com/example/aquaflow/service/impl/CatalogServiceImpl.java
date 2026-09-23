package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.dto.ManagerProductDTO;
import com.example.aquaflow.dto.ProductWithInventoryVO;
import com.example.aquaflow.dto.StationCatalogSettingDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.ProductSubmission;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.ProductSubmissionMapper;
import com.example.aquaflow.service.CatalogService;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.BarrelScope;
import com.example.aquaflow.util.ProductImageResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 通用商品库 + 本站配置的读写编排。
 *
 * <p>规格：{@code docs/design/12-商品与库存重构.md}。本类只做"归属与口径"，不碰表结构细节：</p>
 * <ul>
 *   <li>库存增减一律转交 {@link InventoryService}（它负责加锁、算差额、写流水）；</li>
 *   <li>「通用库商品站长不可改」是靠本类只把站级字段落到 {@code inventory} 实现的 ——
 *       不提供任何改 {@code product.name/price} 的入口；</li>
 *   <li>自定义商品的每一次写操作都先 {@code getOwnedById} 验归属、再校验 affected 行数。</li>
 * </ul>
 */
@Service
@Slf4j
public class CatalogServiceImpl implements CatalogService {

    /** 优先展示上限（与 ManagerProductController 的历史口径一致：最多 3 个） */
    private static final int MAX_PRIORITY_DISPLAY = 3;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductSubmissionMapper submissionMapper;

    @Autowired
    private ProductImageResolver imageResolver;

    /** 上架非桶装商品前的金额门槛校验与建议值（2026-09-19），见 DeliveryConfigGuideService。 */
    @Autowired
    private DeliveryConfigGuideService deliveryConfigGuideService;

    /**
     * 站级价偏离通用库参考价多少算"要提醒"（默认 ±50%）。
     * <p>产品口径：护栏<b>不强制</b>，只提醒 —— 站间可以有价差，但要防手滑把 25 写成 2500。</p>
     */
    @Value("${catalog.price-warn-ratio:0.5}")
    private double priceWarnRatio;

    /**
     * 站长端「选品清单」。
     *
     * <p>⚠️ 底层 SQL（{@code ProductMapper.listWithInventory}）**不过滤 {@code product.status}**
     * —— 所以「平台下架」这件事**必须在这里体现**：只改数据不改这行代码，下架了站长照样看得到、
     * 照样能选。反过来，这里若一刀切按 {@code status = 1} 过滤，站长就会**看不到自己已选用的商品**、
     * 无法再管库存与站级价。</p>
     *
     * <p>因此可见性拆成三条，缺一不可：</p>
     * <ol>
     *   <li><b>本站自定义商品</b>（{@code ownerStationId} 非空）—— 平台下架管不到它，始终可见；</li>
     *   <li><b>通用库商品</b>须在架（{@code status = 1}）；</li>
     *   <li>例外：通用库商品虽已下架、但<b>本站已选用</b>（{@code inventoryId} 非空）—— 仍可见，
     *       否则站长只能看着库存却找不到入口（历史订单与库存都还挂在它身上）。</li>
     * </ol>
     */
    @Override
    public List<ProductWithInventoryVO> listCatalog(Long stationId) {
        return withImageUrls(productMapper.listWithInventory(stationId).stream()
                .filter(vo -> vo.getOwnerStationId() != null
                        || Integer.valueOf(1).equals(vo.getStatus())
                        || vo.getInventoryId() != null)
                .collect(Collectors.toList()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> saveStationSetting(Long stationId, Long productId, StationCatalogSettingDTO dto,
                                           boolean selecting) {
        ProductWithInventoryVO vo = productMapper.getByIdWithInventory(productId, stationId);
        if (vo == null) {
            // 通用库商品与本栈自定义商品都能看到；看不到就是"不存在或不属于本站"
            throw new BusinessException("商品不存在或不属于本站");
        }
        if (vo.getStatus() != null && vo.getStatus() == 0 && !selecting) {
            throw new BusinessException("该商品已被开发者下架，无法在本站配置");
        }
        Inventory current = inventoryMapper.getByStationAndProduct(stationId, productId);

        // [2026-09-19] 押金只对桶装水生效：非桶装商品**不允许**设站级押金。
        // 读侧（PriceUtil.calcDeposit）已经会把非桶装押金算成 0，但只靠读侧会变成"站长填了、系统不认、还不报错"
        // —— 那正是本仓最忌讳的静默不一致。所以在写入这一刻就拒绝并说清原因。
        if (!BarrelScope.isBarrelCategory(vo.getCategory())
                && dto.getDepositPrice() != null && dto.getDepositPrice().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("只有桶装水能设押金："
                    + (vo.getName() != null ? vo.getName() : "该商品")
                    + " 是" + vo.getCategoryText() + "，不收押金（也无需退）");
        }

        // [2026-09-19] 上架**非桶装**商品前的金额门槛硬校验（产品：「没用桶数时，核验金额即可」
        // ＋「选择了上架瓶装水/饮水机等非桶装水业务，最好指引着强制完成金额类字段填写」）：
        // 非桶装不占桶，站长若只配了桶数门槛、没配金额门槛，那条规则对这类订单就是不生效的
        // （见 DeliveryFeeUtil.belowMinOrder 的"条件不适用"分支）。与其让站长以为自己设了门槛，
        // 不如在上架这一刻拦住、并给出按本站最低水价算的建议值 —— 界面上一键就能填完。
        // 只在"本次要上架"时校验：关掉上架（enabled=0）不受影响，避免把下架操作也卡住。
        boolean enabling = Integer.valueOf(1).equals(dto.getEnabled())
                || (dto.getEnabled() == null && current != null && Integer.valueOf(1).equals(current.getEnabled()));
        if (enabling && !BarrelScope.isBarrelCategory(vo.getCategory())) {
            deliveryConfigGuideService.requireAmountFieldsForNonBarrel(stationId);
        }

        Inventory setting = new Inventory();
        setting.setStationId(stationId);
        setting.setProductId(productId);
        // 未上架是默认值：选用≠自动开卖（0 库存 + 已上架 会让顾客下单得到"暂时没货"）
        setting.setEnabled(dto.getEnabled() != null ? dto.getEnabled()
                : (current != null && current.getEnabled() != null ? current.getEnabled() : 0));
        setting.setSalePrice(pickPrice(dto.getSalePrice(), current != null ? current.getSalePrice() : null));
        setting.setDepositPrice(pickPrice(dto.getDepositPrice(), current != null ? current.getDepositPrice() : null));
        setting.setTicketEnabled(dto.getTicketEnabled() != null ? dto.getTicketEnabled()
                : (current != null && current.getTicketEnabled() != null ? current.getTicketEnabled() : 0));
        setting.setTicketPrice(pickPrice(dto.getTicketPrice(), current != null ? current.getTicketPrice() : null));
        // inventory.ticket_price 是 NOT NULL DEFAULT 0：走"未传=保持原值"时新行会算出 null，
        // 直接插会得到 "Column 'ticket_price' cannot be null"（被 GlobalExceptionHandler 兜成业务错误）。
        // 0 与 null 在计价口径上等价（PriceUtil 只认 >0），所以这里回落 0。
        if (setting.getTicketPrice() == null) {
            setting.setTicketPrice(BigDecimal.ZERO);
        }
        Integer priority = dto.getPriorityDisplay() != null ? dto.getPriorityDisplay()
                : (current != null && current.getPriorityDisplay() != null ? current.getPriorityDisplay() : 0);
        requirePriorityWithinLimit(stationId, productId, priority, current);
        setting.setPriorityDisplay(priority);

        inventoryService.saveStationSetting(setting);
        return priceWarnings(vo, setting);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFromStation(Long stationId, Long productId) {
        Inventory current = inventoryMapper.getByStationAndProduct(stationId, productId);
        if (current == null) {
            throw new BusinessException("本站没有配置过该商品");
        }
        int qty = current.getQuantity() == null ? 0 : current.getQuantity();
        if (qty != 0) {
            // 带着库存直接删行 = 库存数与 inventory_record 从此对不上（审计断链）
            throw new BusinessException("本站还有 " + qty + " 件库存，请先盘点为 0 再移除");
        }
        int affected = inventoryMapper.deleteByStationAndProduct(stationId, productId);
        if (affected == 0) {
            throw new BusinessException("移除失败：本站配置已不存在");
        }
    }

    @Override
    public int setStock(Long stationId, Long productId, Integer target, String note) {
        return inventoryService.setStock(stationId, productId, target, InventoryChangeType.ADJUST, null, note);
    }

    @Override
    public List<ProductWithInventoryVO> listMyProducts(Long stationId) {
        return withImageUrls(productMapper.listWithInventory(stationId).stream()
                .filter(vo -> stationId.equals(vo.getOwnerStationId()))
                .collect(Collectors.toList()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createMyProduct(Long stationId, ManagerProductDTO.Save dto) {
        requireCategory(dto.getCategory());
        if (dto.getDeposit() != null && dto.getDeposit().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("押金不能为负数");
        }
        // 桶装水没有押金 = 桶白送（退桶退 0），与桶账模型冲突，必须挡住
        if (BarrelScope.isBarrelCategory(dto.getCategory())
                && (dto.getDeposit() == null || dto.getDeposit().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException("桶装水必须设置大于 0 的押金");
        }
        // [2026-09-19] 反向也挡住：非桶装（瓶装水 / 一次性桶 / 饮水器）**不许有押金** ——
        // 押金是循环桶的押金（product.deposit 列注释"只有桶装水使用"），读了也没有退还路径
        // （退押金按桶型押金条核销，非桶装不建押金条）。读侧 PriceUtil.calcDeposit 同样返回 0，双保险。
        if (!BarrelScope.isBarrelCategory(dto.getCategory())
                && dto.getDeposit() != null && dto.getDeposit().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("只有桶装水能设押金：" + categoryName(dto.getCategory()) + "不收押金（也无需退）");
        }

        Product product = new Product();
        product.setOwnerStationId(stationId);   // ← 关键：自定义商品只属于本站，不进通用库
        product.setName(dto.getName().trim());
        product.setCategory(dto.getCategory());
        product.setBrand(dto.getBrand());
        product.setSpec(dto.getSpec());
        product.setImageObjectName(pickImageValue(dto.getImageObjectName(), dto.getImageUrl()));
        product.setDescription(dto.getDescription());
        product.setPrice(dto.getPrice());
        product.setDeposit(dto.getDeposit() != null ? dto.getDeposit() : BigDecimal.ZERO);
        product.setMaxPerOrder(dto.getMaxPerOrder());
        product.setSort(dto.getSort() != null ? dto.getSort() : 0);
        product.setStatus(1);
        product.setCreateTime(LocalDateTime.now());
        product.setUpdateTime(LocalDateTime.now());
        productMapper.insert(product);

        StationCatalogSettingDTO setting = new StationCatalogSettingDTO();
        setting.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : 0);
        setting.setTicketEnabled(dto.getTicketEnabled());
        setting.setTicketPrice(dto.getTicketPrice());
        setting.setPriorityDisplay(dto.getPriorityDisplay());
        saveStationSetting(stationId, product.getId(), setting, true);

        if (dto.getQuantity() != null && dto.getQuantity() > 0) {
            inventoryService.setStock(stationId, product.getId(), dto.getQuantity(),
                    InventoryChangeType.INBOUND, null, "新建自定义商品初始库存");
        }
        return product.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> updateMyProduct(Long stationId, Long productId, ManagerProductDTO.Update dto) {
        Product product = productMapper.getOwnedById(productId, stationId);
        if (product == null) {
            // 通用库商品也会走到这里 —— 它由开发者维护，站长没有编辑权
            throw new BusinessException("商品不存在，或不是本站自定义商品（通用库商品由平台维护）");
        }
        if (dto.getCategory() != null) {
            requireCategory(dto.getCategory());
            product.setCategory(dto.getCategory());
        }
        if (dto.getName() != null && !dto.getName().trim().isEmpty()) product.setName(dto.getName().trim());
        if (dto.getBrand() != null) product.setBrand(dto.getBrand());
        if (dto.getSpec() != null) product.setSpec(dto.getSpec());
        if (dto.getImageObjectName() != null || dto.getImageUrl() != null) {
            product.setImageObjectName(pickImageValue(dto.getImageObjectName(), dto.getImageUrl()));
        }
        if (dto.getDescription() != null) product.setDescription(dto.getDescription());
        if (dto.getPrice() != null) product.setPrice(dto.getPrice());
        if (dto.getDeposit() != null) product.setDeposit(dto.getDeposit());
        if (dto.getMaxPerOrder() != null) product.setMaxPerOrder(dto.getMaxPerOrder());
        if (dto.getSort() != null) product.setSort(dto.getSort());
        // 校验用的是**改完之后**的 category+deposit（下面两处 set 已完成），故放在这里而不是入口处：
        // ① 桶装水必须有押金（没押金 = 桶白送，与桶账模型冲突）；
        // ② 非桶装不许有押金（押金是循环桶的押金，非桶装没有押金条可核销 → 收了退不出，2026-09-19）。
        if (BarrelScope.isBarrelCategory(product.getCategory())
                && (product.getDeposit() == null || product.getDeposit().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException("桶装水必须设置大于 0 的押金");
        }
        if (!BarrelScope.isBarrelCategory(product.getCategory())
                && product.getDeposit() != null && product.getDeposit().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("只有桶装水能设押金：" + categoryName(product.getCategory()) + "不收押金（也无需退）");
        }
        product.setUpdateTime(LocalDateTime.now());

        int affected = productMapper.updateOwned(product, stationId);
        if (affected == 0) {
            throw new BusinessException("保存失败：该商品已不属于本站");
        }

        StationCatalogSettingDTO setting = new StationCatalogSettingDTO();
        setting.setEnabled(dto.getEnabled());
        setting.setSalePrice(dto.getSalePrice());
        setting.setDepositPrice(dto.getDepositPrice());
        setting.setTicketEnabled(dto.getTicketEnabled());
        setting.setTicketPrice(dto.getTicketPrice());
        setting.setPriorityDisplay(dto.getPriorityDisplay());
        List<String> warnings = saveStationSetting(stationId, productId, setting, false);

        // 库存走流水入口，不允许在设置里直写
        if (dto.getQuantity() != null) {
            Inventory current = inventoryMapper.getByStationAndProduct(stationId, productId);
            if (current == null || !dto.getQuantity().equals(current.getQuantity())) {
                inventoryService.setStock(stationId, productId, dto.getQuantity(),
                        InventoryChangeType.ADJUST, null, "编辑自定义商品时改库存");
            }
        }
        return warnings;
    }

    @Override
    public void deleteMyProduct(Long stationId, Long productId) {
        int affected = productMapper.softDeleteOwned(productId, stationId);
        if (affected == 0) {
            throw new BusinessException("停用失败：商品不存在或不是本站自定义商品（通用库商品由平台维护）");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitMyProduct(Long stationId, Long productId, String note) {
        Product product = productMapper.getOwnedById(productId, stationId);
        if (product == null) {
            throw new BusinessException("只能上报本站的自定义商品");
        }
        if (submissionMapper.countPending(stationId, productId) > 0) {
            throw new BusinessException("该商品已有一条待处理的上报，请等待开发者处理");
        }
        ProductSubmission submission = new ProductSubmission();
        submission.setStationId(stationId);
        submission.setProductId(productId);
        submission.setSubmitterStaffId(AuthContext.getUserId());
        submission.setNote(note);
        submission.setStatus(ProductSubmission.STATUS_PENDING);
        submissionMapper.insert(submission);
        return submission.getId();
    }

    @Override
    public List<ProductSubmission> listMySubmissions(Long stationId, int limit) {
        return submissionMapper.listByStation(stationId, limit <= 0 ? 50 : Math.min(limit, 200));
    }

    /* ==================== 私有辅助 ==================== */

    /**
     * 站级价格三态：{@code null} = 保持原值；{@code <=0} = 清除覆盖（落 NULL，回落通用库参考价）；
     * {@code >0} = 设为该值。
     */
    private static BigDecimal pickPrice(BigDecimal incoming, BigDecimal currentValue) {
        if (incoming == null) return currentValue;
        return incoming.compareTo(BigDecimal.ZERO) > 0 ? incoming : null;
    }

    private void requireCategory(Integer category) {
        if (category == null) {
            throw new BusinessException("商品分类不能为空（1=桶装水，2=瓶装水，3=饮水器）");
        }
        if (category < 1 || category > 3) {
            throw new BusinessException("商品分类值无效，必须为 1(桶装水)、2(瓶装水) 或 3(饮水器)");
        }
    }

    /** 品类中文名，只用于把"为什么不能设押金"这句话说清楚（文案正本仍是 Product.getCategoryText()）。 */
    private static String categoryName(Integer category) {
        if (category == null) return "该商品";
        switch (category) {
            case 1: return "桶装水";
            case 2: return "瓶装水";
            case 3: return "饮水器";
            default: return "该商品";
        }
    }

    /** 优先展示上限 3：切换到自己时先把自己排除，否则"已经是第 3 个"会被自己顶掉 */
    private void requirePriorityWithinLimit(Long stationId, Long productId, Integer priority, Inventory current) {
        if (priority == null || priority != 1) return;
        int count = inventoryMapper.countPriorityDisplay(stationId);
        if (current != null && current.getPriorityDisplay() != null && current.getPriorityDisplay() == 1) {
            count--;
        }
        if (count >= MAX_PRIORITY_DISPLAY) {
            throw new BusinessException("优先展示商品数量已达上限（最多" + MAX_PRIORITY_DISPLAY + "个）");
        }
    }

    /**
     * 站级价偏离通用库参考价过多时给**非阻断**提醒（产品口径：护栏只提醒不强制）。
     * <p>没有参考价（参考值为 0/NULL）时不提醒 —— 那种情况下"偏离比例"没有意义。</p>
     */
    private List<String> priceWarnings(ProductWithInventoryVO vo, Inventory setting) {
        List<String> warnings = new ArrayList<>();
        appendRatioWarning(warnings, "售价", setting.getSalePrice(), vo.getPrice());
        appendRatioWarning(warnings, "押金", setting.getDepositPrice(), vo.getDeposit());
        return warnings;
    }

    private void appendRatioWarning(List<String> warnings, String label, BigDecimal stationValue, BigDecimal refValue) {
        if (stationValue == null || refValue == null || refValue.compareTo(BigDecimal.ZERO) <= 0) return;
        BigDecimal diff = stationValue.subtract(refValue).abs();
        BigDecimal allowed = refValue.multiply(BigDecimal.valueOf(priceWarnRatio));
        if (diff.compareTo(allowed) <= 0) return;
        BigDecimal times = stationValue.divide(refValue, 2, RoundingMode.HALF_UP);
        warnings.add("本站" + label + " ¥" + stationValue.stripTrailingZeros().toPlainString()
                + " 是通用库参考价 ¥" + refValue.stripTrailingZeros().toPlainString()
                + " 的 " + times.stripTrailingZeros().toPlainString() + " 倍，请确认是否填错（不影响保存）");
    }

    /**
     * 从请求里的两个图片字段中挑出**该落库的值**。
     *
     * <p>⚠️ 这里修的是一个真实缺陷（round-trip 污染）：{@code /api/common/upload} 返回的是
     * <b>预签名 URL</b>，站长端把<b>整个 URL</b>回填进 {@code imageUrl}；旧实现直接把它当
     * {@code image_object_name} 落库 → 之后 {@code resolve(那个URL)} 必然签不出有效地址
     * → <b>自定义商品图即使配好 COS 也永远不显示</b>。</p>
     *
     * <p>判据（按落库口径从优到劣）：</p>
     * <ol>
     *   <li>{@code imageObjectName} 是<b>对象键或本地资源路径</b>，本就该落库 → 优先；</li>
     *   <li>{@code imageUrl} 只在<b>以 {@code /} 开头</b>时可用（平台预设图的本地路径）；
     *       若是 http(s) 开头的预签名 URL，说明是上传回填的临时地址，
     *       <b>其对象键已丢失、无法反推</b>，此时宁可存 null（前端显示占位图）也不存一个永远签不出来的值
     *       —— 半坏的图比明确的"无图"更难排查。</li>
     * </ol>
     */
    private String pickImageValue(String imageObjectName, String imageUrl) {
        if (imageObjectName != null && !imageObjectName.isEmpty()) {
            return imageObjectName;
        }
        if (imageUrl != null && imageUrl.startsWith("/")) {
            return imageUrl;
        }
        if (imageUrl != null && !imageUrl.isEmpty()) {
            log.warn("忽略非对象键的 imageUrl（疑为上传返回的预签名 URL，对象键已丢失）: {}", imageUrl);
        }
        return null;
    }

    /**
     * 批量注入图片 URL。
     * <p>⚠️ 别省这一步：商品图片存的是 {@code image_object_name}，它有两种口径 ——
     * <b>本地资源路径</b>（形如 {@code /assets/product/pulisi-pure.webp}）与
     * <b>COS 对象键</b>。前端只认 {@code imageUrl} 一个字段，必须由后端统一翻译
     * （翻译规则见 {@link ProductImageResolver#resolve}）。</p>
     * <p>漏了这一步的表现是"图片全空"而<b>不是报错</b>，很容易被当成前端问题。</p>
     */
    private List<ProductWithInventoryVO> withImageUrls(List<ProductWithInventoryVO> list) {
        for (ProductWithInventoryVO vo : list) {
            vo.setImageUrl(imageResolver.resolve(vo.getImageObjectName()));
        }
        return list;
    }
}
