package com.unbi.engine.settings.bundle;

import java.io.IOException;

/** Where a section puts its entries while a bundle is being written. Paths use {@code /}. */
public interface BundleWriter {

    void add(String path, byte[] content) throws IOException;
}
