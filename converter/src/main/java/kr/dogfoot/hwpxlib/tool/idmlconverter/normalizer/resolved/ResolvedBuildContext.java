package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.StylePropertyResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.PolicyLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.io.File;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * SPEC-013 Stage 1: Phase별 빌더가 공유하는 컨텍스트.
 *
 * <p>현재는 골격 단계 — 모든 필드는 선언만 되어 있고 {@code ResolvedToASTBuilder}는
 * 자체 인스턴스 필드를 그대로 사용한다. Phase 클래스가 점진적으로 분리되면서 각 필드의
 * 소유권이 여기로 넘어온다. 호환성을 위해 setter 대신 final 필드 + 생성자를 쓰지 않고
 * 단순 public 필드 + builder 메서드 방식으로 두어, 분리 도중 시그니처 변경 부담을 줄였다.</p>
 *
 * <p>주의: 이 컨텍스트는 stateful이다. 한 빌드(단일 ASTDocument 생성)마다 새로 만들고,
 * 끝나면 버린다. 동시 빌드 시 공유 금지.</p>
 */
public final class ResolvedBuildContext {
    /** resolved.json 파싱 결과. */
    public ResolvedData resolvedData;

    /** resolved.json 부모 디렉토리 (PNG/PDF 상대경로 기준점). null 가능. */
    public String basePath;

    /** mm/in → pt 변환 계수. resolved.normalizeToPoints() 결과에 적용. */
    public double scaleFactor = 1.0;

    /** IDML 압축 해제 디렉토리 (Story XML 로드용). null 가능. */
    public File idmlDir;

    /** ExtendScript에서 PNG를 내보낼 때 사용한 DPI. 좌표 보정에 활용. */
    public int pngExportDpi;

    /** 누적 결과. {@link ResolvedToASTBuilder#build()}에서 채워진다. */
    public ASTDocument astDocument;

    /** Phase 0에서 IDML Styles.xml로부터 만든 스타일 resolver. */
    public StylePropertyResolver styleResolver;

    /** document pageIndex → section list index 매핑. Phase 1에서 만들어 Phase 6/7이 사용. */
    public Map<Integer, Integer> pageDocOffsetToSection;

    /** SPEC-013: Phase 분리 시 각 phase가 호출하는 helper. 인스턴스 메서드 위임. */
    public IntUnaryOperator toSectionIndex;

    /**
     * SPEC-013 Stage 6: lazy IDML 인프라(테이블 셀 변환용) 셋업 콜백.
     * Phase 클래스가 호출하면 ResolvedToASTBuilder의 인스턴스 필드(idmlDocument 등)가 초기화된다.
     * 호출 직후 {@link #idmlDocumentSupplier}/{@link #colorResolverSupplier}/{@link #imageLoaderSupplier}
     * 가 최신 값을 반환한다.
     */
    public Runnable ensureIdmlInfra;

    /** lazy 초기화된 IDMLDocument 공급. ensureIdmlInfra 호출 후 사용. */
    public Supplier<IDMLDocument> idmlDocumentSupplier;
    /** lazy 초기화된 ColorResolver 공급. */
    public Supplier<ColorResolver> colorResolverSupplier;
    /** lazy 초기화된 ASTImageLoader 공급. */
    public Supplier<ASTImageLoader> imageLoaderSupplier;

    /**
     * Story XML 로딩(캐싱 포함). storyId(decimal) → IDMLStory.
     * ResolvedToASTBuilder의 idmlStoryCache 인스턴스 상태에 위임.
     */
    public Function<String, IDMLStory> loadIDMLStory;

    /**
     * SPEC-016 Phase 2: 매칭 신뢰도 누적 카운터.
     * 인덱스: [0]=HIGH, [1]=MEDIUM, [2]=LOW.
     * Phase 3가 createRunFromIDML 단일 집계 지점에서 ++한다.
     * builder가 같은 배열 참조를 보관하여 build() 종료 시 요약 로그를 출력.
     */
    public int[] spec016Counts;

