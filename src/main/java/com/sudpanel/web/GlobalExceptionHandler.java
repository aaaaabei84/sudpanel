package com.sudpanel.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器（2026-09-28 新增，对应 v1.2 待做 #3）。
 *
 * <p>背景：此前项目没有任何 {@code @ControllerAdvice}，异常一律落到 Spring 默认的 500 响应，
 * 其 body 是 RFC 7807 的 {@code ProblemDetail}（字段名 {@code detail}），
 * 而前端 {@code lib/api.ts} 统一读 {@code body.message} —— 结果用户只能看到「HTTP 500」，
 * 拿不到任何可读的原因。
 *
 * <p>这里把异常统一转成 {@code {"message": "...", "status": n}} 的 JSON：
 * <ul>
 *   <li>{@link NoSuchElementException} → 404（资源不存在）</li>
 *   <li>{@link IllegalStateException} / {@link IllegalArgumentException} → 400（业务校验失败）</li>
 *   <li>其它异常 → 500（兜底，只返回通用提示及诊断编号，详细异常仅保存在本机日志）</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 资源不存在 → 404。 */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * 业务校验失败（状态不合法、参数非法）→ 400。
     * 注意：这类异常在修复前返回 500，前端只认「非 2xx」所以行为兼容，但语义更准确。
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 兜底：未预期异常 → 500，使用诊断编号关联本机日志。 */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(org.springframework.web.server.ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "message", e.getReason() == null ? "请求无法完成" : e.getReason(), "status", e.getStatusCode().value()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        String id = java.util.UUID.randomUUID().toString();
        log.error("平台请求异常，诊断编号 {}", id, e);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "平台内部错误；诊断编号：" + id + "，可在本机日志查找详情");
        result.put("status", 500); result.put("requestId", id);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).header("X-Request-Id", id).body(result);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidJson(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "请求 JSON 格式或字段类型无效，请核对输入");
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleMissingResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "请求地址不存在");
    }

    /** 统一的响应体构造：message 是前端读取的字段，status 便于日志与调试。 */
    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", (message == null || message.isBlank()) ? status.getReasonPhrase() : message);
        m.put("status", status.value());
        return ResponseEntity.status(status).body(m);
    }
}
