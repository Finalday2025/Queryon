package com.queryon.web.config;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.common.error.StructuredError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一结构化错误响应：含失败原因与可操作提示，不泄露问句、提示词或凭据。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(QueryonException.class)
    public ResponseEntity<StructuredError> handle(QueryonException exception) {
        StructuredError error = exception.error();
        HttpStatus status;
        if (ErrorCode.UNAUTHENTICATED.code().equals(error.code())) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (ErrorCode.PERMISSION_DENIED.code().equals(error.code())) {
            status = HttpStatus.FORBIDDEN;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(error);
    }

    /** 请求体不是合法 JSON 或缺失时，返回与其他入口一致的结构化错误，便于定位调用方配置问题。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<StructuredError> handleUnreadable(HttpMessageNotReadableException exception) {
        QueryonException wrapped = new QueryonException(ErrorCode.PARSE_GATE_REJECT, "解析门",
                "请求体无法解析为合法 JSON",
                "请确认 Content-Type 为 application/json，且 body 为未转义的原始 JSON（图形化工具中不要保留命令行里的反斜杠）");
        return ResponseEntity.badRequest().body(wrapped.error());
    }
}
