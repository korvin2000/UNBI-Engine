package com.unbi.engine.llm.auth;

import com.unbi.engine.llm.spec.EndpointSpec;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.function.BooleanSupplier;

/**
 * Somewhere credentials come from.
 *
 * <p>The extension seam for authentication. Adding OAuth — a device-code flow, PKCE, a token cached
 * by another tool — is one more bean implementing this interface, and no node, provider or workflow
 * changes. That separation is deliberate: <em>how a token is obtained</em> and <em>what an
 * integration lets you do with it</em> are different questions, and only the first one is this
 * interface's business.
 */
public interface CredentialSource {

    /** Lower runs first, so a more specific source can shadow a general one. */
    default int order() {
        return 100;
    }

    /** A short name shown beside a credential in the editor, e.g. {@code environment}. */
    String id();

    Optional<Credential> find(String ref);

    /**
     * Obtains a credential for one request.
     *
     * <p>Static sources deliberately retain their local lookup behavior. Renewable sources override
     * this method so resolution can renew an expired session without making editor inspection perform
     * network I/O.
     */
    default Optional<Credential> resolve(String ref, URI resource, Duration timeout, BooleanSupplier cancelled) {
        return find(ref);
    }

    /** Attempts one renewal after the resource rejected a credential. Static sources do not renew. */
    default Optional<Credential> refresh(
            String ref, Credential rejected, URI resource, Duration timeout, BooleanSupplier cancelled) {
        return Optional.empty();
    }

    /** Rejects a credential type that cannot authenticate the endpoint before any secret is sent. */
    default void validate(String ref, EndpointSpec endpoint) {}

    /**
     * The references this source can currently answer to.
     *
     * <p>Names only — this is what the editor's dropdown is built from, and no value ever leaves the
     * engine. Ordered so the list does not reshuffle between reads.
     */
    SequencedSet<String> names();
}
