package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.CustomerService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 站长端综合搜索（搜客户 / 地址 / 订单），<b>仅 {@code STATION_MANAGER}</b>。
 *
 * <p><b>⚠️ 顾客搜商品不要用这个接口</b>：它是站长专属，顾客调用必然 403。
 * 顾客搜商品请用 {@code GET /api/products?keyword=xxx}。</p>
 *
 * <p>历史上小程序端曾因注释误导而误用本接口，导致用户端搜索恒定报"当前账号未绑定水站"。
 * 因此 {@code miniapp-user/config/api.js} 里<b>特意不定义</b> {@code SEARCH} 常量，
 * 并在原位置留了一段反向警示 —— 新增顾客端搜索功能时请先读那段注释。</p>
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    @Autowired
    private AddressMapper addressMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private CustomerService customerService;

    /**
     * 站长端综合搜索：一次返回客户 / 地址 / 订单三类结果。
     *
     * <p>三类结果的匹配口径<b>目前并不一致，这是有意的</b>：</p>
     * <ul>
     *   <li><b>客户</b>：走 {@code CustomerService.searchStationCustomers}
     *       —— 地址参与、归一化后按相关性排序，支持缩写与中英数字混用
     *       （站长认人主要靠地址，见 {@code util/CustomerSearchMatcher}）；</li>
     *   <li><b>地址 / 订单</b>：仍是 {@code AddressMapper} / {@code OrderMapper} 的强子串 LIKE
     *       （它们不在本次改造范围内，且 {@code OrderMapper} 属共享禁改文件）。</li>
     * </ul>
     * <p>所以"同一个关键字，客户里有、地址里没有"是可能出现的，界面不要据此判断数据缺失。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<Map<String, Object>> search(@RequestParam String keyword) {
        Map<String, Object> result = new HashMap<>();
        Long stationId = AuthContext.requireStationId();
        result.put("customers", customerService.searchStationCustomers(stationId, keyword));
        result.put("addresses", addressMapper.listByStation(stationId, keyword));
        result.put("orders", orderMapper.searchByKeywordAndStation(stationId, keyword));
        return Result.success(result);
    }
}
