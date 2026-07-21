package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

/**
 * IDML 마스터 TextVariable placeholder를 기존 HWPX text run 안에서만 치환한다.
 *
 * <p>마스터 페이지 번호/하시라 TF를 새 ASTTextFrameBlock으로 생성하던 legacy 경로는
 * ObjectPlan 이전에 ownership을 재결정하므로 제거했다. 위치/크기/레이어는 resolved
 * master clone과 Stage 1 ObjectPlan이 결정한다.</p>
 */
public final class MasterHashiraPlacer {

    private MasterHashiraPlacer() {}

    /**
     * Story/TextBuilder가 마스터 TextVariableInstance를 일반 텍스트로 가져온 뒤
     * 남긴 "<#소단원명>" placeholder를 IDML MatchCharacterStyleType 값으로 치환한다.
     *
     * <p>새 visible 객체를 만들지 않고 기존 HWPX text run만 바꾸므로 ownership 재판정이 아니다.
     * 값의 진실은 designmap.xml의 TextVariable 정의와 Story XML의 character style이다.</p>
     */
    public static void resolveTextVariablePlaceholders(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx == null || ctx.idmlDir == null || !ctx.idmlDir.exists()
                || sections == null || sections.isEmpty()) {
            return;
        }
        try {
            Map<Integer, Map<String, String>> pageValues = collectPageTextVariableValues(ctx);
            if (pageValues.isEmpty()) return;

            int replaced = 0;
            for (int secIdx = 0; secIdx < sections.size(); secIdx++) {
                ASTSection section = sections.get(secIdx);
                if (section == null || section.blocks() == null) continue;
                int docIdx = documentPageIndexForSection(ctx, secIdx, section);
                Map<String, String> values = pageValues.get(docIdx);
                if (values == null || values.isEmpty()) continue;
                for (ASTBlock block : section.blocks()) {
                    if (!(block instanceof ASTTextFrameBlock)) continue;
                    replaced += replacePlaceholders((ASTTextFrameBlock) block, values);
                }
            }
            if (replaced > 0) {
                System.err.println("[MasterHashiraPlacer] resolved " + replaced + " hashira placeholders");
            }
        } catch (Exception e) {
            System.err.println("[MasterHashiraPlacer] placeholder resolve error: " + e.getMessage());
        }
    }

    private static Map<Integer, Map<String, String>> collectPageTextVariableValues(ResolvedBuildContext ctx)
            throws Exception {
        Map<Integer, Map<String, String>> resolvedValues = collectResolvedPageTextVariableValues(ctx);
        DocumentBuilder db = newDocumentBuilder();
        File designmap = new File(ctx.idmlDir, "designmap.xml");
        Document dmDoc = safeParseXml(db, designmap);
        if (dmDoc == null) return resolvedValues;
        Map<String, String> varToCharStyle = readMatchCharacterStyleVariables(dmDoc);
        if (varToCharStyle.isEmpty()) return resolvedValues;

        List<String> spreadOrder = readSpreadOrder(ctx.idmlDir, dmDoc);
        if (spreadOrder.isEmpty()) return resolvedValues;

        Map<Integer, List<String>> pageToStoryIds = new HashMap<>();
        int pageCount = 0;
        for (String spreadRef : spreadOrder) {
            File spreadFile = new File(ctx.idmlDir, spreadRef);
            if (!spreadFile.exists()) continue;
            Document spreadDoc = safeParseXml(db, spreadFile);
            if (spreadDoc == null) continue;

            NodeList pageNodes = spreadDoc.getElementsByTagName("Page");
            int pagesInSpread = pageNodes.getLength();
            if (pagesInSpread == 0) continue;

            double[][] pageSpreadBounds = readPageSpreadBounds(pageNodes, pagesInSpread);
            NodeList tfNodes = spreadDoc.getElementsByTagName("TextFrame");
            for (int ti = 0; ti < tfNodes.getLength(); ti++) {
                Element tf = (Element) tfNodes.item(ti);
                String storyId = tf.getAttribute("ParentStory");
                if (storyId.isEmpty()) continue;
                double centerX = textFrameCenterX(tf);
                if (Double.isNaN(centerX)) continue;
                for (int pi = 0; pi < pagesInSpread; pi++) {
                    double[] sb = pageSpreadBounds[pi];
                    if (centerX >= sb[1] - 5 && centerX <= sb[3] + 5) {
                        pageToStoryIds.computeIfAbsent(pageCount + pi, k -> new ArrayList<>()).add(storyId);
                        break;
                    }
                }
            }
            pageCount += pagesInSpread;
        }
        Map<Integer, Map<String, String>> idmlValues =
                collectPageTextVariableValues(ctx.idmlDir, db, varToCharStyle, pageCount, pageToStoryIds);
        return mergePageValues(idmlValues, resolvedValues);
    }

    private static Map<Integer, Map<String, String>> collectResolvedPageTextVariableValues(
            ResolvedBuildContext ctx) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.textFrames() == null) {
            return Collections.emptyMap();
        }
        Map<Integer, Map<String, String>> found = new HashMap<>();
        int maxPageIndex = -1;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() < 0 || !tf.nonprinting()) continue;
            String visible = tf.frameVisibleText();
            if (visible == null || !visible.contains("##삭제금지##")) continue;
            ResolvedStory story = ctx.resolvedData.getStory(tf.storyId());
            if (story == null || story.paragraphs() == null) continue;
            Map<String, String> values = new HashMap<>();
            for (ResolvedParagraph paragraph : story.paragraphs()) {
                if (paragraph == null || paragraph.runs() == null) continue;
                for (ResolvedRun run : paragraph.runs()) {
                    if (run == null) continue;
                    String charStyle = run.charStyle();
                    String text = run.text();
                    if (charStyle == null || !charStyle.startsWith("#")) continue;
                    if (text == null || text.trim().isEmpty()) continue;
                    values.put(charStyle, text);
                    values.put("CharacterStyle/" + charStyle, text);
                }
            }
            if (!values.isEmpty()) {
                found.put(tf.pageIndex(), values);
                maxPageIndex = Math.max(maxPageIndex, tf.pageIndex());
            }
        }
        if (found.isEmpty()) return Collections.emptyMap();
        int pageCount = ctx.resolvedData.pages() != null && !ctx.resolvedData.pages().isEmpty()
                ? ctx.resolvedData.pages().size()
                : maxPageIndex + 1;
        Map<Integer, Map<String, String>> carried = new HashMap<>();
        Map<String, String> carry = new HashMap<>();
        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            Map<String, String> onPage = found.get(pageIndex);
            if (onPage != null) carry.putAll(onPage);
            if (!carry.isEmpty()) carried.put(pageIndex, new HashMap<>(carry));
        }
        return carried;
    }

    private static Map<Integer, Map<String, String>> mergePageValues(
            Map<Integer, Map<String, String>> base, Map<Integer, Map<String, String>> overlay) {
        if (base == null || base.isEmpty()) {
            return overlay == null ? Collections.emptyMap() : overlay;
        }
        if (overlay == null || overlay.isEmpty()) return base;
        Map<Integer, Map<String, String>> merged = new HashMap<>();
        Set<Integer> keys = new HashSet<>();
        keys.addAll(base.keySet());
        keys.addAll(overlay.keySet());
        for (Integer key : keys) {
            Map<String, String> values = new HashMap<>();
            Map<String, String> b = base.get(key);
            Map<String, String> o = overlay.get(key);
            if (b != null) values.putAll(b);
            if (o != null) values.putAll(o);
            merged.put(key, values);
        }
        return merged;
    }

    private static Map<Integer, Map<String, String>> collectPageTextVariableValues(
            File idmlDir, DocumentBuilder db, Map<String, String> varToCharStyle,
            int pageCount, Map<Integer, List<String>> pageToStoryIds) {
        Map<String, String> carryOver = new HashMap<>();
        Map<Integer, Map<String, String>> pageHashira = new HashMap<>();
        for (int docIdx = 0; docIdx < pageCount; docIdx++) {
            Map<String, String> foundOnPage = new HashMap<>();
            List<String> storyIds = pageToStoryIds.getOrDefault(docIdx, Collections.emptyList());
            for (String storyId : storyIds) {
                File storyFile = storyFileFor(idmlDir, storyId);
                if (storyFile == null || !storyFile.exists()) continue;
                Document storyDoc = safeParseXml(db, storyFile);
                if (storyDoc == null) continue;
                collectStoryVariableValues(storyDoc, varToCharStyle, foundOnPage);
            }
            Map<String, String> pageValues = new HashMap<>(carryOver);
            pageValues.putAll(foundOnPage);
            carryOver.putAll(foundOnPage);
            pageHashira.put(docIdx, pageValues);
        }
        return pageHashira;
    }

    private static void collectStoryVariableValues(
            Document storyDoc, Map<String, String> varToCharStyle, Map<String, String> foundOnPage) {
        NodeList csrNodes = storyDoc.getElementsByTagName("CharacterStyleRange");
        for (int ci = 0; ci < csrNodes.getLength(); ci++) {
            Element csr = (Element) csrNodes.item(ci);
            String appliedStyle = csr.getAttribute("AppliedCharacterStyle");
            if (appliedStyle.isEmpty()) continue;
            for (Map.Entry<String, String> entry : varToCharStyle.entrySet()) {
                String charStyle = entry.getValue();
                if (!appliedStyle.equals(charStyle)) continue;
                if (foundOnPage.containsKey(charStyle)) continue;
                String text = collectContentText(csr).trim();
                if (!text.isEmpty()) {
                    foundOnPage.put(charStyle, text);
                    foundOnPage.put(entry.getKey(), text);
                }
            }
        }
    }

    private static String collectContentText(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList contents = el.getElementsByTagName("Content");
        for (int i = 0; i < contents.getLength(); i++) {
            sb.append(contents.item(i).getTextContent());
        }
        return sb.toString();
    }

    private static Map<String, String> readMatchCharacterStyleVariables(Document dmDoc) {
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
        return varToCharStyle;
    }

    private static List<String> readSpreadOrder(File idmlDir, Document dmDoc) {
        List<String> spreadOrder = new ArrayList<>();
        NodeList idPkgSpreads = dmDoc.getElementsByTagName("idPkg:Spread");
        for (int i = 0; i < idPkgSpreads.getLength(); i++) {
            String src = ((Element) idPkgSpreads.item(i)).getAttribute("src");
            if (!src.isEmpty() && src.startsWith("Spreads/")) {
                spreadOrder.add(src);
            }
        }
        if (spreadOrder.isEmpty()) {
            File spreadsDir = new File(idmlDir, "Spreads");
            if (spreadsDir.exists()) {
                File[] files = spreadsDir.listFiles((d, n) -> n.endsWith(".xml"));
                if (files != null) {
                    for (File f : files) spreadOrder.add("Spreads/" + f.getName());
                    spreadOrder.sort(String::compareTo);
                }
            }
        }
        return spreadOrder;
    }

    private static double[][] readPageSpreadBounds(NodeList pageNodes, int pagesInSpread) {
        double[][] pageSpreadBounds = new double[pagesInSpread][4];
        for (int pi = 0; pi < pagesInSpread; pi++) {
            Element pageEl = (Element) pageNodes.item(pi);
            double[] gbArr = parseDoubles(pageEl.getAttribute("GeometricBounds"));
            double[] itArr = parseDoubles(pageEl.getAttribute("ItemTransform"));
            if (gbArr.length >= 4 && itArr.length >= 6) {
                double tx = itArr[4], ty = itArr[5];
                pageSpreadBounds[pi][0] = gbArr[0] + ty;
                pageSpreadBounds[pi][1] = gbArr[1] + tx;
                pageSpreadBounds[pi][2] = gbArr[2] + ty;
                pageSpreadBounds[pi][3] = gbArr[3] + tx;
            }
        }
        return pageSpreadBounds;
    }

    private static double textFrameCenterX(Element tf) {
        NodeList anchors = tf.getElementsByTagName("PathPointType");
        if (anchors.getLength() == 0) return Double.NaN;
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int ai = 0; ai < anchors.getLength(); ai++) {
            double[] pt = parseDoubles(((Element) anchors.item(ai)).getAttribute("Anchor"));
            if (pt.length >= 2) {
                minX = Math.min(minX, pt[0]);
                maxX = Math.max(maxX, pt[0]);
                minY = Math.min(minY, pt[1]);
                maxY = Math.max(maxY, pt[1]);
            }
        }
        double[] bounds = transformedBounds(tf, minX, minY, maxX, maxY);
        return (bounds[1] + bounds[3]) / 2.0;
    }

    private static int documentPageIndexForSection(
            ResolvedBuildContext ctx, int sectionIndex, ASTSection section) {
        if (ctx.pageDocOffsetToSection != null) {
            for (Map.Entry<Integer, Integer> e : ctx.pageDocOffsetToSection.entrySet()) {
                if (e.getValue() != null && e.getValue() == sectionIndex) return e.getKey();
            }
        }
        return sectionIndex;
    }

    private static int replacePlaceholders(ASTTextFrameBlock block, Map<String, String> values) {
        if (block == null || block.paragraphs() == null || values == null || values.isEmpty()) return 0;
        int count = 0;
        for (ASTParagraph paragraph : block.paragraphs()) {
            if (paragraph == null || paragraph.items() == null) continue;
            count += replaceRunLocalPlaceholders(paragraph, values);
            count += replaceSplitParagraphPlaceholders(paragraph, values);
        }
        return count;
    }

    private static int replaceRunLocalPlaceholders(ASTParagraph paragraph, Map<String, String> values) {
        int count = 0;
        for (ASTInlineItem item : paragraph.items()) {
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            if (text == null || text.indexOf("<#") < 0) continue;
            String replaced = replaceVariableTokens(text, values);
            if (!text.equals(replaced)) {
                run.text(replaced);
                count++;
            }
        }
        return count;
    }

    private static int replaceSplitParagraphPlaceholders(ASTParagraph paragraph, Map<String, String> values) {
        StringBuilder allText = new StringBuilder();
        List<ASTTextRun> textRuns = new ArrayList<>();
        List<Integer> charToRun = new ArrayList<>();
        List<Integer> charToOffset = new ArrayList<>();
        boolean hasNonTextInline = false;
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTTextRun) {
                ASTTextRun run = (ASTTextRun) item;
                textRuns.add(run);
                String text = run.text();
                if (text != null) {
                    int runIndex = textRuns.size() - 1;
                    for (int i = 0; i < text.length(); i++) {
                        allText.append(text.charAt(i));
                        charToRun.add(runIndex);
                        charToOffset.add(i);
                    }
                }
            } else if (item != null) {
                hasNonTextInline = true;
            }
        }
        if (hasNonTextInline || textRuns.isEmpty()) return 0;
        String raw = allText.toString();
        if (raw.indexOf("<#") < 0) return 0;
        List<PlaceholderMatch> matches = findVariableTokenMatches(raw, values);
        if (matches.isEmpty()) return 0;

        int count = 0;
        for (int mi = matches.size() - 1; mi >= 0; mi--) {
            PlaceholderMatch match = matches.get(mi);
            int start = match.start;
            int endExclusive = match.endExclusive;
            if (start < 0 || endExclusive <= start || endExclusive > charToRun.size()) continue;

            int startRunIndex = charToRun.get(start);
            int startOffset = charToOffset.get(start);
            int endRunIndex = charToRun.get(endExclusive - 1);
            int endOffset = charToOffset.get(endExclusive - 1);
            if (startRunIndex < 0 || startRunIndex >= textRuns.size()
                    || endRunIndex < startRunIndex || endRunIndex >= textRuns.size()) {
                continue;
            }

            ASTTextRun startRun = textRuns.get(startRunIndex);
            String startText = startRun.text() != null ? startRun.text() : "";
            if (startRunIndex == endRunIndex) {
                if (startOffset > startText.length() || endOffset >= startText.length()) continue;
                startRun.text(startText.substring(0, startOffset)
                        + match.value
                        + startText.substring(endOffset + 1));
            } else {
                ASTTextRun endRun = textRuns.get(endRunIndex);
                String endText = endRun.text() != null ? endRun.text() : "";
                if (startOffset > startText.length() || endOffset >= endText.length()) continue;
                startRun.text(startText.substring(0, startOffset) + match.value);
                for (int ri = startRunIndex + 1; ri < endRunIndex; ri++) {
                    textRuns.get(ri).text("");
                }
                endRun.text(endText.substring(endOffset + 1));
            }
            count++;
        }
        return count;
    }

    private static String replaceVariableTokens(String text, Map<String, String> values) {
        String out = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isEmpty() || value == null) continue;
            String normalizedKey = normalizeVariableKey(key);
            if (normalizedKey.isEmpty()) continue;
            out = out.replaceAll("<#\\s*#?" + java.util.regex.Pattern.quote(normalizedKey) + "\\s*>",
                    java.util.regex.Matcher.quoteReplacement(value));
        }
        return out;
    }

    private static String normalizeVariableKey(String key) {
        String out = key == null ? "" : key.trim();
        if (out.startsWith("CharacterStyle/")) {
            out = out.substring("CharacterStyle/".length()).trim();
        }
        if (out.startsWith("#")) out = out.substring(1).trim();
        return out;
    }

    private static List<PlaceholderMatch> findVariableTokenMatches(
            String text, Map<String, String> values) {
        List<PlaceholderMatch> matches = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("<#", i);
            if (start < 0) break;
            int end = text.indexOf('>', start + 2);
            if (end < 0) break;
            String token = text.substring(start + 2, end).trim();
            String value = valueForVariableToken(token, values);
            if (value != null) {
                matches.add(new PlaceholderMatch(start, end + 1, value));
            }
            i = end + 1;
        }
        return matches;
    }

    private static String valueForVariableToken(String token, Map<String, String> values) {
        String normalizedToken = normalizeVariableKey(token);
        if (normalizedToken.isEmpty()) return null;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) continue;
            if (normalizedToken.equals(normalizeVariableKey(key))) {
                return value;
            }
        }
        return null;
    }

    private static final class PlaceholderMatch {
        final int start;
        final int endExclusive;
        final String value;

        PlaceholderMatch(int start, int endExclusive, String value) {
            this.start = start;
            this.endExclusive = endExclusive;
            this.value = value;
        }
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        return dbf.newDocumentBuilder();
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

    private static double[] parseTransform(String s) {
        double[] v = parseDoubles(s);
        if (v.length >= 6) return new double[]{v[0], v[1], v[2], v[3], v[4], v[5]};
        return new double[]{1, 0, 0, 1, 0, 0};
    }

    private static double[] cumulativeTransform(Element el) {
        List<double[]> chain = new ArrayList<>();
        Node n = el;
        while (n != null && n.getNodeType() == Node.ELEMENT_NODE) {
            Element e = (Element) n;
            if (e.hasAttribute("ItemTransform")) {
                chain.add(parseTransform(e.getAttribute("ItemTransform")));
            }
            n = n.getParentNode();
        }
        double[] result = new double[]{1, 0, 0, 1, 0, 0};
        for (int i = chain.size() - 1; i >= 0; i--) {
            result = multiply(result, chain.get(i));
        }
        return result;
    }

    private static double[] multiply(double[] left, double[] right) {
        double a = left[0], b = left[1], c = left[2], d = left[3], tx = left[4], ty = left[5];
        double e = right[0], f = right[1], g = right[2], h = right[3], ux = right[4], uy = right[5];
        return new double[]{
                a * e + c * f,
                b * e + d * f,
                a * g + c * h,
                b * g + d * h,
                a * ux + c * uy + tx,
                b * ux + d * uy + ty
        };
    }

    private static double[] apply(double[] m, double x, double y) {
        return new double[]{
                m[0] * x + m[2] * y + m[4],
                m[1] * x + m[3] * y + m[5]
        };
    }

    private static double[] transformedBounds(Element el,
                                              double minX,
                                              double minY,
                                              double maxX,
                                              double maxY) {
        double[] m = cumulativeTransform(el);
        double[][] pts = new double[][]{
                apply(m, minX, minY),
                apply(m, maxX, minY),
                apply(m, maxX, maxY),
                apply(m, minX, maxY)
        };
        double left = Double.MAX_VALUE, right = -Double.MAX_VALUE;
        double top = Double.MAX_VALUE, bottom = -Double.MAX_VALUE;
        for (double[] p : pts) {
            left = Math.min(left, p[0]);
            right = Math.max(right, p[0]);
            top = Math.min(top, p[1]);
            bottom = Math.max(bottom, p[1]);
        }
        return new double[]{top, left, bottom, right};
    }

}
