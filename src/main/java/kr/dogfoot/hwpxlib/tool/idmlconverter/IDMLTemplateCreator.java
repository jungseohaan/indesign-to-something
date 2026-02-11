package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLLoader;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * 소스 IDML에서 마스터 스프레드를 복사하여 빈 IDML 문서를 생성한다.
 * 소스의 designmap.xml, Resources, META-INF, XML 등을 그대로 복사하고
 * Spread/Story 참조만 빈 스프레드로 교체한다.
 */
public class IDMLTemplateCreator {

    private static final String MIMETYPE = "application/vnd.adobe.indesign-idml-package";

    public static class CreateResult {
        private boolean success;
        private int masterCount;
        private double pageWidth;
        private double pageHeight;
        private List<String> warnings = new ArrayList<String>();

        public boolean success() { return success; }
        public int masterCount() { return masterCount; }
        public double pageWidth() { return pageWidth; }
        public double pageHeight() { return pageHeight; }
        public List<String> warnings() { return warnings; }
    }

    public static class ValidationResult {
        private boolean valid;
        private List<String> errors = new ArrayList<String>();
        private List<String> warnings = new ArrayList<String>();

        public boolean valid() { return valid; }
        public List<String> errors() { return errors; }
        public List<String> warnings() { return warnings; }
    }

    /**
     * 소스 IDML의 마스터 스프레드를 복사하여 빈 IDML 문서를 생성한다.
     * 접근: 소스 IDML의 모든 구조를 그대로 복사하되, Spread와 Story만 빈 것으로 교체.
     */
    public static CreateResult create(String sourceIdmlPath, String outputPath, List<String> masterIds) throws Exception {
        CreateResult result = new CreateResult();

        File sourceFile = new File(sourceIdmlPath);
        if (!sourceFile.exists()) {
            throw new Exception("Source IDML file not found: " + sourceIdmlPath);
        }

        try (ZipFile sourceZip = new ZipFile(sourceFile)) {
            // 1. designmap.xml 파싱하여 마스터 스프레드 정보 수집
            DesignmapInfo designmap = parseDesignmap(sourceZip);

            // 2. 복사할 마스터 스프레드 필터링
            List<MasterSpreadRef> selectedMasters;
            if (masterIds == null || masterIds.isEmpty()) {
                selectedMasters = designmap.masterSpreads;
            } else {
                selectedMasters = new ArrayList<MasterSpreadRef>();
                Set<String> idSet = new HashSet<String>(masterIds);
                for (MasterSpreadRef ref : designmap.masterSpreads) {
                    if (idSet.contains(ref.masterId)) {
                        selectedMasters.add(ref);
                    }
                }
            }

            if (selectedMasters.isEmpty()) {
                result.warnings.add("No master spreads found to copy");
            }

            // 3. 첫 번째 마스터 스프레드에서 페이지 크기 추출
            double pageWidth = 595.28;
            double pageHeight = 841.88;
            String firstMasterId = null;
            if (!selectedMasters.isEmpty()) {
                firstMasterId = selectedMasters.get(0).masterId;
                double[] pageSize = extractPageSizeFromMaster(sourceZip, selectedMasters.get(0).src);
                if (pageSize != null) {
                    pageWidth = pageSize[0];
                    pageHeight = pageSize[1];
                }
            }

            // 4. 소스의 designmap.xml을 수정하여 Spread/Story 참조를 빈 스프레드로 교체
            Set<String> selectedMasterSrcs = new HashSet<String>();
            for (MasterSpreadRef ref : selectedMasters) {
                selectedMasterSrcs.add(ref.src);
            }
            String modifiedDesignmap = modifyDesignmap(sourceZip, selectedMasterSrcs);

            // 5. 출력 파일 생성
            File outputFile = new File(outputPath);
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (FileOutputStream fos = new FileOutputStream(outputFile);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                // mimetype (압축 없이, 첫 번째 항목 - IDML 스펙)
                writeMimetype(zos);

                // 소스에서 직접 복사할 항목들
                Set<String> copiedEntries = new HashSet<String>();
                copiedEntries.add("mimetype");
                copiedEntries.add("designmap.xml");

                // META-INF/ 복사 (container.xml, metadata.xml 등)
                copyMatchingEntries(sourceZip, zos, "META-INF/", copiedEntries);

                // Resources/ 복사
                copyMatchingEntries(sourceZip, zos, "Resources/", copiedEntries);

                // XML/ 복사 (Tags.xml, BackingStory.xml 등)
                copyMatchingEntries(sourceZip, zos, "XML/", copiedEntries);

                // 선택된 MasterSpreads/ 복사
                for (MasterSpreadRef ref : selectedMasters) {
                    copyZipEntry(sourceZip, zos, ref.src);
                    copiedEntries.add(ref.src);
                }

                // 빈 Spread 생성 (소스의 DOMVersion 사용)
                String domVersion = designmap.domVersion != null ? designmap.domVersion : "21.1";
                String spreadXml = generateEmptySpread(pageWidth, pageHeight, firstMasterId, domVersion);
                writeEntry(zos, "Spreads/Spread_ublank.xml", spreadXml);

                // 수정된 designmap.xml 쓰기
                writeEntry(zos, "designmap.xml", modifiedDesignmap);
            }

            result.success = true;
            result.masterCount = selectedMasters.size();
            result.pageWidth = pageWidth;
            result.pageHeight = pageHeight;
        }

        return result;
    }

