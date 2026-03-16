package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;

import org.w3c.dom.*;
import java.io.*;
import java.util.*;
import static kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLXmlUtils.*;

/**
 * IDML(InDesign Markup Language) 파일을 로드하여 IDMLDocument 메모리 모델로 변환한다.
 *
 * 처리 순서:
 * 1. IDML ZIP 해제 (또는 이미 해제된 디렉토리 사용)
 * 2. designmap.xml → 스프레드/Story 목록, Section(페이지 번호) 정보
 * 3. Resources/Fonts.xml → 폰트 정의
 * 4. Resources/Styles.xml → 단락/문자 스타일
 * 5. Resources/Graphic.xml → 색상 정의
 * 6. Spreads/*.xml → 페이지, TextFrame, ImageFrame
 * 7. Stories/*.xml → 텍스트 내용 (ParagraphStyleRange → CharacterStyleRange → Content)
 */
public class IDMLLoader {

    /**
     * IDML ZIP 파일을 로드하여 IDMLDocument로 반환한다.
     */
    public static IDMLDocument load(String idmlPath) throws ConvertException {
        return load(new File(idmlPath));
    }

    /**
     * IDML ZIP 파일을 로드하여 IDMLDocument로 반환한다.
     */
    public static IDMLDocument load(File idmlFile) throws ConvertException {
        if (!idmlFile.exists()) {
            throw new ConvertException(ConvertException.Phase.LOADING,
                    "IDML file not found: " + idmlFile.getAbsolutePath());
        }

        File tempDir = extractZip(idmlFile);
        try {
            IDMLDocument doc = loadFromDirectory(tempDir);
            doc.tempDir(tempDir);  // 변환 완료 후 cleanup()에서 삭제
            return doc;
        } catch (Exception e) {
            // 로드 실패 시 즉시 정리
            deleteDirectory(tempDir);
            if (e instanceof ConvertException) {
                throw (ConvertException) e;
            }
            throw new ConvertException(ConvertException.Phase.LOADING,
                    "Failed to load IDML: " + e.getMessage(), e);
        }
    }

    /**
     * 이미 해제된 IDML 디렉토리에서 로드한다.
     */
    public static IDMLDocument loadFromDirectory(String dirPath) throws ConvertException {
        return loadFromDirectory(new File(dirPath));
    }

