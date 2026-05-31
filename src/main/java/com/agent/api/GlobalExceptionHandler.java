package com.agent.api;

import com.agent.api.dto.ErrorResponse;
import com.agent.auth.ForbiddenException;
import com.agent.auth.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

// 当任何一个 @Controller 或 @RestController 中的方法抛出 UnauthorizedException 时，Spring 会自动调用这个 @ExceptionHandler 方法。
// 该方法可以返回统一格式的错误信息、特定的 HTTP 状态码（如 401），避免在每个控制器里重复写 try-catch
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex,
                                                            HttpServletRequest request) {
        log.warn("Unauthorized: {}", ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, "未登录", ex.getMessage(), request);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex,
                                                         HttpServletRequest request) {
        log.warn("Forbidden: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "无权限", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                HttpServletRequest request) {
        log.warn("Bad request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "参数错误", ex.getMessage(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                              HttpServletRequest request) {
        log.warn("Missing required parameter: {}", ex.getParameterName());
        return buildResponse(HttpStatus.BAD_REQUEST, "缺少必要参数",
                "参数 '" + ex.getParameterName() + "' 是必填的", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                             HttpServletRequest request) {
        log.warn("Type mismatch for parameter: {}", ex.getName());
        return buildResponse(HttpStatus.BAD_REQUEST, "参数类型错误",
                "参数 '" + ex.getName() + "' 的类型不正确", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex,
                                                               HttpServletRequest request) {
        log.warn("File upload exceeds max size: {}", ex.getMessage());
        return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, "文件过大",
                "上传文件大小超过限制 (最大 " + ex.getMaxUploadSize() / 1024 / 1024 + "MB)", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex,
                                                         HttpServletRequest request) {
        log.debug("Resource not found: {}", request.getRequestURI());
        return buildResponse(HttpStatus.NOT_FOUND, "资源不存在",
                "请求的资源 '" + request.getRequestURI() + "' 不存在", request);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException ex,
                                                        HttpServletRequest request) {
        log.error("Unexpected runtime error: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误",
                "服务暂时不可用，请稍后重试", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex,
                                                        HttpServletRequest request) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "服务器错误",
                "系统内部错误，请联系管理员", request);
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String error,
                                                          String message, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .status(status.value())
                .error(error)
                .message(message)
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