    /** Phase 3 IDML Story XML 캐시. builder의 idmlStoryCache와 같은 Map 참조를 공유. */
    public Map<String, IDMLStory> idmlStoryCache;

    /** Phase 3 findResolvedRun에서 마지막 매칭 인덱스를 기록하는 1-element 배열. */
    public int[] lastMatchResult;

    /**
     * SPEC-015: {@code --debug-ast} 활성화 여부. true일 때만 phase 진입 시 currentPhase를 채우고
     * 새로 생성된 블록에 DebugMeta가 자동 부여된다.
     */
    public boolean debugAst;

    /**
     * SPEC-015: 현재 실행 중인 Phase 이름 (예: "Phase2.placeTextFrames"). debugAst가 true일 때만 의미.
     */
    public String currentPhase;

    /**
     * SPEC-017: 테이블 셀 품질 게이트 정책. null이면 기본값 사용.
     */
    public kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig.TableQualityGateConfig tableQualityGate;

    /**
     * IDML AnchoredPosition="Anchored" + TextWrapMode="None" 인라인 앵커 Group ID 집합.
     * 이런 객체는 텍스트를 밀지 않고 BEHIND_TEXT 로 겹쳐야 함 → Phase 3 가 인라인 배치를 건너뛰고
     * Phase 3 후처리가 floating ASTFigure 로 배치.
     */
    public java.util.Set<Integer> deferredAnchoredFloatingIds = new java.util.HashSet<>();

    /**
     * AnchoredPosition="Anchored" + TextWrapMode="None" 이지만 inline_object PNG가 있어
     * loadInlineObject 로 인라인 배치되는 Group ID 집합.
     * 이 객체들은 IDML에서 anchor 앞뒤로 gap이 있으므로 인라인 배치 시 우측 여백을 추가한다.
     */
    public java.util.Set<Integer> customAnchoredInlineIds = new java.util.HashSet<>();

    /*
     * SPEC-035 migration note:
     *
     * 아래 disposition/map/set 묶음은 현재 legacy Phase 2/3/6/7 사이에서
     * "이미 배치됨", "inline으로 유지", "floating으로 전환", "배경 shell 흡수"
     * 같은 결정을 전달하는 임시 상태다. 새 ownership 규칙을 추가할 때 이 집합을
     * 더 늘리면 phase 간 정책 충돌이 다시 커진다.
     *
     * 목표 구조에서는 OwnershipPlanner가 DOM/source id별 ObjectPlan을 만들고,
     * TextBuilder/VisualBuilder는 plan의 textAction/visualAction/zOrder만 실행한다.
     * 따라서 새 결정 상태는 가능하면 ObjectPlan으로 먼저 모델링하고, 이 필드는
     * 기존 동작을 이관하는 동안만 유지한다.
     */

    /**
     * TextFrame DOM ID → 텍스트 처리 소유권 결정.
     *
     * <p>RenderedGroup과 TextFrame이 같은 numeric id를 공유하는 케이스가 있으므로,
     * 텍스트 처리 완료 상태를 PNG 배치 스킵 판단에 재사용하면 안 된다.</p>
     */
    public java.util.Map<Integer, FrameDisposition> textFrameDispositions = new java.util.HashMap<>();

    /** RenderedGroup/page_object ID → PNG 처리 소유권 결정. */
    public java.util.Map<Integer, FrameDisposition> renderedItemDispositions = new java.util.HashMap<>();

    /** inline_object ID → 인라인 PNG 처리 소유권 결정. */
    public java.util.Map<Integer, FrameDisposition> inlineObjectDispositions = new java.util.HashMap<>();

    /**
     * Phase 3가 완성형 단순 버튼 라벨 PNG를 문단 인라인 객체로 실제 배치한 DOM id.
     * Phase 6는 이 집합에 들어 있는 page_object만 중복 플로팅 배치하지 않는다.
     */
    public java.util.Set<Integer> inlineCompleteSimpleButtonLabelIds = new java.util.HashSet<>();

