package com.queryon.common.error;

/**
 * 结构化错误码。护栏四道门与澄清、权限、模型降级共用同一套返回格式。
 */
public enum ErrorCode {

    PARSE_GATE_REJECT("QON-1001", "解析门拦截"),
    WRITE_OPERATION_REJECT("QON-1002", "写操作拒绝"),
    SCHEMA_GATE_REJECT("QON-1003", "Schema 门拦截"),
    SEMANTIC_GATE_REJECT("QON-1004", "语义门拦截"),
    EXECUTION_GATE_REJECT("QON-1005", "执行门拦截"),
    CLARIFICATION_REQUIRED("QON-2001", "需要澄清"),
    UNKNOWN_TERM("QON-2002", "术语或指标无认证口径"),
    UNKNOWN_FIELD("QON-2003", "字段不存在"),
    PERMISSION_DENIED("QON-3001", "权限不足"),
    UNAUTHENTICATED("QON-3002", "身份未识别"),
    MODEL_UNAVAILABLE("QON-4001", "模型端点不可用"),
    INTERNAL_ERROR("QON-5000", "内部错误");

    private final String code;
    private final String title;

    ErrorCode(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }
}
