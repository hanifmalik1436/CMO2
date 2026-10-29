package com.cmo2.ai;

/**
 * Which customer (and, inside a Strange Loop cycle, which iteration and stage) the AI calls on
 * this thread are for. {@link AiService} uses it to record each call against the customer and to
 * apply the customer's own token ceiling. Always opened with try-with-resources, so a pooled
 * thread never keeps a stale scope; nested scopes restore the outer one on close.
 */
public final class AiCallScope implements AutoCloseable {

    public record Scope(Long companyId, Long iterationId, String stage) {
        static final Scope NONE = new Scope(null, null, null);
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private final Scope previous;

    private AiCallScope(Scope scope) {
        this.previous = CURRENT.get();
        CURRENT.set(scope);
    }

    public static AiCallScope forCompany(Long companyId) {
        return new AiCallScope(new Scope(companyId, null, null));
    }

    public static AiCallScope forStage(Long companyId, Long iterationId, String stage) {
        return new AiCallScope(new Scope(companyId, iterationId, stage));
    }

    public static Scope current() {
        Scope s = CURRENT.get();
        return s == null ? Scope.NONE : s;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
