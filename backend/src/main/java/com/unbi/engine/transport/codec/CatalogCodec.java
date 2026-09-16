package com.unbi.engine.transport.codec;

import com.unbi.engine.core.node.NodeAction;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeInput;
import com.unbi.engine.core.node.NodeOutput;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.json.JsonValues;
import java.util.Collection;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes the node catalog for the editor.
 *
 * <p>This is the payload that makes the frontend generic. The browser has no compiled knowledge of
 * any node: it draws whatever this describes, using one component per widget kind. A node pack added
 * to the classpath shows up in the palette after a refresh, with no frontend change at all.
 *
 * <p>The widget switch is exhaustive over the sealed {@link Widget} hierarchy, so adding a widget
 * kind breaks this file at compile time rather than silently shipping a node the UI cannot draw.
 */
public final class CatalogCodec {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private CatalogCodec() {}

    public static ObjectNode catalog(Collection<NodeDescriptor> descriptors) {
        var root = NODES.objectNode();
        var array = root.putArray("nodes");
        descriptors.forEach(descriptor -> array.add(descriptor(descriptor)));
        return root;
    }

    public static ObjectNode descriptor(NodeDescriptor descriptor) {
        var node = NODES.objectNode();
        node.put("id", descriptor.id());
        node.put("label", descriptor.label());
        node.put("category", descriptor.category());
        node.put("subcategory", descriptor.subcategory());
        node.put("icon", descriptor.icon());
        node.put("accent", descriptor.accent());
        node.put("description", descriptor.description());

        var inputs = node.putArray("inputs");
        descriptor.inputs().forEach(input -> inputs.add(input(input)));
        var outputs = node.putArray("outputs");
        descriptor.outputs().forEach(output -> outputs.add(output(output)));
        var actions = node.putArray("actions");
        descriptor.actions().forEach(action -> actions.add(action(action)));
        return node;
    }

    private static ObjectNode input(NodeInput input) {
        var node = NODES.objectNode();
        node.put("key", input.key());
        node.put("label", input.label());
        node.set("type", PortTypeCodec.write(input.type()));
        node.put("required", input.required());
        node.put("connectable", input.connectable());
        node.set("widget", input.hasWidget() ? widget(input.widget()) : NODES.nullNode());
        node.set("default", JsonValues.of(input.defaultValue()));
        node.put("hint", input.hint());
        node.put("advanced", input.advanced());
        node.put("group", input.group());
        if (input.showWhen() != null) {
            var condition = node.putObject("showWhen");
            condition.put("key", input.showWhen().key());
            var values = condition.putArray("values");
            input.showWhen().values().forEach(values::add);
        }
        return node;
    }

    private static ObjectNode action(NodeAction action) {
        var node = NODES.objectNode();
        node.put("key", action.key());
        node.put("label", action.label());
        node.put("icon", action.icon());
        node.put("appliesTo", action.appliesTo());
        node.put("kind", action.kind().name().toLowerCase(java.util.Locale.ROOT));
        node.put("automatic", action.automatic());
        return node;
    }

    private static ObjectNode output(NodeOutput output) {
        var node = NODES.objectNode();
        node.put("key", output.key());
        node.put("label", output.label());
        node.set("type", PortTypeCodec.write(output.type()));
        node.put("hint", output.hint());
        return node;
    }

    private static ObjectNode widget(Widget widget) {
        var node = NODES.objectNode();
        switch (widget) {
            case Widget.TextField field -> {
                node.put("kind", "text");
                node.put("placeholder", field.placeholder());
                node.put("multiline", field.multiline());
                node.put("rows", field.rows());
                node.put("monospace", field.monospace());
                node.put("editor", field.editor());
                node.put("library", field.library());
                node.put("libraryKey", field.libraryKey());
            }
            case Widget.NumberField field -> {
                node.put("kind", "number");
                node.put("min", field.min());
                node.put("max", field.max());
                node.put("step", field.step());
                node.put("unit", field.unit());
                node.put("optional", field.optional());
                node.put("blankLabel", field.blankLabel());
            }
            case Widget.Slider slider -> {
                node.put("kind", "slider");
                node.put("min", slider.min());
                node.put("max", slider.max());
                node.put("step", slider.step());
            }
            case Widget.Toggle ignored -> node.put("kind", "toggle");
            case Widget.Dropdown dropdown -> {
                node.put("kind", "dropdown");
                node.set("options", options(dropdown.options()));
                node.put("optionsKey", dropdown.optionsKey());
                node.put("allowCustom", dropdown.allowCustom());
                if (dropdown.narrowing() != null) {
                    var narrowing = node.putObject("narrowing");
                    narrowing.put("socket", dropdown.narrowing().socket());
                    narrowing.put("listKey", dropdown.narrowing().listKey());
                    var always = narrowing.putArray("always");
                    dropdown.narrowing().always().forEach(always::add);
                }
            }
            case Widget.MultiSelect select -> {
                node.put("kind", "multiselect");
                node.set("options", options(select.options()));
            }
            case Widget.KeyValue pairs -> {
                node.put("kind", "keyvalue");
                node.put("keyPlaceholder", pairs.keyPlaceholder());
                node.put("valuePlaceholder", pairs.valuePlaceholder());
            }
            case Widget.DirectoryPicker ignored -> node.put("kind", "directory");
            case Widget.FilePicker picker -> {
                node.put("kind", "file");
                var extensions = node.putArray("extensions");
                picker.extensions().forEach(extensions::add);
            }
            case Widget.FileList list -> {
                node.put("kind", "filelist");
                var extensions = node.putArray("extensions");
                list.extensions().forEach(extensions::add);
            }
            case Widget.Profile profile -> {
                node.put("kind", "profile");
                node.put("schema", profile.schema());
            }
            case Widget.Credential ignored -> node.put("kind", "credential");
            case Widget.Display display -> {
                node.put("kind", "display");
                node.put("style", display.style().name().toLowerCase(java.util.Locale.ROOT));
                node.put("unit", display.unit());
            }
        }
        return node;
    }

    /** The widget encoding, for anything outside the catalog that renders with the same controls. */
    public static ObjectNode widgetNode(Widget widget) {
        return widget(widget);
    }

    /** One input, for a profile schema served to the editor as a form. */
    public static ObjectNode inputNode(NodeInput input) {
        return input(input);
    }

    private static ArrayNode options(java.util.List<Widget.Option> options) {
        var array = NODES.arrayNode();
        options.forEach(option -> {
            var entry = NODES.objectNode();
            entry.put("value", option.value());
            entry.put("label", option.label());
            array.add(entry);
        });
        return array;
    }
}
