package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

/**
 * Phase 1.6: IDML 마스터 스프레드에서 하시라(TextVariable 러닝 헤더) TF를 추출해 각 페이지에 배치.
 * <p>
 * MatchCharacterStyleType TextVariable("1T", "01 T" 등)을 사용하는 마스터 TF를 찾아
 * 각 실제 페이지의 하시라 텍스트를 결정하고 ASTTextFrameBlock으로 배치한다.
 * <p>
 * 처리 흐름:
 * 1. designmap.xml → TextVariable 정의(varName → appliedCharacterStyle)
 * 2. Spreads/*.xml → 페이지 순서 + 스프레드별 storyId 목록
 * 3. Stories/*.xml → charStyle을 가진 텍스트 수집 (페이지별, carry-over 포함)
 * 4. MasterSpreads/*.xml → 하시라 TF bounds + 정렬
 * 5. 각 페이지에 ASTTextFrameBlock 생성
 */
public final class MasterHashiraPlacer {

    private static final double PT_PER_MM = 2.8346456692913385;
    private static final double DEFAULT_FONT_SIZE_PT = 10.0;

    private MasterHashiraPlacer() {}

    public static void place(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.idmlDir == null || !ctx.idmlDir.exists()) return;
        try {
            doPlace(ctx, sections);
        } catch (Exception e) {
            System.err.println("[MasterHashiraPlacer] error: " + e.getMessage());
        }
    }

    private static void doPlace(ResolvedBuildContext ctx, List<ASTSection> sections) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();

        // 1. designmap.xml → TextVariable 정의 + 스프레드 순서
        File designmap = new File(ctx.idmlDir, "designmap.xml");
        if (!designmap.exists()) return;

        Document dmDoc = safeParseXml(db, designmap);
        if (dmDoc == null) return;

        // varName → charStyle (MatchCharacterStyleType만)
        Map<String, String> varToCharStyle = new LinkedHashMap<>();
        NodeList tvNodes = dmDoc.getElementsByTagName("TextVariable");
        for (int i = 0; i < tvNodes.getLength(); i++) {
            Element tv = (Element) tvNodes.item(i);
            if (!"MatchCharacterStyleType".equals(tv.getAttribute("VariableType"))) continue;
            String varName = tv.getAttribute("Name");
            NodeList prefs = tv.getElementsByTagName("MatchCharacterStylePreference");
            if (prefs.getLength() == 0) continue;
            String charStyle = ((Element) prefs.item(0)).getAttribute("AppliedCharacterStyle");
            if (!varName.isEmpty() && !charStyle.isEmpty()) {
                varToCharStyle.put(varName, charStyle);
            }
        }
        if (varToCharStyle.isEmpty()) return;

        // 스프레드 순서
        List<String> spreadOrder = new ArrayList<>();
        NodeList idPkgSpreads = dmDoc.getElementsByTagName("idPkg:Spread");
        for (int i = 0; i < idPkgSpreads.getLength(); i++) {
            String src = ((Element) idPkgSpreads.item(i)).getAttribute("src");
            if (!src.isEmpty()) spreadOrder.add(src);
        }
        if (spreadOrder.isEmpty()) {
            File spreadsDir = new File(ctx.idmlDir, "Spreads");
            if (spreadsDir.exists()) {
                File[] files = spreadsDir.listFiles((d, n) -> n.endsWith(".xml"));
                if (files != null) {
                    for (File f : files) spreadOrder.add("Spreads/" + f.getName());
                    spreadOrder.sort(String::compareTo);
                }
            }
        }

        // 2. 페이지 순서 + 각 페이지의 storyId 목록 (spread에서 TF → storyId 추출)
        List<PageEntry> pageOrder = new ArrayList<>();
        Map<Integer, List<String>> pageToStoryIds = new HashMap<>();

        int pageCount = 0;
        for (String spreadRef : spreadOrder) {
            File spreadFile = new File(ctx.idmlDir, spreadRef);
            if (!spreadFile.exists()) continue;
            Document spreadDoc = safeParseXml(db, spreadFile);
            if (spreadDoc == null) continue;

            // 스프레드 내 페이지 목록 (순서대로)
            NodeList pageNodes = spreadDoc.getElementsByTagName("Page");
            int pagesInSpread = pageNodes.getLength();
            if (pagesInSpread == 0) continue;

            // 각 페이지의 bounds (spread 좌표)
            double[][] pageSpreadBounds = new double[pagesInSpread][4];
            String[] pageIds = new String[pagesInSpread];
            String[] appliedMasters = new String[pagesInSpread];

            for (int pi = 0; pi < pagesInSpread; pi++) {
                Element pageEl = (Element) pageNodes.item(pi);
                pageIds[pi] = pageEl.getAttribute("Self");
                appliedMasters[pi] = pageEl.getAttribute("AppliedMaster");
                String gb = pageEl.getAttribute("GeometricBounds");
                String it = pageEl.getAttribute("ItemTransform");
                double[] gbArr = parseDoubles(gb);
                double[] itArr = parseDoubles(it);
                if (gbArr.length >= 4 && itArr.length >= 6) {
                    double tx = itArr[4], ty = itArr[5];
                    pageSpreadBounds[pi][0] = gbArr[0] + ty; // spread top
                    pageSpreadBounds[pi][1] = gbArr[1] + tx; // spread left
                    pageSpreadBounds[pi][2] = gbArr[2] + ty; // spread bottom
                    pageSpreadBounds[pi][3] = gbArr[3] + tx; // spread right
                }
                pageOrder.add(new PageEntry(pageIds[pi], appliedMasters[pi], pi));
            }

            // 스프레드 내 TextFrame → storyId → 어느 페이지에 속하는지 판별
            NodeList tfNodes = spreadDoc.getElementsByTagName("TextFrame");
            for (int ti = 0; ti < tfNodes.getLength(); ti++) {
                Element tf = (Element) tfNodes.item(ti);
                String storyId = tf.getAttribute("ParentStory");
                if (storyId.isEmpty()) continue;

                // TF의 중심 X 좌표
                String itStr = tf.getAttribute("ItemTransform");
                double[] itArr = parseDoubles(itStr);
                double tx = itArr.length >= 6 ? itArr[4] : 0;
                double ty = itArr.length >= 6 ? itArr[5] : 0;

                // PathGeometry anchors
                NodeList anchors = tf.getElementsByTagName("PathPointType");
                if (anchors.getLength() == 0) continue;
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                for (int ai = 0; ai < anchors.getLength(); ai++) {
                    double[] pt = parseDoubles(((Element) anchors.item(ai)).getAttribute("Anchor"));
                    if (pt.length >= 2) {
                        if (pt[0] < minX) minX = pt[0];
                        if (pt[0] > maxX) maxX = pt[0];
                    }
                }
                double centerX = (minX + maxX) / 2 + tx;

                // 어느 페이지에 속하는지
                for (int pi = 0; pi < pagesInSpread; pi++) {
                    double[] sb = pageSpreadBounds[pi];
                    if (centerX >= sb[1] - 5 && centerX <= sb[3] + 5) {
                        int docIdx = pageCount + pi;
                        pageToStoryIds.computeIfAbsent(docIdx, k -> new ArrayList<>()).add(storyId);
                        break;
                    }
                }
            }
            pageCount += pagesInSpread;
        }

        if (pageOrder.isEmpty()) return;

        // 3. 각 문서 페이지별로 하시라 텍스트 결정 (FirstOnPage + carry-over)
        // charStyle → text (최근 페이지에서 발견된 값)
        Map<String, String> carryOver = new HashMap<>();
        Map<Integer, Map<String, String>> pageHashira = new HashMap<>();

        for (int docIdx = 0; docIdx < pageOrder.size(); docIdx++) {
            Map<String, String> foundOnPage = new HashMap<>();
            List<String> storyIds = pageToStoryIds.getOrDefault(docIdx, Collections.emptyList());

            for (String storyId : storyIds) {
                File storyFile = storyFileFor(ctx.idmlDir, storyId);
                if (storyFile == null || !storyFile.exists()) continue;

                Document storyDoc = safeParseXml(db, storyFile);
                if (storyDoc == null) continue;

                // CharacterStyleRange에서 각 charStyle에 대응하는 텍스트 수집
                NodeList csrNodes = storyDoc.getElementsByTagName("CharacterStyleRange");
                for (int ci = 0; ci < csrNodes.getLength(); ci++) {
                    Element csr = (Element) csrNodes.item(ci);
                    String appliedStyle = csr.getAttribute("AppliedCharacterStyle");
                    if (appliedStyle.isEmpty()) continue;

                    for (Map.Entry<String, String> entry : varToCharStyle.entrySet()) {
                        String varName = entry.getKey();
                        String charStyle = entry.getValue();
                        if (!appliedStyle.equals(charStyle)) continue;
                        if (foundOnPage.containsKey(charStyle)) continue; // FirstOnPage: 첫 번째만

                        // 이 CharacterStyleRange의 텍스트 수집
                        StringBuilder sb = new StringBuilder();
                        NodeList contents = csr.getElementsByTagName("Content");
                        for (int ki = 0; ki < contents.getLength(); ki++) {
                            sb.append(contents.item(ki).getTextContent());
                        }
                        String text = sb.toString().trim();
                        if (!text.isEmpty()) {
                            foundOnPage.put(charStyle, text);
                        }
                    }
                }
            }

            // carry-over 적용
            Map<String, String> pageValues = new HashMap<>(carryOver);
            pageValues.putAll(foundOnPage);
            carryOver.putAll(foundOnPage); // 새로 찾은 값은 다음 페이지로 carry-over
            pageHashira.put(docIdx, pageValues);
        }

        // 4. 마스터 스프레드별 하시라 TF 정보 캐시
        Map<String, HashiraTFInfo[]> masterHashiraCache = new HashMap<>();

        // 5. 각 페이지에 배치
        int count = 0;
        for (int docIdx = 0; docIdx < pageOrder.size(); docIdx++) {
            PageEntry pe = pageOrder.get(docIdx);
            String masterId = pe.appliedMasterId;
            if (masterId.isEmpty() || "n".equals(masterId)) continue;

            Map<String, String> hashiraValues = pageHashira.getOrDefault(docIdx, Collections.emptyMap());
            if (hashiraValues.isEmpty()) continue;

            if (!masterHashiraCache.containsKey(masterId)) {
                masterHashiraCache.put(masterId,
                        loadMasterHashiraTFs(ctx.idmlDir, masterId, db, varToCharStyle));
            }
            HashiraTFInfo[] infos = masterHashiraCache.get(masterId);
            if (infos == null) continue;

            int secIdx = ctx.toSectionIndex.applyAsInt(docIdx);
            if (secIdx < 0 || secIdx >= sections.size()) continue;

            for (HashiraTFInfo info : infos) {
                if (info == null) continue;
                // 마스터 TF의 좌/우 페이지 위치와 문서 페이지의 spread 내 위치가 일치할 때만 배치
                if (pe.masterPageIdx != info.masterPageIdx) continue;
                String text = hashiraValues.get(info.charStyle);
                if (text == null || text.isEmpty()) continue;

                ASTTextFrameBlock block = buildHashiraBlock(text, info);
                sections.get(secIdx).addBlock(block);
                count++;
            }
        }

        if (count > 0) {
            System.err.println("[MasterHashiraPlacer] placed " + count + " hashira frames");
        }
    }

    // ─── 마스터 스프레드 파싱 ─────────────────────────────────────────────

    private static HashiraTFInfo[] loadMasterHashiraTFs(
            File idmlDir, String masterId, DocumentBuilder db,
            Map<String, String> varToCharStyle) {

        File masterFile = new File(idmlDir, "MasterSpreads/MasterSpread_" + masterId + ".xml");
        if (!masterFile.exists()) return null;

        try {
            Document masterDoc = safeParseXml(db, masterFile);
            if (masterDoc == null) return null;

            // 마스터 페이지 bounds
            NodeList mpNodes = masterDoc.getElementsByTagName("Page");
            int mpCount = mpNodes.getLength();
            double[][] mpSpreadBounds = new double[mpCount][4];
            for (int i = 0; i < mpCount; i++) {
                Element mp = (Element) mpNodes.item(i);
                double[] gb = parseDoubles(mp.getAttribute("GeometricBounds"));
                double[] it = parseDoubles(mp.getAttribute("ItemTransform"));
                if (gb.length >= 4 && it.length >= 6) {
                    mpSpreadBounds[i][0] = gb[0] + it[5];
                    mpSpreadBounds[i][1] = gb[1] + it[4];
                    mpSpreadBounds[i][2] = gb[2] + it[5];
                    mpSpreadBounds[i][3] = gb[3] + it[4];
                }
            }

            // 마스터 페이지별 oval(빨강점 등) 경계 수집 — 하시라 TF와의 겹침 방지용
            // ovalBoundsPerPage[mpi] = list of [relLeft, relRight, relTop, relBottom] in mm
            @SuppressWarnings("unchecked")
            List<double[]>[] ovalBoundsPerPage = new List[mpCount];
            for (int i = 0; i < mpCount; i++) ovalBoundsPerPage[i] = new ArrayList<>();
            for (String ovalTag : new String[]{"Oval", "Rectangle", "Polygon"}) {
                NodeList ovalNodes = masterDoc.getElementsByTagName(ovalTag);
                for (int oi = 0; oi < ovalNodes.getLength(); oi++) {
                    Element oval = (Element) ovalNodes.item(oi);
                    double[] oit = parseDoubles(oval.getAttribute("ItemTransform"));
                    double otx = oit.length >= 6 ? oit[4] : 0, oty = oit.length >= 6 ? oit[5] : 0;
                    NodeList oa = oval.getElementsByTagName("PathPointType");
                    if (oa.getLength() == 0) continue;
                    double oMinX = Double.MAX_VALUE, oMaxX = -Double.MAX_VALUE;
                    double oMinY = Double.MAX_VALUE, oMaxY = -Double.MAX_VALUE;
                    for (int ai = 0; ai < oa.getLength(); ai++) {
                        double[] pt = parseDoubles(((Element) oa.item(ai)).getAttribute("Anchor"));
                        if (pt.length >= 2) {
                            if (pt[0] < oMinX) oMinX = pt[0]; if (pt[0] > oMaxX) oMaxX = pt[0];
                            if (pt[1] < oMinY) oMinY = pt[1]; if (pt[1] > oMaxY) oMaxY = pt[1];
                        }
                    }
                    double oSpreadLeft = oMinX + otx, oSpreadRight = oMaxX + otx;
                    double oSpreadTop = oMinY + oty, oSpreadBottom = oMaxY + oty;
                    double oCx = (oSpreadLeft + oSpreadRight) / 2;
                    for (int mpi = 0; mpi < mpCount; mpi++) {
                        double[] sb = mpSpreadBounds[mpi];
                        if (oCx >= sb[1] - 5 && oCx <= sb[3] + 5) {
                            double oRelLeft  = (oSpreadLeft  - sb[1]) / PT_PER_MM;
                            double oRelRight = (oSpreadRight - sb[1]) / PT_PER_MM;
                            double oRelTop   = (oSpreadTop   - sb[0]) / PT_PER_MM;
                            double oRelBot   = (oSpreadBottom - sb[0]) / PT_PER_MM;
                            // 페이지 배경 사각형 등 큰 도형은 무시 — 작은 장식 원점(빨강점 등)만 처리
                            if (oRelRight - oRelLeft <= 20.0 && oRelBot - oRelTop <= 20.0) {
                                ovalBoundsPerPage[mpi].add(new double[]{oRelLeft, oRelRight, oRelTop, oRelBot});
                            }
                            break;
                        }
                    }
                }
            }

            List<HashiraTFInfo> results = new ArrayList<>();

            NodeList tfNodes = masterDoc.getElementsByTagName("TextFrame");
            for (int ti = 0; ti < tfNodes.getLength(); ti++) {
                Element tf = (Element) tfNodes.item(ti);
                String parentStory = tf.getAttribute("ParentStory");
                if (parentStory.isEmpty()) continue;

                // 이 story가 TextVariableInstance를 포함하는지 확인
                File storyFile = storyFileFor(idmlDir, parentStory);
                if (storyFile == null || !storyFile.exists()) continue;

                Document storyDoc = safeParseXml(db, storyFile);
                if (storyDoc == null) continue;

                // TextVariableInstance 찾기
                String matchedVarName = null;
                NodeList tviNodes = storyDoc.getElementsByTagName("TextVariableInstance");
                for (int vi = 0; vi < tviNodes.getLength(); vi++) {
                    String varName = ((Element) tviNodes.item(vi)).getAttribute("Name");
                    if (varToCharStyle.containsKey(varName)) {
                        matchedVarName = varName;
                        break;
                    }
                }
                if (matchedVarName == null) continue;

                String charStyle = varToCharStyle.get(matchedVarName);

                // TF bounds 계산
                double[] it = parseDoubles(tf.getAttribute("ItemTransform"));
                double tx = it.length >= 6 ? it[4] : 0;
                double ty = it.length >= 6 ? it[5] : 0;

                NodeList anchors = tf.getElementsByTagName("PathPointType");
                if (anchors.getLength() == 0) continue;
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                for (int ai = 0; ai < anchors.getLength(); ai++) {
                    double[] pt = parseDoubles(((Element) anchors.item(ai)).getAttribute("Anchor"));
                    if (pt.length >= 2) {
                        if (pt[0] < minX) minX = pt[0]; if (pt[0] > maxX) maxX = pt[0];
                        if (pt[1] < minY) minY = pt[1]; if (pt[1] > maxY) maxY = pt[1];
                    }
                }

                double spreadLeft = minX + tx, spreadRight = maxX + tx;
                double spreadTop = minY + ty, spreadBottom = maxY + ty;
                double centerX = (spreadLeft + spreadRight) / 2;

                // 어느 마스터 페이지에 속하는지
                for (int mpi = 0; mpi < mpCount; mpi++) {
                    double[] sb = mpSpreadBounds[mpi];
                    if (centerX >= sb[1] - 5 && centerX <= sb[3] + 5) {
                        double relTop    = (spreadTop    - sb[0]) / PT_PER_MM;
                        double relLeft   = (spreadLeft   - sb[1]) / PT_PER_MM;
                        double relBottom = (spreadBottom - sb[0]) / PT_PER_MM;
                        double relRight  = (spreadRight  - sb[1]) / PT_PER_MM;

                        // oval과 겹치면 TF 경계를 oval 바깥으로 조정 (1mm gap)
                        for (double[] ob : ovalBoundsPerPage[mpi]) {
                            double oRelLeft = ob[0], oRelRight = ob[1], oRelTop = ob[2], oRelBot = ob[3];
                            boolean xOverlap = relLeft < oRelRight && relRight > oRelLeft;
                            boolean yOverlap = relTop < oRelBot && relBottom > oRelTop;
                            if (!xOverlap || !yOverlap) continue;
                            // 겹침 발생: oval이 TF 중심 기준 왼쪽이면 TF left를 oval right 바깥으로
                            if (oRelLeft + oRelRight < relLeft + relRight) {
                                relLeft = oRelRight + 1.0;
                            } else {
                                relRight = oRelLeft - 1.0;
                            }
                        }

                        String just = readJustification(idmlDir, parentStory, db);
                        String fontName = readFontName(idmlDir, parentStory, db);
                        String fontStyle = readFontStyle(idmlDir, parentStory, db);
                        double fontSize = readFontSize(idmlDir, parentStory, db);

                        results.add(new HashiraTFInfo(charStyle, mpi, relTop, relLeft, relBottom, relRight,
                                just, fontName, fontStyle, fontSize));
                        break;
                    }
                }
            }

            return results.toArray(new HashiraTFInfo[0]);

        } catch (Exception e) {
            System.err.println("[MasterHashiraPlacer] master parse error " + masterId + ": " + e.getMessage());
            return null;
        }
    }

    // ─── ASTTextFrameBlock 생성 ────────────────────────────────────────────

    private static ASTTextFrameBlock buildHashiraBlock(String text, HashiraTFInfo info) {
        long xHwp = CoordinateConverter.mmToHwpunits(info.relLeft);
        long yHwp = CoordinateConverter.mmToHwpunits(info.relTop);
        long wHwp = CoordinateConverter.mmToHwpunits(info.relRight - info.relLeft);
        long hHwp = CoordinateConverter.mmToHwpunits(info.relBottom - info.relTop);

        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.sourceId("hashira_" + info.charStyle.hashCode() + "_" + text.hashCode());
        block.x(xHwp);
        block.y(yHwp);
        block.width(wHwp);
        block.height(hHwp);
        block.zOrder(20);
        block.columnCount(1);
        block.fillColor("Swatch/None");
        block.strokeColor("Swatch/None");

        ASTParagraph para = new ASTParagraph();
        para.alignment(info.justification);

        ASTTextRun run = new ASTTextRun();
        run.text(text);
        if (!info.fontName.isEmpty()) run.fontFamily(info.fontName);
        if (!info.fontStyle.isEmpty()) run.fontStyle(info.fontStyle);
        if (info.fontSize > 0) {
            run.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(info.fontSize));
        }

        para.addItem(run);
        block.addParagraph(para);
        return block;
    }

    // ─── 스토리 속성 읽기 헬퍼 ────────────────────────────────────────────

    private static String readJustification(File idmlDir, String storyId, DocumentBuilder db) {
        File sf = storyFileFor(idmlDir, storyId);
        if (sf == null || !sf.exists()) return "left";
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
        File sf = storyFileFor(idmlDir, storyId);
        if (sf == null || !sf.exists()) return "";
        try {
            Document doc = safeParseXml(db, sf);
            if (doc == null) return "";
            NodeList fonts = doc.getElementsByTagName("AppliedFont");
            if (fonts.getLength() > 0) return fonts.item(0).getTextContent().trim();
        } catch (Exception ignored) {}
        return "";
    }

    private static String readFontStyle(File idmlDir, String storyId, DocumentBuilder db) {
        File sf = storyFileFor(idmlDir, storyId);
        if (sf == null || !sf.exists()) return "";
        try {
            Document doc = safeParseXml(db, sf);
            if (doc == null) return "";
            NodeList csr = doc.getElementsByTagName("CharacterStyleRange");
            if (csr.getLength() > 0) return ((Element) csr.item(0)).getAttribute("FontStyle");
        } catch (Exception ignored) {}
        return "";
    }

    private static double readFontSize(File idmlDir, String storyId, DocumentBuilder db) {
        File sf = storyFileFor(idmlDir, storyId);
        if (sf == null || !sf.exists()) return DEFAULT_FONT_SIZE_PT;
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

    // ─── 유틸리티 ─────────────────────────────────────────────────────────

    /** storyId(hex "u4daf" 또는 decimal "19887")로 Story XML 파일 반환. */
    private static File storyFileFor(File idmlDir, String storyId) {
        String hexId;
        try {
            if (storyId.startsWith("u") || storyId.startsWith("U")) {
                hexId = storyId.substring(1).toLowerCase();
            } else {
                hexId = Integer.toHexString(Integer.parseInt(storyId));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return new File(idmlDir, "Stories/Story_u" + hexId + ".xml");
    }

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
        final int masterPageIdx;
        PageEntry(String pageId, String appliedMasterId, int masterPageIdx) {
            this.pageId = pageId; this.appliedMasterId = appliedMasterId; this.masterPageIdx = masterPageIdx;
        }
    }

    private static class HashiraTFInfo {
        final String charStyle; // "CharacterStyle/**하시라_대단원"
        final int masterPageIdx; // 마스터 스프레드 내 페이지 인덱스 (0=왼쪽/짝수, 1=오른쪽/홀수)
        final double relTop, relLeft, relBottom, relRight;
        final String justification;
        final String fontName;
        final String fontStyle;
        final double fontSize;

        HashiraTFInfo(String charStyle, int masterPageIdx,
                      double relTop, double relLeft, double relBottom, double relRight,
                      String justification, String fontName, String fontStyle, double fontSize) {
            this.charStyle = charStyle;
            this.masterPageIdx = masterPageIdx;
            this.relTop = relTop; this.relLeft = relLeft; this.relBottom = relBottom; this.relRight = relRight;
            this.justification = justification;
            this.fontName = fontName; this.fontStyle = fontStyle; this.fontSize = fontSize;
        }
    }
}
