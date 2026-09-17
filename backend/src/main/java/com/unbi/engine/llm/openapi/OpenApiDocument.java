package com.unbi.engine.llm.openapi;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import org.snakeyaml.engine.v2.api.LoadSettings;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.databind.JsonNode;

/**
 * Parses a bounded OpenAPI document and resolves only the local references consumed by the
 * endpoint importer. This is deliberately not a general OpenAPI resolver: it never loads a URI.
 */
public final class OpenApiDocument {
    private static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_NESTING_DEPTH = 100;
    private static final int MAX_YAML_ALIASES = 50;
    private static final int MAX_REFERENCE_DEPTH = 32;
    private static final LoadSettings LOAD_SETTINGS = LoadSettings.builder()
            .setAllowDuplicateKeys(false).setAllowRecursiveKeys(false).setAllowNonScalarKeys(false)
            .setMaxAliasesForCollections(MAX_YAML_ALIASES).setCodePointLimit(MAX_DOCUMENT_BYTES)
            .setUseMarks(false).build();
    private static final YAMLMapper YAML = YAMLMapper.builder(YAMLFactory.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_DOCUMENT_BYTES).maxNestingDepth(MAX_NESTING_DEPTH).build())
            .loadSettings(LOAD_SETTINGS).build()).build();

    private final JsonNode root;
    private final int minor;
    private final URI documentUri;
    private OpenApiDocument(JsonNode root, int minor, URI documentUri) {
        this.root = root;
        this.minor = minor;
        this.documentUri = documentUri;
    }

    /** Parses one UTF-8 JSON or YAML object document without loading any referenced resource. */
    public static OpenApiDocument parse(String document, String documentUri) {
        if (document == null || document.isBlank()) {
            throw new Problem("", "An OpenAPI document is required");
        }
        if (utf8Length(document) > MAX_DOCUMENT_BYTES) {
            throw new Problem("", "The OpenAPI document exceeds the 10 MiB limit");
        }
        var uri = parseDocumentUri(documentUri);
        final JsonNode root;
        try {
            preflight(document);
            // Jackson's YAML tree reader represents aliases by their anchor *names*, not their
            // values. Compose with SnakeYAML's safe core schema, then bound expansion explicitly.
            var loaded = new org.snakeyaml.engine.v2.api.Load(LOAD_SETTINGS).loadFromString(document);
            root = tree(loaded, 0, new ExpansionBudget(), new java.util.IdentityHashMap<>());
        } catch (Problem invalid) {
            throw invalid;
        } catch (RuntimeException malformed) {
            throw new Problem("", "The OpenAPI document is not valid JSON or YAML");
        }
        if (root == null || !root.isObject()) {
            throw new Problem("", "An OpenAPI document must contain one object");
        }
        var version = root.path("openapi");
        if (!version.isString()) {
            throw new Problem("/openapi", "An OpenAPI 3 version is required");
        }
        var minor = minor(version.asString());
        return new OpenApiDocument(root, minor, uri);
    }

    /** The parsed document root. */
    public JsonNode root() {
        return root;
    }

    /** The supported OpenAPI minor version (0, 1, or 2). */
    public int minor() {
        return minor;
    }

    /** Optional absolute HTTP(S) URI supplied with the pasted or uploaded document. */
    public URI documentUri() {
        return documentUri;
    }

    /**
     * Resolves a consumed Path Item or Security Scheme metadata object through local JSON Pointer
     * references. The returned pointer always names the final node in this bundled document.
     */
    public Resolved resolve(JsonNode value, String pointer) {
        requireObject(value, pointer);
        var current = value;
        var currentPointer = requirePointer(pointer);
        var visited = new HashSet<String>();
        for (var depth = 0; ; depth++) {
            if (depth > MAX_REFERENCE_DEPTH) {
                throw new Problem(currentPointer, "Local reference nesting exceeds the supported limit");
            }
            if (!current.has("$ref")) {
                return new Resolved(current, currentPointer);
            }
            if (current.propertyNames().stream().anyMatch(name -> !name.equals("$ref")
                    && !name.equals("summary") && !name.equals("description") && !name.startsWith("x-"))) {
                throw new Problem(currentPointer,
                        "A referenced metadata object cannot combine $ref with sibling fields");
            }
            var refPointer = child(currentPointer, "$ref");
            var ref = current.path("$ref");
            if (!ref.isString() || ref.asString().isBlank()) {
                throw new Problem(refPointer, "A local reference must be a nonblank string");
            }
            var targetPointer = localPointer(ref.asString(), refPointer);
            if (!visited.add(targetPointer)) {
                throw new Problem(refPointer, "Local reference cycle detected");
            }
            current = at(targetPointer, refPointer);
            requireObject(current, refPointer);
            currentPointer = targetPointer;
        }
    }

    /** Resolves a URL against the explicit document URI; this method never performs I/O. */
    public URI resolveUrl(String value, String pointer) {
        var problemPointer = requirePointer(pointer);
        if (value == null || value.isBlank()) {
            throw new Problem(problemPointer, "A URL is required");
        }
        final URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException invalid) {
            throw new Problem(problemPointer, "The URL is not valid");
        }
        final URI resolved;
        if (uri.isAbsolute()) {
            resolved = uri;
        } else {
            if (documentUri == null) {
                throw new Problem(problemPointer, "A document base URL is required for this relative URL");
            }
            resolved = documentUri.resolve(uri);
        }
        if (resolved.getHost() == null || resolved.getUserInfo() != null
                || !("http".equalsIgnoreCase(resolved.getScheme()) || "https".equalsIgnoreCase(resolved.getScheme()))) {
            throw new Problem(problemPointer, "The URL must be an HTTP(S) URL without userinfo");
        }
        return resolved;
    }


    /** A resolved metadata node and its actual local JSON Pointer location. */
    public record Resolved(JsonNode node, String pointer) {
        public Resolved {
            Objects.requireNonNull(node, "node");
            pointer = requirePointer(pointer);
        }
    }

    /** Safe, pointer-addressable importer problem without parser source excerpts. */
    public static final class Problem extends IllegalArgumentException {
        private final String pointer;
        private static final long serialVersionUID = 1L;

        public Problem(String pointer, String message) {
            super(Objects.requireNonNull(message, "message"));
            this.pointer = requirePointer(pointer);
        }

        public String pointer() {
            return pointer;
        }
    }

    private static void preflight(String document) {
        try (var parser = YAML.createParser(document.getBytes(StandardCharsets.UTF_8))) {
            var token = parser.nextToken();
            if (token != tools.jackson.core.JsonToken.START_OBJECT)
                throw new Problem("", "An OpenAPI document must contain one object");
            int depth = 0, documents = 0, aliases = 0;
            for (; token != null; token = parser.nextToken()) {
                if (((tools.jackson.dataformat.yaml.YAMLParser) parser).isCurrentAlias() && ++aliases > MAX_YAML_ALIASES)
                    throw new Problem("", "The document exceeds the YAML alias limit");
                if (token == tools.jackson.core.JsonToken.START_OBJECT || token == tools.jackson.core.JsonToken.START_ARRAY) {
                    if (depth++ == 0 && ++documents > 1) throw new Problem("", "Only one OpenAPI document is allowed");
                    if (depth > MAX_NESTING_DEPTH) throw new Problem("", "The document is nested too deeply");
                } else if (token == tools.jackson.core.JsonToken.END_OBJECT || token == tools.jackson.core.JsonToken.END_ARRAY) {
                    depth--;
                } else if (depth == 0) throw new Problem("", "Only one OpenAPI document is allowed");
            }
        }
    }

    private static JsonNode tree(Object value, int depth, ExpansionBudget budget,
            java.util.IdentityHashMap<Object, Boolean> ancestors) {
        if (depth > MAX_NESTING_DEPTH) throw new Problem("", "The document is nested too deeply");
        budget.spend(2);
        var nodes = tools.jackson.databind.node.JsonNodeFactory.instance;
        if (value == null) return nodes.nullNode();
        if (value instanceof String text) {
            budget.spend(utf8Length(text) + (long) text.chars().filter(c -> c < 0x20 || c == '"' || c == '\\').count() * 5);
            return nodes.stringNode(text);
        }
        if (value instanceof Boolean flag) return nodes.booleanNode(flag);
        if (value instanceof Number number) {
            var text = number.toString();
            budget.spend(text.length());
            try { return nodes.numberNode(new java.math.BigDecimal(text)); }
            catch (NumberFormatException invalid) { throw new Problem("", "Non-JSON numeric values are unsupported"); }
        }
        if (ancestors.put(value, Boolean.TRUE) != null) throw new Problem("", "Recursive YAML aliases are unsupported");
        try {
            if (value instanceof java.util.Map<?, ?> map) {
                var result = nodes.objectNode();
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new Problem("", "Object keys must be strings");
                    budget.spend(utf8Length(key) + 4L);
                    result.set(key, tree(entry.getValue(), depth + 1, budget, ancestors));
                }
                return result;
            }
            if (value instanceof java.util.List<?> list) {
                var result = nodes.arrayNode();
                for (var item : list) { budget.spend(1); result.add(tree(item, depth + 1, budget, ancestors)); }
                return result;
            }
            throw new Problem("", "The document contains unsupported YAML values");
        } finally { ancestors.remove(value); }
    }

    private static final class ExpansionBudget {
        private long remaining = MAX_DOCUMENT_BYTES;
        void spend(long bytes) {
            remaining -= bytes;
            if (remaining < 0) throw new Problem("", "The expanded document exceeds the 10 MiB limit");
        }
    }

    /** Appends one escaped JSON Pointer field name. */
    public static String child(String pointer, String field) {
        var parent = requirePointer(pointer);
        Objects.requireNonNull(field, "field");
        return parent + "/" + field.replace("~", "~0").replace("/", "~1");
    }

    private static URI parseDocumentUri(String raw) {
        if (raw == null || raw.isBlank()) return null;
        final URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException invalid) {
            throw new Problem("/documentUri", "The document URI must be an absolute HTTP(S) URL");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new Problem("/documentUri", "The document URI must be an absolute HTTP(S) URL");
        }
        return uri;
    }

    private static int minor(String version) {
        var match = java.util.regex.Pattern
                .compile("3\\.(0|1|2)\\.[0-9]+(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
                .matcher(version);
        if (!match.matches()) {
            throw new Problem("/openapi", "Only OpenAPI 3.0, 3.1, and 3.2 documents are supported");
        }
        return Integer.parseInt(match.group(1));
    }

    private static int utf8Length(String value) {
        long length = 0;
        for (var index = 0; index < value.length(); index++) {
            var unit = value.charAt(index);
            if (unit <= 0x7f) {
                length++;
            } else if (unit <= 0x7ff) {
                length += 2;
            } else if (Character.isHighSurrogate(unit) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                length += 4;
                index++;
            } else if (Character.isSurrogate(unit)) {
                length++;
            } else {
                length += 3;
            }
            if (length > MAX_DOCUMENT_BYTES) return MAX_DOCUMENT_BYTES + 1;
        }
        return (int) length;
    }

    private static String localPointer(String rawReference, String problemPointer) {
        final URI reference;
        try {
            reference = new URI(rawReference);
        } catch (URISyntaxException invalid) {
            throw new Problem(problemPointer, "The local reference is not valid");
        }
        if (reference.getScheme() != null || reference.getRawAuthority() != null
                || (reference.getRawPath() != null && !reference.getRawPath().isEmpty())
                || reference.getRawQuery() != null || reference.getRawFragment() == null) {
            throw new Problem(problemPointer, "Bundle external references before import");
        }
        return decodePercent(reference.getRawFragment(), problemPointer);
    }


    private JsonNode at(String pointer, String problemPointer) {
        if (pointer.isEmpty()) return root;
        if (!pointer.startsWith("/")) {
            throw new Problem(problemPointer, "The local reference JSON Pointer is not valid");
        }
        var node = root;
        for (var rawToken : pointer.substring(1).split("/", -1)) {
            var token = unescapePointerToken(rawToken, problemPointer);
            if (node.isObject()) {
                node = node.get(token);
            } else if (node.isArray()) {
                node = arrayElement(node, token, problemPointer);
            } else {
                node = null;
            }
            if (node == null) {
                throw new Problem(problemPointer, "The local reference does not exist");
            }
        }
        return node;
    }

    private static JsonNode arrayElement(JsonNode array, String token, String problemPointer) {
        if (!token.matches("0|[1-9][0-9]*")) {
            throw new Problem(problemPointer, "The local reference JSON Pointer is not valid");
        }
        try {
            return array.get(Integer.parseInt(token));
        } catch (NumberFormatException tooLarge) {
            throw new Problem(problemPointer, "The local reference does not exist");
        }
    }

    private static String unescapePointerToken(String raw, String problemPointer) {
        var value = new StringBuilder(raw.length());
        for (var index = 0; index < raw.length(); index++) {
            var character = raw.charAt(index);
            if (character != '~') {
                value.append(character);
                continue;
            }
            if (index + 1 == raw.length()) {
                throw new Problem(problemPointer, "The local reference JSON Pointer is not valid");
            }
            var escape = raw.charAt(++index);
            if (escape == '0') value.append('~');
            else if (escape == '1') value.append('/');
            else throw new Problem(problemPointer, "The local reference JSON Pointer is not valid");
        }
        return value.toString();
    }

    private static String decodePercent(String raw, String problemPointer) {
        var decoded = new StringBuilder(raw.length());
        for (var index = 0; index < raw.length();) {
            if (raw.charAt(index) != '%') {
                decoded.append(raw.charAt(index++));
                continue;
            }
            var bytes = new java.io.ByteArrayOutputStream();
            while (index < raw.length() && raw.charAt(index) == '%') {
                if (index + 2 >= raw.length()) {
                    throw new Problem(problemPointer, "The local reference is not valid");
                }
                var high = Character.digit(raw.charAt(index + 1), 16);
                var low = Character.digit(raw.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new Problem(problemPointer, "The local reference is not valid");
                }
                bytes.write((high << 4) + low);
                index += 3;
            }
            try {
                decoded.append(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException invalid) {
                throw new Problem(problemPointer, "The local reference is not valid");
            }
        }
        return decoded.toString();
    }

    private static void requireObject(JsonNode value, String pointer) {
        if (value == null || !value.isObject()) {
            throw new Problem(requirePointer(pointer), "Consumed OpenAPI metadata must be an object");
        }
    }

    private static String requirePointer(String pointer) {
        if (pointer == null || (!pointer.isEmpty() && !pointer.startsWith("/"))) {
            throw new IllegalArgumentException("JSON Pointer must be empty or start with '/'");
        }
        return pointer;
    }
}
