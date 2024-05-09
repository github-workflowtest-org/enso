package org.enso.compiler.dump;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.enso.compiler.core.IR;
import org.enso.compiler.core.ir.MetadataStorage;
import org.enso.compiler.pass.IRPass.IRMetadata;
import org.enso.compiler.pass.resolve.DocumentationComments;

/**
 * Represents a node in the GraphViz graph.
 *
 * @param id Identifier of the node. Used to refer to the node in edges. Must be unique.
 * @param header The first line of the label. It is not justified to the left. Can be null.
 * @param multiLineLabel A label in GraphViz is a simple textual attribute. To make it multi-line,
 *     we need to escape newlines with "\\n".
 * @param additionalAttrs Additional attributes to specify for the node, apart from `label`.
 * @param object The underlying object from which the node was created.
 */
record GraphVizNode(
    String id,
    String header,
    List<String> multiLineLabel,
    Map<String, String> additionalAttrs,
    Object object) {
  public String toGraphViz() {
    var sb = new StringBuilder();
    sb.append(id);
    if (!additionalAttrs.isEmpty()) {
      sb.append(" [");
      additionalAttrs.forEach(
          (k, v) -> {
            assert Utils.hasOneLine(k) : k;
            assert Utils.hasOneLine(v) : v;
            sb.append(k);
            sb.append("=");
            if (!Utils.isSurroundedByQuotes(v)) {
              sb.append("\"").append(v).append("\"");
            } else {
              sb.append(v);
            }
            sb.append(", ");
          });
      sb.append("label=\"");
    } else {
      sb.append(" [label=\"");
    }
    // Id is the "header" of the node - the first line of the label.
    // It is not justified to the left.
    if (header != null) {
      sb.append(header).append("\\n");
    }
    for (var line : multiLineLabel) {
      var formattedLine = line.replace("\"", "\\\"");
      assert Utils.hasOneLine(formattedLine);
      sb.append(formattedLine);
      // Justify every line to the left - it looks better in the resulting graph.
      sb.append("\\l");
    }
    sb.append("\"];");
    return sb.toString();
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public boolean equals(Object otherObj) {
    if (otherObj instanceof GraphVizNode otherNode) {
      return id.equals(otherNode.id);
    }
    return false;
  }

  static class Builder {
    private String id;
    private String header;
    private List<String> labelLines = new ArrayList<>();
    private Map<String, String> additionalAttrs = new HashMap<>();
    private Object object;

    private static final List<Class<? extends IRMetadata>> metadataToSkip =
        List.of(DocumentationComments.Doc.class);

    static Builder fromObject(Object obj) {
      var className = className(obj);
      var id = Utils.id(obj);
      var bldr = new Builder();
      bldr.object = obj;
      bldr.id = id;
      bldr.header = id;
      bldr.addLabelLine("className: " + className);
      return bldr;
    }

    /**
     * Does not include some common info in the labels like class name, only create an empty
     * builder.
     */
    static Builder fromObjectPlain(Object obj) {
      var id = Utils.id(obj);
      var bldr = new Builder();
      bldr.object = obj;
      bldr.id = id;
      bldr.header = null;
      return bldr;
    }

    static Builder fromIr(IR ir) {
      var className = className(ir);
      var bldr = new Builder();
      var id = Utils.id(ir);
      bldr.object = ir;
      bldr.id = id;
      bldr.header = id;
      bldr.addLabelLine("className: " + className);
      if (ir.location().isDefined()) {
        var loc = ir.location().get();
        bldr.addLabelLine("location_start: " + loc.start());
        bldr.addLabelLine("location_end: " + loc.end());
      } else {
        bldr.addLabelLine("location: null");
      }
      bldr.addLabelLine("id: " + ir.getId());
      if (!isPassDataEmpty(ir.passData())) {
        bldr.addLabelLine("pass_data: ");
        ir.passData()
            .map(
                (pass, metadata) -> {
                  if (!metadataToSkip.contains(metadata.getClass())) {
                    var metaName = metadata.metadataName();
                    bldr.addLabelLine("  - " + metaName);
                  }
                  return null;
                });
      } else {
        bldr.addLabelLine("pass_data: []");
      }
      return bldr;
    }

    private static boolean isPassDataEmpty(MetadataStorage passData) {
      int[] counter = new int[] {0};
      passData.map(
          (pass, data) -> {
            counter[0]++;
            return null;
          });
      return counter[0] == 0;
    }

    Builder addLabelLine(String line) {
      labelLines.add(line);
      return this;
    }

    Builder addAttribute(String key, String value) {
      additionalAttrs.put(key, value);
      return this;
    }

    GraphVizNode build() {
      Objects.requireNonNull(id);
      Objects.requireNonNull(labelLines);
      Objects.requireNonNull(additionalAttrs);
      Objects.requireNonNull(object);
      return new GraphVizNode(id, header, labelLines, additionalAttrs, object);
    }

    private static String className(Object obj) {
      return Arrays.stream(obj.getClass().getName().split("\\."))
          .dropWhile(
              item ->
                  item.equals("org")
                      || item.equals("enso")
                      || item.equals("compiler")
                      || item.equals("core"))
          .collect(Collectors.joining("."));
    }
  }
}
