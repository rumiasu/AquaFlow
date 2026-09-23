package com.example.aquaflow.exception;

/**
 * 参数校验异常
 */
public class ValidationException extends BusinessException {
    public ValidationException(String message) {
        super(400, message);
    }
}
