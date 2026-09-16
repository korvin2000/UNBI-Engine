package com.unbi.engine.transport;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lets the editor browse the machine the engine runs on.
 *
 * <p>This exists because the client is a browser and the work is not. A file input in the page can
 * only produce a browser-sandboxed handle to a file the user uploaded, while every node in the pack
 * takes a path that the <em>engine</em> must be able to open — so the only correct picker is one
 * that lists the engine's filesystem. That is what this endpoint is for: it is the server side of
 * the folder and file dialogs, and nothing else reads or writes through it.
 *
 * <p>Read-only by construction. It lists names, sizes and timestamps, and it opens a file in exactly
 * one place — {@link #text} — so that the prompt editor can start from a draft somebody already
 * wrote. That one reader is bounded and refuses anything that is not text, because "read any file on
 * the engine's host and hand it to the browser" is a far larger promise than a picker needs to make.
 *
 * <p>A directory it cannot read is reported as an error on the response rather than as a failed
 * request, because a picker that shows a permission problem in place is far more usable than one
 * that appears to be broken.
 */
@RestController
@RequestMapping("/api")
public class FileSystemController {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /**
     * Bound on purpose. A directory with a hundred thousand entries would freeze the browser long
     * before the user found anything in it, and a picker is for choosing, not for auditing.
     */
    private static final int MAX_ENTRIES = 2_000;

    /**
     * The largest file the text reader will return.
     *
     * <p>A prompt is a page or two. This is generous by three orders of magnitude and still small
     * enough that pointing the reader at a database dump fails with a sentence rather than by
     * filling a browser tab with a gigabyte of binary.
     */
    private static final long MAX_TEXT_BYTES = 2 * 1024 * 1024;

    @GetMapping("/fs")
    public ObjectNode list(
            @RequestParam(name = "path", required = false) String requested,
            @RequestParam(name = "directoriesOnly", defaultValue = "false") boolean directoriesOnly,
            @RequestParam(name = "extensions", required = false) String extensions) {

        var wanted = parseExtensions(extensions);
        var response = NODES.objectNode();
        var start = resolve(requested);
        var directory = start.directory();

        response.put("path", directory.toString());
        var parent = directory.getParent();
        response.put("parent", parent == null ? null : parent.toString());
        response.put("separator", directory.getFileSystem().getSeparator());
        var roots = response.putArray("roots");
        directory.getFileSystem().getRootDirectories().forEach(root -> roots.add(root.toString()));

        var entries = response.putArray("entries");
        String failure = start.failure();
        if (failure == null) {
            try {
                for (var entry : read(directory, directoriesOnly, wanted)) {
                    entries.add(entry);
                }
            } catch (IOException | RuntimeException unreadable) {
                failure = "Cannot read " + directory;
            }
        }
        response.put("error", failure);
        return response;
    }

    /**
     * One text file, for a field that is being filled in from a draft.
     *
     * <p>Deliberately narrow: a size limit, a refusal for anything with NUL bytes in it, and a
     * response that says which file it read. Nothing here writes, and nothing takes a path from
     * anywhere but the picker the user just clicked through.
     */
    @GetMapping("/fs/text")
    public ObjectNode text(@RequestParam("path") String requested) {
        var response = NODES.objectNode();
        response.put("path", requested == null ? "" : requested);
        try {
            var file = Path.of(requested.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                return failed(response, "Not a file: " + file);
            }
            var size = Files.size(file);
            if (size > MAX_TEXT_BYTES) {
                return failed(response, "%s is %,d bytes; the editor reads at most %,d."
                        .formatted(file.getFileName(), size, MAX_TEXT_BYTES));
            }
            var bytes = Files.readAllBytes(file);
            for (var value : bytes) {
                if (value == 0) {
                    // A NUL byte is the cheapest reliable sign of a binary file, and pasting one
                    // into a prompt box produces a mess with no error attached to it.
                    return failed(response, file.getFileName() + " does not look like a text file.");
                }
            }
            response.put("path", file.toString());
            response.put("text", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            response.put("error", (String) null);
            return response;
        } catch (IOException | RuntimeException unreadable) {
            return failed(response, "Cannot read " + requested);
        }
    }

    private static ObjectNode failed(ObjectNode response, String message) {
        response.put("text", "");
        response.put("error", message);
        return response;
    }

    /**
     * Where the listing should start.
     *
     * <p>An unusable path is not an error to throw: the picker opens somewhere sensible and says
     * what went wrong, which is what a user who mistyped a path actually needs.
     */
    private static Start resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return new Start(home(), null);
        }
        try {
            var path = Path.of(requested.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return new Start(path, null);
            }
            var parent = path.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                // A file was passed in — most likely the current value of a file field. Open its
                // folder. If the file is not there at all, say so: the dialog has just moved the
                // user somewhere they did not ask for, and silence would look like a bug.
                return new Start(parent, Files.exists(path) ? null : "Cannot open " + path);
            }
            return new Start(home(), "Cannot open " + path);
        } catch (InvalidPathException malformed) {
            return new Start(home(), "Not a valid path: " + requested);
        }
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    }

    private static List<ObjectNode> read(Path directory, boolean directoriesOnly, Set<String> wanted)
            throws IOException {
        var found = new ArrayList<ObjectNode>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (var candidate : stream) {
                if (found.size() >= MAX_ENTRIES) {
                    break;
                }
                var entry = describe(candidate, directoriesOnly, wanted);
                if (entry != null) {
                    found.add(entry);
                }
            }
        }
        // Folders first, then files, each alphabetically: the order everyone expects from a picker.
        found.sort(Comparator
                .comparing((ObjectNode node) -> !node.get("directory").asBoolean())
                .thenComparing(node -> node.get("name").asString(), String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    /** {@code null} when the entry is filtered out or vanished mid-listing. */
    private static ObjectNode describe(Path candidate, boolean directoriesOnly, Set<String> wanted) {
        try {
            if (Files.isHidden(candidate)) {
                return null;
            }
            var isDirectory = Files.isDirectory(candidate);
            if (!isDirectory) {
                if (directoriesOnly || !Files.isRegularFile(candidate)) {
                    return null;
                }
                if (!wanted.isEmpty() && !wanted.contains(extensionOf(candidate))) {
                    return null;
                }
            }
            var node = NODES.objectNode();
            var name = candidate.getFileName();
            node.put("name", name == null ? candidate.toString() : name.toString());
            node.put("path", candidate.toString());
            node.put("directory", isDirectory);
            node.put("size", isDirectory ? 0L : Files.size(candidate));
            node.put("modified", Files.getLastModifiedTime(candidate).toInstant().toString());
            return node;
        } catch (IOException | RuntimeException vanished) {
            // A file can disappear between being listed and being measured, and one racing entry
            // should not fail a listing of ten thousand.
            return null;
        }
    }

    private static String extensionOf(Path file) {
        var name = file.getFileName();
        if (name == null) {
            return "";
        }
        var text = name.toString();
        var dot = text.lastIndexOf('.');
        return dot < 0 ? "" : text.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Accepts {@code txt,.md; json} — the same forgiving parsing the file nodes use. */
    private static Set<String> parseExtensions(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(token -> token.trim().toLowerCase(Locale.ROOT))
                .map(token -> token.startsWith(".") ? token.substring(1) : token)
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private record Start(Path directory, String failure) {}
}
