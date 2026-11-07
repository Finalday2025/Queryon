package com.queryon.ai.adapter;

import java.util.List;

/**
 * 模型调用端口：把模型供应商与业务逻辑解耦，支持 BYOK 与供应商替换（03 决策六）。
 * 后续按未决项触发结果接入 Spring AI 1.1.x 的 ChatModel／ChatClient 适配器；
 * 当前只提供离线确定性实现，不发起任何真实模型调用。
 *
 * <p>约定：默认不记录 prompt 与 completion 内容；密钥只以环境变量名出现。
 *
 * @param task       任务标识
 * @param candidates 语义层召回的候选集（不含原始问句）
 */
public interface ChatModelPort {

    record ModelRequest(String task, List<String> candidates) {
    }

    /** @param fromModel 是否来自外部模型；离线降级时为 false */
    record ModelCompletion(String content, boolean fromModel) {
    }

    ModelCompletion complete(ModelRequest request);

    boolean available();
}
