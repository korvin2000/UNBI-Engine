package com.unbi.engine.llm.auth;

import java.util.Optional;
import java.util.SequencedSet;

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
     * The references this source can currently answer to.
     *
     * <p>Names only — this is what the editor's dropdown is built from, and no value ever leaves the
     * engine. Ordered so the list does not reshuffle between reads.
     */
    SequencedSet<String> names();
}
