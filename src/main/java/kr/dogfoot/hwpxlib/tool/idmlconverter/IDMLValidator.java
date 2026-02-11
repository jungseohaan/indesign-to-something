package kr.dogfoot.hwpxlib.tool.idmlconverter;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * IDML 파일 포맷 검증기.
 *
 * 5단계 검증:
 *   Level 1 - ZIP 구조 (mimetype 위치/압축방식/내용)
 *   Level 2 - 필수 파일 존재 여부
 *   Level 3 - XML 문법 유효성 (well-formed)
 *   Level 4 - 참조 무결성 (designmap → 실제 파일, StoryList, PageStart, AppliedMaster)
 *   Level 5 - 콘텐츠 일관성 (페이지 바운드, DOMVersion 통일 등)
 */
public class IDMLValidator {

    private static final String MIMETYPE = "application/vnd.adobe.indesign-idml-package";

    public static class Result {
        private boolean valid;
        private final List<Issue> issues = new ArrayList<Issue>();

        public boolean valid() { return valid; }
        public List<Issue> issues() { return issues; }

        public List<Issue> errors() {
            List<Issue> list = new ArrayList<Issue>();
            for (Issue i : issues) {
                if (i.severity == Severity.ERROR) list.add(i);
            }
            return list;
        }

        public List<Issue> warnings() {
            List<Issue> list = new ArrayList<Issue>();
            for (Issue i : issues) {
                if (i.severity == Severity.WARNING) list.add(i);
            }
            return list;
        }

        public List<Issue> infos() {
            List<Issue> list = new ArrayList<Issue>();
            for (Issue i : issues) {
                if (i.severity == Severity.INFO) list.add(i);
            }
            return list;
        }

        void add(Severity severity, int level, String message) {
            issues.add(new Issue(severity, level, message));
        }

        void error(int level, String message) { add(Severity.ERROR, level, message); }
        void warn(int level, String message) { add(Severity.WARNING, level, message); }
        void info(int level, String message) { add(Severity.INFO, level, message); }

