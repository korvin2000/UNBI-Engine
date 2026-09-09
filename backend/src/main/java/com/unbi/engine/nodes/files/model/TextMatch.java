package com.unbi.engine.nodes.files.model;

/**
 * One hit from a text search.
 *
 * @param line   1-based, matching what editors show
 * @param column 1-based offset of the match within the line
 * @param text   the whole line, trimmed for display
 */
public record TextMatch(String path, int line, int column, String text) {}
