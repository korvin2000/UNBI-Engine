package com.unbi.engine.llm.auth;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;

/**
 * A credential source holding names a test declares, and a fake token behind each.
 *
 * <p>For the probes whose first act is "is the key the engine needs actually here?" — a check that
 * must run, and that an empty store would fail before any of the behaviour under test happened.
 * Values are nonsense on purpose: nothing in these tests reaches a socket.
 */
public final class NamedCredentials implements CredentialSource {

    private final SequencedSet<String> names;

    public NamedCredentials(String... names) {
        this.names = new LinkedHashSet<>(List.of(names));
    }

    @Override
    public String id() {
        return "test";
    }

    @Override
    public Optional<Credential> find(String ref) {
        return names.contains(ref) ? Optional.of(Credential.bearer(ref, "not-a-real-key")) : Optional.empty();
    }

    @Override
    public SequencedSet<String> names() {
        return new LinkedHashSet<>(names);
    }
}
