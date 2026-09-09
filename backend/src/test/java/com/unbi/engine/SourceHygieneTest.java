package com.unbi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards against invisible characters in source files.
 *
 * <p>Not hypothetical: a stray NUL byte reached a string literal in this repository, compiled
 * without complaint, and only surfaced as the file-search node quietly skipping its own source as
 * "binary". A defect that survives the compiler and hides from a diff is exactly the kind worth one
 * cheap test.
 */
class SourceHygieneTest {

    private static final Path SOURCE_ROOT = Path.of("src").normalize();

    @Test
    @DisplayName("no source file contains a NUL or other stray control character")
    void sourcesContainNoStrayControlCharacters() throws IOException {
        var offenders = new ArrayList<String>();

        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var name = file.getFileName().toString();
                if (!name.endsWith(".java") && !name.endsWith(".yaml") && !name.endsWith(".json")) {
                    continue;
                }
                var content = Files.readString(file);
                for (int index = 0; index < content.length(); index++) {
                    if (isStrayControl(content.charAt(index))) {
                        offenders.add("%s contains U+%04X at offset %d"
                                .formatted(file, (int) content.charAt(index), index));
                        break;
                    }
                }
            }
        }

        assertThat(offenders).describedAs("source files with invisible control characters").isEmpty();
    }

    @Test
    void theSourceTreeWasActuallyScanned() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            List<Path> java = files.filter(path -> path.toString().endsWith(".java")).toList();
            // A passing scan of zero files would be a silently useless test.
            assertThat(java).hasSizeGreaterThan(20);
        }
    }

    /** Tab, newline and carriage return are legitimate; everything else below space is not. */
    private static boolean isStrayControl(char character) {
        return character < 0x20 && character != '\t' && character != '\n' && character != '\r';
    }
}
