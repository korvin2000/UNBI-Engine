package com.unbi.engine.settings.bundle;

import java.io.IOException;
import java.util.List;

/** A bundle being read: the entries under a prefix, and the bytes of one of them. */
public interface BundleReader {

    /** Entry paths starting with the prefix, directories excluded, in archive order. */
    List<String> entries(String prefix);

    /** @throws IOException when the entry is missing, unreadable, or larger than a section may accept */
    byte[] read(String path) throws IOException;
}
