/**
 * 模型端点与数据源适配：经 ChatModel／ChatClient 抽象隔离供应商，支持 BYOK 与替换。
 * 密钥只以环境变量名引用，默认不记录 prompt 与 completion 内容。
 */
package com.queryon.ai.adapter;
