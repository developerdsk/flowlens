package dev.flowlens;

import dev.flowlens.model.Diagnostic;
import dev.flowlens.source.SourceFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads MyBatis mapper XML ({@code <mapper namespace="...">}) into statements keyed by
 * {@code namespace.id}. External DTDs and entities are never loaded (no network, no XXE).
 */
class MyBatisXml {
    private static final Set<String> STATEMENTS = Set.of("select", "insert", "update", "delete");

    private final ProjectModel model;

    MyBatisXml(ProjectModel model) {
        this.model = model;
    }

    void index(List<SourceFile> files) {
        if (files == null || files.isEmpty()) return;
        DocumentBuilder builder;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setValidating(false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            trySet(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            trySet(f, "http://xml.org/sax/features/external-general-entities", false);
            trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            builder = f.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new StringReader("")));
            builder.setErrorHandler(null);
        } catch (Exception e) {
            model.diagnostics.add(new Diagnostic("warning", null, "MyBatis XML support unavailable: " + e.getMessage()));
            return;
        }
        for (SourceFile sf : files) {
            try {
                Document doc = builder.parse(new org.xml.sax.InputSource(new StringReader(sf.content)));
                Element root = doc.getDocumentElement();
                if (!"mapper".equals(root.getTagName())) continue;
                String ns = root.getAttribute("namespace");
                if (ns.isEmpty()) continue;
                Map<String, String> sqlFragments = new HashMap<>();
                NodeList frags = root.getElementsByTagName("sql");
                for (int i = 0; i < frags.getLength(); i++) {
                    Element el = (Element) frags.item(i);
                    sqlFragments.put(el.getAttribute("id"), text(el, Map.of()));
                }
                NodeList children = root.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (!(children.item(i) instanceof Element el)) continue;
                    if (!STATEMENTS.contains(el.getTagName())) continue;
                    String sql = text(el, sqlFragments).replaceAll("\\s+", " ").trim();
                    SinkClassifier.Sink s = SinkClassifier.parseQuery(sql, model);
                    String op = s != null ? s.op() : el.getTagName().toUpperCase(java.util.Locale.ROOT);
                    String table = s != null ? s.table() : "?";
                    model.mapperStatements.put(ns + "." + el.getAttribute("id"),
                            new ProjectModel.MapperStatement(op, table, sql, sf.path));
                }
            } catch (Exception e) {
                model.diagnostics.add(new Diagnostic("warning", sf.path, "could not read MyBatis mapper XML: " + e.getMessage()));
            }
        }
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean value) {
        try { f.setFeature(feature, value); } catch (Exception ignored) { }
    }

    /** Statement text with {@code <include refid>} fragments inlined and dynamic tags flattened. */
    private static String text(Node n, Map<String, String> fragments) {
        StringBuilder sb = new StringBuilder();
        NodeList kids = n.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(k.getNodeValue()).append(' ');
            } else if (k instanceof Element el) {
                if ("include".equals(el.getTagName())) {
                    sb.append(fragments.getOrDefault(el.getAttribute("refid"), "")).append(' ');
                } else {
                    sb.append(text(el, fragments)).append(' ');
                }
            }
        }
        return sb.toString();
    }
}
