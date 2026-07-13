package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTabStop;

import java.util.Locale;
import java.util.Map;

/**
 * 단락 속성(정렬/줄간격/간격/들여쓰기/탭) 해석 공용 루틴.
 *
 * <p>셀 안/밖 단락이 같은 정렬·속성 해석을 쓰도록 {@link StoryLoader}에서 추출.
 * 동작은 기존 StoryLoader.convertStoryFromIDML 인라인 블록과 byte-identical 이어야 한다.</p>
 */
public final class ParagraphPropertyResolver {
    private ParagraphPropertyResolver() {}

    /**
     * IDML/resolved 단락 속성을 ASTParagraph에 적용한다.
     *
     * @param styleAlignCache cleanStyleName→alignment 캐시(성능용, nullable)
     */
    public static void apply(ASTParagraph para, IDMLParagraph ip,
                             ResolvedParagraph resolvedParagraph, ResolvedBuildContext ctx,
                             Map<String, String> styleAlignCache) {
        // 정렬 우선순위: IDML 단락(스토리) → IDML 스타일 → resolved 단락 → resolved top-level paragraphStyles
        // IDML ParagraphStyleRange의 Justification이 스타일 정의와 다를 경우 로컬 오버라이드이므로 최우선 적용
        String idmlStyleName = ip.appliedParagraphStyle();
        String cleanStyleName = (idmlStyleName != null && idmlStyleName.contains("/"))
                ? idmlStyleName.substring(idmlStyleName.lastIndexOf('/') + 1) : idmlStyleName;
        if (ip.justification() != null) {
            para.alignment(ip.justification());
        } else {
            String idmlStyleJust = cleanStyleName == null ? null
                    : (styleAlignCache != null
                        ? styleAlignCache.computeIfAbsent(cleanStyleName,
                            k -> StoryConverter.resolveStyleAlignment(k, ctx.astDocument))
                        : StoryConverter.resolveStyleAlignment(cleanStyleName, ctx.astDocument));
            if (idmlStyleJust != null) {
                para.alignment(idmlStyleJust);
            } else if (resolvedParagraph != null && resolvedParagraph.justification() != null) {
                para.alignment(resolvedParagraph.justification());
            } else if (cleanStyleName != null && ctx.resolvedData != null
                    && ctx.resolvedData.getParagraphStyleJustification(cleanStyleName) != null) {
                // resolved.json top-level paragraphStyles fallback
                para.alignment(ctx.resolvedData.getParagraphStyleJustification(cleanStyleName));
            }
        }
        // alignment가 null이면 HwpxParagraphBuilder에서 baseStyle 또는 기본 JUSTIFY 적용

        if (resolvedParagraph != null) {
            ResolvedParagraph rp = resolvedParagraph;
            if (rp.autoLeading() != null && rp.autoLeading() > 0) {
                para.autoLeadingPercent((int) Math.round(rp.autoLeading()));
            }
            // leading: resolved 우선 (실제 렌더링 값), IDML 스타일 fallback
            // 단, auto leading(>50pt = percentage 값)은 무시
            Double fixedLeading = rp.fixedLeading(); // resolved (실제 렌더링 값)
            if (fixedLeading == null || fixedLeading <= 0) {
                fixedLeading = RunBuilder.getStyleLeading(ctx, ip.appliedParagraphStyle()); // IDML 스타일
                if (fixedLeading != null && fixedLeading > 50) fixedLeading = null;
            }
            if (fixedLeading == null || fixedLeading <= 0) {
                fixedLeading = ip.leading(); // IDML CharacterRun leading
                if (fixedLeading != null && fixedLeading > 50) fixedLeading = null;
            }
            if (fixedLeading != null && fixedLeading > 0) {
                // InDesign Leading(pt) → HWPX 고정 줄간격(HWPUNIT)
                para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(fixedLeading));
                para.lineSpacingType("fixed");
            }
            if (rp.spaceBefore() != null && rp.spaceBefore() > 0) {
                para.spaceBefore(CoordinateConverter.pointsToHwpunits(rp.spaceBefore()));
            }
            if (rp.spaceAfter() != null && rp.spaceAfter() > 0) {
                para.spaceAfter(CoordinateConverter.pointsToHwpunits(rp.spaceAfter()));
            }
            boolean preserveHangingTab = StoryLoader.shouldPreserveNeutralHangingIndentForTab(rp);
            boolean neutralHangingIndent = StoryLoader.isNeutralHangingIndent(rp.leftIndent(), rp.firstLineIndent());
            if (neutralHangingIndent) {
                para.leftMargin(0L);
                para.firstLineIndent(0L);
            } else if (rp.leftIndent() != null && rp.leftIndent() != 0) {
                para.leftMargin(CoordinateConverter.pointsToHwpunits(rp.leftIndent()));
            }
            if (!neutralHangingIndent && rp.firstLineIndent() != null && rp.firstLineIndent() != 0) {
                para.firstLineIndent(CoordinateConverter.pointsToHwpunits(rp.firstLineIndent()));
            }
            // 탭 스톱
            if (rp.hasTabStops()) {
                // normalizeToPoints() 후 tabStop position과 leftIndent는 이미 pt 단위
                // (applyScale에서 scaleFactor가 적용됨). 다시 scaleFactor를 곱하면 이중 적용.
                double leftPt = (rp.leftIndent() != null ? rp.leftIndent() : 0);
                for (ResolvedTabStop rts : rp.tabStops()) {
                    if (rts.position() != null && rts.position() > 0) {
                        double posPt = preserveHangingTab ? rts.position() : rts.position() - leftPt;
                        if (posPt < 0) posPt = 0;
                        String align = "left";
                        if (rts.alignment() != null) {
                            String a = rts.alignment().toLowerCase();
                            if (a.contains("center")) align = "center";
                            else if (a.contains("right")) align = "right";
                            else if (a.contains("decimal")) align = "decimal";
                        }
                        para.addTabStop(new ASTTabStop(
                                CoordinateConverter.pointsToHwpunits(posPt), align, rts.leader()));
                    }
                }
            }
        } else {
            // resolvedStory 매칭 실패 → IDML 단락 스타일에서 정렬 상속
            String idmlStyle = ip.appliedParagraphStyle();
            if (idmlStyle != null) {
                // "ParagraphStyle/스타일명" → "스타일명"
                String styleName = idmlStyle.contains("/")
                        ? idmlStyle.substring(idmlStyle.lastIndexOf('/') + 1) : idmlStyle;
                String styleJust = StoryConverter.resolveStyleAlignment(styleName, ctx.astDocument);
                if (styleJust != null) para.alignment(styleJust);
            }
        }
    }

    /** Applies resolved-only paragraph properties for planner-declared TextFlow materialization. */
    public static void applyResolved(ASTParagraph para, ResolvedParagraph rp, ResolvedBuildContext ctx) {
        if (para == null || rp == null) return;
        if (rp.styleName() != null) {
            para.paragraphStyleRef(rp.styleName());
        }
        if (rp.justification() != null) {
            para.alignment(rp.justification());
        } else if (rp.styleName() != null && ctx != null) {
            String styleJust = StoryConverter.resolveStyleAlignment(rp.styleName(), ctx.astDocument);
            if (styleJust != null) {
                para.alignment(styleJust);
            } else if (ctx.resolvedData != null
                    && ctx.resolvedData.getParagraphStyleJustification(rp.styleName()) != null) {
                para.alignment(ctx.resolvedData.getParagraphStyleJustification(rp.styleName()));
            }
        }
        Double fixedLeading = rp.fixedLeading();
        if (rp.autoLeading() != null && rp.autoLeading() > 0) {
            para.autoLeadingPercent((int) Math.round(rp.autoLeading()));
        }
        if (fixedLeading != null && fixedLeading > 0) {
            para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(fixedLeading));
            para.lineSpacingType("fixed");
        }
        if (rp.spaceBefore() != null && rp.spaceBefore() > 0) {
            para.spaceBefore(CoordinateConverter.pointsToHwpunits(rp.spaceBefore()));
        }
        if (rp.spaceAfter() != null && rp.spaceAfter() > 0) {
            para.spaceAfter(CoordinateConverter.pointsToHwpunits(rp.spaceAfter()));
        }
        boolean preserveHangingTab = StoryLoader.shouldPreserveNeutralHangingIndentForTab(rp);
        boolean neutralHangingIndent = StoryLoader.isNeutralHangingIndent(rp.leftIndent(), rp.firstLineIndent());
        if (neutralHangingIndent) {
            para.leftMargin(0L);
            para.firstLineIndent(0L);
        } else if (rp.leftIndent() != null && rp.leftIndent() != 0) {
            para.leftMargin(CoordinateConverter.pointsToHwpunits(rp.leftIndent()));
        }
        if (!neutralHangingIndent && rp.firstLineIndent() != null && rp.firstLineIndent() != 0) {
            para.firstLineIndent(CoordinateConverter.pointsToHwpunits(rp.firstLineIndent()));
        }
        if (rp.hasTabStops()) {
            double leftPt = (rp.leftIndent() != null ? rp.leftIndent() : 0);
            for (ResolvedTabStop rts : rp.tabStops()) {
                if (rts.position() == null || rts.position() <= 0) continue;
                double posPt = preserveHangingTab ? rts.position() : rts.position() - leftPt;
                if (posPt < 0) posPt = 0;
                String align = "left";
                if (rts.alignment() != null) {
                    String a = rts.alignment().toLowerCase(Locale.ROOT);
                    if (a.contains("center")) align = "center";
                    else if (a.contains("right")) align = "right";
                    else if (a.contains("decimal")) align = "decimal";
                }
                para.addTabStop(new ASTTabStop(
                        CoordinateConverter.pointsToHwpunits(posPt), align, rts.leader()));
            }
        }
    }

    /**
     * 단일 줄 단락의 양쪽맞춤을 순수 정렬로 정규화한다.
     * 단일 줄에서 justify는 무의미하므로 left로 바꾸되, CenterJustified/RightJustified의
     * center/right 의도는 보존한다(예: '교과서 15쪽' 가운데 라벨이 left로 뭉개지는 회귀 방지).
     */
    public static void normalizeSingleLineJustify(ASTParagraph para) {
        if (para == null) return;
        String alignment = para.alignment();
        if (alignment == null) return;
        String lower = alignment.toLowerCase(Locale.ROOT);
        if (lower.contains("justified") || lower.contains("justify")) {
            if (lower.contains("center")) {
                para.alignment("CenterAlign");
            } else if (lower.contains("right")) {
                para.alignment("RightAlign");
            } else {
                para.alignment("left");
            }
        }
    }
}
