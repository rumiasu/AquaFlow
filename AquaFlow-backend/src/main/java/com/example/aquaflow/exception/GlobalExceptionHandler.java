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
        log.error("运行时异常: {}", e.getMessage(), e);
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result handleMissingParam(MissingServletRequestParameterException e) {
        // 区分 400（客户端参数问题）与 500（服务端错误），避免调试时误判
        log.warn("缺少必填参数: {}", e.getParameterName());
        return Result.error("缺少必填参数：" + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return Result.error("系统错误，请联系管理员");
    }
}