    /**
     * 이미 해제된 IDML 디렉토리에서 로드한다.
     */
    public static IDMLDocument loadFromDirectory(File dir) throws ConvertException {
        if (!dir.exists() || !dir.isDirectory()) {
            throw new ConvertException(ConvertException.Phase.LOADING,
                    "IDML directory not found: " + dir.getAbsolutePath());
        }

        File designmapFile = new File(dir, "designmap.xml");
        if (!designmapFile.exists()) {
            throw new ConvertException(ConvertException.Phase.LOADING,
                    "designmap.xml not found in: " + dir.getAbsolutePath());
        }

        IDMLDocument doc = new IDMLDocument();
        doc.basePath(dir.getAbsolutePath());

        try {
            // 1. designmap.xml에서 기본 정보 추출
            Document designmap = parseXML(designmapFile);
            List<String> spreadSources = new ArrayList<String>();
            List<String> masterSpreadSources = new ArrayList<String>();
            List<SectionInfo> sections = new ArrayList<SectionInfo>();
            IDMLResourceParser.parseDesignmap(designmap, spreadSources, masterSpreadSources, sections, doc);

            // 2. 폰트 로드
            File fontsFile = new File(dir, "Resources/Fonts.xml");
            if (fontsFile.exists()) {
                IDMLResourceParser.parseFonts(parseXML(fontsFile), doc);
            }

            // 3. 스타일 로드
            File stylesFile = new File(dir, "Resources/Styles.xml");
            if (stylesFile.exists()) {
                IDMLResourceParser.parseStyles(parseXML(stylesFile), doc);
            }

            // 4. 색상 로드
            File graphicFile = new File(dir, "Resources/Graphic.xml");
            if (graphicFile.exists()) {
                IDMLResourceParser.parseGraphic(parseXML(graphicFile), doc);
            }

            // 4.5. 마스터 스프레드 로드 (IDMLSpread 객체 생성)
            for (String masterSrc : masterSpreadSources) {
                File masterFile = new File(dir, masterSrc);
                if (masterFile.exists()) {
                    Document masterDoc = parseXML(masterFile);
                    IDMLSpread masterSpread = IDMLSpreadParser.parseSpread(masterDoc, doc.hiddenLayerIds());
                    if (masterSpread.selfId() != null) {
                        doc.addMasterSpread(masterSpread.selfId(), masterSpread);
                    }
                }
            }

            // 5. 스프레드 로드 (페이지 + 프레임)
            int pageIndex = 0;
            for (String spreadSrc : spreadSources) {
                File spreadFile = new File(dir, spreadSrc);
                if (spreadFile.exists()) {
                    IDMLSpread spread = IDMLSpreadParser.parseSpread(parseXML(spreadFile), doc.hiddenLayerIds());
                    // 페이지 번호 할당 및 마스터 마진 상속
                    for (IDMLPage page : spread.pages()) {
                        pageIndex++;
                        int pageNum = resolvePageNumber(pageIndex, page.selfId(), sections);
                        page.pageNumber(pageNum);
                        page.sectionMarker(resolveSectionMarker(pageIndex, sections));

                        // 마스터 스프레드 기반 마진/컬럼 상속
                        if (page.appliedMasterSpread() != null) {
                            IDMLSpread masterSpread = doc.getMasterSpread(page.appliedMasterSpread());
                            if (masterSpread != null && !masterSpread.pages().isEmpty()) {
                                // 스프레드 내 페이지 인덱스로 좌/우 마스터 페이지 매칭
                                int pageIdxInSpread = spread.pages().indexOf(page);
                                int masterPageIdx = Math.min(pageIdxInSpread, masterSpread.pages().size() - 1);
                                masterPageIdx = Math.max(0, masterPageIdx);
                                IDMLPage masterPage = masterSpread.pages().get(masterPageIdx);

                                page.marginTop(masterPage.marginTop());
                                page.marginBottom(masterPage.marginBottom());
                                page.marginLeft(masterPage.marginLeft());
                                page.marginRight(masterPage.marginRight());
                                if (masterPage.columnCount() > 1) {
                                    page.columnCount(masterPage.columnCount());
                                    page.columnGutter(masterPage.columnGutter());
                                }

                                // Facing pages: 왼쪽 페이지(idx=0)는 Inside/Outside가 반전
                                // IDML Left=Inside, Right=Outside → 왼쪽 페이지는 Inside가 오른쪽
                                if (spread.pages().size() == 2 && pageIdxInSpread == 0
                                        && page.marginLeft() != page.marginRight()) {
                                    double tmpL = page.marginLeft();
                                    page.marginLeft(page.marginRight());
                                    page.marginRight(tmpL);
                                }
                            }
                        }
                    }
                    doc.addSpread(spread);
                }
            }

            // 6. 스프레드에서 참조하는 Story들 수집
            Set<String> neededStoryIds = IDMLStoryParser.collectNeededStoryIds(doc);

            // 7. Story 로드 (TextVariable 해결 값도 함께 수집)
            File storiesDir = new File(dir, "Stories");
            if (storiesDir.exists() && storiesDir.isDirectory()) {
                for (String storyId : neededStoryIds) {
                    File storyFile = new File(storiesDir, "Story_" + storyId + ".xml");
                    if (storyFile.exists()) {
                        IDMLStory story = IDMLStoryParser.parseStory(parseXML(storyFile), storyId, doc);
                        doc.putStory(storyId, story);
                    }
                }

                // 7-1. 로드된 Story 내 인라인 TextFrame이 참조하는 추가 Story를 재귀 로드
                IDMLStoryParser.loadReferencedInlineStories(doc, storiesDir);
            }

            // 8. Story에서 인라인 그래픽(앵커 오브젝트) 추출 및 스프레드에 추가
            IDMLStoryParser.extractInlineGraphicsFromStories(doc, storiesDir, neededStoryIds);

            // 9. CharacterStyle 정의에서 fontFamily 상속 (명시적 AppliedFont 없는 런)
            IDMLStoryParser.resolveCharacterStyleFonts(doc);

            // 10. GREP 스타일에서 BT수식M 폰트가 동적 적용되는 런 해석
            IDMLStoryParser.resolveGrepMathStyles(doc);

            // 10-1. GREP 스타일에서 일반 문자 스타일 속성(FillColor 등) 동적 적용
            IDMLStoryParser.resolveGrepGenericStyles(doc);

            // 11. ObjectStyle 상속: CornerRadius가 없고 CornerOption=RoundedCorner인 벡터/프레임에
            //     ObjectStyle의 CornerRadius 적용
            resolveInheritedCornerRadius(doc);

        } catch (ConvertException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.PARSING,
                    "Failed to parse IDML: " + e.getMessage(), e);
        }

