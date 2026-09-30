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

    /**
     * 分级告警：未预期的 500 属**系统故障**，投给系统管理员（见 {@code constant/AlertType}）。
     *
     * <p>为什么放在这里：这是全站唯一的"未预期异常"汇集点 —— 任何没人处理的
     * {@code RuntimeException} 都会经过它。如果只在日志里留一行，等于"系统出事了但没人知道"。
     * AlertServiceImpl 内部已做兜底（落库失败只记日志、独立事务），不会反过来把业务搞崩。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.aquaflow.service.AlertService alertService;

    private void raiseSystemAlert(String source, Throwable e) {
        try {
            alertService.systemFault(source, "未预期的服务端异常",
                    e == null ? "" : (e.getClass().getSimpleName() + ": " + e.getMessage()), null, null);
        } catch (Exception ignore) {
            // 告警自身绝不能影响"把错误回给前端"这件事
        }
    }

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

    /**
     * 业务拒绝（前置条件不满足、权限不足、状态不允许…）。
     *
     * <p>[2026-09-30 修 F-17] <b>级别从 ERROR 降为 WARN，且不再打堆栈</b>：本仓有 271 个端点，
     * "权限不足""库存不足""该订单无需确认退回"这类**正常业务拒绝**每天都在发生，用 ERROR + 完整堆栈
     * 记录会把真正的故障淹没（运维扫日志时无从分辨）。只留异常类型 + message，够定位是哪条路径拒的；
     * 真需要堆栈时，把 {@code com.example.aquaflow} 的日志级别临时调 DEBUG 即可。</p>
     *
     * <p>注意：这里**不触发告警**（{@code AlertService} 只接未预期异常）—— 业务拒绝不是故障，
     * 这条判据不能被"顺手补一条告警"改掉。</p>
     */
    @ExceptionHandler(BusinessException.class)
    public Result handleBusiness(BusinessException e) {
        log.warn("业务拒绝[{}]: {}", e.getClass().getSimpleName(), e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result handleDataIntegrity(DataIntegrityViolationException e) {
        String msg = e.getMessage();
        if (msg == null) msg = "";   // 下面直接 matcher(msg)，为 null 会 NPE
        log.error("数据库约束冲突: {}", msg);

        // [DEF-1] 先区分「值过长 / 超范围」与「字段为空」。
        // 二者在 MySQL 报错里都写作 "Column 'xxx' ..."，旧实现用同一个正则提取列名后
        // 一律回「xxx不能为空」，于是 "Data too long for column 'lot_no'" 被报成
        // 「lot_no不能为空」——排查方向被彻底带偏（真实原因是值超长，不是没传值）。
        if (msg.contains("Data too long") || msg.contains("Data truncation")
                || msg.contains("Out of range")) {
            Matcher too = Pattern.compile("for column '([^']+)'", Pattern.CASE_INSENSITIVE).matcher(msg);
            String col = too.find() ? too.group(1) : null;
            return Result.error(col != null ? (col + "值超出允许长度") : "字段值超出允许长度，请检查输入");
        }

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
        // 外键约束（原先落到兜底，报成「数据提交失败，请检查输入」，用户完全无从下手）：
        //  - Cannot delete/update a parent row → 该记录被别的数据引用（例如地址已被订单占用）
        //  - Cannot add/update a child row     → 引用了不存在的数据
        if (msg.contains("foreign key constraint fails")) {
            if (msg.contains("a parent row")) {
                return Result.error("该记录已被其他数据引用，无法删除");
            }
            return Result.error("关联的数据不存在或已被删除");
        }
        return Result.error("数据提交失败，请检查输入");
    }

    /**
     * 请求方法 / Content-Type 不受支持 —— <b>客户端问题，不是系统故障</b>。
     *
     * <p>[2026-09-25 架构评审] 实测：删掉 {@code POST /api/orders} 之后，同一个路径上仍有
     * {@code GET}（订单列表），于是客户端再发 POST 时 Spring 抛 {@code HttpRequestMethodNotSupportedException}
     * —— 它此前<b>没有分支</b>，落进最下面的 {@code Exception} 处理器：对外 code=500「系统错误」，
     * 同时给系统管理员发一条 SYSTEM 告警。一个客户端把方法写错，被报成后端故障，
     * 既把排查引向错误方向，也会淹掉真故障（同 AGENTS §8.21 的判据）。</p>
     *
     * <p>这里返回可读的 code=1 并把该路径真正支持的方法列出来 ——
     * 客户端据此能自己改对，不必来问后端。</p>
     */
    @ExceptionHandler({
            org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class
    })
    public Result handleMethodOrMediaTypeNotSupported(Exception e) {
        log.warn("请求方法或内容类型不受支持: {}", e.getMessage());
        if (e instanceof org.springframework.web.HttpRequestMethodNotSupportedException m
                && m.getSupportedHttpMethods() != null && !m.getSupportedHttpMethods().isEmpty()) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (org.springframework.http.HttpMethod hm : m.getSupportedHttpMethods()) {
                names.add(hm.name());
            }
            java.util.Collections.sort(names);
            return Result.error("请求方法不支持：该路径只接受 " + String.join(" / ", names));
        }
        return Result.error("请求方法或内容类型不受支持，请检查请求方式与 Content-Type");
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        // 安全：绝不把原始异常信息（NPE 堆栈、SQL 错误等）直接回传给前端，
        // 仅记录日志供排查，前端统一展示中性文案。
        log.error("运行时异常: {}", e.getMessage(), e);
        raiseSystemAlert("GlobalExceptionHandler.runtime", e);
        // [AQ-048] 系统级异常用 code=500，与业务错误(code=1)区分
        return Result.systemError("操作失败，请稍后重试");
    }

    /**
     * 上传体积超限：Servlet 容器在进入 Controller **之前**就拒绝，所以只能在这里兜。
     *
     * <p>[2026-09-20 真机联调] 此前没有任何专属分支，于是落到
     * {@link #handleRuntimeException} → HTTP 200 + {@code code=500}「操作失败，请稍后重试」，
     * 还顺带触发一条 SYSTEM 告警。真机上拍一张大图就会踩到，而这属于**可预期的用户输入问题**，
     * 不该表现为"服务端故障"（判据同 AGENTS §8.21：外部依赖/输入问题不许升级成 500）。</p>
     *
     * <p>上限正本在 {@code application.yml} 的 {@code spring.servlet.multipart.max-file-size}
     * （10MB）；单张图片另有 5MB 的业务限制（{@code CommonController.MAX_FILE_SIZE}）。
     * 这里给的是**对用户可操作**的那一条，不重复声明数字以外的东西。</p>
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public Result handleMaxUploadSize(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.warn("上传体积超限（容器在上传阶段即拒绝）: {}", e.getMessage());
        return Result.error("文件太大了，请压缩后重试（单张图片请控制在 5MB 以内）");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result handleMissingParam(MissingServletRequestParameterException e) {
        // 区分 400（客户端参数问题）与 500（服务端错误），避免调试时误判
        log.warn("缺少必填参数: {}", e.getParameterName());
        return Result.error("缺少必填参数：" + e.getParameterName());
    }

    /**
     * 参数**类型**不匹配（如 stationId 传了 "None"、id 传了非数字）。
     * <p>同样是客户端参数问题，此前会落到 RuntimeException/Exception 分支变成 code=500
     * 「操作失败，请稍后重试」——把前端的拼串错误伪装成后端故障。</p>
     *
     * <p>[2026-09-30 修 F-17] {@code MethodArgumentNotValidException}（Bean Validation 失败）
     * <b>已从这里拆走</b>：两者共用一个处理器时，字段级 message 会被一起压成
     * 「参数格式不正确：参数」，客户端拿不到"数量至少 1""请指定配送员"这类可操作信息
     * （同 skill §8.21②）。拆开后本处理器只负责"类型/格式不对"。</p>
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public Result handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        log.warn("参数格式不正确: {}", e.getMessage());
        return Result.error("参数格式不正确：" + e.getName());
    }

    /**
     * [2026-09-30 修 F-17] Bean Validation 失败：把**字段级** message 原样回给调用方。
     *
     * <p>为什么必须单独一个处理器：DTO 上的 {@code @NotNull(message = "请指定配送员")} /
     * {@code @Min(value = 1, message = "数量至少 1")} 写的就是"该怎么改"，而旧实现与类型不匹配
     * 共用一个处理器，把它们一起压成「参数格式不正确：参数」——前端只能看到一句无从下手的提示。</p>
     *
     * <p>多条字段错误用「；」一次给全（只报第一条会逼调用方反复试）。<b>不要在文案里带字段名以外的
     * 内部信息</b>（如 DTO 类名、校验注解名）——这些是给客户端看的。</p>
     */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public Result handleBeanValidation(
            org.springframework.web.bind.MethodArgumentNotValidException e) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (org.springframework.validation.FieldError fe : e.getBindingResult().getFieldErrors()) {
            String m = fe.getDefaultMessage();
            parts.add(m != null && !m.isBlank() ? m : (fe.getField() + " 参数不合法"));
        }
        String msg = parts.isEmpty() ? "参数校验失败" : String.join("；", parts);
        log.warn("参数校验失败: {}", msg);
        return Result.error(msg);
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
        raiseSystemAlert("GlobalExceptionHandler.system", e);
        // [AQ-048] 系统级异常用 code=500
        return Result.systemError("系统错误，请联系管理员");
    }
}
