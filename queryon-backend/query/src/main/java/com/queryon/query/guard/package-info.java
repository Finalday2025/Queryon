/**
 * 四道护栏：解析门 → Schema 门 → 语义门 → 执行门。
 * 顺序执行、任一不过即终止并返回结构化错误；
 * 语句类型判断须解析成 AST 后按白名单校验，严禁用正则。
 */
package com.queryon.query.guard;
