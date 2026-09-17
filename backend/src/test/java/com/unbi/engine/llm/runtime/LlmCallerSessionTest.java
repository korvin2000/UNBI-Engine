package com.unbi.engine.llm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.llm.Fixtures;
import com.unbi.engine.llm.auth.Credential;
import com.unbi.engine.llm.auth.CredentialSource;
import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.llm.auth.EnvironmentCredentials;
import com.unbi.engine.llm.provider.LlmProvider;
import com.unbi.engine.llm.provider.ProviderRegistry;
import com.unbi.engine.llm.provider.StreamSink;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ResponseFormat;
import com.unbi.engine.llm.spec.ChatResult;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.FinishReason;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.RatePolicy;
import com.unbi.engine.llm.spec.TokenUsage;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class LlmCallerSessionTest {

    @Test
    void resolvesTheCredentialAfterAQueuedPacerWait() throws Exception {
        var source = new RenewableSource();
        var provider = ScriptProvider.successes(2);
        var caller = caller(source, provider, endpoint(1));
        var firstStarted = new CountDownLatch(1);
        provider.blockFirst(firstStarted);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> caller.call(FixturesCall.call(endpoint(1)), policy(1), StreamSink.DISCARD,
                    ignored -> {}));
            assertThat(firstStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> caller.call(FixturesCall.call(endpoint(1)), policy(1), StreamSink.DISCARD,
                    ignored -> {}));
            source.token = Credential.bearer("key", "new-token");
            provider.releaseFirst();
            first.get();
            second.get();
        }

        assertThat(provider.tokens).containsExactly("old-token", "new-token");
    }

    @Test
    void refreshesOnceAndReplaysWithoutSpendingTheGenerationAttempt() {
        var source = new RenewableSource();
        var provider = ScriptProvider.failThenSuccess(401);
        var endpoint = endpoint(0);
        var caller = caller(source, provider, endpoint);

        var result = caller.call(FixturesCall.call(endpoint), policy(1), StreamSink.DISCARD, ignored -> {});

        assertThat(result.text()).isEqualTo("ok");
        assertThat(provider.calls.get()).isEqualTo(2);
        assertThat(source.refreshes.get()).isEqualTo(1);
    }

    @Test
    void doesNotLoopOnSecond401Or403OrStaticCredentials() {
        var renewable = new RenewableSource();
        var second401 = caller(renewable, ScriptProvider.failures(401, 401), endpoint(0));
        assertThatThrownBy(() -> second401.call(FixturesCall.call(endpoint(0)), policy(1), StreamSink.DISCARD, ignored -> {}))
                .isInstanceOf(LlmFailure.class);
        assertThat(renewable.refreshes.get()).isEqualTo(1);

        var forbiddenSource = new RenewableSource();
        var forbidden = caller(forbiddenSource, ScriptProvider.failures(403), endpoint(0));
        assertThatThrownBy(() -> forbidden.call(FixturesCall.call(endpoint(0)), policy(3), StreamSink.DISCARD, ignored -> {}))
                .isInstanceOf(LlmFailure.class);
        assertThat(forbiddenSource.refreshes.get()).isZero();

        var staticProvider = ScriptProvider.failures(403);
        var staticCaller = caller(new EnvironmentCredentials(Map.of("UNBI_LLM_KEY_KEY", "static-token")), staticProvider,
                endpoint(0));
        assertThatThrownBy(() -> staticCaller.call(FixturesCall.call(endpoint(0)), policy(3), StreamSink.DISCARD, ignored -> {}))
                .isInstanceOf(LlmFailure.class);
        assertThat(staticProvider.calls.get()).isEqualTo(1);
    }

    @Test
    void emittedOutputPreventsCredentialRecovery() {
        var source = new RenewableSource();
        var provider = ScriptProvider.emitThenFail(401);
        var chunks = new ArrayList<String>();
        var caller = caller(source, provider, endpoint(0));

        assertThatThrownBy(() -> caller.call(FixturesCall.call(endpoint(0)), policy(3), chunks::add, ignored -> {}))
                .isInstanceOf(LlmFailure.class);

        assertThat(chunks).containsExactly("partial");
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(source.refreshes.get()).isZero();
    }

    @Test
    void generationPolicyNeverReplaysAnAmbiguousCredentialExchange() {
        var resolutions = new AtomicInteger();
        var source = new CredentialSource() {
            @Override public String id() { return "renewing"; }
            @Override public Optional<Credential> find(String ref) { return Optional.empty(); }
            @Override public SequencedSet<String> names() { return new LinkedHashSet<>(List.of("key")); }
            @Override public Optional<Credential> resolve(String ref, URI resource, Duration timeout, BooleanSupplier cancelled) {
                resolutions.incrementAndGet();
                throw new LlmFailure(LlmFailure.Kind.TIMEOUT, "Token renewal timed out");
            }
        };
        var provider = ScriptProvider.successes(3);
        assertThatThrownBy(() -> caller(source, provider, endpoint(0)).call(
                FixturesCall.call(endpoint(0)), policy(3), StreamSink.DISCARD, ignored -> {})).isInstanceOf(LlmFailure.class);
        assertThat(resolutions).hasValue(1);
        assertThat(provider.calls).hasValue(0);
    }

    private static LlmCaller caller(CredentialSource source, ScriptProvider provider, EndpointSpec endpoint) {
        return new LlmCaller(new ProviderRegistry(List.of(provider)), new CredentialStore(List.of(source)),
                new PacerRegistry());
    }

    private static CallPolicy policy(int attempts) {
        return new CallPolicy(true, attempts, 0, true, false);
    }

    private static EndpointSpec endpoint(int maxConcurrent) {
        var base = Fixtures.endpoint();
        return maxConcurrent <= 0 ? base : new EndpointSpec(
                base.id(), base.profile(), base.baseUrl(), base.authScheme(), base.credentialRef(), base.headers(),
                new RatePolicy(0, 0, maxConcurrent), base.timeoutMillis(), base.stream(), base.cachedTokenMode(),
                base.responsesPromptCache(), base.defaultApiFormat(), base.responsesDialect(),
                base.apiKeyLocation(), base.apiKeyName());
    }

    private static final class FixturesCall {
        private static ChatCall call(EndpointSpec endpoint) {
            return new ChatCall(Fixtures.model(endpoint), List.of(com.unbi.engine.llm.spec.ChatMessage.user("hi")),
                    ResponseFormat.TEXT, com.unbi.engine.llm.spec.SamplingParams.UNSET,
                    ChatCall.WebSearch.OFF, "", "");
        }
    }

    private static final class RenewableSource implements CredentialSource {
        private volatile Credential token = Credential.bearer("key", "old-token");
        private final AtomicInteger refreshes = new AtomicInteger();

        @Override
        public String id() {
            return "renewable";
        }

        @Override
        public Optional<Credential> find(String ref) {
            return "key".equals(ref) ? Optional.of(token) : Optional.empty();
        }

        @Override
        public Optional<Credential> resolve(String ref, URI resource, Duration timeout, BooleanSupplier cancelled) {
            return find(ref);
        }

        @Override
        public Optional<Credential> refresh(
                String ref, Credential rejected, URI resource, Duration timeout, BooleanSupplier cancelled) {
            refreshes.incrementAndGet();
            token = Credential.bearer("key", "refreshed-token");
            return Optional.of(token);
        }

        @Override
        public SequencedSet<String> names() {
            return new LinkedHashSet<>(List.of("key"));
        }
    }

    private static final class ScriptProvider implements LlmProvider {
        private final List<Integer> statuses;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> tokens = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch firstStarted;
        private volatile CountDownLatch releaseFirst;

        private ScriptProvider(List<Integer> statuses) {
            this.statuses = statuses;
        }

        static ScriptProvider successes(int count) {
            return new ScriptProvider(java.util.Collections.nCopies(count, 0));
        }

        static ScriptProvider failThenSuccess(int status) {
            return new ScriptProvider(List.of(status, 0));
        }

        static ScriptProvider failures(int... statuses) {
            return new ScriptProvider(java.util.Arrays.stream(statuses).boxed().toList());
        }

        static ScriptProvider emitThenFail(int status) {
            return new ScriptProvider(List.of(-status));
        }

        void blockFirst(CountDownLatch started) {
            firstStarted = started;
            releaseFirst = new CountDownLatch(1);
        }

        void releaseFirst() {
            releaseFirst.countDown();
        }

        @Override
        public ApiFormat format() {
            return ApiFormat.CHAT_COMPLETIONS;
        }

        @Override
        public ChatResult complete(ChatCall call, Credential credential, StreamSink sink) {
            var index = calls.getAndIncrement();
            tokens.add(credential == null ? "<none>" : credential.token());
            if (index == 0 && firstStarted != null) {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LlmFailure(LlmFailure.Kind.CANCELLED, "interrupted", "", 0, -1, interrupted);
                }
            }
            var status = index < statuses.size() ? statuses.get(index) : 0;
            if (status != 0) {
                if (status < 0) {
                    sink.chunk("partial");
                    status = -status;
                }
                throw new LlmFailure(
                        LlmFailure.Kind.AUTH, "unauthorized", call.model().endpoint().baseUrl(), status, -1, null);
            }
            return new ChatResult("ok", FinishReason.STOP, new TokenUsage(1, 1, 0, 0, 2), "model", 1, -1,
                    false, List.of());
        }
    }
}
