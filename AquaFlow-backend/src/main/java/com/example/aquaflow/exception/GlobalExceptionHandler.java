package com.example.aquaflow.exception;

import com.example.aquaflow.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public Result handleNotFound(ResourceNotFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public Result handleValidation(ValidationException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public Result handleBusiness(BusinessException e) {
        log.error("业务异常: {}", e.getMessage(), e);
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result handleDataIntegrity(DataIntegrityViolationException e) {
        String msg = e.getMessage();
        log.error("数据库约束冲突: {}", msg);

        // 提取列名，如 Column 'sale_price' cannot be null
        Matcher m = Pattern.compile("Column '([^']+)'", Pattern.CASE_INSENSITIVE).matcher(msg);
        String column = m.find() ? m.group(1) : null;

        // 映射为中文字段名
        if (column != null) {
            String cn = switch (column) {
                case "sale_price" -> "销售价格";
                case "ticket_price" -> "水票价格";
                case "ticket_enabled" -> "水票开关";
                case "enabled" -> "上架状态";
                case "quantity" -> "库存数量";
                case "product_id" -> "商品";
                case "station_id" -> "水站";
                case "customer_id" -> "客户";
                case "name" -> "名称";
                case "phone" -> "手机号";
                default -> column;
            };
            return Result.error(cn + "不能为空");
        }

        if (msg.contains("cannot be null")) {
            return Result.error("必填字段不能为空");
        }
        if (msg.contains("Duplicate entry")) {
            return Result.error("数据已存在，请勿重复提交");
        }
        return Result.error("数据提交失败，请检查输入");
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        // 安全：绝不把原始异常信息（NPE 堆栈、SQL 错误等）直接回传给前端，
        // 仅记录日志供排查，前端统一展示中性文案。
        log.error("运行时异常: {}", e.getMessage(), e);
        // [AQ-048] 系统级异常用 code=500，与业务错误(code=1)区分
        return Result.systemError("操作失败，请稍后重试");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result handleMissingParam(MissingServletRequestParameterException e) {
        // 区分 400（客户端参数问题）与 500（服务端错误），避免调试时误判
        log.warn("缺少必填参数: {}", e.getParameterName());
        return Result.error("缺少必填参数：" + e.getParameterName());
    }

    /**
     * 参数类型不匹配（如 stationId 传了 "None"、id 传了非数字）。
     * <p>同样是客户端参数问题，此前会落到 RuntimeException/Exception 分支变成 code=500
     * 「操作失败，请稍后重试」——把前端的拼串错误伪装成后端故障。</p>
     */
    @ExceptionHandler({
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class
    })
    public Result handleTypeMismatch(Exception e) {
        String param = "参数";
        if (e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException m) {
            param = m.getName();
        }
        log.warn("参数格式不正确: {}", e.getMessage());
        return Result.error("参数格式不正确：" + param);
    }

    /**
     * 路由不存在（含静态资源未命中）。
     * <p>Spring 6.1 起，未匹配到任何 handler 时抛的是 NoResourceFoundException。
     * 旧实现无对应分支，会落到最下面的 Exception 处理器，对外表现为
     * 「HTTP 200 + code=500 系统错误」——排查时极易被误判为后端逻辑异常，
     * 实际只是前端把接口路径写错了。这里单独识别并给出 code=404。</p>
     */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public Result handleNoHandler(Exception e) {
        String path = null;
        if (e instanceof org.springframework.web.servlet.resource.NoResourceFoundException nrf) {
            path = nrf.getResourcePath();
        }
        log.warn("接口不存在: {}", path != null ? path : e.getMessage());
        return Result.notFound(path != null ? ("接口不存在：" + path) : "接口不存在");
    }

    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        // [AQ-048] 系统级异常用 code=500
        return Result.systemError("系统错误，请联系管理员");
    }
}