    /**
     * Phase 3가 의미 라벨 Group(배경 도형 + editable TF)을 INLINE_TEXT_FRAME으로 재구성한 DOM id.
     * Phase 6는 같은 id의 배경 PNG를 플로팅으로 다시 배치하지 않는다.
     */
    public java.util.Set<Integer> inlineEditableLabelShellIds = new java.util.HashSet<>();

    /** TextFrame domId의 disposition을 등록한다. */
    public void setTextDisposition(int domId, FrameDisposition d) {
        textFrameDispositions.put(domId, d);
    }

    /** RenderedGroup domId의 disposition을 등록한다. */
    public void setRenderedDisposition(int domId, FrameDisposition d) {
        renderedItemDispositions.put(domId, d);
    }

    /** inline_object domId의 disposition을 등록한다. */
    public void setInlineDisposition(int domId, FrameDisposition d) {
        inlineObjectDispositions.put(domId, d);
    }

    /** TextFrame domId가 지정된 disposition으로 등록되어 있으면 true. */
    public boolean isTextDisposed(int domId, FrameDisposition d) {
        return d.equals(textFrameDispositions.get(domId));
    }

    /** RenderedGroup domId가 지정된 disposition으로 등록되어 있으면 true. */
    public boolean isRenderedDisposed(int domId, FrameDisposition d) {
        return d.equals(renderedItemDispositions.get(domId));
    }

    /** inline_object domId가 지정된 disposition으로 등록되어 있으면 true. */
    public boolean isInlineDisposed(int domId, FrameDisposition d) {
        return d.equals(inlineObjectDispositions.get(domId));
    }

    /**
     * 하위 호환용. 새 코드는 처리 대상에 따라 text/rendered/inline 전용 메서드를 써야 한다.
     */
    @Deprecated
    public void setDisposition(int domId, FrameDisposition d) {
        setTextDisposition(domId, d);
    }

    /** 하위 호환용. 새 코드는 처리 대상에 따라 text/rendered/inline 전용 메서드를 써야 한다. */
    @Deprecated
    public boolean isDisposed(int domId, FrameDisposition d) {
        return isTextDisposed(domId, d);
    }

    /**
     * inline_object ID → TF 의 pageIndex 매핑.
     * inline_object의 renderedGroup.pageIndex()가 TF의 실제 섹션과 다를 수 있으므로
     * Phase 2 에서 TF 의 pageIndex 를 함께 저장해 Phase 7b 가 올바른 섹션에 배치.
     */
    public java.util.Map<Integer, Integer> inlineObjectTfPageIndex = new java.util.HashMap<>();

    /**
     * IDML AnchoredPosition="AboveLine" 인 앵커 객체 ID 집합.
     * AboveLine 배지는 실제 인라인이 아니므로 badge PNG floating 처리 대상.
     * prepopulateAnchoredFloatingIds 이후 유효.
     */
    public java.util.Set<Integer> aboveLineAnchoredIds = new java.util.HashSet<>();

    /**
     * Phase 6(BackgroundInjector)이 배치한 page_object DOM ID 집합.
     * Phase 7c가 동일 항목을 중복 배치하지 않도록 건너뛰는 데 사용.
     */
    public java.util.Set<Integer> phase6PlacedIds = new java.util.HashSet<>();

    /**
     * Concept diagram pseudo-table 영역에 속한 TextFrame DOM id.
     * 이 영역은 실제 IDML Table이 아니라 visual shell + editable TF + 독립 설명 TF
     * 조합이므로, table/grid/width-expansion/label-centering 보정보다 원본 좌표 보존을 우선한다.
     */
    public java.util.Set<String> conceptDiagramTextFrameIds = new java.util.HashSet<>();

