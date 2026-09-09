package com.unbi.engine.transport.codec;

import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeInput;
import com.unbi.engine.core.node.NodeOutput;
import com.unbi.engine.core.node.Widget;
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
            }
            case Widget.NumberField field -> {
                node.put("kind", "number");
                node.put("min", field.min());
                node.put("max", field.max());
                node.put("step", field.step());
                node.put("unit", field.unit());
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
                ArrayNode options = node.putArray("options");
                dropdown.options().forEach(option -> {
                    var entry = NODES.objectNode();
                    entry.put("value", option.value());
                    entry.put("label", option.label());
                    options.add(entry);
                });
            }
            case Widget.DirectoryPicker ignored -> node.put("kind", "directory");
            case Widget.FilePicker picker -> {
                node.put("kind", "file");
                var extensions = node.putArray("extensions");
                picker.extensions().forEach(extensions::add);
            }
        }
        return node;
    }
}
