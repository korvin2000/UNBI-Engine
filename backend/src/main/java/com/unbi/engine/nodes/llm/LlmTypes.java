package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.type.PortType;
import com.unbi.engine.core.type.Types;

/**
 * The type vocabulary owned by the LLM pack.
 *
 * <p>Two shapes, chosen for two different reasons.
 *
 * <p>The <b>handles</b> — endpoint, model, sampling, variables, attachment — are primitives. Nothing
 * downstream should reach inside a configured endpoint, and a primitive says so: it can be wired
 * only into a port that asked for exactly that, and it carries no field anyone can come to depend on.
 *
 * <p>{@link #RESULT} is a struct, and that is what makes the pack compose with the rest of the
 * engine. Preview and Generate Report accept {@code Any} and reflect over record components, so a
 * result wired into either renders as a proper table without those nodes knowing this pack exists.
 * The fields declared here and the components of {@link com.unbi.engine.nodes.llm.model.LlmResult}
 * are held to each other by {@code LlmTypeShapeTest}.
 */
public final class LlmTypes {

    private LlmTypes() {}

    /** A configured connection, carrying a credential reference rather than a credential. */
    public static final PortType ENDPOINT = PortType.primitive("LlmEndpoint");

    /** A model on an endpoint, with its capabilities, price and reasoning settings. */
    public static final PortType MODEL = PortType.primitive("LlmModel");

    /** Sampling parameters, every one of them optional. */
    public static final PortType SAMPLING = PortType.primitive("LlmSampling");

    /** Named values a prompt template can read. */
    public static final PortType VARIABLES = PortType.primitive("LlmVariables");

    public static final PortType ATTACHMENT = PortType.primitive("LlmAttachment");

    public static final PortType RESULT = Types.struct("LlmResult", Types.fields(
            "text", Types.TEXT,
            "finishReason", Types.TEXT,
            "model", Types.TEXT,
            "promptTokens", Types.NUMBER,
            "completionTokens", Types.NUMBER,
            "reasoningTokens", Types.NUMBER,
            "costUsd", Types.NUMBER,
            "latencyMillis", Types.NUMBER,
            "sources", Types.TEXT));

    public static final PortType ATTACHMENT_LIST = PortType.list(ATTACHMENT);
    public static final PortType RESULT_LIST = PortType.list(RESULT);
    public static final PortType TEXT_LIST = PortType.list(Types.TEXT);

    /**
     * One prompt, or several to try in turn.
     *
     * <p>The type behind "wire a single text and it is one request; wire a list and it is one
     * request per entry". A union rather than {@code Any} so that only text-shaped things connect,
     * and the editor colours the port as text.
     */
    public static final PortType PROMPTS = PortType.union(Types.TEXT, TEXT_LIST);

    /** Palette identity for every node in this pack, so they group and colour together. */
    static final String CATEGORY = "LLM";
    static final String ACCENT = "violet";
}
