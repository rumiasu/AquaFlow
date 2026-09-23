package com.example.aquaflow.service;

import com.example.aquaflow.entity.AuditLog;
import com.example.aquaflow.mapper.AuditLogMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditLogService {

    @Autowired
    private AuditLogMapper auditLogMapper;

    /**
     * 记录审计日志
     */
    public void log(String module, String action, String target, String detail, String ip) {
        AuditLog log = new AuditLog();
        AuthContext.AuthUser user = AuthContext.get();
        if (user != null) {
            log.setUserId(user.getUserId() != null ? user.getUserId().intValue() : null);
            log.setRole(user.getRole());
        }
        log.setModule(module);
        log.setAction(action);
        log.setTarget(target);
        log.setDetail(detail);
        log.setIp(ip);
        log.setCreateTime(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    public List<AuditLog> listRecent(int limit) {
        return auditLogMapper.listRecent(limit);
    }

    public List<AuditLog> listByUserId(Long userId, int limit) {
        return auditLogMapper.listByUserId(userId, limit);
    }

    public List<AuditLog> listByModule(String module, int limit) {
        return auditLogMapper.listByModule(module, limit);
    }
}
