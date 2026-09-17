package com.unbi.engine.llm.auth;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.llm.spec.LlmFailure;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Engine-owned, renewable credentials.  This is deliberately separate from ordinary API-key
 * sources: records have an explicit resource binding and a private, atomically published session.
 * Nothing in this class turns an entry into a public DTO.
 */
@Component
public final class ManagedCredentialStore implements AutoCloseable {

    private static final String DIRECTORY = "credentials";
    private static final String EXTENSION = ".json";
    private static final String LOCK_SUFFIX = ".lock";
    private static final Duration MAX_RENEWAL = Duration.ofSeconds(30);
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(1);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final DataDirectory data;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final ExecutorService workers;
    private final java.util.concurrent.ScheduledExecutorService deadlines =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("credential-deadlines").factory());
    private final ConcurrentHashMap<String, Object> nameLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RenewalState> renewals = new ConcurrentHashMap<>();

    @Autowired
    public ManagedCredentialStore(DataDirectory data) {
        this(data, Clock.systemUTC());
    }

    /** Test seam for deterministic renewal cooldowns. */
    public ManagedCredentialStore(DataDirectory data, Clock clock) {
        this(data, clock, new ObjectMapper(), Executors.newVirtualThreadPerTaskExecutor());
    }

    ManagedCredentialStore(DataDirectory data, Clock clock, ObjectMapper mapper, ExecutorService workers) {
        this.data = java.util.Objects.requireNonNull(data, "data");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.workers = java.util.Objects.requireNonNull(workers, "workers");
    }

    public enum Type {
        BASIC,
        OAUTH2,
        CODEX
    }

    /**
     * An engine-private snapshot. Accessors return copies so a caller cannot mutate the store's
     * understanding of a definition or a session after it has been read.
     */
    public record Entry(String name, Type type, String revision, ObjectNode configuration, ObjectNode session) {
        public Entry {
            ManagedCredentialStore.validateName(name);
            type = java.util.Objects.requireNonNull(type, "type");
            try {
                UUID.fromString(revision);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid managed credential revision");
            }
            configuration = copyObject(configuration, "configuration");
            session = copyObject(session, "session");
        }

        @Override
        public ObjectNode configuration() {
            return configuration.deepCopy();
        }

        @Override
        public ObjectNode session() {
            return session.deepCopy();
        }

        @Override
        public String toString() {
            return "ManagedCredential[name=%s, type=%s, revision=%s, configuration=<redacted>, session=<redacted>]"
                    .formatted(name, type, revision);
        }
    }

    @FunctionalInterface
    public interface Renewal {
        /** Returns the complete replacement session; the callback must not mutate {@code current}. */
        ObjectNode renew(Entry current, Path capturedRoot) throws Exception;
    }

    public static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,60}")) {
            throw new IllegalArgumentException("Invalid credential name");
        }
    }

    public static java.util.Set<String> configurationFields(Type type) {
        return switch (type) {
            case BASIC -> java.util.Set.of("resourceBaseUrl", "username", "password");
            case CODEX -> java.util.Set.of("resourceBaseUrl");
            case OAUTH2 -> java.util.Set.of("resourceBaseUrl", "grantType", "clientId", "clientSecret",
                    "clientAuthentication", "issuer", "metadataUrl", "authorizationUrl", "tokenUrl",
                    "refreshUrl", "deviceAuthorizationUrl", "scopes");
        };
    }
    /** Public URL policy shared by explicit metadata discovery and token clients. */
    public static java.net.URI oauthEndpoint(String url) {
        try {
            var uri = java.net.URI.create(url);
            if (uri.getRawFragment() != null) throw new IllegalArgumentException();
            resourceUri(uri.getScheme() + "://" + uri.getRawAuthority()
                    + (uri.getRawPath() == null ? "" : uri.getRawPath()), Type.OAUTH2, "OAuth endpoint");
            return uri;
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Invalid OAuth endpoint; use HTTPS or a loopback development server");
        }
    }


    /** Ensures a credential is used only at its configured origin and base path. */
    public static void validateResource(Entry entry, java.net.URI resource) {
        if (entry == null || resource == null) {
            throw new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential has no permitted resource");
        }
        var configured = entry.configuration().path("resourceBaseUrl").asString("");
        var base = resourceUri(configured, entry.type(), "resource base URL");
        var actual = resourceUri(resource, entry.type(), "resource URL");
        if (!sameOrigin(base, actual) || !withinBasePath(base.getPath(), actual.getPath())) {
            throw new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential is not permitted for this resource");
        }
    }

    /** Includes malformed owned files so another source cannot silently answer the same reference. */
    public SequencedSet<String> names() {
        try (var lease = data.acquireLease()) {
            var directory = credentialDirectory(lease.root());
            var names = new LinkedHashSet<String>();
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return names;
            }
            SecretFiles.rejectSymlinks(directory);
            try (var entries = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
                for (var path : entries) {
                    var fileName = path.getFileName().toString();
                    var name = fileName.substring(0, fileName.length() - EXTENSION.length());
                    if (isValidName(name)) names.add(name);
                }
            }
            return names;
        } catch (IOException inaccessible) {
            throw new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential storage is unavailable");
        }
    }

    /**
     * Finds an owned record without network activity. A malformed record is an owned-but-unusable
     * credential, and therefore fails rather than returning empty and allowing source fallback.
     */
    public Optional<Entry> find(String name) {
        validateName(name);
        try (var lease = data.acquireLease()) {
            return read(lease.root(), name);
        } catch (IOException inaccessible) {
            throw unavailable(name);
        }
    }

    public Entry create(String name, Type type, ObjectNode configuration) {
        validateName(name);
        validateConfiguration(type, configuration);
        return mutate(name, root -> {
            if (Files.exists(record(root, name), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("A managed credential already uses that name");
            }
            var created = new Entry(name, type, UUID.randomUUID().toString(), configuration, NODES.objectNode());
            write(root, created);
            return created;
        });
    }

    /** Replacing a definition invalidates any rotating session, including an unchanged-looking edit. */
    public Entry configure(String name, ObjectNode configuration) {
        validateName(name);
        return mutate(name, root -> {
            var previous = required(root, name);
            return replaceLocked(root, previous, previous.type(), configuration);
        });
    }

    /**
     * Atomically changes a definition's type and configuration. The old record remains until the
     * replacement has been durably written, so a failed replacement cannot erase a usable entry.
     */
    public Entry replace(String name, Type type, ObjectNode configuration) {
        validateName(name);
        validateConfiguration(type, configuration);
        return mutate(name, root -> {
            var previous = required(root, name);
            return replaceLocked(root, previous, type, configuration);
        });
    }

    private Entry replaceLocked(Path root, Entry previous, Type type, ObjectNode configuration) throws IOException {
        validateConfiguration(type, configuration);
        var replacement = new Entry(previous.name(), type, UUID.randomUUID().toString(), configuration,
                NODES.objectNode().put("status", "reauth_required"));
        write(root, replacement);
        renewals.remove(previous.name());
        return replacement;
    }

    /**
     * Commits a login result only if the definition revision is unchanged. Unlike renewal, a changed
     * snapshot is never adopted: logout/configuration races must force the login worker to discard it.
     */
    public Entry commitSession(String name, String expectedRevision, ObjectNode session) {
        validateName(name);
        if (expectedRevision == null || expectedRevision.isBlank()) {
            throw new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential changed; reconnect it");
        }
        if (session == null || !session.isObject()) {
            throw new IllegalArgumentException("Managed credential session must be an object");
        }
        return mutate(name, root -> {
            var current = required(root, name);
            if (!current.revision().equals(expectedRevision)) {
                throw new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential changed; reconnect it");
            }
            var committed = new Entry(name, current.type(), UUID.randomUUID().toString(),
                    current.configuration(), session);
            write(root, committed);
            renewals.remove(name);
            return committed;
        });
    }

    /** Clears session state while retaining the connection definition for an explicit reconnect. */
    public Entry logout(String name) {
        validateName(name);
        return mutate(name, root -> {
            var previous = required(root, name);
            var loggedOut = new Entry(name, previous.type(), UUID.randomUUID().toString(), previous.configuration(),
                    NODES.objectNode().put("status", "reauth_required"));
            write(root, loggedOut);
            renewals.remove(name);
            return loggedOut;
        });
    }

    /** Removes an owned record and any legacy engine home left by pre-native versions. */
    public boolean remove(String name) {
        validateName(name);
        return mutate(name, root -> {
            var target = record(root, name);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
            SecretFiles.rejectSymlinks(target);
            deleteLegacyCodexHome(root, name);
            Files.delete(target);
            forceDirectory(target.getParent());
            renewals.remove(name);
            return true;
        });
    }

    private static void deleteLegacyCodexHome(Path root, String name) throws IOException {
        var normalizedRoot = root.toAbsolutePath().normalize();
        var credentials = normalizedRoot.resolve(DIRECTORY).normalize();
        if (!credentials.startsWith(normalizedRoot)) throw new IOException("Credential path escaped data root");
        SecretFiles.rejectSymlinks(credentials);
        var codex = credentials.resolve("codex").normalize();
        if (!codex.startsWith(credentials)) throw new IOException("Credential path escaped credential root");
        SecretFiles.rejectSymlinks(codex);
        var home = codex.resolve(name).normalize();
        if (!home.startsWith(codex) || !Files.exists(home, LinkOption.NOFOLLOW_LINKS)) return;
        SecretFiles.rejectSymlinks(home);
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Legacy Codex home is not a directory");
        try (var paths = Files.walk(home)) {
            var all = paths.sorted(java.util.Comparator.reverseOrder()).toList();
            for (var path : all) {
                if (Files.isSymbolicLink(path)) throw new IOException("Legacy Codex home contains a symbolic link");
            }
            for (var path : all) Files.deleteIfExists(path);
        }
    }

    /**
     * Runs one token/session renewal per name. The worker owns the data lease, name lock and file
     * lock through re-read, callback and durable replacement. A cancelled waiter merely stops
     * waiting; it never interrupts a renewal other callers rely on.
     */
    public Entry renew(Entry expected, Duration timeout, BooleanSupplier cancelled, Renewal operation) {
        java.util.Objects.requireNonNull(expected, "expected");
        var name = expected.name();
        var wait = renewalTimeout(timeout);
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        if (stop.getAsBoolean()) throw cancelled();
        var state = renewal(name, expected, wait, java.util.Objects.requireNonNull(operation, "operation"));
        return await(state.future, wait, stop, name);
    }

    private RenewalState renewal(String name, Entry expected, Duration timeout, Renewal operation) {
        synchronized (renewals) {
            var current = renewals.get(name);
            if (current != null && current.failure != null && clock.millis() < current.retryAtMillis) {
                return current;
            }
            if (current != null && !current.future.isDone()) return current;
            var started = new RenewalState();
            renewals.put(name, started);
            workers.execute(() -> renewWorker(name, expected, timeout, operation, started));
            return started;
        }
    }

    private void renewWorker(String name, Entry expected, Duration timeout, Renewal operation, RenewalState state) {
        var deadline = System.nanoTime() + timeout.toNanos();
        var worker = Thread.currentThread();
        var alarm = deadlines.schedule(worker::interrupt, timeout.toNanos(), TimeUnit.NANOSECONDS);
        try (var lease = data.acquireLease()) {
            var renewed = withLocked(lease.root(), name, deadline, root -> {
                var current = required(root, name);
                if (!current.revision().equals(expected.revision())) {
                    if (current.type() == expected.type() && current.configuration().equals(expected.configuration())
                            && !current.session().isEmpty() && !"reauth_required".equals(current.session().path("status").asString())) {
                        return current;
                    }
                    throw new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential changed; reconnect it");
                }
                var replacement = callback(operation, current, lease.root());
                if (replacement == null) {
                    throw new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential renewal did not return a session");
                }
                var updated = new Entry(name, current.type(), UUID.randomUUID().toString(), current.configuration(), replacement);
                write(root, updated);
                return updated;
            });
            state.future.complete(renewed);
            synchronized (renewals) {
                renewals.remove(name, state);
            }
        } catch (Throwable failure) {
            var safe = renewalFailure(failure);
            state.failure = safe;
            state.retryAtMillis = clock.millis() + Math.max(FAILURE_COOLDOWN.toMillis(), safe.retryAfterMillis());
            state.future.completeExceptionally(safe);
        } finally {
            alarm.cancel(false);
        }
    }

    private ObjectNode callback(Renewal operation, Entry current, Path root) {
        try {
            // The mutation owner executes the callback itself. Even a misbehaving callback cannot
            // outlive its credential/file/data leases and write into a relocated or logged-out home.
            return operation.renew(current, root);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmFailure(LlmFailure.Kind.TIMEOUT, "Managed credential renewal timed out");
        } catch (Exception failed) {
            throw renewalFailure(failed);
        }
    }

    /** Runs a user-initiated native authentication ceremony while holding mutation locks. */
    public Entry establish(Entry expected, boolean create, Duration timeout, BooleanSupplier cancelled, Renewal operation) {
        validateConfiguration(expected.type(), expected.configuration());
        var limit = timeout.compareTo(Duration.ofMinutes(10)) > 0 ? Duration.ofMinutes(10) : timeout;
        var deadline = System.nanoTime() + limit.toNanos();
        var worker = Thread.currentThread();
        var alarm = deadlines.schedule(worker::interrupt, limit.toNanos(), TimeUnit.NANOSECONDS);
        try (var lease = data.acquireLease()) {
            return withLocked(lease.root(), expected.name(), deadline, root -> {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancelled();
                var existing = read(root, expected.name());
                if (create ? existing.isPresent() : existing.isEmpty()
                        || (!create && !existing.orElseThrow().revision().equals(expected.revision())))
                    throw new LlmFailure(LlmFailure.Kind.AUTH, "Credential changed during sign-in; reconnect it");
                var session = operation.renew(expected, root);
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancelled();
                var committed = new Entry(expected.name(), expected.type(), UUID.randomUUID().toString(),
                        expected.configuration(), session);
                write(root, committed);
                renewals.remove(expected.name());
                return committed;
            });
        } catch (LlmFailure | IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw renewalFailure(failure);
        } finally { alarm.cancel(false); }
    }

    private <T> T mutate(String name, LockedWork<T> operation) {
        try (var lease = data.acquireLease()) {
            return withLocked(lease.root(), name, operation);
        } catch (LlmFailure | IllegalArgumentException expected) {
            throw expected;
        } catch (Exception inaccessible) {
            throw unavailable(name);
        }

    }
    private <T> T withLocked(Path root, String name, LockedWork<T> operation) throws Exception {
        return withLocked(root, name, System.nanoTime() + MAX_RENEWAL.toNanos(), operation);
    }

    private <T> T withLocked(Path root, String name, long deadline, LockedWork<T> operation) throws Exception {
        var mutex = nameLocks.computeIfAbsent(name, ignored -> new Object());
        synchronized (mutex) {
            var directory = credentialDirectory(root);
            SecretFiles.directory(directory);
            var lock = lockFile(directory, name);
            try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock held = null;
                while (held == null) {
                    if (System.nanoTime() >= deadline) {
                        throw new LlmFailure(LlmFailure.Kind.TIMEOUT, "Managed credential renewal timed out");
                    }
                    try {
                        held = channel.tryLock();
                    } catch (java.nio.channels.OverlappingFileLockException busy) {
                        // Another ManagedCredentialStore in this JVM owns the same file lock.
                    }
                    if (held == null) {
                        var millis = Math.min(100L, Math.max(1L,
                                TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                        Thread.sleep(millis);
                    }
                }
                var acquired = held;
                try (acquired) {
                    return operation.run(root);
                }
            }
        }
    }

    private Optional<Entry> read(Path root, String name) throws IOException {
        var target = record(root, name);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            var json = mapper.readTree(SecretFiles.read(target));
            return Optional.of(envelope(json, name));
        } catch (IOException | RuntimeException malformed) {
            throw new IOException("Managed credential is unreadable");
        }
    }

    private Entry required(Path root, String name) throws IOException {
        return read(root, name).orElseThrow(() -> unavailable(name));
    }

    private Entry envelope(JsonNode root, String fileName) {
        if (root == null || !root.isObject() || root.path("version").asInt(-1) != 1
                || !fileName.equals(root.path("name").asString()) || !root.path("configuration").isObject()
                || !root.path("session").isObject()) {
            throw new IllegalArgumentException("Invalid managed credential record");
        }
        var type = type(root.path("type").asString());
        var entry = new Entry(fileName, type, root.path("revision").asString(),
                (ObjectNode) root.path("configuration"), (ObjectNode) root.path("session"));
        validateConfiguration(type, entry.configuration());
        return entry;
    }

    private void write(Path root, Entry entry) throws IOException {
        var document = mapper.createObjectNode();
        document.put("version", 1);
        document.put("name", entry.name());
        document.put("type", entry.type().name().toLowerCase(Locale.ROOT));
        document.put("revision", entry.revision());
        document.set("configuration", entry.configuration());
        document.set("session", entry.session());
        SecretFiles.write(record(root, entry.name()), mapper.writeValueAsBytes(document));
    }

    private static Path credentialDirectory(Path root) throws IOException {
        var normalizedRoot = root.toAbsolutePath().normalize();
        SecretFiles.rejectSymlinks(normalizedRoot);
        var directory = normalizedRoot.resolve(DIRECTORY).normalize();
        if (!directory.startsWith(normalizedRoot)) throw new IOException("Credential path escaped data root");
        SecretFiles.rejectSymlinks(directory);
        return directory;
    }

    private static Path record(Path root, String name) throws IOException {
        var directory = credentialDirectory(root);
        var file = directory.resolve(name + EXTENSION).normalize();
        if (!file.startsWith(directory)) throw new IOException("Credential path escaped data root");
        return file;
    }

    private static Path lockFile(Path directory, String name) throws IOException {
        var lock = directory.resolve("." + name + LOCK_SUFFIX).normalize();
        if (!lock.startsWith(directory)) throw new IOException("Credential lock escaped data root");
        SecretFiles.rejectSymlinks(lock);
        if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (java.nio.file.FileAlreadyExistsException race) {
                // The competing engine process created the same zero-byte lock; verify it below.
            }
        }
        if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(lock).equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IOException("Credential lock requires owner-only permissions");
        }
        return lock;
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static ObjectNode copyObject(ObjectNode node, String field) {
        if (node == null) throw new IllegalArgumentException("Managed credential " + field + " must be an object");
        return node.deepCopy();
    }

    private static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,60}");
    }

    private static Type type(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "basic" -> Type.BASIC;
            case "oauth2" -> Type.OAUTH2;
            case "codex" -> Type.CODEX;
            default -> throw new IllegalArgumentException("Invalid managed credential type");
        };
    }

    private static void validateConfiguration(Type type, ObjectNode configuration) {
        if (type == null || configuration == null) throw new IllegalArgumentException("Managed credential configuration is required");
        for (var field : configuration.propertyNames()) {
            if (!configurationFields(type).contains(field))
                throw new IllegalArgumentException("Unknown managed credential configuration field");
        }
        resourceUri(configuration.path("resourceBaseUrl").asString(""), type, "resource base URL");
        switch (type) {
            case BASIC -> {
                if (requiredText(configuration, "username").contains(":"))
                    throw new IllegalArgumentException("A Basic username must not contain a colon");
                requiredText(configuration, "password");
            }
            case OAUTH2 -> validateOAuth(configuration);
            case CODEX -> { /* resourceBaseUrl is the complete Codex definition. */ }
        }
    }

    private static void validateOAuth(ObjectNode configuration) {
        var grant = configuration.path("grantType").asString("");
        if (!grant.equals("authorization_code") && !grant.equals("client_credentials")
                && !grant.equals("device_authorization")) {
            throw new IllegalArgumentException("Unsupported OAuth grant type");
        }
        requiredText(configuration, "clientId");
        oauthEndpoint(requiredText(configuration, "tokenUrl"));
        var authentication = configuration.path("clientAuthentication").asString("");
        if (!authentication.equals("none") && !authentication.equals("client_secret_basic")
                && !authentication.equals("client_secret_post")) {
            throw new IllegalArgumentException("Unsupported OAuth client authentication");
        }
        if (!authentication.equals("none")) requiredText(configuration, "clientSecret");
        if (grant.equals("authorization_code")) {
            oauthEndpoint(requiredText(configuration, "authorizationUrl"));
        }
        if (grant.equals("device_authorization")) {
            oauthEndpoint(requiredText(configuration, "deviceAuthorizationUrl"));
        }
        optionalUrl(configuration, "refreshUrl");
        optionalUrl(configuration, "metadataUrl");
        optionalUrl(configuration, "issuer");
        var scopes = configuration.path("scopes");
        if (!scopes.isMissingNode()) {
            if (!scopes.isArray()) throw new IllegalArgumentException("OAuth scopes must be an array");
            for (var scope : scopes) {
                if (!scope.isString() || scope.asString().isBlank()) {
                    throw new IllegalArgumentException("OAuth scopes must be nonblank strings");
                }
            }
        }
    }

    private static String requiredText(ObjectNode configuration, String field) {
        var value = configuration.path(field);
        if (!value.isString() || value.asString().isBlank() || value.asString().contains("\r") || value.asString().contains("\n"))
            throw new IllegalArgumentException("A managed credential requires a valid " + field);
        return value.asString();
    }

    private static void optionalUrl(ObjectNode configuration, String field) {
        if (configuration.hasNonNull(field) && !configuration.path(field).asString("").isBlank()) {
            if (field.equals("issuer")) resourceUri(requiredText(configuration, field), Type.OAUTH2, field);
            else oauthEndpoint(requiredText(configuration, field));
        }
    }

    private static java.net.URI resourceUri(String value, Type type, String label) {
        try {
            return resourceUri(java.net.URI.create(value), type, label);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    private static java.net.URI resourceUri(java.net.URI uri, Type type, String label) {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        var loopback = isLoopback(uri.getHost());
        if (!scheme.equals("https") && !(scheme.equals("http") && (type == Type.BASIC || loopback))) {
            throw new IllegalArgumentException("Managed OAuth and Codex resources require HTTPS except on loopback");
        }
        var path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        var normalizedPath = java.net.URI.create("http://placeholder" + path).normalize().getPath();
        if (normalizedPath == null || !normalizedPath.startsWith("/")) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return java.net.URI.create("%s://%s%s".formatted(
                scheme,
                hostPort(uri),
                normalizedPath.endsWith("/") && normalizedPath.length() > 1
                        ? normalizedPath.substring(0, normalizedPath.length() - 1)
                        : normalizedPath));
    }

    private static String hostPort(java.net.URI uri) {
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.contains(":")) host = "[" + host + "]";
        var port = uri.getPort();
        if (port < 0 || (uri.getScheme().equalsIgnoreCase("https") && port == 443)
                || (uri.getScheme().equalsIgnoreCase("http") && port == 80)) {
            return host;
        }
        return host + ":" + port;
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        if (host.equalsIgnoreCase("localhost") || host.equals("::1") || host.equals("[::1]")) return true;
        if (!host.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return false;
        for (var part : host.split("\\.")) if (Integer.parseInt(part) > 255) return false;
        return true;
    }

    private static boolean sameOrigin(java.net.URI left, java.net.URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost()) && left.getPort() == right.getPort();
    }

    private static boolean withinBasePath(String base, String candidate) {
        var normalizedBase = base == null || base.isBlank() ? "/" : base;
        var normalizedCandidate = candidate == null || candidate.isBlank() ? "/" : candidate;
        return normalizedBase.equals("/") || normalizedCandidate.equals(normalizedBase)
                || normalizedCandidate.startsWith(normalizedBase + "/");
    }

    private static Duration renewalTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) return MAX_RENEWAL;
        return timeout.compareTo(MAX_RENEWAL) > 0 ? MAX_RENEWAL : timeout;
    }

    private static Entry await(CompletableFuture<Entry> future, Duration timeout, BooleanSupplier cancelled, String name) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (cancelled.getAsBoolean()) throw cancelled();
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new LlmFailure(LlmFailure.Kind.TIMEOUT, "Managed credential renewal timed out");
            try {
                return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException pending) {
                // Poll cancellation independently from the shared worker.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw cancelled();
            } catch (ExecutionException failed) {
                throw renewalFailure(failed.getCause());
            }
        }
    }

    private static LlmFailure unavailable(String name) {
        return new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential '" + name + "' is unavailable; reconnect it");
    }

    private static LlmFailure cancelled() {
        return new LlmFailure(LlmFailure.Kind.CANCELLED, "Cancelled");
    }

    private static LlmFailure renewalFailure(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof LlmFailure known) {
            return new LlmFailure(known.kind(), "Managed credential renewal failed", "", known.status(), known.retryAfterMillis(), null);
        }
        return new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential renewal failed");
    }

    @Override
    @PreDestroy
    public void close() {
        workers.shutdownNow();
        deadlines.shutdownNow();
    }

    @FunctionalInterface
    private interface LockedWork<T> {
        T run(Path root) throws Exception;
    }

    private static final class RenewalState {
        private final CompletableFuture<Entry> future = new CompletableFuture<>();
        private volatile LlmFailure failure;
        private volatile long retryAtMillis;
    }
}