        /**
         * JSON 문자열로 변환
         */
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"valid\": ").append(valid).append(",\n");
            sb.append("  \"error_count\": ").append(errors().size()).append(",\n");
            sb.append("  \"warning_count\": ").append(warnings().size()).append(",\n");
            sb.append("  \"issues\": [\n");
            for (int i = 0; i < issues.size(); i++) {
                Issue issue = issues.get(i);
                sb.append("    {");
                sb.append("\"severity\": \"").append(issue.severity.name()).append("\", ");
                sb.append("\"level\": ").append(issue.level).append(", ");
                sb.append("\"message\": \"").append(escapeJson(issue.message)).append("\"");
                sb.append("}");
                if (i < issues.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ]\n");
            sb.append("}");
            return sb.toString();
        }
    }

    public enum Severity { ERROR, WARNING, INFO }

    public static class Issue {
        public final Severity severity;
        public final int level;
        public final String message;

        Issue(Severity severity, int level, String message) {
            this.severity = severity;
            this.level = level;
            this.message = message;
        }

        @Override
        public String toString() {
            return "[" + severity + " L" + level + "] " + message;
        }
    }

    /**
     * IDML 파일을 종합 검증한다.
     * @param idmlPath IDML 파일 경로
     * @return 검증 결과
     */
    public static Result validate(String idmlPath) {
        Result result = new Result();

        File file = new File(idmlPath);
        if (!file.exists()) {
            result.error(1, "File not found: " + idmlPath);
            result.valid = false;
            return result;
        }

        if (!file.isFile()) {
            result.error(1, "Not a file: " + idmlPath);
            result.valid = false;
            return result;
        }

        try (ZipFile zip = new ZipFile(file)) {
            Set<String> allEntries = collectEntryNames(zip);

            // Level 1: ZIP 구조
            checkZipStructure(zip, result);

            // Level 2: 필수 파일 존재
            checkRequiredFiles(allEntries, result);

            // Level 3: XML well-formed 검사
            checkXmlWellFormed(zip, allEntries, result);

            // Level 4: 참조 무결성
            checkReferenceIntegrity(zip, allEntries, result);

            // Level 5: 콘텐츠 일관성
            checkContentConsistency(zip, allEntries, result);

        } catch (ZipException e) {
            result.error(1, "Invalid ZIP file: " + e.getMessage());
        } catch (IOException e) {
            result.error(1, "Cannot read file: " + e.getMessage());
        }

        result.valid = result.errors().isEmpty();
        return result;
    }

    // ─────────────────────────────────────────────────────────
    // Level 1: ZIP 구조 검증
    // ─────────────────────────────────────────────────────────

    private static void checkZipStructure(ZipFile zip, Result result) {
        // mimetype 엔트리 존재
        ZipEntry mimetypeEntry = zip.getEntry("mimetype");
        if (mimetypeEntry == null) {
            result.error(1, "Missing 'mimetype' entry");
            return;
        }

        // mimetype이 첫 번째 엔트리인지 (ZIP 스펙)
        Enumeration<? extends ZipEntry> entries = zip.entries();
        if (entries.hasMoreElements()) {
            ZipEntry first = entries.nextElement();
            if (!"mimetype".equals(first.getName())) {
                result.warn(1, "mimetype is not the first ZIP entry (found: " + first.getName() + ")");
            }
        }

        // mimetype이 STORED (비압축)인지
        if (mimetypeEntry.getMethod() != ZipEntry.STORED) {
            result.warn(1, "mimetype entry should be STORED (uncompressed), but method=" + mimetypeEntry.getMethod());
        }

        // mimetype 내용
        try {
            String content = readEntryAsString(zip, mimetypeEntry);
            if (!MIMETYPE.equals(content.trim())) {
                result.error(1, "Invalid mimetype content: '" + content.trim() + "' (expected: '" + MIMETYPE + "')");
            }
        } catch (IOException e) {
            result.error(1, "Cannot read mimetype entry: " + e.getMessage());
        }

        // 전체 엔트리 수 정보
        int count = 0;
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) { all.nextElement(); count++; }
        result.info(1, "Total ZIP entries: " + count);
    }

    // ─────────────────────────────────────────────────────────
    // Level 2: 필수 파일 존재 검증
    // ─────────────────────────────────────────────────────────

    private static void checkRequiredFiles(Set<String> entries, Result result) {
        // 절대 필수
        String[] critical = {
            "designmap.xml",
            "META-INF/container.xml"
        };
        for (String f : critical) {
            if (!entries.contains(f)) {
                result.error(2, "Missing critical file: " + f);
            }
        }

        // Resources
        String[] resources = {
            "Resources/Styles.xml",
            "Resources/Graphic.xml",
            "Resources/Fonts.xml",
            "Resources/Preferences.xml"
        };
        for (String f : resources) {
            if (!entries.contains(f)) {
                result.warn(2, "Missing resource file: " + f);
            }
        }

        // XML
        if (!entries.contains("XML/BackingStory.xml")) {
            result.warn(2, "Missing XML/BackingStory.xml");
        }
        if (!entries.contains("XML/Tags.xml")) {
            result.warn(2, "Missing XML/Tags.xml");
        }
        if (!entries.contains("META-INF/metadata.xml")) {
            result.info(2, "Missing META-INF/metadata.xml (optional)");
        }

        // Spread 파일 존재
        boolean hasSpread = false;
        boolean hasMaster = false;
        int spreadCount = 0;
        int masterCount = 0;
        int storyCount = 0;
        for (String e : entries) {
            if (e.startsWith("Spreads/") && e.endsWith(".xml")) { hasSpread = true; spreadCount++; }
            if (e.startsWith("MasterSpreads/") && e.endsWith(".xml")) { hasMaster = true; masterCount++; }
            if (e.startsWith("Stories/") && e.endsWith(".xml")) { storyCount++; }
        }
        if (!hasSpread) {
            result.error(2, "No Spread files found in Spreads/ directory");
        }
        if (!hasMaster) {
            result.info(2, "No MasterSpread files found (document has no master pages)");
        }

        result.info(2, "Files: " + spreadCount + " spreads, " + masterCount + " master spreads, " + storyCount + " stories");
    }

    // ─────────────────────────────────────────────────────────
    // Level 3: XML 문법 유효성 (well-formed)
    // ─────────────────────────────────────────────────────────

    private static void checkXmlWellFormed(ZipFile zip, Set<String> entries, Result result) {
        DocumentBuilderFactory factory = createParserFactory(false);

        int checkedCount = 0;
        int failCount = 0;

        for (String name : entries) {
            if (!name.endsWith(".xml")) continue;
            checkedCount++;

            ZipEntry entry = zip.getEntry(name);
            if (entry == null) continue;

            try {
                DocumentBuilder builder = factory.newDocumentBuilder();
                builder.setErrorHandler(null); // 기본 에러 핸들러 사용
                builder.parse(zip.getInputStream(entry));
            } catch (Exception e) {
                result.error(3, "Malformed XML: " + name + " (" + e.getMessage() + ")");
                failCount++;
            }
        }

        if (failCount == 0) {
            result.info(3, "All " + checkedCount + " XML files are well-formed");
        }
    }

    // ─────────────────────────────────────────────────────────
    // Level 4: 참조 무결성
    // ─────────────────────────────────────────────────────────

    private static void checkReferenceIntegrity(ZipFile zip, Set<String> allEntries, Result result) {
        ZipEntry designmapEntry = zip.getEntry("designmap.xml");
        if (designmapEntry == null) return; // Level 2에서 이미 에러 처리됨

        Document designmap;
        try {
            DocumentBuilderFactory factory = createParserFactory(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            designmap = builder.parse(zip.getInputStream(designmapEntry));
        } catch (Exception e) {
            return; // Level 3에서 이미 에러 처리됨
        }

        Element root = designmap.getDocumentElement();

        // 4-1. designmap.xml 루트 요소
        if (!"Document".equals(root.getTagName())) {
            result.error(4, "designmap.xml root element should be 'Document', found: " + root.getTagName());
        }

        // 4-2. idPkg:* src 참조 → 실제 ZIP 엔트리 존재 확인
        Set<String> referencedSprcs = new HashSet<String>();
        Set<String> referencedMasters = new HashSet<String>();
        Set<String> referencedStories = new HashSet<String>();
        String backingStorySrc = null;

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String tag = elem.getTagName();
            String src = elem.getAttribute("src");

            if (src == null || src.isEmpty()) continue;

            if ("idPkg:Spread".equals(tag)) {
                referencedSprcs.add(src);
                if (!allEntries.contains(src)) {
                    result.error(4, "Spread reference not found in ZIP: " + src);
                }
            } else if ("idPkg:MasterSpread".equals(tag)) {
                referencedMasters.add(src);
                if (!allEntries.contains(src)) {
                    result.error(4, "MasterSpread reference not found in ZIP: " + src);
                }
            } else if ("idPkg:Story".equals(tag)) {
                referencedStories.add(src);
                if (!allEntries.contains(src)) {
                    result.error(4, "Story reference not found in ZIP: " + src);
                }
            } else if ("idPkg:BackingStory".equals(tag)) {
                backingStorySrc = src;
                if (!allEntries.contains(src)) {
                    result.error(4, "BackingStory reference not found in ZIP: " + src);
                }
            }
        }

        // 4-3. ZIP 내 파일 중 designmap에서 참조하지 않는 것이 있는지 (경고)
        for (String entry : allEntries) {
            if (entry.startsWith("Spreads/") && entry.endsWith(".xml")) {
                if (!referencedSprcs.contains(entry)) {
                    result.warn(4, "Orphan spread file not referenced in designmap.xml: " + entry);
                }
            }
            if (entry.startsWith("MasterSpreads/") && entry.endsWith(".xml")) {
                if (!referencedMasters.contains(entry)) {
                    result.warn(4, "Orphan master spread file not referenced in designmap.xml: " + entry);
                }
            }
            if (entry.startsWith("Stories/") && entry.endsWith(".xml")) {
                if (!referencedStories.contains(entry)) {
                    result.warn(4, "Orphan story file not referenced in designmap.xml: " + entry);
                }
            }
        }

        // 4-4. StoryList 무결성
        checkStoryListIntegrity(zip, root, referencedStories, backingStorySrc, result);

        // 4-5. Section PageStart → Page Self 매핑
        checkSectionPageStart(zip, root, referencedSprcs, result);

        // 4-6. Page AppliedMaster → MasterSpread Self 매핑
        checkAppliedMaster(zip, referencedSprcs, referencedMasters, result);

        // 4-7. container.xml 구조
        checkContainerXml(zip, result);
    }

    private static void checkStoryListIntegrity(ZipFile zip, Element root,
                                                 Set<String> storySrcs, String backingStorySrc,
                                                 Result result) {
        String storyListAttr = root.getAttribute("StoryList");
        if (storyListAttr == null || storyListAttr.isEmpty()) {
            if (!storySrcs.isEmpty()) {
                result.warn(4, "StoryList is empty but " + storySrcs.size() + " Story references exist");
            }
            return;
        }

        Set<String> storyListIds = new HashSet<String>(Arrays.asList(storyListAttr.trim().split("\\s+")));
        storyListIds.remove("");

        // Story 파일에서 Self ID 수집
        Set<String> actualStoryIds = new HashSet<String>();
        for (String src : storySrcs) {
            String storyId = extractSelfId(zip, src, "Story");
            if (storyId != null) actualStoryIds.add(storyId);
        }

        // BackingStory의 XmlStory Self ID 수집
        if (backingStorySrc != null) {
            String xmlStoryId = extractSelfId(zip, backingStorySrc, "XmlStory");
            if (xmlStoryId != null) actualStoryIds.add(xmlStoryId);
        }

        // StoryList에 있는데 실제 Story가 없는 ID
        for (String id : storyListIds) {
            if (!actualStoryIds.contains(id)) {
                result.warn(4, "StoryList references ID '" + id + "' but no matching Story/XmlStory found");
            }
        }

        // 실제 Story가 있는데 StoryList에 없는 ID
        for (String id : actualStoryIds) {
            if (!storyListIds.contains(id)) {
                result.info(4, "Story Self='" + id + "' not in StoryList (may be normal for XmlStory)");
            }
        }
    }

    private static void checkSectionPageStart(ZipFile zip, Element root,
                                               Set<String> spreadSrcs, Result result) {
        // Spread 파일들에서 모든 Page Self 수집
        Set<String> allPageIds = new HashSet<String>();
        for (String src : spreadSrcs) {
            collectPageIds(zip, src, allPageIds);
        }

        // Section 요소의 PageStart가 유효한 Page ID인지
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            if (!"Section".equals(((Element) node).getTagName())) continue;
            Element section = (Element) node;
            String pageStart = section.getAttribute("PageStart");
            if (pageStart != null && !pageStart.isEmpty()) {
                if (!allPageIds.contains(pageStart)) {
                    result.error(4, "Section PageStart='" + pageStart + "' references non-existent Page");
                }
            }
        }
    }

    private static void checkAppliedMaster(ZipFile zip,
                                            Set<String> spreadSrcs, Set<String> masterSrcs,
                                            Result result) {
        // MasterSpread Self ID 수집
        Set<String> masterIds = new HashSet<String>();
        for (String src : masterSrcs) {
            String id = extractSelfId(zip, src, "MasterSpread");
            if (id != null) masterIds.add(id);
        }

        // 각 Spread의 Page에서 AppliedMaster 확인
        for (String src : spreadSrcs) {
            checkAppliedMasterInSpread(zip, src, masterIds, result);
        }
    }

    private static void checkAppliedMasterInSpread(ZipFile zip, String src,
                                                    Set<String> masterIds, Result result) {
        try {
            ZipEntry entry = zip.getEntry(src);
            if (entry == null) return;

            DocumentBuilderFactory factory = createParserFactory(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(zip.getInputStream(entry));

            NodeList pages = doc.getElementsByTagName("Page");
            for (int i = 0; i < pages.getLength(); i++) {
                Element page = (Element) pages.item(i);
                String applied = page.getAttribute("AppliedMaster");
                if (applied != null && !applied.isEmpty() && !"n".equals(applied)) {
                    if (!masterIds.contains(applied)) {
                        String pageName = page.getAttribute("Name");
                        result.error(4, "Page '" + pageName + "' in " + src +
                                " references non-existent master: " + applied);
                    }
                }
            }
        } catch (Exception e) {
            // XML 파싱 에러는 Level 3에서 처리됨
        }
    }

    private static void checkContainerXml(ZipFile zip, Result result) {
        ZipEntry entry = zip.getEntry("META-INF/container.xml");
        if (entry == null) return;

        try {
            DocumentBuilderFactory factory = createParserFactory(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(zip.getInputStream(entry));

            Element root = doc.getDocumentElement();
            if (!"container".equals(root.getLocalName())) {
                result.warn(4, "container.xml root should be 'container', found: " + root.getLocalName());
            }

            String ns = root.getNamespaceURI();
            if (ns == null || !ns.contains("oasis")) {
                result.warn(4, "container.xml missing OASIS namespace");
            }

            // rootfile 요소에서 designmap.xml 참조 확인
            NodeList rootfiles = doc.getElementsByTagNameNS("*", "rootfile");
            if (rootfiles.getLength() == 0) {
                rootfiles = doc.getElementsByTagName("rootfile");
            }
            boolean hasDesignmap = false;
            for (int i = 0; i < rootfiles.getLength(); i++) {
                Element rf = (Element) rootfiles.item(i);
                String fullPath = rf.getAttribute("full-path");
                if ("designmap.xml".equals(fullPath)) {
                    hasDesignmap = true;
                    break;
                }
            }
            if (!hasDesignmap) {
                result.warn(4, "container.xml does not reference designmap.xml via rootfile");
            }
        } catch (Exception e) {
            // Level 3에서 처리
        }
    }

    // ─────────────────────────────────────────────────────────
    // Level 5: 콘텐츠 일관성
    // ─────────────────────────────────────────────────────────

    private static void checkContentConsistency(ZipFile zip, Set<String> allEntries, Result result) {
        ZipEntry designmapEntry = zip.getEntry("designmap.xml");
        if (designmapEntry == null) return;

        Document designmap;
        try {
            DocumentBuilderFactory factory = createParserFactory(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            designmap = builder.parse(zip.getInputStream(designmapEntry));
        } catch (Exception e) {
            return;
        }

        Element root = designmap.getDocumentElement();
        String mainVersion = root.getAttribute("DOMVersion");

        // 5-1. DOMVersion 통일성
        checkDOMVersionConsistency(zip, allEntries, mainVersion, result);

        // 5-2. 각 Spread의 Page 존재 및 GeometricBounds 유효성
        checkSpreadPageValidity(zip, allEntries, result);

        // 5-3. Layer 존재
        boolean hasLayer = false;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "Layer".equals(((Element) node).getTagName())) {
                hasLayer = true;
                break;
            }
        }
        if (!hasLayer) {
            result.warn(5, "No Layer element found in designmap.xml");
        }

        // 5-4. Document 필수 속성
        String[] requiredAttrs = {"Self", "DOMVersion", "ActiveLayer", "Name"};
        for (String attr : requiredAttrs) {
            String val = root.getAttribute(attr);
            if (val == null || val.isEmpty()) {
                result.warn(5, "Document missing attribute: " + attr);
            }
        }

        if (mainVersion != null && !mainVersion.isEmpty()) {
            result.info(5, "DOMVersion: " + mainVersion);
        }
    }

    private static void checkDOMVersionConsistency(ZipFile zip, Set<String> allEntries,
                                                    String expectedVersion, Result result) {
        if (expectedVersion == null || expectedVersion.isEmpty()) {
            result.warn(5, "designmap.xml has no DOMVersion attribute");
            return;
        }

        String[] prefixes = {"Spreads/", "MasterSpreads/", "Stories/"};
        int mismatchCount = 0;

        for (String entry : allEntries) {
            if (!entry.endsWith(".xml")) continue;
            boolean relevant = false;
            for (String prefix : prefixes) {
                if (entry.startsWith(prefix)) { relevant = true; break; }
            }
            if (!relevant) continue;

            ZipEntry ze = zip.getEntry(entry);
            if (ze == null) continue;

            try {
                DocumentBuilderFactory factory = createParserFactory(false);
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(zip.getInputStream(ze));
                Element docRoot = doc.getDocumentElement();
                String version = docRoot.getAttribute("DOMVersion");
                if (version != null && !version.isEmpty() && !expectedVersion.equals(version)) {
                    result.warn(5, "DOMVersion mismatch in " + entry + ": " + version + " (expected " + expectedVersion + ")");
                    mismatchCount++;
                }
            } catch (Exception e) {
                // 이미 Level 3에서 처리됨
            }
        }

        if (mismatchCount == 0) {
            result.info(5, "DOMVersion consistent across all files");
        }
    }

    private static void checkSpreadPageValidity(ZipFile zip, Set<String> allEntries, Result result) {
        for (String entry : allEntries) {
            if (!entry.startsWith("Spreads/") && !entry.startsWith("MasterSpreads/")) continue;
            if (!entry.endsWith(".xml")) continue;

            ZipEntry ze = zip.getEntry(entry);
            if (ze == null) continue;

            try {
                DocumentBuilderFactory factory = createParserFactory(false);
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(zip.getInputStream(ze));

                NodeList pages = doc.getElementsByTagName("Page");
                if (pages.getLength() == 0) {
                    result.warn(5, "No Page element in " + entry);
                    continue;
                }

                for (int i = 0; i < pages.getLength(); i++) {
                    Element page = (Element) pages.item(i);
                    String bounds = page.getAttribute("GeometricBounds");
                    if (bounds == null || bounds.isEmpty()) {
                        result.warn(5, "Page in " + entry + " missing GeometricBounds");
                        continue;
                    }

                    String[] parts = bounds.trim().split("\\s+");
                    if (parts.length != 4) {
                        result.error(5, "Invalid GeometricBounds in " + entry + ": " + bounds);
                        continue;
                    }

                    try {
                        double top = Double.parseDouble(parts[0]);
                        double left = Double.parseDouble(parts[1]);
                        double bottom = Double.parseDouble(parts[2]);
                        double right = Double.parseDouble(parts[3]);
                        double w = right - left;
                        double h = bottom - top;
                        if (w <= 0 || h <= 0) {
                            result.error(5, "Non-positive page dimensions in " + entry +
                                    ": width=" + w + " height=" + h);
                        }
                    } catch (NumberFormatException e) {
                        result.error(5, "Non-numeric GeometricBounds in " + entry + ": " + bounds);
                    }
                }
            } catch (Exception e) {
                // 이미 Level 3에서 처리됨
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Utility methods
    // ─────────────────────────────────────────────────────────

    /**
     * InDesign XML은 속성이 200개 이상인 요소가 있으므로 JDK 기본 제한을 해제한다.
     */
    private static DocumentBuilderFactory createParserFactory(boolean namespaceAware) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        try {
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/elementAttributeLimit", 0);
        } catch (IllegalArgumentException e) {
            // JDK가 이 속성을 지원하지 않으면 시스템 프로퍼티로 설정
            System.setProperty("jdk.xml.elementAttributeLimit", "0");
        }
        return factory;
    }

    private static Set<String> collectEntryNames(ZipFile zip) {
        Set<String> names = new LinkedHashSet<String>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            names.add(entries.nextElement().getName());
        }
        return names;
    }

    private static String readEntryAsString(ZipFile zip, ZipEntry entry) throws IOException {
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

    private static String extractSelfId(ZipFile zip, String src, String tagName) {
        try {
            ZipEntry entry = zip.getEntry(src);
            if (entry == null) return null;

            DocumentBuilderFactory factory = createParserFactory(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(zip.getInputStream(entry));

            NodeList nodes = doc.getElementsByTagName(tagName);
            if (nodes.getLength() > 0) {
                return ((Element) nodes.item(0)).getAttribute("Self");
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private static void collectPageIds(ZipFile zip, String src, Set<String> pageIds) {
        try {
            ZipEntry entry = zip.getEntry(src);
            if (entry == null) return;

            DocumentBuilderFactory factory = createParserFactory(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(zip.getInputStream(entry));

            NodeList pages = doc.getElementsByTagName("Page");
            for (int i = 0; i < pages.getLength(); i++) {
                String id = ((Element) pages.item(i)).getAttribute("Self");
                if (id != null && !id.isEmpty()) {
                    pageIds.add(id);
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
