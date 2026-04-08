package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTPageLayout;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * SPEC-013 Phase 1: resolved.pages()를 ASTSection 리스트로 변환.
 *
 * <p>각 페이지의 크기·여백을 hwpunit으로 변환해 ASTPageLayout을 생성하고,
 * document pageIndex → sections 리스트 인덱스 매핑을 ctx에 기록한다.</p>
 *
 * <p>{@code ResolvedToASTBuilder.buildSections}에서 stateless static helper로 발췌.
 * 동작은 동일.</p>
 */
public final class PageLayoutBuilder {

    private PageLayoutBuilder() {}

    /**
     * 페이지 레이아웃을 빌드해 ASTSection 리스트로 반환한다.
     * 부수효과: {@code ctx.pageDocOffsetToSection}에 매핑을 채운다.
     */
    public static List<ASTSection> build(ResolvedBuildContext ctx) {
        List<ASTSection> sections = new ArrayList<>();
        List<ResolvedPage> pages = ctx.resolvedData.pages();

        for (int i = 0; i < pages.size(); i++) {
            ResolvedPage page = pages.get(i);
            ASTSection section = new ASTSection();

            // 페이지 번호: name이 숫자면 사용, 아니면 인덱스+1
            int pageNum = i + 1;
            try { pageNum = Integer.parseInt(page.name()); } catch (NumberFormatException e) {}
            section.pageNumber(pageNum);

            // 페이지 레이아웃 (normalizeToPoints() 후 이미 pt 단위)
            ASTPageLayout layout = new ASTPageLayout();
            layout.pageWidth(CoordinateConverter.pointsToHwpunits(page.width()));
            layout.pageHeight(CoordinateConverter.pointsToHwpunits(page.height()));
            layout.marginTop(CoordinateConverter.pointsToHwpunits(page.marginTop()));
            layout.marginBottom(CoordinateConverter.pointsToHwpunits(page.marginBottom()));
            layout.marginLeft(CoordinateConverter.pointsToHwpunits(page.marginLeft()));
            layout.marginRight(CoordinateConverter.pointsToHwpunits(page.marginRight()));
            layout.columnCount(1);
            layout.columnGutter(0);
            section.layout(layout);

            sections.add(section);
        }

        // document pageIndex → section list index 매핑 빌드
        ctx.pageDocOffsetToSection = new HashMap<>();
        for (int i = 0; i < pages.size(); i++) {
            ctx.pageDocOffsetToSection.put(pages.get(i).index(), i);
        }

        return sections;
    }
}
