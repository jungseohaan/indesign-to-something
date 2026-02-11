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
        private int pageCount;
        private double pageWidth;
        private double pageHeight;
        private List<String> warnings = new ArrayList<String>();

        public boolean success() { return success; }
        public int masterCount() { return masterCount; }
        public int pageCount() { return pageCount; }
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
     *
     * @param sourceIdmlPath  소스 IDML 파일 경로
     * @param outputPath      출력 IDML 경로
     * @param masterIds       복사할 마스터 스프레드 ID 목록 (null이면 전체)
     * @param pageSpecs       페이지별 마스터 ID 목록 (null이면 1페이지, "none"은 마스터 없음)
     * @param textFrameSpecs  페이지별 텍스트 프레임 크기 [width, height]. null entry=없음, {-1,-1}=auto
     * @param inlineCount     인라인 텍스트 프레임 개수 (0이면 인라인 없음)
     * @param tfMode          텍스트 프레임 모드: "master"=마스터 상속(기본), "custom"=2단 5mm gutter
     */
    public static CreateResult create(String sourceIdmlPath, String outputPath,
                                       List<String> masterIds, List<String> pageSpecs,
                                       List<double[]> textFrameSpecs, int inlineCount,
                                       String tfMode) throws Exception {
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

            // 3. 첫 번째 마스터 스프레드에서 레이아웃 정보 추출
            MasterLayout layout = new MasterLayout();
            String firstMasterId = null;
            if (!selectedMasters.isEmpty()) {
                firstMasterId = selectedMasters.get(0).masterId;
                MasterLayout extracted = extractMasterLayout(sourceZip, selectedMasters.get(0).src);
                if (extracted != null) layout = extracted;
            }

            // 4. custom 모드: 컬럼 설정 오버라이드 (2단, 5mm gutter)
            boolean isCustomMode = "custom".equalsIgnoreCase(tfMode);
            int overrideColumnCount = isCustomMode ? 2 : -1;
            double overrideColumnGutter = isCustomMode ? 14.173228346456694 : -1; // 5mm

            // 5. 페이지 수 결정
            int pageCount = (pageSpecs != null && !pageSpecs.isEmpty()) ? pageSpecs.size() : 1;

            // 6. 텍스트 프레임이 있는 페이지 인덱스 수집 (auto-flow 링크용)
            List<Integer> tfPageIndices = new ArrayList<Integer>();
            if (textFrameSpecs != null) {
                for (int i = 0; i < Math.min(textFrameSpecs.size(), pageCount); i++) {
                    if (textFrameSpecs.get(i) != null) {
                        tfPageIndices.add(i);
                    }
                }
            }
            boolean hasTextFrames = !tfPageIndices.isEmpty();
            String storyId = "autoflow_story";

            // 6. 소스의 designmap.xml을 수정하여 Spread/Story 참조를 빈 스프레드로 교체
            Set<String> selectedMasterSrcs = new HashSet<String>();
            for (MasterSpreadRef ref : selectedMasters) {
                selectedMasterSrcs.add(ref.src);
            }
            String modifiedDesignmap = modifyDesignmap(sourceZip, selectedMasterSrcs, pageCount,
                    hasTextFrames, storyId, hasTextFrames ? inlineCount : 0);

            // 7. 출력 파일 생성
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

                // 각 페이지의 마스터에 맞는 레이아웃 캐시 (마스터별)
                Map<String, MasterLayout> layoutCache = new HashMap<String, MasterLayout>();
                for (MasterSpreadRef ref : selectedMasters) {
                    MasterLayout ml = extractMasterLayout(sourceZip, ref.src);
                    if (ml != null) layoutCache.put(ref.masterId, ml);
                }

                // Spread 생성 (페이지별 1 스프레드)
                String domVersion = designmap.domVersion != null ? designmap.domVersion : "21.1";
                for (int i = 0; i < pageCount; i++) {
                    String masterId;
                    if (pageSpecs != null && !pageSpecs.isEmpty()) {
                        masterId = pageSpecs.get(i);
                        if ("none".equalsIgnoreCase(masterId)) masterId = null;
                    } else {
                        masterId = firstMasterId;
                    }

                    // 이 페이지의 레이아웃 결정 (마스터별)
                    MasterLayout pageLayout = (masterId != null && layoutCache.containsKey(masterId))
                            ? layoutCache.get(masterId) : layout;

                    String spreadId = "ublank" + i;
                    String pageId = "ublankpage" + i;

                    // 텍스트 프레임 정보
                    String textFrameId = null;
                    String prevFrameId = "n";
                    String nextFrameId = "n";
                    double tfWidth = 0;
                    double tfHeight = 0;

                    if (textFrameSpecs != null && i < textFrameSpecs.size() && textFrameSpecs.get(i) != null) {
                        textFrameId = "tf_autoflow_" + i;
                        double[] spec = textFrameSpecs.get(i);

                        // auto(-1)면 콘텐츠영역 전체 너비/높이 사용 (다단은 TextFramePreference로)
                        if (spec[0] < 0) {
                            tfWidth = pageLayout.pageWidth - pageLayout.marginLeft - pageLayout.marginRight;
                        } else {
                            tfWidth = spec[0];
                        }
                        if (spec[1] < 0) {
                            tfHeight = pageLayout.pageHeight - pageLayout.marginTop - pageLayout.marginBottom;
                        } else {
                            tfHeight = spec[1];
                        }

                        // linked frame chain: 이전/다음 텍스트 프레임 ID
                        int posInChain = tfPageIndices.indexOf(i);
                        if (posInChain > 0) {
                            prevFrameId = "tf_autoflow_" + tfPageIndices.get(posInChain - 1);
                        }
                        if (posInChain >= 0 && posInChain < tfPageIndices.size() - 1) {
                            nextFrameId = "tf_autoflow_" + tfPageIndices.get(posInChain + 1);
                        }
                    }

                    // custom 모드면 컬럼 설정 오버라이드
                    int effectiveColCount = isCustomMode ? overrideColumnCount : pageLayout.columnCount;
                    double effectiveColGutter = isCustomMode ? overrideColumnGutter : pageLayout.columnGutter;

                    String spreadXml = generateEmptySpread(
                            pageLayout.pageWidth, pageLayout.pageHeight, masterId, domVersion,
                            spreadId, pageId, String.valueOf(i + 1),
                            textFrameId, storyId, prevFrameId, nextFrameId,
                            tfWidth, tfHeight, pageLayout.marginTop, pageLayout.marginLeft,
                            effectiveColCount, effectiveColGutter,
                            designmap.activeLayer);
                    writeEntry(zos, "Spreads/Spread_" + spreadId + ".xml", spreadXml);
                }

                // 텍스트 프레임이 있으면 Story 파일 생성
                if (hasTextFrames) {
                    // 인라인 TF 크기: 1단 너비, 높이는 AutoSizing
                    double contentWidth = layout.pageWidth - layout.marginLeft - layout.marginRight;
                    int cols = Math.max(isCustomMode ? overrideColumnCount : layout.columnCount, 1);
                    double gutter = isCustomMode ? overrideColumnGutter : layout.columnGutter;
                    double colWidth = (contentWidth - (cols - 1) * gutter) / cols;
                    double inlineWidth = colWidth;
                    double inlineHeight = 100; // 초기값 (AutoSizing으로 자동 조정)

                    String storyXml = generateAutoflowStory(storyId, domVersion,
                            inlineCount, inlineWidth, inlineHeight);
                    writeEntry(zos, "Stories/Story_" + storyId + ".xml", storyXml);

                    // 각 인라인 콘텐츠 Story + 넘버링 Story + 년도 Story 파일 생성
                    for (int ic = 0; ic < inlineCount; ic++) {
                        String inlineStoryId = "inline_story_" + ic;
                        String inlineStoryXml = generateInlineStory(inlineStoryId, domVersion, ic);
                        writeEntry(zos, "Stories/Story_" + inlineStoryId + ".xml", inlineStoryXml);

                        String numStoryId = "inline_num_story_" + ic;
                        String numStoryXml = generateNumberingStory(numStoryId, domVersion, ic);
                        writeEntry(zos, "Stories/Story_" + numStoryId + ".xml", numStoryXml);

                        String yearStoryId = "inline_year_story_" + ic;
                        String yearStoryXml = generateYearStory(yearStoryId, domVersion, ic);
                        writeEntry(zos, "Stories/Story_" + yearStoryId + ".xml", yearStoryXml);
                    }
                }

                // 수정된 designmap.xml 쓰기
                writeEntry(zos, "designmap.xml", modifiedDesignmap);
            }

            result.success = true;
            result.masterCount = selectedMasters.size();
            result.pageCount = pageCount;
            result.pageWidth = layout.pageWidth;
            result.pageHeight = layout.pageHeight;
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────
    // 데이터 머지 기반 IDML 생성
    // ─────────────────────────────────────────────────────────

    /**
     * JSON 데이터를 파싱하여 데이터 기반 IDML을 생성한다.
     * dataJson 형식:
     * {
     *   "pages": [{"master":"uf5","textFrame":true}, ...],
     *   "tfMode": "custom",
     *   "items": [
     *     {"year":"2021학년도...","number":"1","question":"문제...","choices":"①..."},
     *     ...
     *   ]
     * }
     *
     * items의 각 필드명은 스키마의 groupTemplate.children[].fields[].name과 대응한다.
     * 각 field에 대한 style 매핑은 스키마에서 추출한 것을 사용한다.
     */
    public static CreateResult createFromData(String sourceIdmlPath, String outputPath,
                                               String dataJson) throws Exception {
        // JSON 파싱 (가벼운 수동 파싱 - 외부 라이브러리 없이)
        MergeConfig config = parseMergeConfig(dataJson);

        // 스키마 추출 (소스 IDML에서)
        String schemaJson = IDMLSchemaExtractor.extractSchema(sourceIdmlPath);
        SchemaInfo schema = parseSchemaInfo(schemaJson);

        // pages → pageSpecs, textFrameSpecs 변환
        List<String> pageSpecs = new ArrayList<>();
        List<double[]> textFrameSpecs = new ArrayList<>();
        for (PageConfig pc : config.pages) {
            pageSpecs.add(pc.master != null ? pc.master : "none");
            textFrameSpecs.add(pc.textFrame ? new double[]{-1, -1} : null);
        }

        // 기존 create() 호출 (Group 구조와 Story 생성은 items 데이터로 오버라이드)
        return createWithData(sourceIdmlPath, outputPath, null, pageSpecs, textFrameSpecs,
                config.tfMode, config.items, schema);
    }

    /**
     * 데이터 항목을 사용하여 IDML을 생성한다 (내부 구현).
     */
    private static CreateResult createWithData(String sourceIdmlPath, String outputPath,
                                                List<String> masterIds, List<String> pageSpecs,
                                                List<double[]> textFrameSpecs, String tfMode,
                                                List<DataItem> items, SchemaInfo schema) throws Exception {
        CreateResult result = new CreateResult();
        int inlineCount = items.size();

        File sourceFile = new File(sourceIdmlPath);
        if (!sourceFile.exists()) {
            throw new Exception("Source IDML file not found: " + sourceIdmlPath);
        }

        try (ZipFile sourceZip = new ZipFile(sourceFile)) {
            DesignmapInfo designmap = parseDesignmap(sourceZip);

            List<MasterSpreadRef> selectedMasters;
            if (masterIds == null || masterIds.isEmpty()) {
                selectedMasters = designmap.masterSpreads;
            } else {
                selectedMasters = new ArrayList<>();
                Set<String> idSet = new HashSet<>(masterIds);
                for (MasterSpreadRef ref : designmap.masterSpreads) {
                    if (idSet.contains(ref.masterId)) selectedMasters.add(ref);
                }
            }

            MasterLayout layout = new MasterLayout();
            String firstMasterId = null;
            if (!selectedMasters.isEmpty()) {
                firstMasterId = selectedMasters.get(0).masterId;
                MasterLayout extracted = extractMasterLayout(sourceZip, selectedMasters.get(0).src);
                if (extracted != null) layout = extracted;
            }

            boolean isCustomMode = "custom".equalsIgnoreCase(tfMode);
            int overrideColumnCount = isCustomMode ? 2 : -1;
            double overrideColumnGutter = isCustomMode ? 14.173228346456694 : -1;

            int pageCount = (pageSpecs != null && !pageSpecs.isEmpty()) ? pageSpecs.size() : 1;

            List<Integer> tfPageIndices = new ArrayList<>();
            if (textFrameSpecs != null) {
                for (int i = 0; i < Math.min(textFrameSpecs.size(), pageCount); i++) {
                    if (textFrameSpecs.get(i) != null) tfPageIndices.add(i);
                }
            }
            boolean hasTextFrames = !tfPageIndices.isEmpty();
            String storyId = "autoflow_story";

            Set<String> selectedMasterSrcs = new HashSet<>();
            for (MasterSpreadRef ref : selectedMasters) selectedMasterSrcs.add(ref.src);
            String modifiedDesignmap = modifyDesignmap(sourceZip, selectedMasterSrcs, pageCount,
                    hasTextFrames, storyId, hasTextFrames ? inlineCount : 0);

            File outputFile = new File(outputPath);
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

            try (FileOutputStream fos = new FileOutputStream(outputFile);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                writeMimetype(zos);

                Set<String> copiedEntries = new HashSet<>();
                copiedEntries.add("mimetype");
                copiedEntries.add("designmap.xml");

                copyMatchingEntries(sourceZip, zos, "META-INF/", copiedEntries);
                copyMatchingEntries(sourceZip, zos, "Resources/", copiedEntries);
                copyMatchingEntries(sourceZip, zos, "XML/", copiedEntries);

                for (MasterSpreadRef ref : selectedMasters) {
                    copyZipEntry(sourceZip, zos, ref.src);
                    copiedEntries.add(ref.src);
                }

                Map<String, MasterLayout> layoutCache = new HashMap<>();
                for (MasterSpreadRef ref : selectedMasters) {
                    MasterLayout ml = extractMasterLayout(sourceZip, ref.src);
                    if (ml != null) layoutCache.put(ref.masterId, ml);
                }

                String domVersion = designmap.domVersion != null ? designmap.domVersion : "21.1";
                for (int i = 0; i < pageCount; i++) {
                    String masterId;
                    if (pageSpecs != null && !pageSpecs.isEmpty()) {
                        masterId = pageSpecs.get(i);
                        if ("none".equalsIgnoreCase(masterId)) masterId = null;
                    } else {
                        masterId = firstMasterId;
                    }

                    MasterLayout pageLayout = (masterId != null && layoutCache.containsKey(masterId))
                            ? layoutCache.get(masterId) : layout;

                    String spreadId = "ublank" + i;
                    String pageId = "ublankpage" + i;

                    String textFrameId = null;
                    String prevFrameId = "n";
                    String nextFrameId = "n";
                    double tfWidth = 0, tfHeight = 0;

                    if (textFrameSpecs != null && i < textFrameSpecs.size() && textFrameSpecs.get(i) != null) {
                        textFrameId = "tf_autoflow_" + i;
                        double[] spec = textFrameSpecs.get(i);
                        tfWidth = spec[0] < 0 ? pageLayout.pageWidth - pageLayout.marginLeft - pageLayout.marginRight : spec[0];
                        tfHeight = spec[1] < 0 ? pageLayout.pageHeight - pageLayout.marginTop - pageLayout.marginBottom : spec[1];

                        int posInChain = tfPageIndices.indexOf(i);
                        if (posInChain > 0) prevFrameId = "tf_autoflow_" + tfPageIndices.get(posInChain - 1);
                        if (posInChain >= 0 && posInChain < tfPageIndices.size() - 1)
                            nextFrameId = "tf_autoflow_" + tfPageIndices.get(posInChain + 1);
                    }

                    int effectiveColCount = isCustomMode ? overrideColumnCount : pageLayout.columnCount;
                    double effectiveColGutter = isCustomMode ? overrideColumnGutter : pageLayout.columnGutter;

                    String spreadXml = generateEmptySpread(
                            pageLayout.pageWidth, pageLayout.pageHeight, masterId, domVersion,
                            spreadId, pageId, String.valueOf(i + 1),
                            textFrameId, storyId, prevFrameId, nextFrameId,
                            tfWidth, tfHeight, pageLayout.marginTop, pageLayout.marginLeft,
                            effectiveColCount, effectiveColGutter,
                            designmap.activeLayer);
                    writeEntry(zos, "Spreads/Spread_" + spreadId + ".xml", spreadXml);
                }

                // 텍스트 프레임이 있으면 Story 파일 생성 (데이터 기반)
                if (hasTextFrames) {
                    double contentWidth = layout.pageWidth - layout.marginLeft - layout.marginRight;
                    int cols = Math.max(isCustomMode ? overrideColumnCount : layout.columnCount, 1);
                    double gutter = isCustomMode ? overrideColumnGutter : layout.columnGutter;
                    double colWidth = (contentWidth - (cols - 1) * gutter) / cols;

                    String storyXml = generateAutoflowStory(storyId, domVersion,
                            inlineCount, colWidth, 100);
                    writeEntry(zos, "Stories/Story_" + storyId + ".xml", storyXml);

                    // 각 아이템별 Story 파일 생성
                    for (int ic = 0; ic < inlineCount; ic++) {
                        DataItem item = items.get(ic);

                        // content Story (문제+문항 - 다중 단락)
                        String inlineStoryId = "inline_story_" + ic;
                        String inlineStoryXml = generateStoryFromFields(inlineStoryId, domVersion,
                                schema.contentFields, item);
                        writeEntry(zos, "Stories/Story_" + inlineStoryId + ".xml", inlineStoryXml);

                        // number Story
                        String numStoryId = "inline_num_story_" + ic;
                        String numVal = item.fields.getOrDefault("number", String.valueOf(ic + 1));
                        String numStoryXml = generateSimpleStory(numStoryId, domVersion,
                                schema.numberStyle != null ? schema.numberStyle : "!문제번호", numVal);
                        writeEntry(zos, "Stories/Story_" + numStoryId + ".xml", numStoryXml);

                        // year Story
                        String yearStoryId = "inline_year_story_" + ic;
                        String yearVal = item.fields.getOrDefault("year", "");
                        String yearStoryXml = generateSimpleStory(yearStoryId, domVersion,
                                schema.yearStyle != null ? schema.yearStyle : "!년도", yearVal);
                        writeEntry(zos, "Stories/Story_" + yearStoryId + ".xml", yearStoryXml);
                    }
                }

                writeEntry(zos, "designmap.xml", modifiedDesignmap);
            }

            result.success = true;
            result.masterCount = selectedMasters.size();
            result.pageCount = pageCount;
            result.pageWidth = layout.pageWidth;
            result.pageHeight = layout.pageHeight;
        }
        return result;
    }

    /**
     * 다중 필드(단락)를 가진 Story XML 생성.
     * 각 필드는 하나의 ParagraphStyleRange가 된다.
     */
    private static String generateStoryFromFields(String storyId, String domVersion,
                                                    List<FieldMapping> fields, DataItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<idPkg:Story xmlns:idPkg=\"http://ns.adobe.com/AdobeInDesign/idml/1.0/packaging\" DOMVersion=\"").append(domVersion).append("\">\n");
        sb.append("\t<Story Self=\"").append(storyId).append("\"");
        sb.append(" StoryTitle=\"\" AppliedTOCStyle=\"n\" UserText=\"true\" AppliedNamedGrid=\"n\">\n");
        sb.append("\t\t<StoryPreference OpticalMarginAlignment=\"false\" OpticalMarginSize=\"12\"");
        sb.append(" FrameType=\"TextFrameType\" StoryOrientation=\"Horizontal\" StoryDirection=\"LeftToRightDirection\" />\n");
        sb.append("\t\t<InCopyExportOption IncludeGraphicProxies=\"true\" IncludeAllResources=\"false\" />\n");

        for (int f = 0; f < fields.size(); f++) {
            FieldMapping fm = fields.get(f);
            String text = item.fields.getOrDefault(fm.name, "");
            String escapedText = escapeXml(text);

            // 마지막 단락이 아니면 &#xD; (캐리지리턴) 추가로 단락 구분
            if (f < fields.size() - 1) {
                escapedText += "&#xD;";
            }

            sb.append("\t\t<ParagraphStyleRange AppliedParagraphStyle=\"ParagraphStyle/").append(fm.style).append("\">\n");
            sb.append("\t\t\t<CharacterStyleRange AppliedCharacterStyle=\"CharacterStyle/$ID/[None]\">\n");
            sb.append("\t\t\t\t<Content>").append(escapedText).append("</Content>\n");
            sb.append("\t\t\t</CharacterStyleRange>\n");
            sb.append("\t\t</ParagraphStyleRange>\n");
        }

        sb.append("\t</Story>\n");
        sb.append("</idPkg:Story>\n");
        return sb.toString();
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    // ─────────────────────────────────────────────────────────
    // JSON 파싱 (외부 라이브러리 없이 간단 파싱)
    // ─────────────────────────────────────────────────────────

    static class DataItem {
        Map<String, String> fields = new LinkedHashMap<>();
    }

    private static class PageConfig {
        String master;
        boolean textFrame = true;
    }

    private static class MergeConfig {
        List<PageConfig> pages = new ArrayList<>();
        String tfMode = "master";
        List<DataItem> items = new ArrayList<>();
    }

    private static class FieldMapping {
        String style;  // 단락 스타일명
        String name;   // 필드명
        FieldMapping(String style, String name) { this.style = style; this.name = name; }
    }

    private static class SchemaInfo {
        List<FieldMapping> contentFields = new ArrayList<>(); // content TF의 필드들
        String numberStyle;  // number TF의 스타일
        String yearStyle;    // year TF의 스타일
    }

    /**
     * 간단한 JSON 파서: MergeConfig를 추출한다.
     * 구조: {"pages":[{"master":"id","textFrame":true},...], "tfMode":"custom", "items":[{"field":"value",...},...]}
     */
    private static MergeConfig parseMergeConfig(String json) {
        MergeConfig config = new MergeConfig();

        // tfMode 추출
        int tfModeIdx = json.indexOf("\"tfMode\"");
        if (tfModeIdx >= 0) {
            int colonIdx = json.indexOf(':', tfModeIdx);
            int startQuote = json.indexOf('"', colonIdx + 1);
            int endQuote = json.indexOf('"', startQuote + 1);
            if (startQuote >= 0 && endQuote > startQuote) {
                config.tfMode = json.substring(startQuote + 1, endQuote);
            }
        }

        // pages 추출
        int pagesIdx = json.indexOf("\"pages\"");
        if (pagesIdx >= 0) {
            int arrStart = json.indexOf('[', pagesIdx);
            int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
            if (arrStart >= 0 && arrEnd > arrStart) {
                String pagesArr = json.substring(arrStart + 1, arrEnd);
                int pos = 0;
                while (pos < pagesArr.length()) {
                    int objStart = pagesArr.indexOf('{', pos);
                    if (objStart < 0) break;
                    int objEnd = pagesArr.indexOf('}', objStart);
                    if (objEnd < 0) break;
                    String obj = pagesArr.substring(objStart, objEnd + 1);

                    PageConfig pc = new PageConfig();
                    String masterVal = extractJsonStringValue(obj, "master");
                    pc.master = masterVal;
                    String tfVal = extractJsonStringValue(obj, "textFrame");
                    if (tfVal != null) {
                        pc.textFrame = "true".equals(tfVal);
                    } else {
                        // boolean으로 파싱
                        pc.textFrame = obj.contains("\"textFrame\"") ?
                                obj.contains("\"textFrame\":true") || obj.contains("\"textFrame\": true") : true;
                    }
                    config.pages.add(pc);
                    pos = objEnd + 1;
                }
            }
        }

        // items 추출
        int itemsIdx = json.indexOf("\"items\"");
        if (itemsIdx >= 0) {
            int arrStart = json.indexOf('[', itemsIdx);
            int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
            if (arrStart >= 0 && arrEnd > arrStart) {
                String itemsArr = json.substring(arrStart + 1, arrEnd);
                int pos = 0;
                while (pos < itemsArr.length()) {
                    int objStart = itemsArr.indexOf('{', pos);
                    if (objStart < 0) break;
                    int objEnd = findMatchingBracket(itemsArr, objStart, '{', '}');
                    if (objEnd < 0) break;
                    String obj = itemsArr.substring(objStart + 1, objEnd);

                    DataItem item = new DataItem();
                    // 모든 "key":"value" 쌍 추출
                    int kPos = 0;
                    while (kPos < obj.length()) {
                        int keyStart = obj.indexOf('"', kPos);
                        if (keyStart < 0) break;
                        int keyEnd = obj.indexOf('"', keyStart + 1);
                        if (keyEnd < 0) break;
                        String key = obj.substring(keyStart + 1, keyEnd);

                        int colonPos = obj.indexOf(':', keyEnd);
                        if (colonPos < 0) break;
                        int valStart = obj.indexOf('"', colonPos + 1);
                        if (valStart < 0) break;
                        int valEnd = findEndOfString(obj, valStart);
                        if (valEnd < 0) break;
                        String value = obj.substring(valStart + 1, valEnd);
                        // unescape JSON
                        value = value.replace("\\\"", "\"").replace("\\\\", "\\")
                                     .replace("\\n", "\n").replace("\\t", "\t");

                        item.fields.put(key, value);
                        kPos = valEnd + 1;
                    }
                    config.items.add(item);
                    pos = objEnd + 1;
                }
            }
        }

        return config;
    }

    /**
     * 스키마 JSON에서 필드 매핑 정보를 추출한다.
     */
    private static SchemaInfo parseSchemaInfo(String schemaJson) {
        SchemaInfo info = new SchemaInfo();

        // groupTemplate.children에서 textFrame의 fields 추출
        int gtIdx = schemaJson.indexOf("\"groupTemplate\"");
        if (gtIdx < 0) return info;

        int childrenIdx = schemaJson.indexOf("\"children\"", gtIdx);
        if (childrenIdx < 0) return info;

        int arrStart = schemaJson.indexOf('[', childrenIdx);
        int arrEnd = findMatchingBracket(schemaJson, arrStart, '[', ']');
        if (arrStart < 0 || arrEnd < 0) return info;
        String childrenArr = schemaJson.substring(arrStart, arrEnd + 1);

        // 각 child 객체 파싱
        int pos = 0;
        while (pos < childrenArr.length()) {
            int objStart = childrenArr.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBracket(childrenArr, objStart, '{', '}');
            if (objEnd < 0) break;
            String childObj = childrenArr.substring(objStart, objEnd + 1);

            String type = extractJsonStringValue(childObj, "type");
            String role = extractJsonStringValue(childObj, "role");

            if ("textFrame".equals(type) && childObj.contains("\"fields\"")) {
                // fields 배열에서 style+name 추출
                int fieldsIdx = childObj.indexOf("\"fields\"");
                int fArrStart = childObj.indexOf('[', fieldsIdx);
                int fArrEnd = findMatchingBracket(childObj, fArrStart, '[', ']');
                if (fArrStart >= 0 && fArrEnd > fArrStart) {
                    String fieldsArr = childObj.substring(fArrStart, fArrEnd + 1);
                    List<FieldMapping> fieldMappings = new ArrayList<>();
                    int fPos = 0;
                    while (fPos < fieldsArr.length()) {
                        int fObjStart = fieldsArr.indexOf('{', fPos);
                        if (fObjStart < 0) break;
                        int fObjEnd = fieldsArr.indexOf('}', fObjStart);
                        if (fObjEnd < 0) break;
                        String fObj = fieldsArr.substring(fObjStart, fObjEnd + 1);
                        String style = extractJsonStringValue(fObj, "style");
                        String name = extractJsonStringValue(fObj, "name");
                        if (style != null && name != null) {
                            fieldMappings.add(new FieldMapping(style, name));
                        }
                        fPos = fObjEnd + 1;
                    }

                    if ("content".equals(role)) {
                        info.contentFields = fieldMappings;
                    } else if ("number".equals(role) && !fieldMappings.isEmpty()) {
                        info.numberStyle = fieldMappings.get(0).style;
                    } else if ("year".equals(role) && !fieldMappings.isEmpty()) {
                        info.yearStyle = fieldMappings.get(0).style;
                    }
                }
            }

            pos = objEnd + 1;
        }

        return info;
    }

    private static String extractJsonStringValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;
        // skip whitespace
        int i = colonIdx + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        int endQuote = findEndOfString(json, i);
        if (endQuote < 0) return null;
        return json.substring(i + 1, endQuote);
    }

    private static int findMatchingBracket(String s, int start, char open, char close) {
        if (start < 0 || start >= s.length() || s.charAt(start) != open) return -1;
        int depth = 1;
        boolean inString = false;
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && inString) { i++; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == open) depth++;
            else if (c == close) { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static int findEndOfString(String s, int startQuote) {
        for (int i = startQuote + 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"') return i;
        }
        return -1;
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
        String activeLayer; // ActiveLayer 속성 (TextFrame에 필요)
    }

    private static class MasterSpreadRef {
        String src;
        String masterId;
    }

    static class MasterLayout {
        double pageWidth = 595.28;
        double pageHeight = 841.88;
        double marginTop = 56.69291338582678;
        double marginBottom = 56.69291338582678;
        double marginLeft = 56.69291338582678;
        double marginRight = 56.69291338582678;
        int columnCount = 1;
        double columnGutter = 14.173228346456694;
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
        info.activeLayer = root.getAttribute("ActiveLayer");

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
    private static String modifyDesignmap(ZipFile zip, Set<String> keepMasterSrcs, int pageCount,
                                              boolean hasTextFrames, String storyId,
                                              int inlineCount) throws Exception {
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
                // 기존 Spread 참조는 모두 제거 (새로 N개 삽입)
                toRemove.add(node);
            } else if ("idPkg:Story".equals(tagName)) {
                // 일반 Story만 제거 (BackingStory는 유지)
                toRemove.add(node);
                removedStorySrcs.add(elem.getAttribute("src"));
            } else if ("Section".equals(tagName)) {
                // Section의 PageStart를 첫 페이지로, Length를 페이지 수로 변경
                elem.setAttribute("PageStart", "ublankpage0");
                elem.setAttribute("Length", String.valueOf(pageCount));
            }
        }

        // StoryList에서 제거된 Story의 ID만 삭제
        if (root.hasAttribute("StoryList") && !removedStorySrcs.isEmpty()) {
            Set<String> removedStoryIds = new HashSet<String>();
            for (String src : removedStorySrcs) {
                String removedId = extractStoryIdFromFile(zip, src);
                if (removedId != null) {
                    removedStoryIds.add(removedId);
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

        // 제거
        for (Node node : toRemove) {
            root.removeChild(node);
        }

        // N개의 Spread 참조 삽입 (Layer/Section 앞에)
        Node insertBefore = null;
        NodeList updatedChildren = root.getChildNodes();
        for (int i = 0; i < updatedChildren.getLength(); i++) {
            Node node = updatedChildren.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                String tagName = ((Element) node).getTagName();
                if ("Layer".equals(tagName) || "Section".equals(tagName)) {
                    insertBefore = node;
                    break;
                }
            }
        }
        for (int i = 0; i < pageCount; i++) {
            Element spreadElem = doc.createElement("idPkg:Spread");
            spreadElem.setAttribute("src", "Spreads/Spread_ublank" + i + ".xml");
            if (insertBefore != null) {
                root.insertBefore(spreadElem, insertBefore);
            } else {
                root.appendChild(spreadElem);
            }
        }

        // 텍스트 프레임이 있으면 Story 참조 추가 + StoryList에 추가
        if (hasTextFrames) {
            Element storyElem = doc.createElement("idPkg:Story");
            storyElem.setAttribute("src", "Stories/Story_" + storyId + ".xml");
            // Spread 참조 뒤에 삽입 (Layer/Section 앞)
            Node storyInsertBefore = null;
            NodeList finalChildren = root.getChildNodes();
            for (int i = 0; i < finalChildren.getLength(); i++) {
                Node node = finalChildren.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    String tagName = ((Element) node).getTagName();
                    if ("Layer".equals(tagName) || "Section".equals(tagName)) {
                        storyInsertBefore = node;
                        break;
                    }
                }
            }
            if (storyInsertBefore != null) {
                root.insertBefore(storyElem, storyInsertBefore);
            } else {
                root.appendChild(storyElem);
            }

            // 인라인 콘텐츠 Story + 넘버링 Story + 년도 Story 참조 추가
            for (int ic = 0; ic < inlineCount; ic++) {
                Element inlineStoryElem = doc.createElement("idPkg:Story");
                inlineStoryElem.setAttribute("src", "Stories/Story_inline_story_" + ic + ".xml");
                if (storyInsertBefore != null) {
                    root.insertBefore(inlineStoryElem, storyInsertBefore);
                } else {
                    root.appendChild(inlineStoryElem);
                }

                Element numStoryElem = doc.createElement("idPkg:Story");
                numStoryElem.setAttribute("src", "Stories/Story_inline_num_story_" + ic + ".xml");
                if (storyInsertBefore != null) {
                    root.insertBefore(numStoryElem, storyInsertBefore);
                } else {
                    root.appendChild(numStoryElem);
                }

                Element yearStoryElem = doc.createElement("idPkg:Story");
                yearStoryElem.setAttribute("src", "Stories/Story_inline_year_story_" + ic + ".xml");
                if (storyInsertBefore != null) {
                    root.insertBefore(yearStoryElem, storyInsertBefore);
                } else {
                    root.appendChild(yearStoryElem);
                }
            }

            // StoryList에 autoflow_story + 인라인 Story + 넘버링 Story + 년도 Story 추가
            StringBuilder addStoryIds = new StringBuilder(storyId);
            for (int ic = 0; ic < inlineCount; ic++) {
                addStoryIds.append(" inline_story_").append(ic);
                addStoryIds.append(" inline_num_story_").append(ic);
                addStoryIds.append(" inline_year_story_").append(ic);
            }
            String currentStoryList = root.getAttribute("StoryList");
            if (currentStoryList == null || currentStoryList.isEmpty()) {
                root.setAttribute("StoryList", addStoryIds.toString());
            } else {
                root.setAttribute("StoryList", currentStoryList + " " + addStoryIds.toString());
            }
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

    private static MasterLayout extractMasterLayout(ZipFile zip, String src) {
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
                MasterLayout ml = new MasterLayout();

                String boundsStr = pageElem.getAttribute("GeometricBounds");
                if (boundsStr != null && !boundsStr.isEmpty()) {
                    String[] parts = boundsStr.trim().split("\\s+");
                    if (parts.length >= 4) {
                        double top = Double.parseDouble(parts[0]);
                        double left = Double.parseDouble(parts[1]);
                        double bottom = Double.parseDouble(parts[2]);
                        double right = Double.parseDouble(parts[3]);
                        ml.pageWidth = right - left;
                        ml.pageHeight = bottom - top;
                    }
                }

                // MarginPreference에서 마진/컬럼 정보 추출
                NodeList marginPrefs = pageElem.getElementsByTagName("MarginPreference");
                if (marginPrefs.getLength() > 0) {
                    Element mp = (Element) marginPrefs.item(0);
                    ml.marginTop = parseDoubleAttr(mp, "Top", ml.marginTop);
                    ml.marginBottom = parseDoubleAttr(mp, "Bottom", ml.marginBottom);
                    ml.marginLeft = parseDoubleAttr(mp, "Left", ml.marginLeft);
                    ml.marginRight = parseDoubleAttr(mp, "Right", ml.marginRight);
                    ml.columnCount = parseIntAttr(mp, "ColumnCount", ml.columnCount);
                    ml.columnGutter = parseDoubleAttr(mp, "ColumnGutter", ml.columnGutter);
                }

                // 마스터의 TextFrame에서 TextColumnCount를 확인 (MarginPreference보다 우선)
                NodeList textFrames = doc.getElementsByTagName("TextFrame");
                for (int i = 0; i < textFrames.getLength(); i++) {
                    Element tf = (Element) textFrames.item(i);
                    NodeList tfPrefs = tf.getElementsByTagName("TextFramePreference");
                    if (tfPrefs.getLength() > 0) {
                        Element pref = (Element) tfPrefs.item(0);
                        int tfColCount = parseIntAttr(pref, "TextColumnCount", 1);
                        if (tfColCount > ml.columnCount) {
                            ml.columnCount = tfColCount;
                            double tfColGutter = parseDoubleAttr(pref, "TextColumnGutter", ml.columnGutter);
                            ml.columnGutter = tfColGutter;
                        }
                        break; // 첫 번째 TextFrame만 확인
                    }
                }

                return ml;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private static double parseDoubleAttr(Element elem, String attr, double defaultVal) {
        String val = elem.getAttribute(attr);
        if (val == null || val.isEmpty()) return defaultVal;
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return defaultVal; }
    }

    private static int parseIntAttr(Element elem, String attr, int defaultVal) {
        String val = elem.getAttribute(attr);
        if (val == null || val.isEmpty()) return defaultVal;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultVal; }
    }

    // ─────────────────────────────────────────────────────────
    // Spread generation
    // ─────────────────────────────────────────────────────────

    private static String generateEmptySpread(double pageWidth, double pageHeight,
                                               String appliedMaster, String domVersion,
                                               String spreadId, String pageId, String pageName,
                                               String textFrameId, String parentStoryId,
                                               String prevFrameId, String nextFrameId,
                                               double tfWidth, double tfHeight,
                                               double marginTop, double marginLeft,
                                               int columnCount, double columnGutter,
                                               String itemLayer) {
        double halfHeight = pageHeight / 2.0;
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<idPkg:Spread xmlns:idPkg=\"http://ns.adobe.com/AdobeInDesign/idml/1.0/packaging\" DOMVersion=\"").append(domVersion).append("\">\n");
        sb.append("\t<Spread Self=\"").append(spreadId).append("\" PageTransitionType=\"None\" PageTransitionDirection=\"NotApplicable\" PageTransitionDuration=\"Medium\" ShowMasterItems=\"true\" PageCount=\"1\" BindingLocation=\"0\" SpreadHidden=\"false\" AllowPageShuffle=\"true\" ItemTransform=\"1 0 0 1 0 0\" FlattenerOverride=\"Default\">\n");
        sb.append("\t\t<FlattenerPreference LineArtAndTextResolution=\"400\" GradientAndMeshResolution=\"400\" ClipComplexRegions=\"false\" ConvertAllStrokesToOutlines=\"false\" ConvertAllTextToOutlines=\"false\">\n");
        sb.append("\t\t\t<Properties>\n");
        sb.append("\t\t\t\t<RasterVectorBalance type=\"double\">50</RasterVectorBalance>\n");
        sb.append("\t\t\t</Properties>\n");
        sb.append("\t\t</FlattenerPreference>\n");
        sb.append("\t\t<Page Self=\"").append(pageId).append("\" TabOrder=\"\" AppliedMaster=\"");
        sb.append(appliedMaster != null ? appliedMaster : "n");
        sb.append("\" OverrideList=\"\" MasterPageTransform=\"1 0 0 1 0 0\" Name=\"").append(pageName).append("\"");
        sb.append(" AppliedTrapPreset=\"TrapPreset/$ID/kDefaultTrapStyleName\"");
        sb.append(" GeometricBounds=\"0 0 ").append(pageHeight).append(" ").append(pageWidth).append("\"");
        sb.append(" ItemTransform=\"1 0 0 1 0 -").append(halfHeight).append("\"");
        sb.append(" LayoutRule=\"UseMaster\" SnapshotBlendingMode=\"IgnoreLayoutSnapshots\" OptionalPage=\"false\" GridStartingPoint=\"TopOutside\" UseMasterGrid=\"true\">\n");
        sb.append("\t\t\t<Properties>\n");
        sb.append("\t\t\t\t<PageColor type=\"enumeration\">UseMasterColor</PageColor>\n");
        sb.append("\t\t\t</Properties>\n");
        sb.append("\t\t\t<MarginPreference ColumnCount=\"1\" ColumnGutter=\"14.173228346456694\" Top=\"56.69291338582678\" Bottom=\"56.69291338582678\" Left=\"56.69291338582678\" Right=\"56.69291338582678\" ColumnDirection=\"Horizontal\" ColumnsPositions=\"0 ").append(pageWidth - 2 * 56.69291338582678).append("\" />\n");
        sb.append("\t\t</Page>\n");

        // TextFrame (자동 플로우용)
        if (textFrameId != null) {
            double tfTop = marginTop;
            double tfLeft = marginLeft;
            double tfBottom = tfTop + tfHeight;
            double tfRight = tfLeft + tfWidth;
            sb.append("\t\t<TextFrame Self=\"").append(textFrameId).append("\"");
            sb.append(" ParentStory=\"").append(parentStoryId).append("\"");
            sb.append(" ContentType=\"TextType\"");
            sb.append(" Visible=\"true\"");
            if (itemLayer != null && !itemLayer.isEmpty()) {
                sb.append(" ItemLayer=\"").append(itemLayer).append("\"");
            }
            sb.append(" GeometricBounds=\"").append(tfTop).append(" ").append(tfLeft).append(" ").append(tfBottom).append(" ").append(tfRight).append("\"");
            sb.append(" ItemTransform=\"1 0 0 1 0 -").append(halfHeight).append("\"");
            sb.append(" PreviousTextFrame=\"").append(prevFrameId).append("\"");
            sb.append(" NextTextFrame=\"").append(nextFrameId).append("\"");
            sb.append(" AppliedObjectStyle=\"ObjectStyle/$ID/[Normal Text Frame]\"");
            sb.append(" FillColor=\"Color/$ID/[None]\" StrokeColor=\"Color/$ID/[None]\" StrokeWeight=\"0\">\n");
            // PathGeometry — InDesign requires this to render the frame shape
            sb.append("\t\t\t<Properties>\n");
            sb.append("\t\t\t\t<PathGeometry>\n");
            sb.append("\t\t\t\t\t<GeometryPathType PathOpen=\"false\">\n");
            sb.append("\t\t\t\t\t\t<PathPointArray>\n");
            sb.append("\t\t\t\t\t\t\t<PathPointType Anchor=\"").append(tfLeft).append(" ").append(tfTop).append("\"");
            sb.append(" LeftDirection=\"").append(tfLeft).append(" ").append(tfTop).append("\"");
            sb.append(" RightDirection=\"").append(tfLeft).append(" ").append(tfTop).append("\" />\n");
            sb.append("\t\t\t\t\t\t\t<PathPointType Anchor=\"").append(tfLeft).append(" ").append(tfBottom).append("\"");
            sb.append(" LeftDirection=\"").append(tfLeft).append(" ").append(tfBottom).append("\"");
            sb.append(" RightDirection=\"").append(tfLeft).append(" ").append(tfBottom).append("\" />\n");
            sb.append("\t\t\t\t\t\t\t<PathPointType Anchor=\"").append(tfRight).append(" ").append(tfBottom).append("\"");
            sb.append(" LeftDirection=\"").append(tfRight).append(" ").append(tfBottom).append("\"");
            sb.append(" RightDirection=\"").append(tfRight).append(" ").append(tfBottom).append("\" />\n");
            sb.append("\t\t\t\t\t\t\t<PathPointType Anchor=\"").append(tfRight).append(" ").append(tfTop).append("\"");
            sb.append(" LeftDirection=\"").append(tfRight).append(" ").append(tfTop).append("\"");
            sb.append(" RightDirection=\"").append(tfRight).append(" ").append(tfTop).append("\" />\n");
            sb.append("\t\t\t\t\t\t</PathPointArray>\n");
            sb.append("\t\t\t\t\t</GeometryPathType>\n");
            sb.append("\t\t\t\t</PathGeometry>\n");
            sb.append("\t\t\t</Properties>\n");
            int cols = Math.max(columnCount, 1);
            sb.append("\t\t\t<TextFramePreference TextColumnCount=\"").append(cols).append("\"");
            sb.append(" TextColumnGutter=\"").append(columnGutter).append("\"");
            sb.append(" InsetSpacing=\"0 0 0 0\" VerticalJustification=\"TopAlign\" />\n");
            sb.append("\t\t</TextFrame>\n");
        }

        sb.append("\t</Spread>\n");
        sb.append("</idPkg:Spread>\n");
        return sb.toString();
    }

    /**
     * 자동 플로우 Story 생성.
     * inlineCount > 0이면 인라인 앵커 텍스트 프레임을 포함한다.
     */
    private static String generateAutoflowStory(String storyId, String domVersion,
                                                 int inlineCount, double inlineWidth, double inlineHeight) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<idPkg:Story xmlns:idPkg=\"http://ns.adobe.com/AdobeInDesign/idml/1.0/packaging\" DOMVersion=\"").append(domVersion).append("\">\n");
        sb.append("\t<Story Self=\"").append(storyId).append("\"");
        sb.append(" StoryTitle=\"\" AppliedTOCStyle=\"n\" UserText=\"true\" AppliedNamedGrid=\"n\">\n");
        sb.append("\t\t<StoryPreference OpticalMarginAlignment=\"false\" OpticalMarginSize=\"12\"");
        sb.append(" FrameType=\"TextFrameType\" StoryOrientation=\"Horizontal\" StoryDirection=\"LeftToRightDirection\" />\n");
        sb.append("\t\t<InCopyExportOption IncludeGraphicProxies=\"true\" IncludeAllResources=\"false\" />\n");

        if (inlineCount > 0) {
            // 각 인라인 항목을 Group(5개 자식)으로 구성 (flow.idml 구조 참조):
            // 1. Rectangle (배경, GraphicType)
            // 2. TextFrame 년도 (!년도)
            // 3. TextFrame 문제+문항 (20풀_문제 + 20풀_문항)
            // 4. TextFrame 문제번호 (!문제번호)
            // 5. GraphicLine (구분선)
            double numW = 51;    // 문제번호 너비
            double numH = 31;    // 문제번호 높이
            double yearH = 14;   // 년도 높이
            double rectH = 14;   // 배경 스트립 높이
            double contentH = inlineHeight; // 문제+문항 높이 (AutoSizing)
            double totalHeight = numH + contentH;
            String ind5 = "\t\t\t\t\t";
            String ind6 = "\t\t\t\t\t\t";

            sb.append("\t\t<ParagraphStyleRange AppliedParagraphStyle=\"ParagraphStyle/$ID/[Basic Paragraph]\">\n");
            sb.append("\t\t\t<CharacterStyleRange AppliedCharacterStyle=\"CharacterStyle/$ID/[None]\">\n");
            for (int i = 0; i < inlineCount; i++) {
                String groupId = "inline_group_" + i;
                String rectId = "inline_rect_" + i;
                String yearTfId = "inline_year_tf_" + i;
                String yearStoryId = "inline_year_story_" + i;
                String contentTfId = "inline_tf_" + i;
                String contentStoryId = "inline_story_" + i;
                String numTfId = "inline_num_tf_" + i;
                String numStoryId = "inline_num_story_" + i;
                String lineId = "inline_line_" + i;

                // ── Group (인라인 앵커 객체) ──
                sb.append("\t\t\t\t<Group Self=\"").append(groupId).append("\"");
                sb.append(" Visible=\"true\"");
                sb.append(" ItemTransform=\"1 0 0 1 0 0\">\n");

                // ① Rectangle (배경 스트립, 상단)
                sb.append(ind5).append("<Rectangle Self=\"").append(rectId).append("\"");
                sb.append(" ContentType=\"GraphicType\" Visible=\"true\"");
                sb.append(" GeometricBounds=\"0 0 ").append(rectH).append(" ").append(inlineWidth).append("\"");
                sb.append(" ItemTransform=\"1 0 0 1 0 0\"");
                sb.append(" FillColor=\"Color/$ID/[None]\" StrokeColor=\"Color/$ID/[None]\" StrokeWeight=\"0\">\n");
                sb.append(ind6).append("<Properties>\n");
                appendPathGeometry(sb, ind6 + "\t", 0, 0, rectH, inlineWidth);
                sb.append(ind6).append("</Properties>\n");
                sb.append(ind5).append("</Rectangle>\n");

                // ② TextFrame 년도 (!년도, 좌상단)
                appendTextFrame(sb, ind5, yearTfId, yearStoryId,
                        0, 0, yearH, inlineWidth, inlineWidth, true);

                // ③ TextFrame 문제+문항 (20풀_문제 + 20풀_문항, 메인 영역)
                appendTextFrame(sb, ind5, contentTfId, contentStoryId,
                        numH, 0, numH + contentH, inlineWidth, inlineWidth, true);

                // ④ TextFrame 문제번호 (!문제번호, 좌상단 오버레이)
                appendTextFrame(sb, ind5, numTfId, numStoryId,
                        0, 0, numH, numW, numW, true);

                // ⑤ GraphicLine (세로 구분선, 우측)
                sb.append(ind5).append("<GraphicLine Self=\"").append(lineId).append("\"");
                sb.append(" StrokeWeight=\"0.2\" StrokeColor=\"Color/Black\"");
                sb.append(" ItemTransform=\"1 0 0 1 0 0\">\n");
                sb.append(ind6).append("<Properties>\n");
                sb.append(ind6).append("\t<PathGeometry>\n");
                sb.append(ind6).append("\t\t<GeometryPathType PathOpen=\"true\">\n");
                sb.append(ind6).append("\t\t\t<PathPointArray>\n");
                appendPathPoint(sb, ind6 + "\t\t\t\t", inlineWidth, 0);
                appendPathPoint(sb, ind6 + "\t\t\t\t", inlineWidth, totalHeight);
                sb.append(ind6).append("\t\t\t</PathPointArray>\n");
                sb.append(ind6).append("\t\t</GeometryPathType>\n");
                sb.append(ind6).append("\t</PathGeometry>\n");
                sb.append(ind6).append("</Properties>\n");
                sb.append(ind5).append("</GraphicLine>\n");

                // ── Group 레벨의 앵커 + 텍스트 랩 설정 ──
                sb.append(ind5).append("<AnchoredObjectSetting AnchoredPosition=\"InlinePosition\"");
                sb.append(" SpineRelative=\"false\" LockPosition=\"false\" PinPosition=\"true\"");
                sb.append(" AnchorPoint=\"BottomRightAnchor\" HorizontalAlignment=\"LeftAlign\"");
                sb.append(" HorizontalReferencePoint=\"TextFrame\" VerticalAlignment=\"BottomAlign\"");
                sb.append(" VerticalReferencePoint=\"LineBaseline\"");
                sb.append(" AnchorXoffset=\"0\" AnchorYoffset=\"0\" AnchorSpaceAbove=\"0\" />\n");
                sb.append(ind5).append("<TextWrapPreference Inverse=\"false\" ApplyToMasterPageOnly=\"false\"");
                sb.append(" TextWrapSide=\"BothSides\" TextWrapMode=\"None\">\n");
                sb.append(ind6).append("<Properties>\n");
                sb.append(ind6).append("\t<TextWrapOffset Top=\"0\" Left=\"0\" Bottom=\"0\" Right=\"0\" />\n");
                sb.append(ind6).append("</Properties>\n");
                sb.append(ind5).append("</TextWrapPreference>\n");
                sb.append("\t\t\t\t</Group>\n");

                // 인라인 객체 사이에 Br (줄바꿈) 삽입
                if (i < inlineCount - 1) {
                    sb.append("\t\t\t\t<Br />\n");
                }
            }
            sb.append("\t\t\t</CharacterStyleRange>\n");
            sb.append("\t\t</ParagraphStyleRange>\n");
        } else {
            // 인라인 없으면 기본 플레이스홀더
            sb.append("\t\t<ParagraphStyleRange AppliedParagraphStyle=\"ParagraphStyle/$ID/[Basic Paragraph]\">\n");
            sb.append("\t\t\t<CharacterStyleRange AppliedCharacterStyle=\"CharacterStyle/$ID/[None]\">\n");
            sb.append("\t\t\t\t<Content>Lorem ipsum dolor sit amet, consectetur adipiscing elit.</Content>\n");
            sb.append("\t\t\t</CharacterStyleRange>\n");
            sb.append("\t\t</ParagraphStyleRange>\n");
        }

        sb.append("\t</Story>\n");
        sb.append("</idPkg:Story>\n");
        return sb.toString();
    }

    /**
     * PathGeometry (닫힌 사각형) 출력 헬퍼.
     */
    private static void appendPathGeometry(StringBuilder sb, String indent, double top, double left, double bottom, double right) {
        sb.append(indent).append("<PathGeometry>\n");
        sb.append(indent).append("\t<GeometryPathType PathOpen=\"false\">\n");
        sb.append(indent).append("\t\t<PathPointArray>\n");
        String pi = indent + "\t\t\t";
        appendPathPoint(sb, pi, left, top);
        appendPathPoint(sb, pi, left, bottom);
        appendPathPoint(sb, pi, right, bottom);
        appendPathPoint(sb, pi, right, top);
        sb.append(indent).append("\t\t</PathPointArray>\n");
        sb.append(indent).append("\t</GeometryPathType>\n");
        sb.append(indent).append("</PathGeometry>\n");
    }

    /**
     * TextFrame 요소 출력 헬퍼. AutoSizing 포함.
     */
    private static void appendTextFrame(StringBuilder sb, String indent, String tfId, String storyId,
                                          double top, double left, double bottom, double right,
                                          double width, boolean autoSizing) {
        sb.append(indent).append("<TextFrame Self=\"").append(tfId).append("\"");
        sb.append(" ParentStory=\"").append(storyId).append("\"");
        sb.append(" PreviousTextFrame=\"n\" NextTextFrame=\"n\"");
        sb.append(" ContentType=\"TextType\" Visible=\"true\"");
        sb.append(" GeometricBounds=\"").append(top).append(" ").append(left).append(" ").append(bottom).append(" ").append(right).append("\"");
        sb.append(" ItemTransform=\"1 0 0 1 0 0\"");
        sb.append(" AppliedObjectStyle=\"ObjectStyle/$ID/[Normal Text Frame]\"");
        sb.append(" FillColor=\"Color/$ID/[None]\" StrokeColor=\"Color/$ID/[None]\" StrokeWeight=\"0\">\n");
        String ind1 = indent + "\t";
        sb.append(ind1).append("<Properties>\n");
        appendPathGeometry(sb, ind1 + "\t", top, left, bottom, right);
        sb.append(ind1).append("</Properties>\n");
        sb.append(ind1).append("<TextFramePreference TextColumnCount=\"1\" TextColumnGutter=\"12\"");
        sb.append(" TextColumnMaxWidth=\"0\" InsetSpacing=\"0 0 0 0\" VerticalJustification=\"TopAlign\"");
        if (autoSizing) {
            sb.append(" AutoSizingType=\"HeightOnly\" AutoSizingReferencePoint=\"TopCenterPoint\"");
            sb.append(" UseNoLineBreaksForAutoSizing=\"false\"");
        }
        sb.append(" />\n");
        sb.append(indent).append("</TextFrame>\n");
    }

    private static void appendPathPoint(StringBuilder sb, String indent, double x, double y) {
        sb.append(indent).append("<PathPointType Anchor=\"").append(x).append(" ").append(y).append("\"");
        sb.append(" LeftDirection=\"").append(x).append(" ").append(y).append("\"");
        sb.append(" RightDirection=\"").append(x).append(" ").append(y).append("\" />\n");
    }

    /**
     * 인라인 텍스트 프레임용 개별 Story 생성 (콘텐츠).
     * 두 단락: 문제 텍스트 (20풀_문제) + 선택지 (20풀_문항)
     */
    private static String generateInlineStory(String storyId, String domVersion, int index) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<idPkg:Story xmlns:idPkg=\"http://ns.adobe.com/AdobeInDesign/idml/1.0/packaging\" DOMVersion=\"").append(domVersion).append("\">\n");
        sb.append("\t<Story Self=\"").append(storyId).append("\"");
        sb.append(" StoryTitle=\"\" AppliedTOCStyle=\"n\" UserText=\"true\" AppliedNamedGrid=\"n\">\n");
        sb.append("\t\t<StoryPreference OpticalMarginAlignment=\"false\" OpticalMarginSize=\"12\"");
        sb.append(" FrameType=\"TextFrameType\" StoryOrientation=\"Horizontal\" StoryDirection=\"LeftToRightDirection\" />\n");
        sb.append("\t\t<InCopyExportOption IncludeGraphicProxies=\"true\" IncludeAllResources=\"false\" />\n");
        // 단락 1: 문제 (20풀_문제)
        sb.append("\t\t<ParagraphStyleRange AppliedParagraphStyle=\"ParagraphStyle/20풀_문제\">\n");
        sb.append("\t\t\t<CharacterStyleRange AppliedCharacterStyle=\"CharacterStyle/$ID/[None]\">\n");
        sb.append("\t\t\t\t<Content>자연수 n이 2&lt;n&lt;11일 때, -n@+9n-18의 n제곱근 중에서 음의 실수가 존재하도록 하는 모든 n의 값의 합은? [3점]&#xD;</Content>\n");
        sb.append("\t\t\t</CharacterStyleRange>\n");
        sb.append("\t\t</ParagraphStyleRange>\n");
        // 단락 2: 선택지 (20풀_문항)
        sb.append("\t\t<ParagraphStyleRange AppliedParagraphStyle=\"ParagraphStyle/20풀_문항\">\n");
        sb.append("\t\t\t<CharacterStyleRange AppliedCharacterStyle=\"CharacterStyle/$ID/[None]\">\n");
        sb.append("\t\t\t\t<Content>① 31\t② 33\t③ 35\t④ 37\t⑤ 39</Content>\n");
        sb.append("\t\t\t</CharacterStyleRange>\n");
        sb.append("\t\t</ParagraphStyleRange>\n");
        sb.append("\t</Story>\n");
        sb.append("</idPkg:Story>\n");
        return sb.toString();
    }

    /**
     * 넘버링 텍스트 프레임용 개별 Story 생성.
     * 단락스타일: !문제번호
     */
    private static String generateNumberingStory(String storyId, String domVersion, int index) {
        return generateSimpleStory(storyId, domVersion, "!문제번호", String.valueOf(index + 1));
    }

    /**
     * 년도(출처) 텍스트 프레임용 개별 Story 생성.
     * 단락스타일: !년도
     */
    private static String generateYearStory(String storyId, String domVersion, int index) {
        return generateSimpleStory(storyId, domVersion, "!년도", "2021학년도 6월 가형 " + (index + 1) + "번");
    }

    /**
     * 단일 단락 Story 생성 헬퍼.
     */
    private static String generateSimpleStory(String storyId, String domVersion, String styleName, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<idPkg:Story xmlns:idPkg=\"http://ns.adobe.com/AdobeInDesign/idml/1.0/packaging\" DOMVersion=\"").append(domVersion).append("\">\n");
        sb.append("\t<Story Self=\"").append(storyId).append("\"");
        sb.append(" StoryTitle=\"\" AppliedTOCStyle=\"n\" UserText=\"true\" AppliedNamedGrid=\"n\">\n");
        sb.append("\t\t<StoryPreference OpticalMarginAlignment=\"false\" OpticalMarginSize=\"12\"");
        sb.append(" FrameType=\"TextFrameType\" StoryOrientation=\"Horizontal\" StoryDirection=\"LeftToRightDirection\" />\n");
        sb.append("\t\t<InCopyExportOption IncludeGraphicProxies=\"true\" IncludeAllResources=\"false\" />\n");
        sb.append("\t\t<ParagraphStyleRange AppliedParagraphStyle=\"ParagraphStyle/").append(styleName).append("\">\n");
        sb.append("\t\t\t<CharacterStyleRange AppliedCharacterStyle=\"CharacterStyle/$ID/[None]\">\n");
        sb.append("\t\t\t\t<Content>").append(content).append("</Content>\n");
        sb.append("\t\t\t</CharacterStyleRange>\n");
        sb.append("\t\t</ParagraphStyleRange>\n");
        sb.append("\t</Story>\n");
        sb.append("</idPkg:Story>\n");
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
