package com.unbi.engine.settings.bundle;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Writes and reads {@code .ucfg} bundles: a zip with a readable manifest and one folder per section.
 *
 * <p>A zip because it is the container everybody already has a tool for: a bundle written here opens
 * in 7-Zip, and a folder of profiles inside it reads as the folder it came from. AES-256 when a
 * password is given, which the same tools understand — but only for the section entries; the
 * manifest stays in the clear so the import dialog can describe the file before asking for anything.
 *
 * <p>Which sections exist is a matter of which beans exist. This class knows their ids and their
 * order, and nothing about what any of them contains.
 */
@Component
public class SettingsBundle {

    public static final String EXTENSION = ".ucfg";

    /** A bundle larger than this is not settings; it is a mistake, and it is refused before it is read. */
    static final long MAX_BUNDLE_BYTES = 256L * 1024 * 1024;

    private final List<SettingsSection> sections;
    private final String engineVersion;

    @Autowired
    public SettingsBundle(List<SettingsSection> discovered, ObjectProvider<BuildProperties> build) {
        this(discovered, Optional.ofNullable(build.getIfAvailable()).map(BuildProperties::getVersion).orElse("development"));
    }

    /** The test seam: sections by hand, a version to write into the manifest. */
    public SettingsBundle(List<SettingsSection> discovered, String engineVersion) {
        var byId = new LinkedHashMap<String, SettingsSection>();
        for (var section : discovered.stream()
                .sorted(Comparator.comparingInt(SettingsSection::order).thenComparing(SettingsSection::id))
                .toList()) {
            if (!section.id().matches("[a-z][a-z0-9-]{0,30}")) {
                throw new IllegalStateException("Not a usable settings section id: " + section.id());
            }
            if (byId.putIfAbsent(section.id(), section) != null) {
                throw new IllegalStateException("Two settings sections claim the id " + section.id());
            }
        }
        this.sections = List.copyOf(byId.values());
        this.engineVersion = engineVersion;
    }

    /** What a bundle turned out to hold, before anything is imported. */
    public record Inspection(BundleManifest manifest, List<Entry> sections) {

        /** A section in the file: how many items, and whether this engine has a section for it. */
        public record Entry(String id, int count, boolean known) {}
    }

    public record ImportReport(List<SettingsSection.SectionReport> sections) {}

    public List<SettingsSection> sections() {
        return sections;
    }

    public Optional<SettingsSection> section(String id) {
        return sections.stream().filter(section -> section.id().equals(id)).findFirst();
    }

    /**
     * The bundle as bytes: in memory, because a bundle is kilobytes to a few megabytes and the
     * response is one download.
     *
     * @param password blank for an unencrypted bundle
     */
    public byte[] export(Collection<String> ids, String password) throws IOException {
        var chosen = chosen(ids);
        var key = key(password);
        var counts = new LinkedHashMap<String, Integer>();
        chosen.forEach(section -> counts.put(section.id(), section.count()));
        var manifest = new BundleManifest(engineVersion, Instant.now(), key != null, counts);

        var bytes = new ByteArrayOutputStream();
        try (var zip = key == null ? new ZipOutputStream(bytes) : new ZipOutputStream(bytes, key)) {
            add(zip, BundleManifest.ENTRY, manifest.toJson(), false);
            for (var section : chosen) {
                section.export((path, content) -> add(zip, path, content, key != null));
            }
        }
        return bytes.toByteArray();
    }

    /** Reads the manifest and says what is inside, without a password. */
    public Inspection inspect(Path file) throws IOException {
        checkSize(file);
        try (var zip = new ZipFile(file.toFile())) {
            var manifest = manifest(zip);
            var entries = new ArrayList<Inspection.Entry>();
            manifest.counts().forEach((id, count) -> entries.add(
                    new Inspection.Entry(id, count, section(id).isPresent())));
            return new Inspection(manifest, entries);
        }
    }

    /**
     * Imports the chosen sections.
     *
     * <p>The password is verified against the first encrypted entry before any section is touched,
     * so a wrong one fails whole rather than half way through the presets.
     */
    public ImportReport importFrom(Path file, Collection<String> ids, String password, ConflictPolicy policy)
            throws IOException {
        var chosen = chosen(ids);
        checkSize(file);
        var key = key(password);
        try (var zip = key == null ? new ZipFile(file.toFile()) : new ZipFile(file.toFile(), key)) {
            var manifest = manifest(zip);
            if (manifest.encrypted() || zip.isEncrypted()) {
                if (key == null) {
                    throw new IllegalArgumentException("This bundle is password-protected. Enter its password.");
                }
                verifyPassword(zip);
            }
            var reader = readerOver(zip);
            var reports = new ArrayList<SettingsSection.SectionReport>();
            for (var section : chosen) {
                reports.add(section.importFrom(reader, policy));
            }
            return new ImportReport(reports);
        }
    }

