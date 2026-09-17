package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.llm.spec.LlmFailure;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ManagedCredentialStoreTest {

    private final List<ManagedCredentialStore> stores = new ArrayList<>();

    @AfterEach
    void closeStores() {
        stores.forEach(ManagedCredentialStore::close);
    }

    @Test
    void twentyWaitersShareOneRenewalAndRotatedSessionSurvivesRestart(@TempDir Path root) throws Exception {
        var store = store(root);
        var created = store.create("gateway", ManagedCredentialStore.Type.BASIC, basicConfig("https://gateway.example/v1"));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callbacks = new AtomicInteger();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var calls = new ArrayList<CompletableFuture<ManagedCredentialStore.Entry>>();
            for (var i = 0; i < 20; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> store.renew(created, Duration.ofSeconds(5), () -> false, (current, capturedRoot) -> {
                    callbacks.incrementAndGet();
                    started.countDown();
                    release.await();
                    return session("access-rotated");
                }), executor));
            }
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
            assertThat(callbacks).hasValue(1);
            assertThat(store.find("gateway")).get().extracting(ManagedCredentialStore.Entry::session)
                    .isEqualTo(session("access-rotated"));
        } finally {
            executor.close();
        }

        var restarted = store(root);
        assertThat(restarted.find("gateway")).get().extracting(ManagedCredentialStore.Entry::session)
                .isEqualTo(session("access-rotated"));
    }

    @Test
    void credentialsRenewIndependently(@TempDir Path root) throws Exception {
        var store = store(root);
        var first = store.create("first", ManagedCredentialStore.Type.BASIC, basicConfig("https://one.example/"));
        var second = store.create("second", ManagedCredentialStore.Type.BASIC, basicConfig("https://two.example/"));
        var callbacks = new AtomicInteger();
        var one = CompletableFuture.supplyAsync(() -> store.renew(first, Duration.ofSeconds(2), () -> false,
                (entry, capturedRoot) -> { callbacks.incrementAndGet(); return session("one"); }));
        var two = CompletableFuture.supplyAsync(() -> store.renew(second, Duration.ofSeconds(2), () -> false,
                (entry, capturedRoot) -> { callbacks.incrementAndGet(); return session("two"); }));
        CompletableFuture.allOf(one, two).join();
        assertThat(callbacks).hasValue(2);
        assertThat(store.find("first")).get().extracting(ManagedCredentialStore.Entry::session).isEqualTo(session("one"));
        assertThat(store.find("second")).get().extracting(ManagedCredentialStore.Entry::session).isEqualTo(session("two"));
    }

    @Test
    void cancellingOneWaiterDoesNotCancelSharedRenewal(@TempDir Path root) throws Exception {
        var store = store(root);
        var created = store.create("gateway", ManagedCredentialStore.Type.BASIC, basicConfig("https://gateway.example/"));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var leader = CompletableFuture.supplyAsync(() -> store.renew(created, Duration.ofSeconds(5), () -> false,
                (entry, capturedRoot) -> { started.countDown(); release.await(); return session("new-token"); }));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        var waiter = CompletableFuture.supplyAsync(() -> store.renew(created, Duration.ofSeconds(5), cancelled::get,
                (entry, capturedRoot) -> { throw new AssertionError("second callback"); }));
        cancelled.set(true);
        assertThatThrownBy(waiter::join).hasRootCauseInstanceOf(LlmFailure.class);
        release.countDown();
        assertThat(leader.join().session()).isEqualTo(session("new-token"));
    }

    @Test
    void logoutAfterRenewalCannotBeResurrected(@TempDir Path root) throws Exception {
        var store = store(root);
        var created = store.create("gateway", ManagedCredentialStore.Type.BASIC, basicConfig("https://gateway.example/"));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var renewal = CompletableFuture.supplyAsync(() -> store.renew(created, Duration.ofSeconds(5), () -> false,
                (entry, capturedRoot) -> { started.countDown(); release.await(); return session("must-not-survive-logout"); }));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        var logout = CompletableFuture.runAsync(() -> store.logout("gateway"));
        release.countDown();
        renewal.join();
        logout.join();
        assertThat(store.find("gateway").orElseThrow().session().hasNonNull("accessToken")).isFalse();
    }

    @Test
    void malformedAndSymlinkRecordsRemainOwnedWithoutLeakingContents(@TempDir Path root) throws Exception {
        var credentials = Files.createDirectories(root.resolve("credentials"));
        Files.writeString(credentials.resolve("broken.json"), "{\"session\":\"secret-value\"", StandardCharsets.UTF_8);
        assertThat(store(root).names()).contains("broken");
        assertThatThrownBy(() -> store(root).find("broken"))
                .isInstanceOf(LlmFailure.class)
                .hasMessageNotContaining("secret-value");

        var external = Files.createTempFile("outside", ".json");
        Files.writeString(external, "{}", StandardCharsets.UTF_8);
        Files.createSymbolicLink(credentials.resolve("escape.json"), external);
        var escaped = store(root);
        assertThat(escaped.names()).contains("escape");
        assertThatThrownBy(() -> escaped.find("escape"))
                .isInstanceOf(LlmFailure.class)
                .hasMessageNotContaining(external.toString());
    }

    @Test
    void validatesNamesResourceBindingAndTransportPolicy(@TempDir Path root) {
        var store = store(root);
        assertThatThrownBy(() -> store.create("../escape", ManagedCredentialStore.Type.BASIC, basicConfig("http://localhost/")))
                .isInstanceOf(IllegalArgumentException.class);
        var created = store.create("gateway", ManagedCredentialStore.Type.BASIC, basicConfig("https://gateway.example/api/"));
        assertThatThrownBy(() -> ManagedCredentialStore.validateResource(created, java.net.URI.create("https://gateway.example/other")))
                .isInstanceOf(LlmFailure.class);
        assertThatThrownBy(() -> store.create("oauth", ManagedCredentialStore.Type.OAUTH2,
                oauthConfig("http://public.example/"))).isInstanceOf(IllegalArgumentException.class);
        store.create("oauth", ManagedCredentialStore.Type.OAUTH2, oauthConfig("http://localhost:4312/"));
    }

    @Test
    void replacementAlwaysRotatesRevisionAndClearsSession(@TempDir Path root) {
        var store = store(root);
        var created = store.create("gateway", ManagedCredentialStore.Type.BASIC, basicConfig("https://gateway.example/"));
        var renewed = store.renew(created, Duration.ofSeconds(2), () -> false,
                (entry, capturedRoot) -> session("token"));
        var replaced = store.configure("gateway", basicConfig("https://gateway.example/"));
        assertThat(replaced.revision()).isNotEqualTo(renewed.revision());
        assertThat(replaced.session().hasNonNull("accessToken")).isFalse();
    }

    private ManagedCredentialStore store(Path root) {
        var created = new ManagedCredentialStore(new DataDirectory(root),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        stores.add(created);
        return created;
    }

    @Test
    void loginCommitRequiresTheCapturedDefinitionRevision(@TempDir Path root) {
        var store = store(root);
        var created = store.create("gateway", ManagedCredentialStore.Type.BASIC, basicConfig("https://gateway.example/"));
        var changed = store.configure("gateway", basicConfig("https://gateway.example/"));
        assertThatThrownBy(() -> store.commitSession("gateway", created.revision(), session("stale")))
                .isInstanceOf(LlmFailure.class)
                .hasMessageNotContaining("stale");
        var committed = store.commitSession("gateway", changed.revision(), session("fresh"));
        assertThat(committed.session()).isEqualTo(session("fresh"));
    }

    @Test
    void aLateWaiterUsesTheAlreadyPersistedRotation(@TempDir Path root) {
        var store = store(root);
        var initial = store.create("gateway", ManagedCredentialStore.Type.BASIC, basicConfig("https://gateway.example/"));
        var rotated = store.renew(initial, Duration.ofSeconds(2), () -> false, (entry, captured) -> session("rotated"));
        var late = store.renew(initial, Duration.ofSeconds(2), () -> false, (entry, captured) -> {
            throw new AssertionError("The same rejected revision must not be exchanged twice");
        });
        assertThat(late.revision()).isEqualTo(rotated.revision());
        store.logout("gateway");
        assertThatThrownBy(() -> store.renew(initial, Duration.ofSeconds(2), () -> false,
                (entry, captured) -> session("resurrected"))).isInstanceOf(LlmFailure.class);
        assertThat(store.find("gateway").orElseThrow().session().hasNonNull("accessToken")).isFalse();
    }

    @Test
    void timedOutMutationRetainsItsDataLeaseUntilCallbackCleanupFinishes(@TempDir Path root) throws Exception {
        var settings = new com.unbi.engine.settings.SettingsStore(new com.unbi.engine.settings.EngineHome(root));
        var store = new ManagedCredentialStore(new DataDirectory(settings));
        stores.add(store);
        var initial = store.create("gateway", ManagedCredentialStore.Type.BASIC, basicConfig("https://gateway.example/"));
        var cleanupStarted = new CountDownLatch(1);
        var cleanupRelease = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> store.renew(initial, Duration.ofMillis(100), () -> false, (entry, captured) -> {
                try { Thread.sleep(10_000); return session("too-late"); }
                finally { cleanupStarted.countDown(); cleanupRelease.await(); }
            })).isInstanceOf(LlmFailure.class);
            assertThat(cleanupStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> settings.relocate(com.unbi.engine.settings.SettingsStore.Target.DATA,
                    root.resolve("relocated").toString(), false))
                    .isInstanceOf(com.unbi.engine.settings.SettingsStore.DataBusyException.class);
        } finally { cleanupRelease.countDown(); }
    }

    private static ObjectNode basicConfig(String resource) {
        return JsonNodeFactory.instance.objectNode()
                .put("resourceBaseUrl", resource)
                .put("username", "user")
                .put("password", "write-only-password");
    }

    private static ObjectNode oauthConfig(String resource) {
        return JsonNodeFactory.instance.objectNode()
                .put("resourceBaseUrl", resource)
                .put("grantType", "client_credentials")
                .put("clientId", "client")
                .put("clientAuthentication", "none")
                .put("tokenUrl", resource + "token");
    }

    private static ObjectNode session(String token) {
        return JsonNodeFactory.instance.objectNode().put("accessToken", token).put("tokenType", "Bearer");
    }
}
