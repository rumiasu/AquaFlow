package com.example.aquaflow.exception;

/**
 * 资源不存在异常
 */
public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resource) {
        super(404, resource + "不存在");
    }

    public ResourceNotFoundException(String resource, Object id) {
        super(404, resource + "[" + id + "]不存在");
    }
}