    private List<SettingsSection> chosen(Collection<String> ids) {
        var wanted = new LinkedHashSet<String>();
        for (var id : ids == null ? List.<String>of() : ids) {
            if (id != null && !id.isBlank()) {
                wanted.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one kind of setting.");
        }
        var chosen = new ArrayList<SettingsSection>();
        for (var section : sections) {
            if (wanted.remove(section.id())) {
                chosen.add(section);
            }
        }
        if (!wanted.isEmpty()) {
            throw new IllegalArgumentException("No such setting section: " + String.join(", ", wanted));
        }
        return chosen;
    }

    private static char[] key(String password) {
        return password == null || password.isBlank() ? null : password.toCharArray();
    }

    private static void add(ZipOutputStream zip, String path, byte[] content, boolean encrypt) throws IOException {
        var parameters = new ZipParameters();
        parameters.setFileNameInZip(path);
        parameters.setCompressionMethod(CompressionMethod.DEFLATE);
        if (encrypt) {
            parameters.setEncryptFiles(true);
            parameters.setEncryptionMethod(EncryptionMethod.AES);
            parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        }
        zip.putNextEntry(parameters);
        zip.write(content);
        zip.closeEntry();
    }

    private static BundleManifest manifest(ZipFile zip) throws IOException {
        FileHeader header;
        try {
            header = zip.getFileHeader(BundleManifest.ENTRY);
        } catch (ZipException notAZip) {
            throw new IllegalArgumentException("This is not a UNBI settings bundle.", notAZip);
        }
        if (header == null) {
            throw new IllegalArgumentException("This is not a UNBI settings bundle.");
        }
        return BundleManifest.parse(readFully(zip, header));
    }

    private static void verifyPassword(ZipFile zip) throws IOException {
        for (var header : zip.getFileHeaders()) {
            if (header.isEncrypted() && !header.isDirectory()) {
                readFully(zip, header);
                return;
            }
        }
    }

    private static byte[] readFully(ZipFile zip, FileHeader header) throws IOException {
        if (header.getUncompressedSize() > FileTreeSection.MAX_ENTRY_BYTES) {
            throw new IOException(header.getFileName() + " is too large for a settings bundle");
        }
        try (var in = zip.getInputStream(header)) {
            var bytes = in.readNBytes(FileTreeSection.MAX_ENTRY_BYTES + 1);
            if (bytes.length > FileTreeSection.MAX_ENTRY_BYTES) {
                throw new IOException(header.getFileName() + " is too large for a settings bundle");
            }
            return bytes;
        } catch (ZipException refused) {
            if (refused.getType() == ZipException.Type.WRONG_PASSWORD
                    || String.valueOf(refused.getMessage()).toLowerCase(Locale.ROOT).contains("password")) {
                throw new IllegalArgumentException("Wrong password.", refused);
            }
            throw refused;
        }
    }

    private static BundleReader readerOver(ZipFile zip) throws IOException {
        var headers = zip.getFileHeaders();
        return new BundleReader() {
            @Override
            public List<String> entries(String prefix) {
                return headers.stream()
                        .filter(header -> !header.isDirectory() && header.getFileName().startsWith(prefix))
                        .map(FileHeader::getFileName)
                        .toList();
            }

            @Override
            public byte[] read(String path) throws IOException {
                var header = zip.getFileHeader(path);
                if (header == null) {
                    throw new FileNotFoundException(path);
                }
                return readFully(zip, header);
            }
        };
    }

    private static void checkSize(Path file) throws IOException {
        if (java.nio.file.Files.size(file) > MAX_BUNDLE_BYTES) {
            throw new IllegalArgumentException("That file is too large to be a settings bundle.");
        }
    }

    /** For the dialog: every section, with what it holds right now. */
    public Map<String, Integer> counts() {
        var counts = new LinkedHashMap<String, Integer>();
        sections.forEach(section -> counts.put(section.id(), section.count()));
        return counts;
    }
}
