package com.unbi.engine.settings.bundle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A section whose items are files under one directory: profiles, presets, the workflow library.
 *
 * <p>The three stores differ in what a valid file is and what it may be called, and agree on
 * everything else — walk the folder, copy the files, and on the way back in check the name, check
 * the content, and apply the conflict policy. This class is the "everything else"; a subclass says
 * which relative paths belong to it and what a valid file looks like.
 *
 * <p>Every incoming path is treated as hostile until it has been shown to be one of this section's
 * own file names: a bundle is a zip somebody made, and {@code presets/../../.bashrc} is exactly the
 * entry name a zip can carry.
 */
public abstract class FileTreeSection implements SettingsSection {

    /** No profile, preset or workflow is this large; an entry that is, is not one of those. */
    static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;

    private final String id;
    private final String label;
    private final String description;
    private final int order;

    protected FileTreeSection(String id, String label, String description, int order) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.order = order;
    }

    /** The directory the files live in. It may not exist yet. */
    protected abstract Path root();

    /** Whether a relative path (forward slashes, no leading slash) names one of this section's files. */
    protected abstract boolean accepts(String relative);

    /** @throws IllegalArgumentException with the reason, when the content is not a valid file here */
    protected abstract void validate(String relative, byte[] content);

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public int count() {
        return files().size();
    }

    @Override
    public void export(BundleWriter writer) throws IOException {
        var root = root();
        for (var file : files()) {
            writer.add(id + "/" + relativeOf(root, file), Files.readAllBytes(file));
        }
    }

    @Override
    public SectionReport importFrom(BundleReader reader, ConflictPolicy policy) throws IOException {
        var root = root().toAbsolutePath().normalize();
        var prefix = id + "/";
        int imported = 0;
        int replaced = 0;
        int skipped = 0;
        var problems = new ArrayList<String>();

        for (var path : reader.entries(prefix)) {
            var relative = path.substring(prefix.length());
            try {
                if (!isSafe(relative) || !accepts(relative)) {
                    throw new IllegalArgumentException("not a " + label + " file");
                }
                var target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException("not inside " + root);
                }
                var content = reader.read(path);
                validate(relative, content);

                if (Files.exists(target)) {
                    switch (policy) {
                        case SKIP -> {
                            skipped++;
                            continue;
                        }
                        case REPLACE -> {
                            write(target, content);
                            replaced++;
                            continue;
                        }
                        case KEEP_BOTH -> target = freeName(target);
                    }
                }
                write(target, content);
                imported++;
            } catch (IOException | RuntimeException problem) {
                problems.add(relative + ": " + (problem.getMessage() == null
                        ? problem.getClass().getSimpleName() : problem.getMessage()));
            }
        }
        return new SectionReport(id, imported, replaced, skipped, problems);
    }

    /** Every accepted file under the root, sorted, or nothing when the root is not there yet. */
    private List<Path> files() {
        var root = root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> accepts(relativeOf(root, file)))
                    .sorted()
                    .toList();
        } catch (IOException | UncheckedIOException unreadable) {
            return List.of();
        }
    }

    private static String relativeOf(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static void write(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    /**
     * A relative path that can only land inside the root: no absolute form, no parent segments, no
     * empty segments, no separator a zip tool might have written the other way, nothing a filesystem
     * would refuse or reinterpret.
     */
    static boolean isSafe(String relative) {
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains("\\") || relative.contains(":")) {
            return false;
        }
        for (var segment : relative.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
            for (var i = 0; i < segment.length(); i++) {
                if (segment.charAt(i) < 0x20 || segment.charAt(i) == 0x7f) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@code name.json} taken → {@code name-2.json}, then {@code name-3.json}; the suffix goes before the first dot. */
    static Path freeName(Path taken) {
        var fileName = taken.getFileName().toString();
        var dot = fileName.indexOf('.');
        var stem = dot < 0 ? fileName : fileName.substring(0, dot);
        var rest = dot < 0 ? "" : fileName.substring(dot);
        for (var suffix = 2; suffix < 1000; suffix++) {
            var candidate = taken.resolveSibling(stem + "-" + suffix + rest);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Too many copies of " + fileName);
    }
}
