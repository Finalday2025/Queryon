package com.queryon.web.dto;

/** 保存数据集：复用统一查询请求，由服务端执行并缓存结果，不接受客户端提交的结果集。 */
public record DatasetSaveRequest(String name, QueryRequest query) {
}
