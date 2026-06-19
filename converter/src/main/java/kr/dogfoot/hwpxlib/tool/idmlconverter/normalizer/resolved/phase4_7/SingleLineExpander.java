package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_7;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.List;

/**
 * SPEC-029 Phase 4.7: 단일 행 글상자 폭 자동 확장.
 *
 * <p>InDesign 원본에서 한 줄로 표시되던 글상자가 HWPX 변환 후 다른 폰트 메트릭 때문에 줄바꿈
 * 되는 경우, 글상자 폭을 수평으로 확장하여 한 줄 유지.</p>
 *
 * <p>적용 조건 (모두 충족):
 * <ol>
 *   <li>paragraphs.size() == 1</li>
 *   <li>텍스트에 \n / \r 없음</li>
 *   <li>columnCount <= 1</li>
 *   <li>resolved composedLines.size() == 1 (원본 1줄)</li>
 *   <li>인라인 객체 (그림/표) 없음</li>
 * </ol></p>
 *
 * <p>확장 정책:
 * <ul>
 *   <li>예상 폭 (font metric × 글자 수) > 현재 폭 → 확장</li>
 *   <li>새 폭 = 예상 폭 + 2pt 여백</li>
 *   <li>50% 이상 확장 시 매핑 폰트 이슈로 간주 → 로그만 출력, 미적용</li>
 *   <li>인접 객체 충돌 감지 시 미적용</li>
 * </ul></p>
 */
public final class SingleLineExpander {

    private SingleLineExpander() {}

    private static final double SAFETY_MARGIN_PT = 2.0;
    private static final double MAX_EXPAND_RATIO = 2.0;
    private static final long COLLISION_GAP_HWP = 200; // 2pt
    private static final double DEFAULT_KOR_ADVANCE = 1.0;  // 한글 1em
    private static final double DEFAULT_LAT_ADVANCE = 0.55; // 라틴 평균 ~0.5-0.6em

