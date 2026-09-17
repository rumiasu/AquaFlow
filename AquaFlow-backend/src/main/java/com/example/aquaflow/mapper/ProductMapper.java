package com.example.aquaflow.mapper;

import com.example.aquaflow.dto.ProductWithInventoryVO;
import com.example.aquaflow.dto.StationProductVO;
import com.example.aquaflow.entity.Product;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 商品表访问（通用商品库 + 各站自定义商品，靠 {@code owner_station_id} 逻辑隔离）。
 *
 * <p><b>2026-09-16 商品与库存重构（docs/design/12-商品与库存重构.md）后，本表的可见性规则：</b></p>
 * <ul>
 *   <li>{@code owner_station_id IS NULL} = 通用商品库，由开发者/运维维护，站长只读；</li>
 *   <li>{@code owner_station_id = X} = 水站 X 的自定义商品，<b>只有 X 能读能写</b>，不入通用库。</li>
 * </ul>
 * <p>⚠️ 凡是"给某个水站看/给顾客看"的查询，都<b>必须</b>带上
 * {@code (owner_station_id IS NULL OR owner_station_id = 本站)}，否则就是把别站的自定义商品
 * 泄露出去（重构前 {@link #listWithInventory} 就是这么干的：它不过滤 product，于是站 A 建的商品
 * 出现在站 B 的「商品与库存」列表里）。</p>
 */
@Mapper
public interface ProductMapper {

    @Insert("insert into product(owner_station_id, name, category, brand, spec, image_object_name, description, price, deposit, max_per_order, status, sort, create_time, update_time) " +
            "values(#{ownerStationId}, #{name}, #{category}, #{brand}, #{spec}, #{imageObjectName}, #{description}, #{price}, #{deposit}, #{maxPerOrder}, #{status}, #{sort}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Product product);

    /**
     * 按 id 整行更新（含 owner 无关的目录字段）。
     * <p>⚠️ 只允许用于<b>本站自定义商品</b>：通用库商品的目录字段由开发者维护，站长不得改写。
     * 调用前必须先用 {@link #getOwnedById} 校验归属 —— 别拿"id 存在"当"我有权限"。</p>
     */
    @Update("update product set name=#{name}, category=#{category}, brand=#{brand}, spec=#{spec}, image_object_name=#{imageObjectName}, " +
            "description=#{description}, price=#{price}, deposit=#{deposit}, max_per_order=#{maxPerOrder}, status=#{status}, sort=#{sort}, update_time=#{updateTime} where id=#{id}")
    void update(Product product);

    /**
     * 归属限定的更新：只有 {@code owner_station_id} 命中才生效，返回受影响行数供调用方校验。
     * <p>本仓铁律：按 id 操作记录必须逐条验归属 + 看 affected，否则会静默改到别人数据上（AGENTS.md §8.20）。</p>
     */
    @Update("update product set name=#{p.name}, category=#{p.category}, brand=#{p.brand}, spec=#{p.spec}, " +
            "image_object_name=#{p.imageObjectName}, description=#{p.description}, price=#{p.price}, deposit=#{p.deposit}, " +
            "max_per_order=#{p.maxPerOrder}, status=#{p.status}, sort=#{p.sort}, update_time=#{p.updateTime} " +
            "where id=#{p.id} and owner_station_id=#{ownerStationId}")
    int updateOwned(@Param("p") Product product, @Param("ownerStationId") Long ownerStationId);

    /**
     * 软删除(停用/下架) 归属限定版：不物理删除, 保留历史订单的商品引用.
     * <p>{@code product.id} 是 15 张业务表的锚点，永远不要物理删商品。</p>
     *
     * @return 受影响行数（0 = 不是本站自定义商品）
     */
    @Update("update product set status = 0, update_time = NOW() where id = #{id} and owner_station_id = #{ownerStationId}")
    int softDeleteOwned(@Param("id") Long id, @Param("ownerStationId") Long ownerStationId);

    @Update("update product set status = #{status}, update_time = NOW() where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /** 全部商品（<b>仅开发者/运维与测试造数使用</b>；任何面向站长或顾客的接口都不许调它）。 */
    @Select("select * from product order by sort asc, id asc")
    List<Product> list();

    @Select("select * from product where id = #{id}")
    Product getById(@Param("id") Long id);

    /** 本站可见（通用库 + 本站自定义，含下架/停售），用于站长侧"选品"与排查。 */
    @Select("select * from product where owner_station_id is null or owner_station_id = #{stationId} order by sort asc, id asc")
    List<Product> listVisibleToStation(@Param("stationId") Long stationId);

    /** 本站自定义商品（不含软删）。 */
    @Select("select * from product where owner_station_id = #{stationId} and status <> 0 order by id desc")
    List<Product> listOwnedByStation(@Param("stationId") Long stationId);

    /** 归属限定的取单条：非本站自定义商品返回 null（通用库商品也返回 null —— 它不是"本站的"）。 */
    @Select("select * from product where id = #{id} and owner_station_id = #{stationId}")
    Product getOwnedById(@Param("id") Long id, @Param("stationId") Long stationId);

    List<Product> listByKeyword(@Param("keyword") String keyword);

    @Select("select * from product where category = #{category} and status = 1 order by sort asc, id asc")
    List<Product> listByCategory(@Param("category") Integer category);

    /** 通用库在售（顾客可见性的"平台侧"一半）。 */
    @Select("select * from product where owner_station_id is null and status = 1 order by sort asc, id asc")
    List<Product> listOnSale();

    /**
     * 本站**可售**商品（顾客与抢单池用）：本站已上架({@code inventory.enabled=1}) ∩ 在售 ∩ (通用库或本站自定义)。
     * <p>排序把"优先展示"放在最前 —— 重构前 {@code priority_display} 只影响站长列表，
     * 客户端另一条查询按 {@code sort, id} 排，于是站长设了"优先展示"顾客根本看不出来
     * （见 docs/design/12 缺陷 8）。</p>
     * <p>⚠️ 不要在这里加 {@code distinct}：{@code inventory} 上有唯一键
     * {@code (station_id, product_id)}，join 不可能产生重复行；而 MySQL 的
     * "SELECT DISTINCT + ORDER BY 非选择列表列" 会直接报 3065（实测踩到过）。</p>
     */
    @Select("select p.* from product p " +
            "join inventory i on i.product_id = p.id " +
            "where i.station_id = #{stationId} and i.enabled = 1 and p.status = 1 " +
            "and (p.owner_station_id is null or p.owner_station_id = #{stationId}) " +
            "order by i.priority_display desc, p.sort asc, p.id asc")
    List<Product> listSellableByStation(@Param("stationId") Long stationId);

    // ===== 顾客侧：本站可售商品（价格一律下发**本站有效价**，见 StationProductVO） =====

    /**
     * 本站可售商品（顾客商城/首页/水票页用）：本站已上架 ∩ 在售 ∩ (通用库或本站自定义)。
     * <p>排序把"优先展示"放最前 —— 重构前该字段只影响站长列表，顾客完全看不出来（缺陷 8）。</p>
     */
    @Select("select p.id, p.name, p.category, p.brand, p.spec, p.image_object_name, p.description, " +
            "p.price, p.deposit, p.max_per_order, " +
            "i.sale_price, i.deposit_price, i.ticket_enabled, i.ticket_price, i.priority_display, " +
            "i.quantity as available_qty " +
            "from product p " +
            "join inventory i on i.product_id = p.id " +
            "where i.station_id = #{stationId} and i.enabled = 1 and p.status = 1 " +
            "and (p.owner_station_id is null or p.owner_station_id = #{stationId}) " +
            "order by i.priority_display desc, p.sort asc, p.id asc")
    List<StationProductVO> listSellableByStationWithInventory(@Param("stationId") Long stationId);

    /**
     * 单个商品的"本站视角"（详情/下单页用）。
     * <p>本站没配置过时 {@code i.*} 全为 NULL：生效价回落通用库参考价、{@code inStock=false}；
     * <b>可见性</b>（这个商品能不能给他看）由 Controller 按 {@code owner_station_id} + {@code status} 判定。</p>
     */
    @Select("select p.id, p.owner_station_id, p.name, p.category, p.brand, p.spec, p.image_object_name, p.description, " +
            "p.price, p.deposit, p.max_per_order, p.status, " +
            "i.sale_price, i.deposit_price, i.ticket_enabled, i.ticket_price, i.priority_display, " +
            "i.quantity as available_qty " +
            "from product p " +
            "left join inventory i on i.product_id = p.id and i.station_id = #{stationId} " +
            "where p.id = #{id}")
    StationProductVO getStationProduct(@Param("id") Long id, @Param("stationId") Long stationId);

    /**
     * 本站**有 inventory 行**且在售的商品（抢单池做商品匹配用：只看"这个站卖什么"）。
     * <p>与 {@link #listSellableByStation} 的区别：这里不要求 {@code enabled=1} ——
     * 匹配提示是给站长看的建议，未上架的商品也能作为"本站有这个品"的依据。</p>
     */
    @Select("select p.* from product p " +
            "join inventory i on i.product_id = p.id " +
            "where i.station_id = #{stationId} and p.status = 1 " +
            "and (p.owner_station_id is null or p.owner_station_id = #{stationId})")
    List<Product> listByStationId(@Param("stationId") Long stationId);

    // ===== 管理端: 商品 + 库存联合查询（同样必须带归属过滤） =====

    @Select("select p.id, p.owner_station_id, p.name, p.category, p.brand, p.spec, p.image_object_name, p.description, " +
            "p.price, p.deposit, p.max_per_order, p.status, p.sort, p.create_time, p.update_time, " +
            "i.id as inventory_id, i.quantity, i.enabled, i.sale_price, i.deposit_price, " +
            "i.ticket_enabled, i.ticket_price, i.priority_display " +
            "from product p " +
            "left join inventory i on i.product_id = p.id and i.station_id = #{stationId} " +
            "where p.owner_station_id is null or p.owner_station_id = #{stationId} " +
            "order by i.priority_display desc, p.sort asc, p.id asc")
    List<ProductWithInventoryVO> listWithInventory(@Param("stationId") Long stationId);

    @Select("select p.id, p.owner_station_id, p.name, p.category, p.brand, p.spec, p.image_object_name, p.description, " +
            "p.price, p.deposit, p.max_per_order, p.status, p.sort, p.create_time, p.update_time, " +
            "i.id as inventory_id, i.quantity, i.enabled, i.sale_price, i.deposit_price, " +
            "i.ticket_enabled, i.ticket_price, i.priority_display " +
            "from product p " +
            "left join inventory i on i.product_id = p.id and i.station_id = #{stationId} " +
            "where p.id = #{id} and (p.owner_station_id is null or p.owner_station_id = #{stationId})")
    ProductWithInventoryVO getByIdWithInventory(@Param("id") Long id, @Param("stationId") Long stationId);
}
