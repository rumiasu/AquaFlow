package com.example.aquaflow.service;

import com.example.aquaflow.dto.PayrollDTO;
import com.example.aquaflow.entity.StaffEarningItem;

import java.util.List;

/**
 * 站长自定义工资条目（v44）：加项 / 扣项字典的读写。规格见 {@code docs/design/18} §5。
 *
 * <p><b>本服务是 {@code staff_earning_item} 的唯一写入口</b>。三条口径：</p>
 * <ol>
 *   <li><b>方向由条目决定</b>，录钱的人只填正数金额（{@code EarningItemDirection}）。</li>
 *   <li><b>被流水用过的条目只能停用、不能删</b> —— 条目是历史工资的标签，
 *       删掉它那些流水在界面上就成了"无条目的人工调整"，站长再也说不清那笔钱是什么。</li>
 *   <li><b>改名不改写历史</b>：明细里的 {@code item_name} 是写入时的快照，
 *       改名只影响之后新录的（金额口径与 {@code order_item.product_name} 同源）。</li>
 * </ol>
 */
public interface StaffEarningItemService {

    /** 本站条目列表；{@code includeDisabled=false} 只返回启用的（录一笔时的可选集） */
    List<StaffEarningItem> list(Long stationId, boolean includeDisabled);

    /** 新建条目，返回 id。同名（同站）直接拒绝 —— 两个"高温补贴"会让月底汇总对不上账。 */
    Long create(Long stationId, PayrollDTO.EarningItem body);

    /** 改名 / 改方向 / 改顺序 */
    void update(Long stationId, Long id, PayrollDTO.EarningItem body);

    /** 启用（1）/ 停用（0） */
    void setStatus(Long stationId, Long id, Integer status);

    /** 删除；被流水引用过则拒绝（提示改为停用） */
    void delete(Long stationId, Long id);
}
