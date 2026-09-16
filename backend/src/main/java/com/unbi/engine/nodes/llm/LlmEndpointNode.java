package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeAction;
import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeProbe;
import com.unbi.engine.core.node.ValueContext;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.profiles.Profile;
import com.unbi.engine.profiles.ProfileStore;
import org.springframework.stereotype.Component;

/**
 * Names the endpoint requests go to.
 *
 * <p>One field. Everything about the connection — URL, gateway kind, authentication, credential
 * name, pacing — lives in an endpoint <em>profile</em> saved on the engine, and this node holds the
 * profile's id. That is what makes a workflow portable: the file says "openrouter", and each
 * machine decides what "openrouter" means. The button beside the field opens the profile dialog,
 * where profiles are created, edited, tested and deleted.
 *
 * <p>This node is also where "which endpoint does this node mean?" is answered for everyone else:
 * the Model and Request nodes resolve the endpoint wired into them through {@link #resolve}, so a
 * model listing, a request check and a run cannot disagree about the target.
 */
@Component
public class LlmEndpointNode implements NodeDefinition, NodeProbe {

    private final ProfileStore store;
    private final EndpointProfiles profiles;

    public LlmEndpointNode(ProfileStore store, EndpointProfiles profiles) {
        this.store = store;
        this.profiles = profiles;
    }

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.endpoint", "LLM Endpoint")
                .in(LlmTypes.CATEGORY, "Connection")
                .icon("plug")
                .accent(LlmTypes.ACCENT)
                .describedAs("Names a saved endpoint profile: which gateway, where it lives, and how to "
                        + "authenticate. Profiles are kept on the engine, so the same workflow can "
                        + "point at different servers on different machines.")
                .action(NodeAction.check("test", "Test this endpoint", "bulb"))
                .setting("profile", "Endpoint", Types.TEXT, Widget.Profile.of(EndpointProfiles.ID), "")
                .hint("Pick a saved profile, or open the profile editor to create one. A credential "
                        + "is referred to by name and never stored in a workflow.")
                .out("endpoint", "Endpoint", LlmTypes.ENDPOINT)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var endpoint = resolve(context);
        context.output("endpoint", endpoint);
        context.progress(1, endpoint.baseUrl());
    }

    /** Tests the chosen profile the way a run would use it: same lookup, same builder, same store. */
    @Override
    public Result probe(String action, Request request) {
        if (!"test".equals(action)) {
            return Result.failed("This node has no action called " + action);
        }
        Profile profile;
        try {
            profile = profileOf(new ValueContext(request.values()));
        } catch (RuntimeException misconfigured) {
            return Result.failed(misconfigured.getMessage());
        }
        return profiles.test(profile.values())
                .map(result -> {
                    var rebuilt = result.ok() ? Result.ok(result.message()) : Result.problem(result.message());
                    rebuilt.detail("Profile: " + profile.name());
                    result.details().forEach(rebuilt::detail);
                    return rebuilt.build();
                })
                .orElseGet(() -> Result.failed("This endpoint cannot be tested."));
    }

    /**
     * The endpoint this node's values name.
     *
     * <p>Everything the profile and its gateway kind supplied is logged to the context, so "from
     * gateway" is never a mystery in a run's log.
     */
    public EndpointSpec resolve(NodeContext context) {
        var profile = profileOf(context);
        var values = new ValueContext(profile.values());
        var endpoint = EndpointProfiles.build(values, profile.id());
        context.log("Endpoint profile: " + profile.name());
        values.logs().forEach(context::log);
        return endpoint;
    }

    /** The same, for a probe holding this node's values rather than the node itself. */
    public EndpointSpec resolve(NodeProbe.Source endpointNode) {
        if (!endpointNode.isPresent()) {
            throw new IllegalStateException("Wire an LLM Endpoint into this node first.");
        }
        return resolve(new ValueContext(endpointNode.values()));
    }

    private Profile profileOf(NodeContext context) {
        var id = context.text("profile").trim();
        if (id.isEmpty()) {
            throw new IllegalStateException(
                    "Choose an endpoint profile, or create one with the button beside the field.");
        }
        return store.find(EndpointProfiles.ID, id).orElseThrow(() -> new IllegalStateException(
                "There is no endpoint profile called '%s' on this engine. Pick another, or create it."
                        .formatted(id)));
    }
}
