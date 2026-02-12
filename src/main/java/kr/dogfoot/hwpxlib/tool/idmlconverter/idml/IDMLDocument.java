package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.io.File;
import java.util.*;

/**
 * IDML 문서 전체 모델 (메모리 내 표현).
 */
public class IDMLDocument {
    private List<IDMLSpread> spreads;
    private Map<String, IDMLStory> stories;
    private Map<String, IDMLStyleDef> paraStyles;
    private Map<String, IDMLStyleDef> charStyles;
    private Map<String, IDMLFontDef> fonts;
    private Map<String, String> colors;
    private Set<String> hiddenLayerIds;
    private Map<String, IDMLSpread> masterSpreads;
    private String basePath;
    private File tempDir;  // ZIP에서 추출한 임시 디렉토리 (cleanup 대상)
    private int pageNumberStart;

    public IDMLDocument() {
        this.spreads = new ArrayList<IDMLSpread>();
        this.stories = new LinkedHashMap<String, IDMLStory>();
        this.paraStyles = new LinkedHashMap<String, IDMLStyleDef>();
        this.charStyles = new LinkedHashMap<String, IDMLStyleDef>();
        this.fonts = new LinkedHashMap<String, IDMLFontDef>();
        this.colors = new LinkedHashMap<String, String>();
        this.hiddenLayerIds = new HashSet<String>();
        this.masterSpreads = new LinkedHashMap<String, IDMLSpread>();
        this.pageNumberStart = 1;
    }

    public List<IDMLSpread> spreads() { return spreads; }
    public void addSpread(IDMLSpread spread) { spreads.add(spread); }

    public Map<String, IDMLStory> stories() { return stories; }
    public IDMLStory getStory(String storyId) { return stories.get(storyId); }
    public void putStory(String storyId, IDMLStory story) { stories.put(storyId, story); }

    public Map<String, IDMLStyleDef> paraStyles() { return paraStyles; }
    public IDMLStyleDef getParagraphStyle(String styleRef) { return paraStyles.get(styleRef); }
    public void putParagraphStyle(String styleRef, IDMLStyleDef style) { paraStyles.put(styleRef, style); }

    public Map<String, IDMLStyleDef> charStyles() { return charStyles; }
    public IDMLStyleDef getCharacterStyle(String styleRef) { return charStyles.get(styleRef); }
    public void putCharacterStyle(String styleRef, IDMLStyleDef style) { charStyles.put(styleRef, style); }

    public Map<String, IDMLFontDef> fonts() { return fonts; }
    public IDMLFontDef getFont(String fontRef) { return fonts.get(fontRef); }
    public void putFont(String fontRef, IDMLFontDef font) { fonts.put(fontRef, font); }

    public Map<String, String> colors() { return colors; }
    public String getColor(String colorRef) { return colors.get(colorRef); }
    public void putColor(String colorRef, String hexColor) { colors.put(colorRef, hexColor); }

    public Map<String, IDMLSpread> masterSpreads() { return masterSpreads; }
    public IDMLSpread getMasterSpread(String masterId) { return masterSpreads.get(masterId); }
    public void addMasterSpread(String masterId, IDMLSpread spread) { masterSpreads.put(masterId, spread); }

    public Set<String> hiddenLayerIds() { return hiddenLayerIds; }
    public void addHiddenLayerId(String id) { hiddenLayerIds.add(id); }
    public boolean isHiddenLayer(String layerId) {
        return layerId != null && hiddenLayerIds.contains(layerId);
    }

    public String basePath() { return basePath; }
    public void basePath(String v) { this.basePath = v; }

    public File tempDir() { return tempDir; }
    public void tempDir(File v) { this.tempDir = v; }

    public int pageNumberStart() { return pageNumberStart; }
    public void pageNumberStart(int v) { this.pageNumberStart = v; }

    /**
     * ZIP에서 추출한 임시 디렉토리가 있으면 삭제한다.
     * 변환 작업이 완전히 끝난 후 호출해야 한다.
     */
    public void cleanup() {
        if (tempDir != null && tempDir.exists()) {
            deleteDirectory(tempDir);
            tempDir = null;
        }
    }

    private static void deleteDirectory(File dir) {
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

    /**
     * 전체 페이지 목록 (모든 스프레드의 페이지를 순서대로).
     */
    public List<IDMLPage> getAllPages() {
        List<IDMLPage> allPages = new ArrayList<IDMLPage>();
        for (IDMLSpread spread : spreads) {
            allPages.addAll(spread.pages());
        }
        return allPages;
    }

    /**
     * 전체 페이지 수.
     */
    public int totalPageCount() {
        int count = 0;
        for (IDMLSpread spread : spreads) {
            count += spread.pages().size();
        }
        return count;
    }
}
