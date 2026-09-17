package com.unbi.engine.settings;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one directory the engine has to be told about, or guess: where {@code settings.json} lives.
 *
 * <p>Everything else the engine keeps — profiles, credentials, presets, the workflow library — can
 * be pointed elsewhere from that file, which is exactly why the file itself cannot move: a setting
 * that says where the settings are is a setting nobody can find. Defaults to {@code ~/.unbi-engine};
 * {@code unbi.home} overrides it, and the older {@code unbi.data-dir} still does too, so a launch
 * script written against the first release keeps working.
 */
@Component
public class EngineHome {

    private final Path root;

    @Autowired
    public EngineHome(@Value("${unbi.home:}") String home, @Value("${unbi.data-dir:}") String legacy) {
        this(resolve(home, legacy));
    }

    public EngineHome(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** A file directly under the home. Not created, and its parent is not either. */
    public Path file(String name) {
        return root.resolve(name);
    }

    private static Path resolve(String home, String legacy) {
        var configured = isBlank(home) ? legacy : home;
        return isBlank(configured)
                ? Path.of(System.getProperty("user.home"), ".unbi-engine")
                : Path.of(configured.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
