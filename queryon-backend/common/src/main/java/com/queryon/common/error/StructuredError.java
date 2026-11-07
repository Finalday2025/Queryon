package com.queryon.common.error;

/**
 * 统一结构化错误：含失败原因与可操作提示，供用户澄清或系统改写。
 *
 * @param code   错误码
 * @param gate   拦截发生的门或阶段（解析门／Schema 门／语义门／执行门／权限注入等）
 * @param reason 失败原因
 * @param hint   可操作提示
 */
public record StructuredError(String code, String gate, String reason, String hint) {

    public static StructuredError of(ErrorCode errorCode, String gate, String reason, String hint) {
        return new StructuredError(errorCode.code(), gate, reason, hint);
    }

    public String title() {
        for (ErrorCode value : ErrorCode.values()) {
            if (value.code().equals(code)) {
                return value.title();
            }
        }
        return code;
    }
}