    /**
     * IDML 파일의 유효성을 검증한다.
     */
    public static ValidationResult validate(String idmlPath) {
        ValidationResult result = new ValidationResult();

        File file = new File(idmlPath);
        if (!file.exists()) {
            result.errors.add("File not found: " + idmlPath);
            return result;
        }

        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry mimetypeEntry = zip.getEntry("mimetype");
            if (mimetypeEntry == null) {
                result.errors.add("Missing mimetype entry");
            } else {
                String mimetype = readZipEntryAsString(zip, mimetypeEntry);
                if (!MIMETYPE.equals(mimetype.trim())) {
                    result.errors.add("Invalid mimetype: " + mimetype);
                }
            }

            if (zip.getEntry("designmap.xml") == null) {
                result.errors.add("Missing designmap.xml");
            }
            if (zip.getEntry("META-INF/container.xml") == null) {
                result.errors.add("Missing META-INF/container.xml");
            }

            String[] requiredResources = {"Resources/Styles.xml", "Resources/Graphic.xml", "Resources/Fonts.xml"};
            for (String res : requiredResources) {
                if (zip.getEntry(res) == null) {
                    result.warnings.add("Missing resource: " + res);
                }
            }

            boolean hasSpread = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("Spreads/") && entry.getName().endsWith(".xml")) {
                    hasSpread = true;
                    break;
                }
            }
            if (!hasSpread) {
                result.warnings.add("No spread files found");
            }
        } catch (Exception e) {
            result.errors.add("Failed to read ZIP: " + e.getMessage());
        }

        try {
            IDMLDocument doc = IDMLLoader.load(idmlPath);
            if (doc == null) {
                result.errors.add("IDMLLoader returned null");
            } else {
                if (doc.spreads().isEmpty()) {
                    result.warnings.add("No spreads loaded");
                }
                if (doc.masterSpreads().isEmpty()) {
                    result.warnings.add("No master spreads loaded");
                }
                doc.cleanup();
            }
        } catch (Exception e) {
            result.errors.add("IDMLLoader failed: " + e.getMessage());
        }

        result.valid = result.errors.isEmpty();
        return result;
    }

    // ─────────────────────────────────────────────────────────
    // Internal types
    // ─────────────────────────────────────────────────────────

    private static class DesignmapInfo {
        List<MasterSpreadRef> masterSpreads = new ArrayList<MasterSpreadRef>();
        String domVersion;
    }

    private static class MasterSpreadRef {
        String src;
        String masterId;
    }

    // ─────────────────────────────────────────────────────────
    // Designmap parsing & modification
    // ─────────────────────────────────────────────────────────

    private static DesignmapInfo parseDesignmap(ZipFile zip) throws Exception {
        DesignmapInfo info = new DesignmapInfo();

        ZipEntry entry = zip.getEntry("designmap.xml");
        if (entry == null) {
            throw new Exception("designmap.xml not found in IDML");
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(zip.getInputStream(entry));

        Element root = doc.getDocumentElement();
        info.domVersion = root.getAttribute("DOMVersion");

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;

            if ("idPkg:MasterSpread".equals(elem.getTagName())) {
                String src = elem.getAttribute("src");
                if (src != null && !src.isEmpty()) {
                    MasterSpreadRef ref = new MasterSpreadRef();
                    ref.src = src;
                    ref.masterId = extractMasterIdFromXml(zip, src);
                    info.masterSpreads.add(ref);
                }
            }
        }

        return info;
    }

    /**
     * 소스 designmap.xml을 수정하여:
     * 1. 선택되지 않은 idPkg:MasterSpread 참조 제거
     * 2. 기존 idPkg:Spread 참조를 빈 스프레드로 교체
     * 3. 기존 idPkg:Story 참조 제거 (빈 문서이므로)
     * 4. StoryList에서 제거된 Story ID만 삭제 (BackingStory ID는 유지)
     */
    private static String modifyDesignmap(ZipFile zip, Set<String> keepMasterSrcs) throws Exception {
        ZipEntry entry = zip.getEntry("designmap.xml");
        if (entry == null) {
            throw new Exception("designmap.xml not found in IDML");
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(zip.getInputStream(entry));

        Element root = doc.getDocumentElement();

        // 제거할 노드 수집 (순회 중 제거하면 안 되므로)
        List<Node> toRemove = new ArrayList<Node>();
        // 제거될 Story의 src 경로 수집 (StoryList에서 해당 ID 제거용)
        Set<String> removedStorySrcs = new HashSet<String>();
        boolean spreadReplaced = false;

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String tagName = elem.getTagName();

            if ("idPkg:MasterSpread".equals(tagName)) {
                String src = elem.getAttribute("src");
                if (!keepMasterSrcs.contains(src)) {
                    toRemove.add(node);
                }
            } else if ("idPkg:Spread".equals(tagName)) {
                if (!spreadReplaced) {
                    // 첫 번째 Spread를 빈 스프레드로 교체
                    elem.setAttribute("src", "Spreads/Spread_ublank.xml");
                    spreadReplaced = true;
                } else {
                    toRemove.add(node);
                }
            } else if ("idPkg:Story".equals(tagName)) {
                // 일반 Story만 제거 (BackingStory는 유지)
                toRemove.add(node);
                removedStorySrcs.add(elem.getAttribute("src"));
            } else if ("Section".equals(tagName)) {
                // Section의 PageStart를 빈 스프레드의 페이지로 변경
                elem.setAttribute("PageStart", "ublankpage");
                elem.setAttribute("Length", "1");
            }
        }

        // StoryList에서 제거된 Story의 ID만 삭제
        if (root.hasAttribute("StoryList") && !removedStorySrcs.isEmpty()) {
            Set<String> removedStoryIds = new HashSet<String>();
            for (String src : removedStorySrcs) {
                String storyId = extractStoryIdFromFile(zip, src);
                if (storyId != null) {
                    removedStoryIds.add(storyId);
                }
            }
            if (!removedStoryIds.isEmpty()) {
                String storyList = root.getAttribute("StoryList");
                String[] ids = storyList.trim().split("\\s+");
                StringBuilder newStoryList = new StringBuilder();
                for (String id : ids) {
                    if (!id.isEmpty() && !removedStoryIds.contains(id)) {
                        if (newStoryList.length() > 0) newStoryList.append(" ");
                        newStoryList.append(id);
                    }
                }
                root.setAttribute("StoryList", newStoryList.toString());
            }
        }

        // Spread가 없었으면 추가
        if (!spreadReplaced) {
            Element spreadElem = doc.createElement("idPkg:Spread");
            spreadElem.setAttribute("src", "Spreads/Spread_ublank.xml");
            // Layer 앞에 삽입
            Node insertBefore = null;
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    String tagName = ((Element) node).getTagName();
                    if ("Layer".equals(tagName) || "Section".equals(tagName)) {
                        insertBefore = node;
                        break;
                    }
                }
            }
            if (insertBefore != null) {
                root.insertBefore(spreadElem, insertBefore);
            } else {
                root.appendChild(spreadElem);
            }
        }

        // 제거
        for (Node node : toRemove) {
            root.removeChild(node);
        }

        // DOM을 문자열로 변환
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "no");
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.STANDALONE, "yes");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }

    // ─────────────────────────────────────────────────────────
    // XML helpers
    // ─────────────────────────────────────────────────────────

    private static String extractStoryIdFromFile(ZipFile zip, String src) {
        try {
            ZipEntry entry = zip.getEntry(src);
            if (entry == null) return null;

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(zip.getInputStream(entry));

            NodeList nodes = doc.getElementsByTagName("Story");
            if (nodes.getLength() > 0) {
                return ((Element) nodes.item(0)).getAttribute("Self");
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private static String extractMasterIdFromXml(ZipFile zip, String src) {
        try {
            ZipEntry entry = zip.getEntry(src);
            if (entry == null) return "unknown";

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(zip.getInputStream(entry));

            NodeList nodes = doc.getElementsByTagName("MasterSpread");
            if (nodes.getLength() == 0) {
                nodes = doc.getElementsByTagName("Spread");
            }
            if (nodes.getLength() > 0) {
                return ((Element) nodes.item(0)).getAttribute("Self");
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }

    private static double[] extractPageSizeFromMaster(ZipFile zip, String src) {
        try {
            ZipEntry entry = zip.getEntry(src);
            if (entry == null) return null;

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(zip.getInputStream(entry));

            NodeList pages = doc.getElementsByTagName("Page");
            if (pages.getLength() > 0) {
                Element pageElem = (Element) pages.item(0);
                String boundsStr = pageElem.getAttribute("GeometricBounds");
                if (boundsStr != null && !boundsStr.isEmpty()) {
                    String[] parts = boundsStr.trim().split("\\s+");
                    if (parts.length >= 4) {
                        double top = Double.parseDouble(parts[0]);
                        double left = Double.parseDouble(parts[1]);
                        double bottom = Double.parseDouble(parts[2]);
                        double right = Double.parseDouble(parts[3]);
                        return new double[]{right - left, bottom - top};
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────
    // Spread generation
    // ─────────────────────────────────────────────────────────

    private static String generateEmptySpread(double pageWidth, double pageHeight, String appliedMaster, String domVersion) {
        double halfHeight = pageHeight / 2.0;
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<idPkg:Spread xmlns:idPkg=\"http://ns.adobe.com/AdobeInDesign/idml/1.0/packaging\" DOMVersion=\"").append(domVersion).append("\">\n");
        sb.append("\t<Spread Self=\"ublank\" PageTransitionType=\"None\" PageTransitionDirection=\"NotApplicable\" PageTransitionDuration=\"Medium\" ShowMasterItems=\"true\" PageCount=\"1\" BindingLocation=\"0\" SpreadHidden=\"false\" AllowPageShuffle=\"true\" ItemTransform=\"1 0 0 1 0 0\" FlattenerOverride=\"Default\">\n");
        sb.append("\t\t<FlattenerPreference LineArtAndTextResolution=\"400\" GradientAndMeshResolution=\"400\" ClipComplexRegions=\"false\" ConvertAllStrokesToOutlines=\"false\" ConvertAllTextToOutlines=\"false\">\n");
        sb.append("\t\t\t<Properties>\n");
        sb.append("\t\t\t\t<RasterVectorBalance type=\"double\">50</RasterVectorBalance>\n");
        sb.append("\t\t\t</Properties>\n");
        sb.append("\t\t</FlattenerPreference>\n");
        sb.append("\t\t<Page Self=\"ublankpage\" TabOrder=\"\" AppliedMaster=\"");
        sb.append(appliedMaster != null ? appliedMaster : "n");
        sb.append("\" OverrideList=\"\" MasterPageTransform=\"1 0 0 1 0 0\" Name=\"1\"");
        sb.append(" AppliedTrapPreset=\"TrapPreset/$ID/kDefaultTrapStyleName\"");
        sb.append(" GeometricBounds=\"0 0 ").append(pageHeight).append(" ").append(pageWidth).append("\"");
        sb.append(" ItemTransform=\"1 0 0 1 0 -").append(halfHeight).append("\"");
        sb.append(" LayoutRule=\"UseMaster\" SnapshotBlendingMode=\"IgnoreLayoutSnapshots\" OptionalPage=\"false\" GridStartingPoint=\"TopOutside\" UseMasterGrid=\"true\">\n");
        sb.append("\t\t\t<Properties>\n");
        sb.append("\t\t\t\t<PageColor type=\"enumeration\">UseMasterColor</PageColor>\n");
        sb.append("\t\t\t</Properties>\n");
        sb.append("\t\t\t<MarginPreference ColumnCount=\"1\" ColumnGutter=\"14.173228346456694\" Top=\"56.69291338582678\" Bottom=\"56.69291338582678\" Left=\"56.69291338582678\" Right=\"56.69291338582678\" ColumnDirection=\"Horizontal\" ColumnsPositions=\"0 ").append(pageWidth - 2 * 56.69291338582678).append("\" />\n");
        sb.append("\t\t</Page>\n");
        sb.append("\t</Spread>\n");
        sb.append("</idPkg:Spread>\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────
    // ZIP utilities
    // ─────────────────────────────────────────────────────────

    private static void copyMatchingEntries(ZipFile sourceZip, ZipOutputStream zos, String prefix, Set<String> copiedEntries) throws IOException {
        Enumeration<? extends ZipEntry> entries = sourceZip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry srcEntry = entries.nextElement();
            String name = srcEntry.getName();
            if (name.startsWith(prefix) && !copiedEntries.contains(name)) {
                copyZipEntry(sourceZip, zos, name);
                copiedEntries.add(name);
            }
        }
    }

    private static void copyZipEntry(ZipFile sourceZip, ZipOutputStream zos, String entryName) throws IOException {
        ZipEntry sourceEntry = sourceZip.getEntry(entryName);
        if (sourceEntry == null) return;

        ZipEntry newEntry = new ZipEntry(entryName);
        newEntry.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(newEntry);

        try (InputStream is = sourceZip.getInputStream(sourceEntry)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
        }

        zos.closeEntry();
    }

    private static void writeMimetype(ZipOutputStream zos) throws IOException {
        byte[] mimetypeBytes = MIMETYPE.getBytes(StandardCharsets.US_ASCII);

        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(mimetypeBytes.length);
        entry.setCompressedSize(mimetypeBytes.length);

        CRC32 crc = new CRC32();
        crc.update(mimetypeBytes);
        entry.setCrc(crc.getValue());

        zos.putNextEntry(entry);
        zos.write(mimetypeBytes);
        zos.closeEntry();
    }

    private static void writeEntry(ZipOutputStream zos, String path, String content) throws IOException {
        if (content == null) content = "";
        ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String readZipEntryAsString(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return baos.toString("UTF-8");
        }
    }
}
