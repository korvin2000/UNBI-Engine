package com.unbi.engine.llm.provider;

import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.LlmFailure;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Which provider speaks which dialect.
 *
 * <p>Populated by dependency injection, the same mechanism the node registry uses: a new transport
 * is a bean, and nothing here changes. A second provider claiming a dialect already taken fails at
 * startup rather than resolving arbitrarily, because "which one answered?" must never be a question.
 */
@Component
public class ProviderRegistry {

    private final Map<ApiFormat, LlmProvider> byFormat = new EnumMap<>(ApiFormat.class);

    public ProviderRegistry(List<LlmProvider> providers) {
        for (var provider : providers) {
            var previous = byFormat.put(provider.format(), provider);
            if (previous != null) {
                throw new IllegalStateException("Two providers claim %s: %s and %s".formatted(
                        provider.format(), previous.getClass().getName(), provider.getClass().getName()));
            }
        }
    }

    public LlmProvider forFormat(ApiFormat format) {
        var provider = byFormat.get(format);
        if (provider == null) {
            throw new LlmFailure(
                    LlmFailure.Kind.UNSUPPORTED, "No provider is registered for " + format.wireName());
        }
        return provider;
    }
}
