package com.unbi.engine.llm.spec;

/**
 * What shape the answer should take.
 *
 * <p>Sealed, so the two wire encoders are exhaustive: a new format cannot be added without both of
 * them being made to decide what it means.
 *
 * <p>Requesting one of the JSON forms from a model that does not declare the matching capability is
 * a refusal rather than a silent drop — see {@code CapabilityCheck}. A gateway that merely ignores
 * an unknown field survives being sent one; a gateway that rejects it fails every call, and the
 * measured version of that failure looked exactly like an ordinary provider error.
 */
public sealed interface ResponseFormat {

    /** Whichever capability a model must declare to be asked for this, or null for plain text. */
    Capability requiredCapability();

    record Text() implements ResponseFormat {
        @Override
        public Capability requiredCapability() {
            return null;
        }
    }

    record JsonObject() implements ResponseFormat {
        @Override
        public Capability requiredCapability() {
            return Capability.JSON_OBJECT;
        }
    }

    /**
     * @param schema the JSON schema, as written by the user; parsed by the wire encoder so that
     *               {@code spec} stays free of a JSON library
     */
    record JsonSchema(String name, String schema, boolean strict) implements ResponseFormat {

        public JsonSchema {
            if (name == null || name.isBlank()) {
                name = "response";
            }
            if (schema == null || schema.isBlank()) {
                throw new IllegalArgumentException("A JSON schema response format needs a schema");
            }
        }

        @Override
        public Capability requiredCapability() {
            return Capability.JSON_SCHEMA;
        }
    }

    ResponseFormat TEXT = new Text();
    ResponseFormat JSON_OBJECT = new JsonObject();

    /** True when the answer is meant to be machine-readable rather than prose. */
    default boolean isJson() {
        return !(this instanceof Text);
    }
}
