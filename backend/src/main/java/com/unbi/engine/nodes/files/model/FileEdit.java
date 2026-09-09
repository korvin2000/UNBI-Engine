package com.unbi.engine.nodes.files.model;

/**
 * The outcome of applying a replacement to one file.
 *
 * @param applied false in a dry run, where replacements are counted but nothing is written
 */
public record FileEdit(String path, int replacements, boolean applied) {}
