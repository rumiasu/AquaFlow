package com.example.aquaflow.service;

import com.example.aquaflow.entity.TicketPackage;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.TicketPackageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Manager package changes and their legacy one-ticket price form one transaction. */
@Service
public class TicketPackageService {
    private final TicketPackageMapper packages;
    private final InventoryMapper inventory;
    public TicketPackageService(TicketPackageMapper packages, InventoryMapper inventory) {
        this.packages = packages; this.inventory = inventory;
    }

    @Transactional(rollbackFor = Exception.class)
    public TicketPackage save(Long station, TicketPackage body) {
        if (body.getProductId() == null) throw new BusinessException("请选择商品");
        if (body.getQty() == null || body.getQty() <= 0 || body.getQty() > 5000)
            throw new BusinessException("套餐张数须为 1 至 5000 的整数");
        if (body.getPrice() == null || body.getPrice().signum() <= 0)
            throw new BusinessException("套餐总价必须大于 0");
        BigDecimal price = body.getPrice().setScale(2, RoundingMode.HALF_UP);
        if (price.signum() <= 0 || price.compareTo(new BigDecimal("99999999.99")) > 0)
            throw new BusinessException("套餐总价超出支持范围");
        if (body.getStatus() != null && body.getStatus() != 0 && body.getStatus() != 1)
            throw new BusinessException("套餐状态无效");
        // Serialize all tier/retail changes for this station/product. No physical quantity changes.
        if (inventory.getByStationAndProductForUpdate(station, body.getProductId()) == null)
            throw new BusinessException("该商品尚未在本站选用");
        TicketPackage previous = body.getId() == null ? null : packages.getByIdForUpdate(body.getId());
        if (body.getId() != null && (previous == null || !Objects.equals(station, previous.getStationId())
                || !Objects.equals(body.getProductId(), previous.getProductId())))
            throw new BusinessException("套餐不存在或不属于本站商品");
        TicketPackage duplicate = packages.getByStationProductQty(station, body.getProductId(), body.getQty());
        if (previous != null && duplicate != null && !Objects.equals(previous.getId(), duplicate.getId()))
            throw new BusinessException("该张数已有套餐，请修改原套餐");
        TicketPackage saved = new TicketPackage();
        saved.setId(body.getId()); saved.setStationId(station); saved.setProductId(body.getProductId());
        saved.setQty(body.getQty()); saved.setPrice(price);
        saved.setUnitPrice(price.divide(BigDecimal.valueOf(body.getQty()), 2, RoundingMode.HALF_UP));
        saved.setTitle(body.getTitle()); saved.setStatus(body.getStatus() == null ? 1 : body.getStatus());
        saved.setSort(body.getSort() == null ? 0 : body.getSort());
        if (previous == null) packages.upsert(saved);
        else if (packages.update(saved) != 1) throw new BusinessException("套餐已变化，请重新读取");
        // 2026-10-26: clearing or disabling a real one-ticket tier must not resurrect the
        // old retail field, including callers using the independent package-management page.
        if (Integer.valueOf(1).equals(saved.getQty())) mirror(station, saved.getProductId(), saved.isOnShelf() ? price : BigDecimal.ZERO);
        else if (previous != null && Integer.valueOf(1).equals(previous.getQty())) {
            TicketPackage tombstone = new TicketPackage();
            tombstone.setStationId(station); tombstone.setProductId(saved.getProductId()); tombstone.setQty(1);
            tombstone.setPrice(previous.getPrice()); tombstone.setUnitPrice(previous.getUnitPrice());
            tombstone.setStatus(0); tombstone.setSort(previous.getSort());
            packages.upsert(tombstone);
            mirror(station, saved.getProductId(), BigDecimal.ZERO);
        }
        return packages.getByStationProductQty(station, saved.getProductId(), saved.getQty());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long station, Long id) {
        TicketPackage before = packages.getById(id);
        if (before == null || !Objects.equals(station, before.getStationId()))
            throw new BusinessException("套餐不存在或不属于本站");
        if (inventory.getByStationAndProductForUpdate(station, before.getProductId()) == null)
            throw new BusinessException("商品配置已变化，请重新读取");
        before = packages.getByIdForUpdate(id);
        if (before == null || !Objects.equals(station, before.getStationId()))
            throw new BusinessException("套餐已变化，请重新读取");
        if (Integer.valueOf(1).equals(before.getQty())) {
            // Keep a disabled one-ticket tombstone: later old-client writes to ticket_price
            // cannot make the deleted tier appear again. Purchased lots/payment records are untouched.
            before.setStatus(0);
            if (packages.update(before) != 1) throw new BusinessException("套餐已变化，请重新读取");
            mirror(station, before.getProductId(), BigDecimal.ZERO);
        } else if (packages.delete(id, station) != 1) throw new BusinessException("套餐已变化，请重新读取");
    }

    private void mirror(Long station, Long product, BigDecimal price) {
        if (inventory.updateTicketPrice(station, product, price) != 1)
            throw new BusinessException("商品配置已变化，请重新读取");
    }
}