        return doc;
    }

    // ===== 페이지 번호 관련 =====

    /**
     * Section 정보를 기반으로 페이지의 실제 번호를 결정한다.
     */
    private static int resolvePageNumber(int pageIndex, String pageId,
                                         List<SectionInfo> sections) {
        // Section이 없으면 순차 번호
        if (sections.isEmpty()) return pageIndex;

        // 가장 마지막으로 시작된 Section을 찾는다
        SectionInfo applicableSection = null;
        int pagesBeforeSection = 0;
        int currentPageCount = 0;

        for (SectionInfo section : sections) {
            if (section.pageStart != null && !section.pageStart.isEmpty()) {
                if (currentPageCount < pageIndex) {
                    applicableSection = section;
                    pagesBeforeSection = currentPageCount;
                }
                currentPageCount += section.length;
            }
        }

        if (applicableSection != null) {
            int offset = pageIndex - pagesBeforeSection - 1;
            return applicableSection.pageNumberStart + offset;
        }

        return pageIndex;
    }

    /**
     * 페이지 인덱스에 해당하는 섹션 마커 텍스트를 반환한다.
     */
    private static String resolveSectionMarker(int pageIndex,
                                               List<SectionInfo> sections) {
        if (sections.isEmpty()) return null;
        SectionInfo applicableSection = null;
        int currentPageCount = 0;
        for (SectionInfo section : sections) {
            if (section.pageStart != null && !section.pageStart.isEmpty()) {
                if (currentPageCount < pageIndex) {
                    applicableSection = section;
                }
                currentPageCount += section.length;
            }
        }
        return applicableSection != null ? applicableSection.marker : null;
    }

    /**
     * CMYK 또는 RGB ColorValue를 #RRGGBB로 변환한다.
     */
    public static String convertColorToHex(String colorValue, String model, String space) {
        return IDMLResourceParser.convertColorToHex(colorValue, model, space);
    }

    // ===== 내부 데이터 =====

    /**
     * designmap.xml의 Section 정보.
     */
    static class SectionInfo {
        String selfId;
        String pageStart;
        int pageNumberStart;
        int length;
        String name;
        String marker;
    }

    /**
     * ObjectStyle 상속: CornerRadius가 0이고 CornerOption이 RoundedCorner인 요소에
     * ObjectStyle의 CornerRadius를 적용한다.
     */
    private static void resolveInheritedCornerRadius(IDMLDocument doc) {
        for (IDMLSpread spread : doc.spreads()) {
            for (IDMLVectorShape vs : spread.vectorShapes()) {
                resolveVectorShapeCornerRadius(vs, doc);
            }
            for (IDMLGroup grp : spread.groups()) {
                resolveGroupCornerRadius(grp, doc);
            }
            for (IDMLImageFrame imgFrame : spread.imageFrames()) {
                resolveImageFrameCornerRadius(imgFrame, doc);
            }
        }
        for (IDMLSpread spread : doc.masterSpreads().values()) {
            for (IDMLVectorShape vs : spread.vectorShapes()) {
                resolveVectorShapeCornerRadius(vs, doc);
            }
        }
    }

    private static void resolveVectorShapeCornerRadius(IDMLVectorShape vs, IDMLDocument doc) {
        if (vs.cornerRadius() > 0) return;
        String co = vs.cornerOption();
        if (co == null || !co.contains("RoundedCorner")) return;
        String style = vs.appliedObjectStyle();
        if (style == null) return;
        double inherited = doc.getObjectStyleCornerRadius(style);
        if (inherited > 0) {
            vs.cornerRadius(inherited);
        }
    }

    private static void resolveImageFrameCornerRadius(IDMLImageFrame imgFrame, IDMLDocument doc) {
        if (imgFrame.cornerRadius() > 0) return;
        String style = imgFrame.appliedObjectStyle();
        if (style == null) return;
        double inherited = doc.getObjectStyleCornerRadius(style);
        if (inherited > 0) {
            imgFrame.cornerRadius(inherited);
            if (imgFrame.cornerOption() == null) {
                imgFrame.cornerOption("RoundedCorner");
            }
        }
    }

    private static void resolveGroupCornerRadius(IDMLGroup grp, IDMLDocument doc) {
        for (IDMLVectorShape vs : grp.vectorShapes()) {
            resolveVectorShapeCornerRadius(vs, doc);
        }
        for (IDMLGroup child : grp.childGroups()) {
            resolveGroupCornerRadius(child, doc);
        }
    }
}
