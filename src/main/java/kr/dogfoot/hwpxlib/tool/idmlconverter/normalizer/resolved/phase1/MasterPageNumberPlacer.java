package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

/**
 * Phase 1.5: IDML 마스터 스프레드에서 쪽 번호 TF를 추출해 각 페이지에 배치.
 * <p>
 * IDML 마스터 스프레드에서 ACE 18(자동 페이지 번호) 문자를 포함한 TextFrame을 찾아
 * 각 실제 페이지에 해당하는 ASTTextFrameBlock을 생성한다.
 * <p>
 * 처리 흐름:
 * 1. designmap.xml → 섹션 번호 시작, 스프레드 순서
 * 2. Spreads/*.xml → 페이지 순서, 적용 마스터, 페이지 내 좌/우 위치
 * 3. MasterSpreads/*.xml → 페이지 번호 TF bounds (mm), 정렬
 * 4. 각 페이지에 ASTTextFrameBlock 생성
 */
public final class MasterPageNumberPlacer {

    private static final double PT_PER_MM = 2.8346456692913385;
    private static final double DEFAULT_FONT_SIZE_PT = 12.0;

    private MasterPageNumberPlacer() {}

    /** @param sections phase 1 이후 섹션 리스트 (section index == doc page index 기준) */
    public static void place(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.idmlDir == null || !ctx.idmlDir.exists()) return;