    public static void run(ResolvedBuildContext ctx, List<ASTSection> sections) {
        int expanded = 0, skipped = 0;
        for (ASTSection section : sections) {
            for (ASTBlock blk : section.blocks()) {
                if (!(blk instanceof ASTTextFrameBlock)) continue;
                ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
                Outcome o = considerExpand(ctx, tfb, section);
                if (o == Outcome.EXPANDED) expanded++;
                else if (o == Outcome.SKIPPED_LARGE) skipped++;
            }
        }
        if (expanded > 0 || skipped > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 4.7: " + expanded
                    + " text frames width-expanded (single-line guarantee), "
                    + skipped + " skipped (expand>50%)");
        }
    }

    private enum Outcome { NOT_CANDIDATE, EXPANDED, SKIPPED_LARGE, COLLISION, NO_CHANGE }

    private static Outcome considerExpand(ResolvedBuildContext ctx, ASTTextFrameBlock tfb, ASTSection section) {
        if (tfb.paragraphs() == null || tfb.paragraphs().size() != 1) return Outcome.NOT_CANDIDATE;
        if (tfb.columnCount() > 1) return Outcome.NOT_CANDIDATE;
        if (isConceptDiagramTextFrame(ctx, tfb)) return Outcome.NOT_CANDIDATE;
        ASTParagraph p = tfb.paragraphs().get(0);
        if (p.items() == null || p.items().isEmpty()) return Outcome.NOT_CANDIDATE;

        // 텍스트 추출 + 비-텍스트 아이템(이미지/표) 검사
        StringBuilder sb = new StringBuilder();
        double maxFontSizePt = 0;
        String dominantFamily = null;
        double dominantTracking = 0;
        for (ASTInlineItem it : p.items()) {
            if (!(it instanceof ASTTextRun)) {
                // 인라인 객체가 있으면 폭 계산 복잡 → 스킵
                return Outcome.NOT_CANDIDATE;
            }
            ASTTextRun run = (ASTTextRun) it;
            String t = run.text();
            if (t == null) continue;
            if (t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0) return Outcome.NOT_CANDIDATE;
            sb.append(t);
            Integer fs = run.fontSizeHwpunits();
            if (fs != null) {
                double pt = fs / 100.0;
                if (pt > maxFontSizePt) {
                    maxFontSizePt = pt;
                    if (run.fontFamily() != null) dominantFamily = run.fontFamily();
                    if (run.letterSpacing() != null) dominantTracking = run.letterSpacing();
                }
            }
        }
        String text = sb.toString();
        if (text.trim().isEmpty()) return Outcome.NOT_CANDIDATE;
        if (maxFontSizePt <= 0) return Outcome.NOT_CANDIDATE;

        // resolved 의 composedLines 가 1줄인지 확인 (원본 1줄 검증)
        if (!isSingleLineInResolved(ctx, tfb)) return Outcome.NOT_CANDIDATE;

        // InDesign PNG가 visual shell을 소유하고 HWPX TF가 텍스트만 얹는 경우,
        // 텍스트 박스 폭을 늘리면 배경 PNG bounds와 어긋난다.
        if (hasRenderedVisualShell(ctx, tfb)) return Outcome.NOT_CANDIDATE;

        // 장식 도형 (Polygon/Oval/Rectangle with fill) 안에 포함된 textbox 는 확장 금지.
        // 확장하면 도형 경계를 넘어가 시각적으로 깨짐. (예: page 30 "계획 세우기" 가 dark pill 안)
        if (isInsideDecorativeShape(ctx, tfb)) return Outcome.NOT_CANDIDATE;

        // 예상 폭 계산 — tracking 은 HWPX letterSpacing 단위(%fontSize) 로 이미 변환된 값
        double estPt = estimateTextWidthPt(ctx, text, maxFontSizePt, dominantFamily, dominantTracking);
        long contentWidthHwp = Math.max(0L, tfb.width() - tfb.insetLeft() - tfb.insetRight());
        double contentWidthPt = contentWidthHwp / 100.0;

        // 확장 필요?
        if (estPt + SAFETY_MARGIN_PT <= contentWidthPt) return Outcome.NO_CHANGE;

        double targetContentPt = estPt + SAFETY_MARGIN_PT;
        long targetWidthHwp = CoordinateConverter.pointsToHwpunits(targetContentPt)
                + tfb.insetLeft() + tfb.insetRight();

        // 50% 초과 확장은 폰트 매핑 이슈로 간주 → 미적용
        double expandRatio = (double) targetWidthHwp / Math.max(1, tfb.width());
        if (expandRatio > MAX_EXPAND_RATIO) {
            return Outcome.SKIPPED_LARGE;
        }

        // 충돌 감지 — 같은 섹션의 다른 블록과 X 방향 겹침 확인
        long newRight = tfb.x() + targetWidthHwp;
        if (collidesWithOtherBlock(section, tfb, newRight)) {
            return Outcome.COLLISION;
        }

        tfb.width(targetWidthHwp);
        return Outcome.EXPANDED;
    }

    /**
     * Textbox 가 같은 페이지의 장식 도형(Polygon/Oval/Rectangle with fill) 안에 포함되어 있는지.
     * 포함된 경우 확장하면 도형 경계를 넘어가 시각적으로 깨짐 → 확장 금지.
     */
    private static boolean isInsideDecorativeShape(ResolvedBuildContext ctx, ASTTextFrameBlock tfb) {
        int tfDomId = sourceDomId(tfb);
        if (tfDomId < 0) return false;
        ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(String.valueOf(tfDomId));
        if (rtf == null) return false;
        double[] tfBounds = rtf.geometricBounds();
        if (tfBounds == null || tfBounds.length < 4) return false;
        int pageIdx = rtf.pageIndex();
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null) continue;
            if (pi.pageIndex() != pageIdx) continue;
            if (pi.id() != null && pi.id().equals(String.valueOf(tfDomId))) continue;
            String t = pi.type();
            if (!"Polygon".equals(t) && !"Oval".equals(t) && !"Rectangle".equals(t)) continue;
            String fc = pi.fillColorName();
            if (fc == null || "None".equals(fc) || "[None]".equals(fc)) continue;
            double[] sb = pi.geometricBounds();
            if (sb == null || sb.length < 4) continue;
            // tfBounds 가 sb 안에 포함되는지 (1pt 여유)
            if (sb[0] <= tfBounds[0] + 1 && sb[1] <= tfBounds[1] + 1
                    && sb[2] >= tfBounds[2] - 1 && sb[3] >= tfBounds[3] - 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRenderedVisualShell(ResolvedBuildContext ctx, ASTTextFrameBlock tfb) {
        if (ctx == null || ctx.resolvedData == null || tfb == null) return false;
        int tfDomId = sourceDomId(tfb);
        if (tfDomId < 0) return false;
        if (ctx.isTextFrameOwnedByTextShellPlan(tfDomId)) return true;
        List<RenderedGroup> groups = ctx.resolvedData.allRenderedFloatingItems();
        if (groups == null) return false;
        for (RenderedGroup rg : groups) {
            if (rg == null) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            if (!"hwpx_tf".equals(rg.textOwner())) continue;
            if (!rg.hasEditableTextHiddenFromPng()) continue;
            if (!containsId(rg.editableTextFrameIds(), tfDomId)) continue;
            return true;
        }
        return false;
    }

    private static boolean containsId(String[] ids, int id) {
        if (ids == null) return false;
        String expected = String.valueOf(id);
        for (String candidate : ids) {
            if (expected.equals(candidate)) return true;
        }
        return false;
    }

    private static boolean isConceptDiagramTextFrame(ResolvedBuildContext ctx, ASTTextFrameBlock tfb) {
        if (ctx == null || ctx.conceptDiagramTextFrameIds == null || tfb == null) return false;
        int tfDomId = sourceDomId(tfb);
        return tfDomId >= 0 && ctx.conceptDiagramTextFrameIds.contains(String.valueOf(tfDomId));
    }

    private static int sourceDomId(ASTTextFrameBlock tfb) {
        if (tfb == null) return -1;
        String src = tfb.sourceId();
        if (src == null || !src.startsWith("u")) return -1;
        String hexPart = src.substring(1);
        int us = hexPart.indexOf('_');
        if (us >= 0) hexPart = hexPart.substring(0, us);
        try { return Integer.parseInt(hexPart, 16); }
        catch (NumberFormatException e) { return -1; }
    }

    private static boolean isSingleLineInResolved(ResolvedBuildContext ctx, ASTTextFrameBlock tfb) {
        int tfDomId = sourceDomId(tfb);
        if (tfDomId < 0) return false;
        ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(String.valueOf(tfDomId));
        if (rtf == null) {
            // 마스터 인스턴스 등 composedLines 가 없는 TF — paragraphs 가 1개이고 \r 없는 단순 텍스트로 추정
            // 호출 측이 이미 paragraphs.size()==1 + \n/\r 없음을 확인했으므로 통과시킴.
            return true;
        }
        if (rtf.composedLines() == null) return true;
        return rtf.composedLines().size() == 1;
    }

    private static double estimateTextWidthPt(ResolvedBuildContext ctx, String text, double fontSizePt, String family, double trackingPercent) {
        // HWPX 폰트 (한컴돋움 등) 의 한글 글리프 폭은 보통 ~1.0em.
        // 매핑 시 호환을 위해 그대로 사용 (resolved 의 IDML 메트릭 은 원본 폰트 기준 → 매핑 후 폭 추정에 부적합).
        double korAdvance = DEFAULT_KOR_ADVANCE;
        double latAdvance = DEFAULT_LAT_ADVANCE;
        // tracking 변환 (1/1000 em → 1/100 em): tracking 50 → 5%
        double spacingPctOfFont = trackingPercent / 100.0; // 0.05 means 5%
        double spacingExtra = fontSizePt * spacingPctOfFont;
        double total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCJK(c)) total += korAdvance * fontSizePt + spacingExtra;
            else if (c == ' ') total += latAdvance * fontSizePt * 0.5 + spacingExtra * 0.5;
            else if (c == '\t') total += latAdvance * fontSizePt * 2.0;
            else total += latAdvance * fontSizePt + spacingExtra;
        }
        return total;
    }

    private static boolean isCJK(char c) {
        if (c >= '가' && c <= '힣') return true;       // 한글
        if (c >= 'ㄱ' && c <= 'ㆎ') return true;       // 한글 자모
        if (c >= '一' && c <= '鿿') return true;       // CJK 통합
        if (c >= '　' && c <= '〿') return true;       // CJK 부호
        if (c >= '！' && c <= '｠') return true;       // 전각
        return false;
    }

    private static boolean collidesWithOtherBlock(ASTSection section, ASTTextFrameBlock self, long newRight) {
        long selfTop = self.y();
        long selfBottom = self.y() + self.height();
        long selfRight = self.x() + self.width();
        for (ASTBlock other : section.blocks()) {
            if (other == self) continue;
            // 다른 블록 좌표 추출 — TextFrameBlock 또는 ASTFigure 만 검사
            long oX, oY, oW, oH;
            if (other instanceof ASTTextFrameBlock) {
                ASTTextFrameBlock o = (ASTTextFrameBlock) other;
                oX = o.x(); oY = o.y(); oW = o.width(); oH = o.height();
            } else if (other instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure o = (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) other;
                oX = o.x(); oY = o.y(); oW = o.width(); oH = o.height();
            } else {
                continue;
            }
            long oRight = oX + oW;
            long oBottom = oY + oH;
            // Y 범위 겹침 확인
            if (oBottom <= selfTop || oY >= selfBottom) continue;
            // 새로운 우측 끝이 다른 블록 좌측보다 작거나, 원래 우측이 이미 다른 블록 우측보다 큰 경우는 제외
            if (oX >= selfRight && oX < newRight + COLLISION_GAP_HWP) return true;
        }
        return false;
    }
}
