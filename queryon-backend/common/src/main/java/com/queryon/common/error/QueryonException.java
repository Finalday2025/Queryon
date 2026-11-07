package com.queryon.common.error;

/**
 * 业务异常。只携带结构化错误，不携带原始问句、提示词或任何凭据。
 */
public class QueryonException extends RuntimeException {

    private final StructuredError error;

    public QueryonException(StructuredError error) {
        super(error.code() + " " + error.title() + "：" + error.reason());
        this.error = error;
    }

    public QueryonException(ErrorCode errorCode, String gate, String reason, String hint) {
        this(StructuredError.of(errorCode, gate, reason, hint));
    }

    public StructuredError error() {
        return error;
    }
}
