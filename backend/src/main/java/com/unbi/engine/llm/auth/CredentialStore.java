package com.unbi.engine.llm.auth;

import com.unbi.engine.llm.spec.LlmFailure;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;
import org.springframework.stereotype.Component;

/**
 * The one place a secret is looked up, from every source the deployment has.
 *
 * <p>Sources are injected and ordered, so adding a way to authenticate is adding a bean — the same
 * plugin mechanism the node registry uses, for the same reason.
 *
 * <p>The store deals in names in public and values in private: {@link #names()} is what the editor
 * is allowed to see, {@link #require} is what a provider calls with a request already in hand.
 */
@Component
public class CredentialStore {

    private final List<CredentialSource> sources;

    public CredentialStore(List<CredentialSource> sources) {
        this.sources = sources.stream()
                .sorted(Comparator.comparingInt(CredentialSource::order).thenComparing(CredentialSource::id))
                .toList();
    }

    public Optional<Credential> find(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        for (var source : sources) {
            var found = source.find(ref);
            if (found.isPresent() && !found.get().isEmpty()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * @throws LlmFailure naming the reference and every place that was searched — the one error in
     *     this subsystem that a user can always act on, provided it says where to put the key
     */
    public Credential require(String ref) {
        return find(ref).orElseThrow(() -> new LlmFailure(
                LlmFailure.Kind.AUTH,
                "No credential named '%s'. Looked in: %s.".formatted(ref, describeSearchPath())));
    }

    /** Every reference currently resolvable, with the source that answers for it. Names only. */
    public SequencedMap<String, String> catalog() {
        var found = new LinkedHashMap<String, String>();
        for (var source : sources) {
            for (var name : source.names()) {
                found.putIfAbsent(name, source.id());
            }
        }
        return found;
    }

    public SequencedSet<String> names() {
        return new LinkedHashSet<>(catalog().keySet());
    }

    private String describeSearchPath() {
        var described = new java.util.ArrayList<String>();
        for (var source : sources) {
            described.add(switch (source) {
                case EnvironmentCredentials ignored ->
                        "environment (" + EnvironmentCredentials.PREFIX + "<NAME>)";
                case PropertiesFileCredentials file -> String.valueOf(file.location());
                case CodexCredentials codex -> codex.describeLocation();
                default -> source.id();
            });
        }
        return String.join(", ", described);
    }
}
