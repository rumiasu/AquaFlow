package com.example.aquaflow.service;

import com.example.aquaflow.entity.OrderBarrelException;
import com.example.aquaflow.mapper.OrderBarrelExceptionMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

/** 员工自己的现场反馈只读回执，不提供站长处理接口或客户资料。 */
@Service
public class DeliveryProblemReadService {
    private final OrderBarrelExceptionMapper mapper;
    public DeliveryProblemReadService(OrderBarrelExceptionMapper mapper) { this.mapper = mapper; }
    public record Receipt(Long id, Long orderId, String staffNote, String statusText,
                          LocalDateTime createdAt, LocalDateTime decidedAt, LocalDateTime executedAt) {}
    public List<Receipt> listMine() {
        Long staffId = AuthContext.getUserId();
        Long stationId = AuthContext.requireStationId();
        if (staffId == null || !"staff".equals(AuthContext.getUserType()))
            throw new com.example.aquaflow.exception.BusinessException("仅员工可查看本人反馈");
        return mapper.listMyDeliveryProblems(staffId, stationId).stream()
                .map(e -> new Receipt(e.getId(), e.getOrderId(), e.getStaffNote(), e.getStatusText(),
                        e.getCreatedAt(), e.getDecidedAt(), e.getExecutedAt())).toList();
    }
}
