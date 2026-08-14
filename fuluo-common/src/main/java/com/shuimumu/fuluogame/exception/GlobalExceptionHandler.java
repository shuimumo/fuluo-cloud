package com.shuimumu.fuluogame.exception;

import com.shuimumu.fuluogame.vo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器。
 *
 * <p>P2 修复（异常 HTTP 语义）：此前业务/系统异常统一返回 HTTP 200 + {@code code:0}，
 * 破坏标准 HTTP 客户端、网关重试与监控的语义。现改为返回正确的状态码，
 * <strong>同时保留 {@code Result}（code/msg/data）响应体结构</strong>，便于前端按新契约平滑迁移：
 * <ul>
 *   <li>业务校验失败（{@link BusinessException}）→ 400 Bad Request</li>
 *   <li>静态资源不存在（{@link NoResourceFoundException}）→ 404 Not Found</li>
 *   <li>运行时异常 / 未捕获异常 → 500 Internal Server Error</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常：业务规则校验失败，属客户端错误，返回 400。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.error(e.getMessage()));
    }

    /**
     * 处理静态资源 404（如 favicon.ico），不打印日志。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.notFound().build();
    }

    /**
     * 处理运行时异常：服务器内部错误，返回 500。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<Void>> handleRuntimeException(RuntimeException e) {
        log.error("运行时异常: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error("系统内部错误，请稍后重试"));
    }

    /**
     * 处理所有未捕获的异常：兜底 500。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("未知异常: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error("系统异常，请联系管理员"));
    }
}
