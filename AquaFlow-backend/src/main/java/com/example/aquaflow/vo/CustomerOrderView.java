package com.example.aquaflow.vo;

import com.example.aquaflow.entity.Orders;
import org.springframework.beans.BeanUtils;

/** 客户订单视图：员工内部备注不得通过实体序列化给客户。 */
public final class CustomerOrderView extends Orders {
    private boolean legacyNoteUnavailable;
    public static CustomerOrderView of(Orders source) {
        CustomerOrderView view = new CustomerOrderView();
        String transferKind = source.getTransferKind();
        boolean legacyUnknown = source.getCustomerNote() == null && source.getSpecialNote() != null
                && !source.getSpecialNote().isBlank();
        BeanUtils.copyProperties(source, view);
        // 先保存派生结果，再清除底层混合字段；即使序列化器读取字段也不能暴露内部文本。
        view.setTransferPendingKind(transferKind);
        view.setSpecialNote(null);
        view.setBarrelDiscrepancyNote(null);
        view.setCustomerRiskNote(null);
        view.legacyNoteUnavailable = legacyUnknown;
        return view;
    }
    @Override public String getSpecialNote() { return getCustomerNote(); }
    public boolean getLegacyNoteUnavailable() { return legacyNoteUnavailable; }
}
