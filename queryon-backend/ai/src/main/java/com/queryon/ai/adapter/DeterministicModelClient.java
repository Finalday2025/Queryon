package com.queryon.ai.adapter;

/**
 * 默认离线实现：高频确定性问题走规则路径，模型路径不臆测、不编造语义查询。
 * 模型端点未启用或降级时返回空结果，由解析器触发澄清（G3）。
 */
public final class DeterministicModelClient implements ChatModelPort {

    @Override
    public ModelCompletion complete(ModelRequest request) {
        return new ModelCompletion("", false);
    }

    @Override
    public boolean available() {
        return true;
    }
}