        try {
            doPlace(ctx, sections);
        } catch (Exception e) {
            System.err.println("[MasterPageNumberPlacer] error: " + e.getMessage());
        }
    }

    private static void doPlace(ResolvedBuildContext ctx, List<ASTSection> sections) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();

        // 1. designmap.xml: 섹션 번호 시작 + 스프레드 순서
        File designmap = new File(ctx.idmlDir, "designmap.xml");
        if (!designmap.exists()) return;

        Document dmDoc = safeParseXml(db, designmap);
        if (dmDoc == null) return;

        int sectionPageNumberStart = 1;
        String sectionPageStartId = null;
        NodeList sectionNodes = dmDoc.getElementsByTagName("Section");
        if (sectionNodes.getLength() > 0) {
            Element section = (Element) sectionNodes.item(0);
            String pns = section.getAttribute("PageNumberStart");
            if (!pns.isEmpty()) {
                try { sectionPageNumberStart = Integer.parseInt(pns); } catch (NumberFormatException ignored) {}
            }
            sectionPageStartId = section.getAttribute("PageStart");
        }

        // 스프레드 순서
        List<String> spreadOrder = new ArrayList<>();
        NodeList idPkgSpreads = dmDoc.getElementsByTagName("idPkg:Spread");
        for (int i = 0; i < idPkgSpreads.getLength(); i++) {
            Element el = (Element) idPkgSpreads.item(i);
            String src = el.getAttribute("src");
            if (!src.isEmpty()) spreadOrder.add(src);
        }

        if (spreadOrder.isEmpty()) {
            // fallback: scan Spreads/ directory
            File spreadsDir = new File(ctx.idmlDir, "Spreads");
            if (spreadsDir.exists()) {
                File[] files = spreadsDir.listFiles((d, n) -> n.endsWith(".xml"));
                if (files != null) {
                    for (File f : files) spreadOrder.add("Spreads/" + f.getName());
                    spreadOrder.sort(String::compareTo);
                }
            }
        }

        // 2. 페이지 순서 빌드: pageId → {docIndex, appliedMasterId, masterPageIdx}
        List<PageEntry> pageOrder = new ArrayList<>();
        for (String spreadRef : spreadOrder) {
            File spreadFile = new File(ctx.idmlDir, spreadRef);
            if (!spreadFile.exists()) continue;
            Document spreadDoc = safeParseXml(db, spreadFile);
            if (spreadDoc == null) continue;

            NodeList pageNodes = spreadDoc.getElementsByTagName("Page");
            int pagesInSpread = pageNodes.getLength();
            for (int pi = 0; pi < pagesInSpread; pi++) {
                Element pageEl = (Element) pageNodes.item(pi);
                String pageId = pageEl.getAttribute("Self");
                String appliedMaster = pageEl.getAttribute("AppliedMaster");
                if (pageId.isEmpty()) continue;
                pageOrder.add(new PageEntry(pageId, appliedMaster, pi));
            }
        }

        if (pageOrder.isEmpty()) {
            System.err.println("[MasterPageNumberPlacer] no pages found");
            return;
        }

        // 섹션 시작 인덱스 (sectionPageStartId 기준)
        int sectionStartDocIndex = 0;
        if (sectionPageStartId != null && !sectionPageStartId.isEmpty()) {
            for (int i = 0; i < pageOrder.size(); i++) {
                if (sectionPageStartId.equals(pageOrder.get(i).pageId)) {
                    sectionStartDocIndex = i;
                    break;
                }
            }
        }

        // 3. 마스터 스프레드별 페이지 번호 TF bounds 캐시
        Map<String, PageNumTFInfo[]> masterBoundsCache = new HashMap<>();

        // 4. 각 페이지에 배치
        int count = 0;
        for (int docIdx = 0; docIdx < pageOrder.size(); docIdx++) {
            PageEntry pe = pageOrder.get(docIdx);
            String masterId = pe.appliedMasterId;
            if (masterId.isEmpty() || masterId.equals("n")) continue; // 마스터 없음

            if (!masterBoundsCache.containsKey(masterId)) {
                masterBoundsCache.put(masterId, loadMasterPageNumBounds(ctx.idmlDir, masterId, db));
            }
            PageNumTFInfo[] masterInfos = masterBoundsCache.get(masterId);
            if (masterInfos == null) continue;

            int mpIdx = pe.masterPageIdx; // 0=좌, 1=우
            if (mpIdx >= masterInfos.length) continue;
            PageNumTFInfo info = masterInfos[mpIdx];
            if (info == null) continue;

            // 페이지 번호 계산
            int pageNumber = sectionPageNumberStart + (docIdx - sectionStartDocIndex);

            // 섹션 인덱스 → section 매핑
            int secIdx = ctx.toSectionIndex.applyAsInt(docIdx);
            if (secIdx < 0 || secIdx >= sections.size()) continue;

            ASTTextFrameBlock block = buildPageNumberBlock(pageNumber, info, mpIdx);
            sections.get(secIdx).addBlock(block);
            count++;
        }

        if (count > 0) {
            System.err.println("[MasterPageNumberPlacer] placed " + count + " page number frames");
        }
    }

    // ─── 마스터 스프레드 XML 파싱 ─────────────────────────────────────────

    private static PageNumTFInfo[] loadMasterPageNumBounds(File idmlDir, String masterId, DocumentBuilder db) {
        File masterFile = new File(idmlDir, "MasterSpreads/MasterSpread_" + masterId + ".xml");
        if (!masterFile.exists()) return null;

        try {
            Document masterDoc = safeParseXml(db, masterFile);
            if (masterDoc == null) return null;

            // 마스터 페이지 목록 (순서가 중요: index 0=좌, 1=우)
            NodeList masterPageNodes = masterDoc.getElementsByTagName("Page");
            int mpCount = masterPageNodes.getLength();
            if (mpCount == 0) return null;

            // 각 마스터 페이지의 bounds (spread 좌표계, pt)
            double[][] mpSpreadBounds = new double[mpCount][4]; // [top, left, bottom, right] in spread pt
            for (int i = 0; i < mpCount; i++) {
                Element mpEl = (Element) masterPageNodes.item(i);
                String gb = mpEl.getAttribute("GeometricBounds");
                String it = mpEl.getAttribute("ItemTransform");
                double[] gbArr = parseDoubles(gb);
                double[] itArr = parseDoubles(it);
                if (gbArr.length < 4 || itArr.length < 6) continue;
                double tx = itArr[4], ty = itArr[5];
                // GeometricBounds is [top, left, bottom, right] in LOCAL page coords
                // spread_pos = local + transform
                mpSpreadBounds[i][0] = gbArr[0] + ty; // spread top
                mpSpreadBounds[i][1] = gbArr[1] + tx; // spread left
                mpSpreadBounds[i][2] = gbArr[2] + ty; // spread bottom
                mpSpreadBounds[i][3] = gbArr[3] + tx; // spread right
            }

            // Story 파일들 중 ACE 18 포함 목록 수집
            Set<String> aceStoryIds = new HashSet<>();
            File storiesDir = new File(idmlDir, "Stories");
            if (storiesDir.exists()) {
                File[] storyFiles = storiesDir.listFiles((d, n) -> n.endsWith(".xml"));
                if (storyFiles != null) {
                    for (File sf : storyFiles) {
                        if (fileContainsAce18(sf)) {
                            String name = sf.getName(); // Story_uXXXX.xml
                            String stId = name.replace("Story_", "").replace(".xml", "");
                            aceStoryIds.add(stId);
                        }
                    }
                }
            }
            if (aceStoryIds.isEmpty()) return null;

            // TextFrame 목록에서 ACE 18 story를 참조하는 것 찾기
            PageNumTFInfo[] result = new PageNumTFInfo[mpCount];
            NodeList tfNodes = masterDoc.getElementsByTagName("TextFrame");

            for (int ti = 0; ti < tfNodes.getLength(); ti++) {
                Element tf = (Element) tfNodes.item(ti);
                String parentStory = tf.getAttribute("ParentStory");
                if (!aceStoryIds.contains(parentStory)) continue;

                String itemTransform = tf.getAttribute("ItemTransform");
                double[] it = parseDoubles(itemTransform);
                if (it.length < 6) continue;
                double tx = it[4], ty = it[5];

                // PathGeometry: anchor points (local coords)
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                NodeList anchors = tf.getElementsByTagName("PathPointType");
                for (int ai = 0; ai < anchors.getLength(); ai++) {
                    String anchor = ((Element) anchors.item(ai)).getAttribute("Anchor");
                    double[] pt = parseDoubles(anchor);
                    if (pt.length < 2) continue;
                    if (pt[0] < minX) minX = pt[0];
                    if (pt[0] > maxX) maxX = pt[0];
                    if (pt[1] < minY) minY = pt[1];
                    if (pt[1] > maxY) maxY = pt[1];
                }
                if (minX == Double.MAX_VALUE) continue;

                // TF bounds in spread coords (pt)
                double spreadLeft   = minX + tx;
                double spreadRight  = maxX + tx;
                double spreadTop    = minY + ty;
                double spreadBottom = maxY + ty;

                double centerX = (spreadLeft + spreadRight) / 2;

                // 어느 마스터 페이지에 속하는지 판별
                for (int mpi = 0; mpi < mpCount; mpi++) {
                    double[] mpB = mpSpreadBounds[mpi];
                    if (centerX >= mpB[1] - 5 && centerX <= mpB[3] + 5) {
                        // page-relative bounds (mm)
                        double relTop    = (spreadTop    - mpB[0]) / PT_PER_MM;
                        double relLeft   = (spreadLeft   - mpB[1]) / PT_PER_MM;
                        double relBottom = (spreadBottom - mpB[0]) / PT_PER_MM;
                        double relRight  = (spreadRight  - mpB[1]) / PT_PER_MM;

                        // 정렬: story 파일에서 읽기
                        String just = "left"; // default
                        String vertJust = "bottom"; // default
                        just = readJustification(idmlDir, parentStory, db);
                        String vertJustAttr = getChildTextFramePreference(tf);
                        if ("BottomAlign".equals(vertJustAttr)) vertJust = "bottom";
                        else if ("TopAlign".equals(vertJustAttr)) vertJust = "top";
                        else if ("CenterAlign".equals(vertJustAttr)) vertJust = "middle";

                        // 폰트 정보
                        String fontName = readFontName(idmlDir, parentStory, db);
                        double fontSize = readFontSize(idmlDir, parentStory, db);
                        String fontStyle = readFontStyle(idmlDir, parentStory, db);

                        result[mpi] = new PageNumTFInfo(relTop, relLeft, relBottom, relRight, just, vertJust,
                                fontName, fontStyle, fontSize);
                        break;
                    }
                }
            }

            return result;

        } catch (Exception e) {
            System.err.println("[MasterPageNumberPlacer] master parse error " + masterId + ": " + e.getMessage());
            return null;
        }
    }

    private static String getChildTextFramePreference(Element tf) {
        NodeList prefs = tf.getElementsByTagName("TextFramePreference");
        if (prefs.getLength() > 0) {
            return ((Element) prefs.item(0)).getAttribute("VerticalJustification");
        }
        return "";
    }

    private static boolean fileContainsAce18(File f) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("ACE 18")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String readJustification(File idmlDir, String storyId, DocumentBuilder db) {
        File sf = new File(idmlDir, "Stories/Story_" + storyId + ".xml");
        if (!sf.exists()) return "left";
        try {
            Document doc = safeParseXml(db, sf);
            if (doc == null) return "left";
            NodeList psr = doc.getElementsByTagName("ParagraphStyleRange");
            if (psr.getLength() > 0) {
                String j = ((Element) psr.item(0)).getAttribute("Justification");
                if ("RightAlign".equals(j)) return "right";
                if ("CenterAlign".equals(j)) return "center";
            }
        } catch (Exception ignored) {}
        return "left";
    }

    private static String readFontName(File idmlDir, String storyId, DocumentBuilder db) {
        File sf = new File(idmlDir, "Stories/Story_" + storyId + ".xml");
        if (!sf.exists()) return "";
        try {
            Document doc = safeParseXml(db, sf);
            if (doc == null) return "";
            NodeList fonts = doc.getElementsByTagName("AppliedFont");
            if (fonts.getLength() > 0) {
                return fonts.item(0).getTextContent().trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String readFontStyle(File idmlDir, String storyId, DocumentBuilder db) {
        File sf = new File(idmlDir, "Stories/Story_" + storyId + ".xml");
        if (!sf.exists()) return "";
        try {
            Document doc = safeParseXml(db, sf);
            if (doc == null) return "";
            NodeList csr = doc.getElementsByTagName("CharacterStyleRange");
            if (csr.getLength() > 0) {
                return ((Element) csr.item(0)).getAttribute("FontStyle");
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static double readFontSize(File idmlDir, String storyId, DocumentBuilder db) {
        File sf = new File(idmlDir, "Stories/Story_" + storyId + ".xml");
        if (!sf.exists()) return DEFAULT_FONT_SIZE_PT;
        try {
            Document doc = safeParseXml(db, sf);
            if (doc == null) return DEFAULT_FONT_SIZE_PT;
            NodeList csr = doc.getElementsByTagName("CharacterStyleRange");
            if (csr.getLength() > 0) {
                String ps = ((Element) csr.item(0)).getAttribute("PointSize");
                if (!ps.isEmpty()) return Double.parseDouble(ps);
            }
        } catch (Exception ignored) {}
        return DEFAULT_FONT_SIZE_PT;
    }

    // ─── ASTTextFrameBlock 생성 ───────────────────────────────────────────

    private static ASTTextFrameBlock buildPageNumberBlock(int pageNumber, PageNumTFInfo info, int masterPageIdx) {
        // mm → hwpunits
        long xHwp = CoordinateConverter.mmToHwpunits(info.relLeft);
        long yHwp = CoordinateConverter.mmToHwpunits(info.relTop);
        long wHwp = CoordinateConverter.mmToHwpunits(info.relRight - info.relLeft);
        long hHwp = CoordinateConverter.mmToHwpunits(info.relBottom - info.relTop);

        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.sourceId("page_num_" + masterPageIdx + "_" + pageNumber);
        block.x(xHwp);
        block.y(yHwp);
        block.width(wHwp);
        block.height(hHwp);
        block.zOrder(20); // 배경(z=0) + 장식(z=5)보다 위
        block.columnCount(1);
        block.fillColor("Swatch/None");
        block.strokeColor("Swatch/None");
        block.verticalJustification(info.vertJust);

        ASTParagraph para = new ASTParagraph();
        para.alignment(info.justification);

        ASTTextRun run = new ASTTextRun();
        run.text(String.valueOf(pageNumber));
        if (!info.fontName.isEmpty()) run.fontFamily(info.fontName);
        if (!info.fontStyle.isEmpty()) run.fontStyle(info.fontStyle);
        if (info.fontSize > 0) {
            run.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(info.fontSize));
        }

        para.addItem(run);
        block.addParagraph(para);

        return block;
    }

    // ─── 유틸리티 ─────────────────────────────────────────────────────────

    private static Document safeParseXml(DocumentBuilder db, File file) {
        try (InputStream is = new FileInputStream(file)) {
            return db.parse(is);
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] parseDoubles(String s) {
        if (s == null || s.trim().isEmpty()) return new double[0];
        String[] parts = s.trim().split("\\s+");
        double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Double.parseDouble(parts[i]); } catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    // ─── 데이터 클래스 ────────────────────────────────────────────────────

    private static class PageEntry {
        final String pageId;
        final String appliedMasterId;
        final int masterPageIdx; // 스프레드 내 위치 (0=좌, 1=우)
        PageEntry(String pageId, String appliedMasterId, int masterPageIdx) {
            this.pageId = pageId;
            this.appliedMasterId = appliedMasterId;
            this.masterPageIdx = masterPageIdx;
        }
    }

    private static class PageNumTFInfo {
        final double relTop, relLeft, relBottom, relRight;
        final String justification; // "left", "right", "center"
        final String vertJust;      // "top", "middle", "bottom"
        final String fontName;
        final String fontStyle;
        final double fontSize;      // pt

        PageNumTFInfo(double relTop, double relLeft, double relBottom, double relRight,
                      String justification, String vertJust,
                      String fontName, String fontStyle, double fontSize) {
            this.relTop = relTop; this.relLeft = relLeft;
            this.relBottom = relBottom; this.relRight = relRight;
            this.justification = justification; this.vertJust = vertJust;
            this.fontName = fontName; this.fontStyle = fontStyle;
            this.fontSize = fontSize;
        }
    }
}
