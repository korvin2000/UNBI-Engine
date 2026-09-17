package com.unbi.engine.llm.auth;

import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.LlmFailure;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * The one place a secret is looked up, from every source the deployment has.
 *
 * <p>Sources are injected and ordered, so adding a way to authenticate is adding a bean — the same
 * plugin mechanism the node registry uses, for the same reason.
 *
 * <p>The store deals in names in public and values in private: {@link #names()} is what the editor
 * is allowed to see, while {@link #session} owns request-time resolution and recovery.
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

    /** Starts one request-scoped session with stable source ownership. */
    public Session session(EndpointSpec endpoint, Duration timeout, BooleanSupplier cancelled) {
        return new Session(endpoint, timeout, cancelled);
    }

    public Credential require(String ref) {
        return find(ref).orElseThrow(() -> missing(ref));
    }

    /** Every reference currently advertised by the configured sources, with source ownership. */
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

    private LlmFailure missing(String ref) {
        return new LlmFailure(
                LlmFailure.Kind.AUTH,
                "No credential named '%s'. Looked in: %s.".formatted(ref, describeSearchPath()));
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

    public final class Session {
        private final EndpointSpec endpoint;
        private final Duration timeout;
        private final BooleanSupplier cancelled;
        private final String ref;
        private final CredentialSource owner;
        private boolean recovered;
        private Session(EndpointSpec endpoint, Duration timeout, BooleanSupplier cancelled) {
            this.endpoint = endpoint;
            this.timeout = timeout == null ? Duration.ZERO : timeout;
            this.cancelled = cancelled == null ? () -> false : cancelled;
            if (endpoint.authScheme() == EndpointSpec.AuthScheme.NONE) {
                this.ref = "";
                this.owner = null;
            } else {
                this.ref = endpoint.credentialRef();
                this.owner = ownerFor(ref);
            }
        }

        public Credential resolve() {
            if (owner == null) {
                if (endpoint.authScheme() == EndpointSpec.AuthScheme.NONE) {
                    return null;
                }
                throw missing(ref);
            }
            owner.validate(ref, endpoint);
            return owner.resolve(ref, resource(), timeout, cancelled)
                    .filter(value -> !value.isEmpty())
                    .orElseThrow(() -> missing(ref));
        }

        /**
         * Refreshes at most once after a resource HTTP 401. Source ownership never changes.
        */
        public boolean recover(LlmFailure failure, Credential rejected) {
            if (owner == null || rejected == null || recovered || failure == null || failure.status() != 401) {
                return false;
            }
            recovered = true;
            owner.validate(ref, endpoint);
            return owner.refresh(ref, rejected, resource(), timeout, cancelled)
                    .filter(value -> !value.isEmpty())
                    .isPresent();
        }

        private URI resource() {
            return URI.create(endpoint.baseUrl());
        }
    }

    /** Local ownership inspection, including static aliases and disconnected managed records. */
    public boolean contains(String ref) {
        return ownerFor(ref) != null;
    }

    private CredentialSource ownerFor(String ref) {
        if (ref == null || ref.isBlank()) return null;
        for (var source : sources) {
            // Names claim disconnected managed records; find preserves aliases whose advertised
            // names are canonicalized (for example environment variables and Codex).
            if (source.names().contains(ref)
                    || source.find(ref).filter(value -> !value.isEmpty()).isPresent()) {
                return source;
            }
        }
        return null;
    }
}