    /** rendered page_object의 최종 배치/스킵 사유 추적용 JSONL 라인. */
    public java.util.List<String> renderDecisionLines = new java.util.ArrayList<>();

    /** SPEC-035 OwnershipPlanner 관찰 모드: ObjectPlan JSONL 라인. */
    public java.util.List<String> ownershipPlanLines = new java.util.ArrayList<>();

    /** SPEC-035 OwnershipPlanner 관찰 모드: invariant warning JSONL 라인. */
    public java.util.List<String> ownershipWarningLines = new java.util.ArrayList<>();

    /**
     * SPEC-035 Stage 1 ObjectPlan.
     *
     * <p>현재는 legacy Phase가 전부 plan executor로 이관되기 전의 중간 단계다.
     * Visual Builder는 여기서 먼저 DROP_VISUAL만 존중해, 같은 DOM id를 가진
     * inline/page_object 쌍을 과하게 묶어 스킵하지 않도록 렌더 메타데이터까지
     * 정확히 매칭한다.</p>
     */
    public java.util.List<ObjectPlan> ownershipPlans = new java.util.ArrayList<>();

    public void addOwnershipPlan(ObjectPlan plan) {
        if (plan != null) ownershipPlans.add(plan);
    }

    public boolean shouldDropVisualByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        return plan != null && !plan.hasVisibleVisual();
    }

    public VisualAction visualActionByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        return plan != null ? plan.visualAction : null;
    }

    public TextAction textActionByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        return plan != null ? plan.textAction : null;
    }

    public Placement placementByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        return plan != null ? plan.placement : null;
    }

    public PolicyLayer policyLayerByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        return plan != null ? plan.visualPolicyLayer() : null;
    }

    public boolean shouldPlaceInlinePngByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        return plan != null && plan.visualAction == VisualAction.PLACE_INLINE_PNG;
    }

    public boolean shouldPlaceFloatingVisualByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        if (plan == null || !plan.hasVisibleVisual()) return false;
        if (plan.placement != Placement.FLOATING) return false;
        return plan.visualAction == VisualAction.PLACE_FLOATING_PNG
                || plan.visualAction == VisualAction.PLACE_TEXT_SHELL;
    }

    public boolean hasVisibleVisualByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        return plan != null && plan.hasVisibleVisual();
    }

    public boolean hasOwnershipPlan(RenderedGroup rg) {
        return findOwnershipPlanForRendered(rg) != null;
    }

    public ObjectPlan findOwnershipPlanForDomId(int domId) {
        if (domId < 0) return null;
        for (ObjectPlan plan : ownershipPlans) {
            if (plan.domId == domId) return plan;
            if (plan.sourceObjectIds != null) {
                for (int sourceObjectId : plan.sourceObjectIds) {
                    if (sourceObjectId == domId) return plan;
                }
            }
        }
        return null;
    }

    public boolean isCompleteInlinePngByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_PNG
                && plan.visualAction == VisualAction.PLACE_INLINE_PNG;
    }

    public Integer zOrderByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        if (plan == null || !plan.hasVisibleVisual()) return null;
        return plan.zOrder;
    }

    public String visualLayerByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        if (plan == null || !plan.hasVisibleVisual() || plan.visualLayer == null) return null;
        return plan.visualLayer.name();
    }

    public Boolean inFrontLayerByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        if (plan == null || !plan.hasVisibleVisual() || plan.visualLayer == null) return null;
        return plan.visualLayer == VisualLayer.CONTAINER_FACE
                || plan.visualLayer == VisualLayer.LABEL_BACKDROP
                || plan.visualLayer == VisualLayer.CONTENT_VISUAL
                || plan.visualLayer == VisualLayer.CONTAINER_OUTLINE
                || plan.visualLayer == VisualLayer.FOREGROUND_MASK;
    }

    public ObjectPlan findOwnershipPlanForRendered(RenderedGroup rg) {
        if (rg == null) return null;
        ObjectPlan sameDom = null;
        ObjectPlan sameFileAndBounds = null;
        ObjectPlan sameFile = null;
        Placement placement = placementOf(rg);
        for (ObjectPlan plan : ownershipPlans) {
            if (plan.pageIndex != rg.pageIndex()) continue;
            boolean fileMatches = safeEquals(plan.file, rg.file());
            boolean placementMatches = plan.placement == placement;
            boolean renderMatches = plan.renderId != null && plan.renderId.intValue() == rg.id();
            boolean domMatches = plan.domId == rg.id();
            if (renderMatches && fileMatches && placementMatches) return plan;
            if (renderMatches && placementMatches) return plan;
            if (renderMatches) return plan;
            if (sameDom == null && domMatches && placementMatches) sameDom = plan;
            if (sameFileAndBounds == null && fileMatches && placementMatches && sameRoundedBounds(plan.bounds, rg.bounds())) {
                sameFileAndBounds = plan;
            }
            if (sameFile == null && fileMatches && placementMatches) sameFile = plan;
        }
        if (sameDom != null) return sameDom;
        if (sameFileAndBounds != null) return sameFileAndBounds;
        return sameFile;
    }

    private static Placement placementOf(RenderedGroup rg) {
        if (rg != null && ("inline_object".equals(rg.type()) || "inline_object".equals(rg.itemType()))) {
            return Placement.INLINE;
        }
        return Placement.FLOATING;
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private static boolean sameRoundedBounds(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        for (int i = 0; i < 4; i++) {
            if (Math.round(a[i] * 100.0) != Math.round(b[i] * 100.0)) return false;
        }
        return true;
    }

    public void recordRenderedDecision(RenderedGroup rg, String phase, String decision, String detail) {
        if (rg == null) return;
        StringBuilder sb = new StringBuilder(256);
        sb.append('{')
                .append("\"phase\":\"").append(jsonEscape(phase)).append("\",")
                .append("\"decision\":\"").append(jsonEscape(decision)).append("\",")
                .append("\"detail\":\"").append(jsonEscape(detail)).append("\",")
                .append("\"id\":").append(rg.id()).append(',')
                .append("\"pageIndex\":").append(rg.pageIndex()).append(',')
                .append("\"file\":\"").append(jsonEscape(rg.file())).append("\",")
                .append("\"reason\":\"").append(jsonEscape(rg.reason())).append("\",")
                .append("\"itemType\":\"").append(jsonEscape(rg.itemType())).append("\",")
                .append("\"visualOwner\":\"").append(jsonEscape(rg.visualOwner())).append("\",")
                .append("\"textOwner\":\"").append(jsonEscape(rg.textOwner())).append("\",")
                .append("\"placementAllowed\":").append(Boolean.FALSE.equals(rg.placementAllowed()) ? "false" : "true");
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        if (plan != null) {
            sb.append(",\"planTextAction\":\"").append(plan.textAction).append("\",")
                    .append("\"planVisualAction\":\"").append(plan.visualAction).append("\",")
                    .append("\"planVisualLayer\":\"").append(plan.visualLayer).append("\",")
                    .append("\"planPolicyLayer\":\"").append(plan.visualPolicyLayer()).append("\",")
                    .append("\"planPlacement\":\"").append(plan.placement).append("\",")
                    .append("\"planZOrder\":").append(plan.zOrder).append(',')
                    .append("\"planReason\":\"").append(jsonEscape(plan.reason)).append("\"");
        }
        double[] b = rg.bounds();
        if (b != null && b.length >= 4) {
            sb.append(",\"bounds\":[")
                    .append(b[0]).append(',')
                    .append(b[1]).append(',')
                    .append(b[2]).append(',')
                    .append(b[3]).append(']');
        }
        sb.append('}');
        renderDecisionLines.add(sb.toString());
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    public ResolvedBuildContext() {
    }
}
