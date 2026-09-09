package com.unbi.engine.nodes.files;

import com.unbi.engine.core.type.PortType;
import com.unbi.engine.core.type.Types;

/**
 * The type vocabulary owned by the file-processing pack.
 *
 * <p>A pack declares its own types next to the nodes that use them. Nothing in {@code core} knows
 * that files exist, which is what lets a second pack be added without touching the engine.
 *
 * <p>Every struct here is mirrored by a record in {@code model}, and
 * {@code FileTypeShapeTest} asserts the two never drift.
 */
public final class FileTypes {

    private FileTypes() {}

    /** A filesystem directory. Distinct from Text so a path cannot be wired into any string input. */
    public static final PortType DIRECTORY = PortType.primitive("Directory");

    public static final PortType FILE_REF = Types.struct("FileRef", Types.fields(
            "path", Types.TEXT,
            "name", Types.TEXT,
            "extension", Types.TEXT,
            "size", Types.NUMBER));

    public static final PortType TEXT_MATCH = Types.struct("TextMatch", Types.fields(
            "path", Types.TEXT,
            "line", Types.NUMBER,
            "column", Types.NUMBER,
            "text", Types.TEXT));

    public static final PortType FILE_EDIT = Types.struct("FileEdit", Types.fields(
            "path", Types.TEXT,
            "replacements", Types.NUMBER,
            "applied", Types.BOOLEAN));

    public static final PortType FILE_LIST = PortType.list(FILE_REF);
    public static final PortType MATCH_LIST = PortType.list(TEXT_MATCH);
    public static final PortType EDIT_LIST = PortType.list(FILE_EDIT);
}
