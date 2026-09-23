package com.example.aquaflow.common;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(){
        Result<T> result =new Result<>();
        result.code=0;
        return result;
    }
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.code = 0;
        result.data = data;
        return result;
    }

    public static <T> Result<T> error(String message) {
        Result<T> result = new Result<>();
        result.code = 1;
        result.message = message;
        return result;
    }

    /**
     * [AQ-048] 系统异常（未预期错误）。
     * <p>与业务错误（code=1）区分，前端可据此显示"系统繁忙"而非把技术错误当成业务提示。</p>
     */
    public static <T> Result<T> systemError(String message) {
        Result<T> result = new Result<>();
        result.code = 500;
        result.message = message;
        return result;
    }

    /**
     * 接口/资源不存在。
     * <p>此前打到不存在的路由会被兜底的 Exception 处理器接住，返回 code=500「系统错误」，
     * 排查时极易误判成后端逻辑出错。独立出 code=404，一眼能看出是路径写错。</p>
     */
    public static <T> Result<T> notFound(String message) {
        Result<T> result = new Result<>();
        result.code = 404;
        result.message = message;
        return result;
    }
}
