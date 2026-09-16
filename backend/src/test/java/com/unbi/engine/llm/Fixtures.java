package com.unbi.engine.llm;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.llm.discovery.GatewayDirectory;
import com.unbi.engine.llm.provider.HttpTransport;
import com.unbi.engine.llm.runtime.LlmCaller;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.Pricing;
import com.unbi.engine.llm.spec.ProviderRouting;
import com.unbi.engine.llm.spec.RatePolicy;
import com.unbi.engine.llm.spec.Reasoning;
import com.unbi.engine.llm.spec.SamplingParams;
import com.unbi.engine.llm.spec.TokenUsage;
import com.unbi.engine.llm.spec.WebSearchMode;
import com.unbi.engine.nodes.llm.EndpointProfiles;
import com.unbi.engine.nodes.llm.LlmEndpointNode;
import com.unbi.engine.nodes.llm.LlmModelNode;
import com.unbi.engine.nodes.llm.LlmRequestNode;
import com.unbi.engine.profiles.Profile;
import com.unbi.engine.profiles.ProfileStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Targets for the wire tests to build requests against.
 *
 * <p>Two builders rather than a dozen constants: what these tests assert is the effect of one field
 * at a time, so every test wants a plain target with exactly one thing changed.
 */
public final class Fixtures {

    private Fixtures() {}

    /**
     * A directory with no credentials behind it.
     *
     * <p>The node tests never press a probe, so this is only here to satisfy a constructor. It is
     * deliberately a real object rather than a stub: a fake would be a second implementation of
     * "ask the gateway" that nothing holds to the first.
     */
    public static GatewayDirectory gateways() {
        return new GatewayDirectory(new HttpTransport(), new CredentialStore(List.of()));
    }

    /** The endpoint schema, over a directory with no credentials behind it. */
    public static EndpointProfiles endpointProfiles() {
        return new EndpointProfiles(gateways(), new CredentialStore(List.of()));
    }

    /** An endpoint node whose profiles live under {@code directory}. */
    public static LlmEndpointNode endpointNode(Path directory) {
        var profiles = endpointProfiles();
        return new LlmEndpointNode(new ProfileStore(new DataDirectory(directory), List.of(profiles)), profiles);
    }

    /** Saves an endpoint profile under {@code directory}, as the dialog would. */
    public static Profile saveProfile(Path directory, String name, Map<String, Object> values) throws IOException {
        var profiles = endpointProfiles();
        var store = new ProfileStore(new DataDirectory(directory), List.of(profiles));
        return store.save(new Profile("", EndpointProfiles.ID, name, "", values, Instant.now()));
    }

    /** A model node. Its execute path never touches the endpoint resolver, so a throwaway one will do. */
    public static LlmModelNode modelNode() {
        return new LlmModelNode(gateways(), endpointProfiles(), endpointNode(scratch()));
    }

    public static LlmRequestNode requestNode(LlmCaller caller) {
        return new LlmRequestNode(caller, gateways(), endpointNode(scratch()));
    }

    private static Path scratch() {
        try {
            return java.nio.file.Files.createTempDirectory("unbi-fixture");
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    public static EndpointSpec endpoint() {
        return new EndpointSpec(
                "test", "custom", "https://gateway.test/v1", EndpointSpec.AuthScheme.BEARER, "key",
                Map.of(), RatePolicy.UNLIMITED, 30_000, false,
                TokenUsage.CachedTokenMode.INCLUDED, false);
    }

    public static ModelSpec model() {
        return model(endpoint());
    }

    public static ModelSpec model(EndpointSpec endpoint) {
        return new ModelSpec(
                endpoint,
                "vendor/model-1",
                ApiFormat.CHAT_COMPLETIONS,
                Set.of(),
                Reasoning.UNSPECIFIED,
                WebSearchMode.NONE,
                Pricing.FREE,
                128_000,
                4096,
                ModelSpec.MaxTokensParam.MAX_TOKENS,
                SamplingParams.UNSET,
                ProviderRouting.NONE,
                List.of(),
                Map.of());
    }

    public static ModelSpec with(ModelSpec base, Capability... capabilities) {
        return new ModelSpec(
                base.endpoint(), base.name(), base.apiFormat(), Set.of(capabilities), base.reasoning(),
                base.webSearchMode(), base.pricing(), base.contextWindow(), base.maxOutputTokens(),
                base.maxTokensParam(), base.sampling(), base.routing(), base.tags(), base.extraBody());
    }

    public static ModelSpec reasoning(ModelSpec base, Reasoning reasoning) {
        return new ModelSpec(
                base.endpoint(), base.name(), base.apiFormat(), base.capabilities(), reasoning,
                base.webSearchMode(), base.pricing(), base.contextWindow(), base.maxOutputTokens(),
                base.maxTokensParam(), base.sampling(), base.routing(), base.tags(), base.extraBody());
    }

    public static ModelSpec searching(ModelSpec base, WebSearchMode mode, ApiFormat format) {
        return new ModelSpec(
                base.endpoint(), base.name(), format, base.capabilities(), base.reasoning(),
                mode, base.pricing(), base.contextWindow(), base.maxOutputTokens(),
                base.maxTokensParam(), base.sampling(), base.routing(), base.tags(), base.extraBody());
    }

    public static ModelSpec routed(ModelSpec base, ProviderRouting routing) {
        return new ModelSpec(
                base.endpoint(), base.name(), base.apiFormat(), base.capabilities(), base.reasoning(),
                base.webSearchMode(), base.pricing(), base.contextWindow(), base.maxOutputTokens(),
                base.maxTokensParam(), base.sampling(), routing, base.tags(), base.extraBody());
    }
}
