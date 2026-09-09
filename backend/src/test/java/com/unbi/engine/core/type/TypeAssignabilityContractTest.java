package com.unbi.engine.core.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.transport.codec.PortTypeCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the Java {@link TypeSystem} to {@code contract/type-assignability.json}.
 *
 * <p>The frontend runs the same table against its own implementation. If either side drifts, one of
 * the two suites goes red — which is the entire point of keeping the table in a shared file instead
 * of duplicating the cases in two languages.
 */
class TypeAssignabilityContractTest {

    private static final Path CONTRACT =
            Path.of("..", "contract", "type-assignability.json").normalize();

    private final TypeSystem types = new TypeSystem();

    private record Contract(Map<String, PortType> types, JsonNode cases) {}

    private Contract load() {
        var mapper = JsonMapper.builder().build();
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(CONTRACT));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the shared contract at " + CONTRACT.toAbsolutePath(), e);
        }
        var declared = new LinkedHashMap<String, PortType>();
        var typesNode = root.get("types");
        typesNode.propertyNames().forEach(alias -> declared.put(alias, PortTypeCodec.read(typesNode.get(alias))));
        return new Contract(declared, root.get("cases"));
    }

    @TestFactory
    Iterable<DynamicTest> everyContractCaseHolds() {
        var contract = load();
        var tests = new ArrayList<DynamicTest>();
        contract.cases().forEach(testCase -> {
            var fromAlias = testCase.get("from").asString();
            var toAlias = testCase.get("to").asString();
            var expected = testCase.get("assignable").asBoolean();
            var rule = testCase.get("rule").asString();
            tests.add(DynamicTest.dynamicTest(
                    "%s -> %s is %s (%s)".formatted(fromAlias, toAlias, expected ? "allowed" : "refused", rule),
                    () -> {
                        var from = require(contract, fromAlias);
                        var to = require(contract, toAlias);
                        assertThat(types.assignable(from, to))
                                .describedAs("%s -> %s :: %s", from.describe(), to.describe(), rule)
                                .isEqualTo(expected);
                    }));
        });
        assertThat(tests).describedAs("the contract file should not be empty").isNotEmpty();
        return tests;
    }

    @Test
    void codecRoundTripsEveryTypeInTheContract() {
        load().types().forEach((alias, type) ->
                assertThat(PortTypeCodec.read(PortTypeCodec.write(type)))
                        .describedAs("round trip of '%s'", alias)
                        .isEqualTo(type));
    }

    @Test
    void rejectionMessagesNameTheActualProblem() {
        var contract = load();
        var missingField = require(contract, "FileRefMissingField");
        var fileRef = require(contract, "FileRef");

        assertThat(types.explainRejection(missingField, fileRef))
                .contains("extension")
                .contains("FileRef");

        assertThat(types.explainRejection(require(contract, "FileRef"), require(contract, "FileList")))
                .contains("list");
    }

    @Test
    void explainingACompatiblePairIsAProgrammingError() {
        assertThatThrownBy(() -> types.explainRejection(Types.TEXT, Types.TEXT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PortType require(Contract contract, String alias) {
        var type = contract.types().get(alias);
        if (type == null) {
            throw new IllegalArgumentException(
                    "Contract case references undeclared type alias '" + alias + "'");
        }
        return type;
    }
}
