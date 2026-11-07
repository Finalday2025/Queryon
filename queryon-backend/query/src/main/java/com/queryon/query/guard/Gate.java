package com.queryon.query.guard;

/** 护栏门契约：任一不过即抛结构化错误，链路立即终止。 */
public interface Gate {

    String name();

    void check(GuardContext context);
}
