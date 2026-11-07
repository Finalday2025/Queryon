package com.queryon.query.guard;

import com.queryon.semantic.SemanticRegistry;

import java.util.List;

/** 四道护栏顺序执行：解析门 → Schema 门 → 语义门 → 执行门；任一不过即终止。 */
public final class GuardChain {

    private final List<Gate> gates;

    public GuardChain(SemanticRegistry registry) {
        this.gates = List.of(
                new ParseGate(),
                new SchemaGate(registry),
                new SemanticGate(registry),
                new ExecutionGate());
    }

    public void check(GuardContext context) {
        for (Gate gate : gates) {
            gate.check(context);
            context.trace().record("护栏", gate.name() + "：通过");
        }
    }
}
