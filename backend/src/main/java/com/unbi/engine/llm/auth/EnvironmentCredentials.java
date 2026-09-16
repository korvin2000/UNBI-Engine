package com.unbi.engine.llm.auth;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Credentials taken from the environment.
 *
 * <p>The first place to look, because it is the one that survives a container restart without a file
 * and the one CI already has. A reference {@code openrouter} is read from
 * {@code UNBI_LLM_KEY_OPENROUTER}.
 *
 * <p>A handful of conventional names are also accepted unprefixed ({@code OPENROUTER_API_KEY} and
 * friends), so a machine already set up for these gateways needs no new variables. The prefix wins
 * where both exist: an explicit variable should not be shadowed by a convention.
 */
@Component
public class EnvironmentCredentials implements CredentialSource {

    public static final String PREFIX = "UNBI_LLM_KEY_";

    /** Conventional variables, so an existing shell works unchanged. Ref name to variable. */
    private static final Map<String, String> WELL_KNOWN = Map.of(
            "openrouter", "OPENROUTER_API_KEY",
            "openai", "OPENAI_API_KEY",
            "omniroute", "OMNIROUTE_API_KEY");

    private final Map<String, String> environment;

    public EnvironmentCredentials() {
        this(System.getenv());
    }

    /** Testable seam: the environment is the one dependency that cannot be injected otherwise. */
    public EnvironmentCredentials(Map<String, String> environment) {
        // Case-insensitive because Windows environment variables are, and a lookup that works in
        // one shell and not the other is the least debuggable kind of failure.
        var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(environment);
        this.environment = copy;
    }

    @Override
    public String id() {
        return "environment";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Credential> find(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        var normalised = ref.trim();
        return Optional.ofNullable(environment.get(PREFIX + variableName(normalised)))
                .or(() -> Optional.ofNullable(WELL_KNOWN.get(normalised.toLowerCase(Locale.ROOT)))
                        .map(environment::get))
                .filter(value -> !value.isBlank())
                .map(value -> Credential.bearer(normalised, value.trim()));
    }

    @Override
    public SequencedSet<String> names() {
        var found = new LinkedHashSet<String>();
        environment.forEach((key, value) -> {
            if (key.toUpperCase(Locale.ROOT).startsWith(PREFIX) && !value.isBlank()) {
                found.add(key.substring(PREFIX.length()).toLowerCase(Locale.ROOT));
            }
        });
        WELL_KNOWN.forEach((ref, variable) -> {
            var value = environment.get(variable);
            if (value != null && !value.isBlank()) {
                found.add(ref);
            }
        });
        return found;
    }

    /** {@code my-key} and {@code my.key} both live in {@code UNBI_LLM_KEY_MY_KEY}. */
    private static String variableName(String ref) {
        return ref.replace('-', '_').replace('.', '_').toUpperCase(Locale.ROOT);
    }
}
