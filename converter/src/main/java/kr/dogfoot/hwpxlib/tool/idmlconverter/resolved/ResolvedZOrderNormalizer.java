package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * resolved.json의 zOrder를 IDML Spread XML 순서로 통일한다.
 *
 * <p>ExtendScript의 {@code allPageItems} / {@code absoluteZOrderIndex} 값은 런타임 DOM 기준이라
 * textFrames와 pageItems 사이에서 서로 다른 좌표계를 만들 수 있다. 이 보정기는 변환 입력 IDML만
 * z-depth의 기준으로 삼아 resolved 객체에 다시 적용한다.</p>
 */
public final class ResolvedZOrderNormalizer {
    private ResolvedZOrderNormalizer() {}

    public static void applyFromIdml(ResolvedData data, IDMLDocument idmlDoc) {
        if (data == null || idmlDoc == null || idmlDoc.tempDir() == null) return;

        Map<String, Integer> zByDomId = readZOrderMap(idmlDoc);
        if (zByDomId.isEmpty()) return;

        int pageItems = 0;
        int textFrames = 0;
        int rendered = 0;

        for (ResolvedPageItem item : data.pageItems()) {
            if (item == null) continue;
            Integer z = findZ(zByDomId, item.id());
            if (z != null) {
                item.zOrder(z);
                pageItems++;
            }
        }

        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null) continue;
            Integer z = findZ(zByDomId, tf.id());
            if (z != null) {
                tf.zOrder(z);
                textFrames++;
            }
        }

        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            if (applyRenderedZ(zByDomId, rg)) rendered++;
        }
        for (RenderedGroup rg : data.allRenderedPdfFrames()) {
            if (applyRenderedZ(zByDomId, rg)) rendered++;
        }
        for (RenderedGroup rg : data.allRenderedGraphicFrames()) {
            if (applyRenderedZ(zByDomId, rg)) rendered++;
        }
        for (RenderedGroup rg : data.allRenderedImageFrames()) {
            if (applyRenderedZ(zByDomId, rg)) rendered++;
        }

        System.err.println("[ResolvedZOrderNormalizer] IDML zOrder applied: pageItems="
                + pageItems + ", textFrames=" + textFrames + ", rendered=" + rendered);
    }

    public static Map<String, Integer> readSourceZOrderMap(IDMLDocument idmlDoc) {
        if (idmlDoc == null || idmlDoc.tempDir() == null) {
            return java.util.Collections.emptyMap();
        }
        return readZOrderMap(idmlDoc);
    }

    private static boolean applyRenderedZ(Map<String, Integer> zByDomId, RenderedGroup rg) {
        if (rg == null) return false;
        Integer z = zByDomId.get(String.valueOf(rg.id()));
        if (z == null && rg.sourceObjectIds() != null) {
            for (int sourceId : rg.sourceObjectIds()) {
                Integer childZ = zByDomId.get(String.valueOf(sourceId));
                if (childZ != null && (z == null || childZ < z)) {
                    z = childZ;
                }
            }
        }
        if (z == null) return false;
        rg.zOrder(z);
        rg.zOrderKnown(true);
        return true;
    }

    private static Integer findZ(Map<String, Integer> zByDomId, String domId) {
        if (domId == null) return null;
        Integer direct = zByDomId.get(domId);
        if (direct != null) return direct;

        // Synthetic master/off-canvas IDs: "3279_pi25", "17037_oc24" 등은 원본 ID로 폴백.
        int suffix = domId.indexOf('_');
        if (suffix > 0) {
            return zByDomId.get(domId.substring(0, suffix));
        }
        return null;
    }

    private static Map<String, Integer> readZOrderMap(IDMLDocument idmlDoc) {
        Map<String, Integer> result = new HashMap<>();
        File spreadsDir = new File(idmlDoc.tempDir(), "Spreads");
        File[] files = spreadsDir.listFiles((dir, name) -> name.endsWith(".xml"));
        if (files == null) return result;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        Set<String> hiddenLayers = idmlDoc.hiddenLayerIds() != null
                ? idmlDoc.hiddenLayerIds() : new HashSet<>();
        List<String> layerOrder = idmlDoc.layerOrder() != null
                ? idmlDoc.layerOrder() : java.util.Collections.emptyList();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {}

        for (File file : files) {
            try {
                Document doc = factory.newDocumentBuilder().parse(file);
                Element root = firstElement(doc, "Spread");
                if (root == null) root = firstElement(doc, "MasterSpread");
                if (root == null) continue;

                int[] counter = {0};
                for (Element child : sortedTopLevelChildren(root, layerOrder)) {
                    visitPageItem(child, counter, result, hiddenLayers);
                }
            } catch (Exception e) {
                System.err.println("[ResolvedZOrderNormalizer] Spread zOrder read failed: "
                        + file.getName() + " - " + e.getMessage());
            }
        }
        return result;
    }

    private static Element firstElement(Document doc, String tagName) {
        NodeList list = doc.getElementsByTagName(tagName);
        if (list.getLength() == 0) return null;
        return (Element) list.item(0);
    }

    private static List<Element> sortedTopLevelChildren(Element spreadRoot, List<String> layerOrder) {
        List<Element> elements = childElements(spreadRoot);
        if (layerOrder == null || layerOrder.isEmpty()) return elements;

        Map<String, Integer> layerIndex = new HashMap<>();
        for (int i = 0; i < layerOrder.size(); i++) {
            layerIndex.put(layerOrder.get(i), i);
        }
        final int defaultIndex = -1;
        elements.sort((a, b) -> {
            int ia = layerIndex.getOrDefault(a.getAttribute("ItemLayer"), defaultIndex);
            int ib = layerIndex.getOrDefault(b.getAttribute("ItemLayer"), defaultIndex);
            return Integer.compare(ia, ib); // back layer first, front layer later
        });
        return elements;
    }

    private static void visitPageItem(Element elem, int[] counter, Map<String, Integer> zByDomId,
                                      Set<String> hiddenLayers) {
        if (elem == null) return;
        if ("Page".equals(elem.getTagName())) return;

        String itemLayer = elem.getAttribute("ItemLayer");
        if (itemLayer != null && itemLayer.length() > 0 && hiddenLayers.contains(itemLayer)) return;
        if ("true".equals(elem.getAttribute("Nonprinting"))) return;

        if (isStackingItem(elem.getTagName())) {
            String domId = toDomId(elem.getAttribute("Self"));
            if (domId != null && !zByDomId.containsKey(domId)) {
                zByDomId.put(domId, counter[0]);
            }
            counter[0]++;
        }

        if ("Group".equals(elem.getTagName())
                || "Rectangle".equals(elem.getTagName())
                || "Polygon".equals(elem.getTagName())
                || "Oval".equals(elem.getTagName())) {
            for (Element child : childElements(elem)) {
                visitPageItem(child, counter, zByDomId, hiddenLayers);
            }
        }
    }

    private static boolean isStackingItem(String tagName) {
        return "TextFrame".equals(tagName)
                || "Rectangle".equals(tagName)
                || "Polygon".equals(tagName)
                || "Oval".equals(tagName)
                || "GraphicLine".equals(tagName)
                || "Group".equals(tagName);
    }

    private static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static String toDomId(String idmlSelf) {
        if (idmlSelf == null || idmlSelf.length() < 2 || idmlSelf.charAt(0) != 'u') return null;
        try {
            return String.valueOf(Integer.parseInt(idmlSelf.substring(1), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
