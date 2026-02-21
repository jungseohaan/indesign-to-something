package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * IDML 파일 처리에 필요한 XML 파싱 및 ZIP 유틸리티 메서드 모음.
 * IDMLLoader에서 추출됨.
 */
public class IDMLXmlUtils {

    // ===== ZIP 처리 =====

    public static File extractZip(File zipFile) throws ConvertException {
        try {
            File tempDir = File.createTempFile("idml_", "_extract");
            tempDir.delete();
            tempDir.mkdirs();

            ZipFile zip = new ZipFile(zipFile);
            try {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    File outFile = new File(tempDir, entry.getName());

                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        InputStream in = zip.getInputStream(entry);
                        try {
                            FileOutputStream out = new FileOutputStream(outFile);
                            try {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = in.read(buf)) > 0) {
                                    out.write(buf, 0, len);
                                }
                            } finally {
                                out.close();
                            }
                        } finally {
                            in.close();
                        }
                    }
                }
            } finally {
                zip.close();
            }

            return tempDir;
        } catch (IOException e) {
            throw new ConvertException(ConvertException.Phase.LOADING,
                    "Failed to extract IDML ZIP: " + e.getMessage(), e);
        }
    }

    public static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    // ===== XML 유틸리티 =====

    public static Document parseXML(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        // JDK XML 파서 속성 제한 해제 (IDML ParagraphStyle에 200개 이상 속성이 있을 수 있음)
        try {
            factory.setAttribute("jdk.xml.elementAttributeLimit", "0");
        } catch (IllegalArgumentException e) {
            // JDK 버전에 따라 지원되지 않을 수 있음 - 무시
        }

        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    public static List<Element> getChildElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<Element>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    /**
     * 자손 요소 중 특정 태그명의 요소를 재귀적으로 검색한다.
     */
    public static List<Element> getDescendantElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<Element>();
        NodeList descendants = parent.getElementsByTagName(tagName);
        for (int i = 0; i < descendants.getLength(); i++) {
            result.add((Element) descendants.item(i));
        }
        return result;
    }

    public static Element getFirstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /**
     * Properties 블록 안의 특정 요소의 텍스트 내용을 가져온다.
     * 예: <Properties><AppliedFont type="string">Myriad Pro</AppliedFont></Properties>
     */
    public static String getPropertyText(Element propsElem, String propertyName) {
        NodeList children = propsElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && propertyName.equals(node.getNodeName())) {
                String text = node.getTextContent();
                return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            }
        }
        return null;
    }

    public static String getAttrOrNull(Element elem, String attrName) {
        String val = elem.getAttribute(attrName);
        return (val != null && !val.isEmpty()) ? val : null;
    }

    public static Double parseDoubleAttr(Element elem, String attrName) {
        String val = elem.getAttribute(attrName);
        if (val == null || val.isEmpty()) return null;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static double parseDoubleAttrDef(Element elem, String attrName, double defaultVal) {
        Double val = parseDoubleAttr(elem, attrName);
        return val != null ? val : defaultVal;
    }

    public static int parseIntAttr(Element elem, String attrName, int defaultVal) {
        String val = elem.getAttribute(attrName);
        if (val == null || val.isEmpty()) return defaultVal;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /**
     * 자식 엘리먼트의 텍스트 내용을 반환한다.
     * IDML TabList 등에서 &lt;Position type="unit"&gt;215.4&lt;/Position&gt; 형태를 파싱할 때 사용.
     */
    public static String getChildElementText(Element parent, String childName) {
        Element child = getFirstChildElement(parent, childName);
        if (child == null) return null;
        String text = child.getTextContent();
        return (text != null && !text.isEmpty()) ? text.trim() : null;
    }

    public static Double parseChildElementDouble(Element parent, String childName) {
        String text = getChildElementText(parent, childName);
        if (text == null) return null;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
