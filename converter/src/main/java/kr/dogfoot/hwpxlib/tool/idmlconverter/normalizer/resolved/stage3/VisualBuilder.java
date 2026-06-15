package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6.BackgroundInjector;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase7.RenderableFramePlacer;

import java.util.List;

/**
 * Stage 3 visual execution entry point.
 *
 * <p>SPEC-035의 목표 구조에서는 Stage 1 ObjectPlan이 모든 visual ownership,
 * placement, layer를 결정하고 이 클래스는 plan을 실행만 한다. 현재는 Phase 6/7의
 * legacy executors를 내부 브리지로 호출하되, 상위 파이프라인에서 Phase 6/7 직접 의존을
 * 제거해 이후 executor를 하나씩 흡수할 수 있게 한다.</p>
 */
public final class VisualBuilder {
    private VisualBuilder() {
    }

    public static void place(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx == null || sections == null || sections.isEmpty()) return;

        try (ConversionTiming.Scope ignored =
                     ConversionTiming.time("stage3.visualBuilder.legacyBackgroundInjector")) {
            BackgroundInjector.inject(ctx, sections);
        }

        try (ConversionTiming.Scope ignored =
                     ConversionTiming.time("stage3.visualBuilder.legacyRenderableFramePlacer")) {
            RenderableFramePlacer.place(ctx, sections);
        }
    }
}
