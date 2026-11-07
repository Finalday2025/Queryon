package com.queryon.web.dto;

/** @param question 自然语言问句 @param sessionId 多轮会话标识 */
public record AskRequest(String question, String sessionId) {
}
