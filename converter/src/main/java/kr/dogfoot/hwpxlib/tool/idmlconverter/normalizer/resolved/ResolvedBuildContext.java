package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.StylePropertyResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.AnchoredTablePlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.PolicyLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.SideHeadFlowPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.SimpleButtonLabelPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualPlanePolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDiagnostics;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowIndex;
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
     *
     * <p>새 코드에서는 필요한 범위에 맞춰 {@link #ensureColorInfra} 또는
     * {@link #ensureImageInfra}를 우선 사용한다. 이 필드는 legacy 호출 호환용이다.</p>
     */
    public Runnable ensureIdmlInfra;

    /** 색상 fallback 해석에 필요한 IDMLDocument + ColorResolver만 lazy 초기화한다. */
    public Runnable ensureColorInfra;

    /** IDMLDocument + ColorResolver + ASTImageLoader를 lazy 초기화한다. */
    public Runnable ensureImageInfra;

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
     * AnchoredPosition="Anchored" + TextWrapMode="None" 이지만 inline_object PNG가 있어
     * loadInlineObject 로 인라인 배치되는 Group ID 집합.
     * 이 객체들은 IDML에서 anchor 앞뒤로 gap이 있으므로 인라인 배치 시 우측 여백을 추가한다.
     */
    public java.util.Set<Integer> customAnchoredInlineIds = new java.util.HashSet<>();

    /*
     * source ownership policy migration note:
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

    /**
     * 변환 1회 동안 렌더 PNG 원본 바이트를 재사용한다.
     *
     * <p>같은 파일을 Phase 6/7 또는 overflow 처리에서 반복해서 읽는 비용을 줄이기 위한
     * 실행 캐시이며, ownership/placement 결정에는 참여하지 않는다.</p>
     */
    public final java.util.Map<String, byte[]> renderedPngByteCache = new java.util.HashMap<>();

    /** ParagraphStyle → Story text run fallback style 값 캐시. */
    public final java.util.Map<String, ParagraphStyleContext> paragraphStyleContextCache = new java.util.HashMap<>();

    public static final class ParagraphStyleContext {
        public final String fillColor;
        public final Double tracking;
        public final String fontFamily;
        public final Double fontSize;
        public final Double horizontalScale;
        public final String underlineColor;

        public ParagraphStyleContext(String fillColor, Double tracking, String fontFamily,
                                     Double fontSize, Double horizontalScale, String underlineColor) {
            this.fillColor = fillColor;
            this.tracking = tracking;
            this.fontFamily = fontFamily;
            this.fontSize = fontSize;
            this.horizontalScale = horizontalScale;
            this.underlineColor = underlineColor;
        }
    }

    /**
     * rendered group 조회용 실행 인덱스.
     *
     * <p>legacy Phase 여러 곳에서 {@code allRenderedFloatingItems()}를 반복 선형 탐색한다.
     * 아래 인덱스는 같은 첫 매칭 결과를 빠르게 돌려주는 캐시일 뿐이며,
     * ownership/placement 결정을 새로 만들지 않는다.</p>
     */
    private java.util.Map<Integer, RenderedGroup> renderedFloatingById;
    private java.util.Map<Integer, RenderedGroup> inlineObjectById;
    private java.util.Map<String, RenderedGroup> tfInlineVisualOwnerByTextFrameId;

    /**
     * Phase 4(TableBuilder)가 셀 단락을 공용 루틴으로 빌드하면서 셀 안 inline 객체(배지 drawText 등)로
     * 임베드한 DOM id. 셀 흐름 안에 텍스트+박스가 이미 들어가므로 Phase 6/7c는 같은 id의 page_object
     * 플로팅 PNG(원본 절대 좌표의 배지 배경)를 배치하면 안 된다 — 안 그러면 셀 흐름과 어긋난 위치의
     * 박스 PNG가 인라인 텍스트를 가린다.
     */
    public java.util.Set<Integer> cellInlineEmbeddedDomIds = new java.util.HashSet<>();

    /** TextFrame domId의 disposition을 등록한다. */
    public void setTextDisposition(int domId, FrameDisposition d) {
        textFrameDispositions.put(domId, d);
    }

    /** RenderedGroup domId의 disposition을 등록한다. */
    public void setRenderedDisposition(int domId, FrameDisposition d) {
        renderedItemDispositions.put(domId, d);
    }

    /**
     * Stage 3 visual executor 또는 그 이전 text/table builder가 rendered visual을
     * 이미 처리/억제했음을 표시한다.
     */
    public void markRenderedVisualHandled(int domId) {
        renderedItemDispositions.put(domId, FrameDisposition.TEXT_BLOCK_PLACED);
    }

    /** 여러 rendered visual을 Stage 3에서 이미 처리된 것으로 표시한다. */
    public void markRenderedVisualsHandled(java.util.Collection<Integer> domIds) {
        if (domIds == null) return;
        for (Integer domId : domIds) {
            if (domId == null) continue;
            markRenderedVisualHandled(domId);
        }
    }

    /** TextFrame domId가 지정된 disposition으로 등록되어 있으면 true. */
    public boolean isTextDisposed(int domId, FrameDisposition d) {
        return d.equals(textFrameDispositions.get(domId));
    }

    /** RenderedGroup domId가 지정된 disposition으로 등록되어 있으면 true. */
    public boolean isRenderedDisposed(int domId, FrameDisposition d) {
        return d.equals(renderedItemDispositions.get(domId));
    }

    public RenderedGroup renderedFloatingById(int id) {
        ensureRenderedGroupIndexes();
        return renderedFloatingById != null ? renderedFloatingById.get(id) : null;
    }

    public RenderedGroup inlineObjectById(int id) {
        ensureRenderedGroupIndexes();
        return inlineObjectById != null ? inlineObjectById.get(id) : null;
    }

    public RenderedGroup tfInlineVisualOwnerForTextFrame(String domId) {
        if (domId == null) return null;
        ensureRenderedGroupIndexes();
        return tfInlineVisualOwnerByTextFrameId != null
                ? tfInlineVisualOwnerByTextFrameId.get(domId)
                : null;
    }

    public boolean hasInlineObjectPng(int objectId) {
        RenderedGroup rg = inlineObjectById(objectId);
        if (rg == null) return false;
        String file = rg.file();
        if (file == null || file.isEmpty()) return false;
        if (basePath == null) return true;
        try {
            return new File(basePath, file).exists();
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureRenderedGroupIndexes() {
        if (renderedFloatingById != null) return;
        renderedFloatingById = new java.util.HashMap<>();
        inlineObjectById = new java.util.HashMap<>();
        tfInlineVisualOwnerByTextFrameId = new java.util.HashMap<>();
        java.util.List<RenderedGroup> groups = resolvedData != null
                ? resolvedData.allRenderedFloatingItems()
                : null;
        if (groups == null) return;
        for (RenderedGroup rg : groups) {
            if (rg == null) continue;
            renderedFloatingById.putIfAbsent(rg.id(), rg);
            if ("inline_object".equals(rg.itemType()) || "inline_object".equals(rg.type())) {
                inlineObjectById.putIfAbsent(rg.id(), rg);
            }
            if (rg.tfInlineVisualIds() == null || rg.tfInlineVisualIds().length == 0) continue;
            if (!"hwpx_tf".equals(rg.textOwner())) continue;
            String[] tfIds = rg.editableTextFrameIds();
            if (tfIds == null) continue;
            for (String tfId : tfIds) {
                if (tfId != null) {
                    tfInlineVisualOwnerByTextFrameId.putIfAbsent(tfId, rg);
                }
            }
        }
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
     * Phase 2 에서 TF 의 pageIndex 를 함께 저장해 Stage 3 visual executor가 올바른 섹션에 배치.
     */
    public java.util.Map<Integer, Integer> inlineObjectTfPageIndex = new java.util.HashMap<>();

    /**
     * IDML AnchoredPosition="AboveLine" 인 앵커 객체 ID 집합.
     * AboveLine 배지는 실제 인라인이 아니므로 badge PNG floating 처리 대상.
     * Stage 0/1 input metadata scan 이후 유효.
     */
    public java.util.Set<Integer> aboveLineAnchoredIds = new java.util.HashSet<>();

    /**
     * FramePlacer(Phase 2)가 네이티브 fill로 흡수한 배경 도형 DOM ID 집합
     * (deco PNG가 nativeFillChildIds로 풀어준 대형 배경). 이 도형은 별도 complex_graphic
     * PNG로도 추출되어 floating 배치될 수 있으므로, Stage 3 visual executor가 floating 배치를 건너뛴다.
     */
    public java.util.Set<Integer> nativeFillAbsorbedIds = new java.util.HashSet<>();

    /**
     * Concept diagram pseudo-table 영역에 속한 TextFrame DOM id.
     * 이 영역은 실제 IDML Table이 아니라 visual shell + editable TF + 독립 설명 TF
     * 조합이므로, table/grid/width-expansion/label-centering 보정보다 원본 좌표 보존을 우선한다.
     */
    public java.util.Set<String> conceptDiagramTextFrameIds = new java.util.HashSet<>();

    /** rendered page_object의 최종 배치/스킵 사유 추적용 JSONL 라인. */
    public java.util.List<String> renderDecisionLines = new java.util.ArrayList<>();

    /** ExtractionPlan candidate -> PNG -> ObjectPlan -> HWPX placement human-readable trace. */
    public java.util.List<String> ownershipTraceLines = new java.util.ArrayList<>();

    /** source ownership policy OwnershipPlanner 관찰 모드: ObjectPlan JSONL 라인. */
    public java.util.List<String> ownershipPlanLines = new java.util.ArrayList<>();

    /** source ownership policy OwnershipPlanner 관찰 모드: invariant warning JSONL 라인. */
    public java.util.List<String> ownershipWarningLines = new java.util.ArrayList<>();

    /** Java legacy ownership bridge가 새로 추가한 ObjectPlan JSONL 라인. */
    public java.util.List<String> legacyBridgeAddedPlanLines = new java.util.ArrayList<>();

    /** Java legacy ownership bridge가 import된 ObjectPlan을 수정한 before/after JSONL 라인. */
    public java.util.List<String> legacyBridgeMutatedPlanLines = new java.util.ArrayList<>();

    /** Java legacy ownership bridge 추가/수정 plan 분류 요약 JSONL 라인. */
    public java.util.List<String> legacyBridgeSummaryLines = new java.util.ArrayList<>();

    /**
     * Stage 2 TextFlow snapshot.
     *
     * <p>현재는 diagnostics + lookup index 용도로만 사용한다. 다음 이관 단계에서
     * Story/Table/Shell text builder가 이 모델을 소비하고, legacy IDML Story 재탐색을
     * 줄인다.</p>
     */
    public TextFlowDiagnostics textFlowDiagnostics;
    public TextFlowDocument textFlowDocument;
    public TextFlowIndex textFlowIndex = TextFlowIndex.empty();

    /**
     * source ownership policy Stage 1 ObjectPlan.
     *
     * <p>현재는 legacy Phase가 전부 plan executor로 이관되기 전의 중간 단계다.
     * 실행 단계는 여기서 선언된 plan을 정확한 render/placement key로 조회해 실행만 한다.
     * source-only/overlap/fallback lookup으로 placement나 visibility를 새로 판단하지 않는다.</p>
     */
    public java.util.List<ObjectPlan> ownershipPlans = new java.util.ArrayList<>();
    private final java.util.Map<String, ObjectPlan> ownershipPlanRenderedCache = new java.util.HashMap<>();
    private java.util.Map<String, ObjectPlan> ownershipPlanByRenderFileKey;
    private java.util.Map<String, ObjectPlan> ownershipPlanByRenderUnitKey;
    private java.util.Map<String, ObjectPlan> ownershipPlanByRenderKey;
    private java.util.Map<String, ObjectPlan> ownershipPlanByCandidateKey;
    private java.util.Map<String, ObjectPlan> ownershipPlanByDomKey;
    private java.util.Map<String, ObjectPlan> ownershipPlanByFileBoundsKey;
    private java.util.Map<String, ObjectPlan> ownershipPlanByFileKey;
    private boolean ownershipPlanIndexDirty = true;

    /** Stage 1 simple marker label plans. Key: inline anchor DOM id. */
    public final java.util.Map<Integer, SimpleButtonLabelPlan> simpleButtonLabelPlans =
            new java.util.LinkedHashMap<>();

    /** Stage 1 simple marker label reverse index. Key: label TextFrame DOM id. */
    public final java.util.Map<Integer, SimpleButtonLabelPlan> simpleButtonLabelPlansByTextFrameId =
            new java.util.HashMap<>();

    /** Stage 1 anchored table plans. Key: owner TextFrame DOM id. */
    private final java.util.Map<Integer, java.util.List<AnchoredTablePlan>> anchoredTablePlansByOwnerTextFrameId =
            new java.util.LinkedHashMap<>();

    /** Anchored table source ids consumed by StoryConverter, including wrapper and nested table ids. */
    private final java.util.Set<String> anchoredTableSourceIds =
            new java.util.HashSet<>();
    private final java.util.Set<String> anchoredWrapperTableSourceIds =
            new java.util.HashSet<>();
    private final java.util.Set<String> anchoredNestedTableSourceIds =
            new java.util.HashSet<>();

    /** Stage 1 side-head flow table plans. Key: IDML table source id. */
    private final java.util.Map<String, SideHeadFlowPlan> sideHeadFlowPlansByTableSourceId =
            new java.util.LinkedHashMap<>();

    public void addSimpleButtonLabelPlan(SimpleButtonLabelPlan plan) {
        if (plan == null) return;
        simpleButtonLabelPlans.put(plan.anchorDomId, plan);
        if (plan.labelTextFrameDomId >= 0) {
            simpleButtonLabelPlansByTextFrameId.put(plan.labelTextFrameDomId, plan);
        }
    }

    public SimpleButtonLabelPlan simpleButtonLabelPlan(int anchorDomId) {
        SimpleButtonLabelPlan plan = simpleButtonLabelPlans.get(anchorDomId);
        if (plan != null) return plan;
        return simpleButtonLabelPlansByTextFrameId.get(anchorDomId);
    }

    public void addAnchoredTablePlan(AnchoredTablePlan plan) {
        if (plan == null) return;
        anchoredTablePlansByOwnerTextFrameId
                .computeIfAbsent(plan.ownerTextFrameDomId, k -> new java.util.ArrayList<>())
                .add(plan);
        addAnchoredTableSourceId(plan.wrapperTableId);
        addAnchoredTableSourceId(plan.nestedTableId);
        addAnchoredWrapperTableSourceId(plan.wrapperTableId);
        addAnchoredNestedTableSourceId(plan.nestedTableId);
    }

    public java.util.List<AnchoredTablePlan> anchoredTablePlansForOwnerTextFrame(int domId) {
        java.util.List<AnchoredTablePlan> plans = anchoredTablePlansByOwnerTextFrameId.get(domId);
        if (plans == null || plans.isEmpty()) return java.util.Collections.emptyList();
        return plans;
    }

    public java.util.List<AnchoredTablePlan> anchoredTablePlans() {
        if (anchoredTablePlansByOwnerTextFrameId.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<AnchoredTablePlan> result = new java.util.ArrayList<>();
        for (java.util.List<AnchoredTablePlan> plans : anchoredTablePlansByOwnerTextFrameId.values()) {
            if (plans != null) result.addAll(plans);
        }
        return result;
    }

    public boolean isAnchoredTableSource(String tableSourceId) {
        return tableSourceId != null && anchoredTableSourceIds.contains(tableSourceId);
    }

    public boolean isAnchoredWrapperTableSource(String tableSourceId) {
        return tableSourceId != null && anchoredWrapperTableSourceIds.contains(tableSourceId);
    }

    public boolean isAnchoredNestedTableSource(String tableSourceId) {
        return tableSourceId != null && anchoredNestedTableSourceIds.contains(tableSourceId);
    }

    public void addSideHeadFlowPlan(SideHeadFlowPlan plan) {
        if (plan == null || plan.tableSourceId == null || plan.tableSourceId.isEmpty()) return;
        sideHeadFlowPlansByTableSourceId.put(plan.tableSourceId, plan);
    }

    public SideHeadFlowPlan sideHeadFlowPlanForTable(String tableSourceId) {
        if (tableSourceId == null || tableSourceId.isEmpty()) return null;
        return sideHeadFlowPlansByTableSourceId.get(tableSourceId);
    }

    public boolean isSideHeadFlowTableSource(String tableSourceId) {
        return sideHeadFlowPlanForTable(tableSourceId) != null;
    }

    public boolean hasSideHeadFlowPlans() {
        return !sideHeadFlowPlansByTableSourceId.isEmpty();
    }

    private void addAnchoredTableSourceId(String tableSourceId) {
        if (tableSourceId != null && !tableSourceId.isEmpty()) {
            anchoredTableSourceIds.add(tableSourceId);
        }
    }

    private void addAnchoredWrapperTableSourceId(String tableSourceId) {
        if (tableSourceId != null && !tableSourceId.isEmpty()) {
            anchoredWrapperTableSourceIds.add(tableSourceId);
        }
    }

    private void addAnchoredNestedTableSourceId(String tableSourceId) {
        if (tableSourceId != null && !tableSourceId.isEmpty()) {
            anchoredNestedTableSourceIds.add(tableSourceId);
        }
    }

    public void addOwnershipPlan(ObjectPlan plan) {
        if (plan != null) {
            ownershipPlans.add(plan);
            ownershipPlanIndexDirty = true;
            ownershipPlanRenderedCache.clear();
        }
    }

    public void clearOwnershipPlansForRewrite() {
        ownershipPlans.clear();
        ownershipPlanLines.clear();
        ownershipPlanIndexDirty = true;
        ownershipPlanRenderedCache.clear();
        ownershipPlanByRenderFileKey = null;
        ownershipPlanByRenderKey = null;
        ownershipPlanByCandidateKey = null;
        ownershipPlanByDomKey = null;
        ownershipPlanByFileBoundsKey = null;
        ownershipPlanByFileKey = null;
    }

    public void rebuildOwnershipPlanLinesFromPlans() {
        ownershipPlanLines.clear();
        for (java.util.List<AnchoredTablePlan> grouped : anchoredTablePlansByOwnerTextFrameId.values()) {
            if (grouped == null) continue;
            for (AnchoredTablePlan plan : grouped) {
                if (plan != null) ownershipPlanLines.add(plan.toJson());
            }
        }
        for (SideHeadFlowPlan plan : sideHeadFlowPlansByTableSourceId.values()) {
            if (plan != null) ownershipPlanLines.add(plan.toJson());
        }
        for (ObjectPlan plan : ownershipPlans) {
            if (plan != null) ownershipPlanLines.add(plan.toJson());
        }
    }

    public boolean shouldDropVisualByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        if (plan != null) {
            return !plan.hasVisibleVisual();
        }
        return isTextFrameOwnedButPlanMissingForRendered(rg);
    }

    public VisualAction visualActionByOwnershipPlan(RenderedGroup rg) {
        ObjectPlan plan = findOwnershipPlanForRendered(rg);
        return plan != null ? plan.visualAction : null;
    }

    public ShellRole shellRoleByOwnershipPlan(RenderedGroup rg) {
        return ShellRole.from(findOwnershipPlanForRendered(rg));
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
                || ShellRole.isTextShell(plan);
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
        }
        for (ObjectPlan plan : ownershipPlans) {
            if (plan.sourceObjectIds != null) {
                for (int sourceObjectId : plan.sourceObjectIds) {
                    if (sourceObjectId == domId) return plan;
                }
            }
        }
        return null;
    }

    public ObjectPlan findTextFrameOwnershipPlan(int domId) {
        if (domId < 0) return null;
        for (ObjectPlan plan : ownershipPlans) {
            if (plan == null || plan.domId != domId) continue;
            if (plan.kind != null && plan.kind.startsWith("text_frame")) return plan;
        }
        return null;
    }

    public boolean ownershipPlanPlacesFloatingHwpxText(int domId) {
        if (isTextFrameOwnedByTextShellPlanWithPlacement(domId, Placement.INLINE)) {
            return false;
        }
        if (isTextFrameOwnedByTextShellPlanWithPlacement(domId, Placement.FLOATING)) {
            return true;
        }
        ObjectPlan plan = findTextFrameOwnershipPlan(domId);
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.placement == Placement.FLOATING;
    }

    public boolean ownershipPlanPlacesInlineHwpxText(int domId) {
        if (isTextFrameOwnedByTextShellPlanWithPlacement(domId, Placement.INLINE)) {
            return true;
        }
        if (isTextFrameOwnedByTextShellPlanWithPlacement(domId, Placement.FLOATING)) {
            return false;
        }
        ObjectPlan plan = findTextFrameOwnershipPlan(domId);
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.placement == Placement.INLINE;
    }

    public boolean isTextFrameOwnedByTextShellPlan(int domId) {
        if (domId < 0 || ownershipPlans == null) return false;
        for (ObjectPlan plan : ownershipPlans) {
            if (plan == null) continue;
            if (!isTextShellTextPlacementPlan(plan)) continue;
            if (plan.domId == domId) return true;
            if (plan.ownedTextFrameIds != null) {
                for (int textFrameId : plan.ownedTextFrameIds) {
                    if (textFrameId == domId) return true;
                }
            }
            if (plan.sourceObjectIds == null) continue;
            for (int sourceObjectId : plan.sourceObjectIds) {
                if (sourceObjectId == domId) return true;
            }
        }
        return false;
    }

    public boolean isTextFrameStyleOwnedByVisibleTextShellPlan(int domId) {
        if (domId < 0 || ownershipPlans == null) return false;
        for (ObjectPlan plan : ownershipPlans) {
            if (plan == null || !plan.hasVisibleVisual()) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if ("editable_textframe_visual_shell".equals(plan.reason)) continue;
            if (plan.styleSourceObjectIds == null) continue;
            for (int styleSourceId : plan.styleSourceObjectIds) {
                if (styleSourceId == domId) return true;
            }
        }
        return false;
    }

    public boolean isTextFrameOwnedByFloatingTextShellPlan(int domId) {
        if (isTextFrameOwnedByTextShellPlanWithPlacement(domId, Placement.INLINE)) {
            return false;
        }
        return isTextFrameOwnedByTextShellPlanWithPlacement(domId, Placement.FLOATING);
    }

    private boolean isTextFrameOwnedByTextShellPlanWithPlacement(int domId, Placement placement) {
        if (domId < 0 || placement == null || ownershipPlans == null) return false;
        for (ObjectPlan plan : ownershipPlans) {
            if (plan == null) continue;
            if (!isTextShellTextPlacementPlan(plan)) continue;
            if (plan.placement != placement) continue;
            if (plan.ownedTextFrameIds != null) {
                for (int textFrameId : plan.ownedTextFrameIds) {
                    if (textFrameId == domId) return true;
                }
            }
            if (plan.domId == domId) return true;
        }
        return false;
    }

    private static boolean isTextShellTextPlacementPlan(ObjectPlan plan) {
        if (plan == null || plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (ShellRole.isTextShell(plan)) return true;
        return plan.visualAction == VisualAction.DROP_VISUAL
                && "container_backdrop_absorbed_by_table_style".equals(plan.reason)
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    public boolean isVisualSourceClaimedByVisibleTextShellPlan(int sourceId) {
        if (sourceId < 0 || ownershipPlans == null) return false;
        for (ObjectPlan plan : ownershipPlans) {
            if (plan == null) continue;
            if (!plan.hasVisibleVisual()) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (plan.visualSourceObjectIds == null) continue;
            for (int visualSourceId : plan.visualSourceObjectIds) {
                if (visualSourceId == sourceId) return true;
            }
        }
        return false;
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
        return VisualPlanePolicy.isInFrontLayer(plan.visualLayer);
    }

    public ObjectPlan findOwnershipPlanForRendered(RenderedGroup rg) {
        if (rg == null) return null;
        String cacheKey = renderedPlanCacheKey(rg);
        if (ownershipPlanRenderedCache.containsKey(cacheKey)) {
            return ownershipPlanRenderedCache.get(cacheKey);
        }
        ensureOwnershipPlanIndexes();
        Placement placement = placementOf(rg);
        Placement alternatePlacement = placement == Placement.INLINE ? Placement.FLOATING : Placement.INLINE;
        ObjectPlan plan = findRenderedPlanForPlacement(rg, placement, false);
        ObjectPlan alternatePlan = findRenderedPlanForPlacement(rg, alternatePlacement, false);
        if (plan == null || (alternatePlan != null && alternatePlan.hasVisibleVisual() && !plan.hasVisibleVisual())) {
            plan = alternatePlan;
        }
        if (plan == null) {
            plan = findRenderedPlanForPlacement(rg, placement, true);
            alternatePlan = findRenderedPlanForPlacement(rg, alternatePlacement, true);
            if (plan == null || (alternatePlan != null && alternatePlan.hasVisibleVisual() && !plan.hasVisibleVisual())) {
                plan = alternatePlan;
            }
        }
        if (plan == null) {
            plan = renderedOnly(ownershipPlanByRenderUnitKey.get(renderUnitKey(rg.renderUnitId())));
        }
        ownershipPlanRenderedCache.put(cacheKey, plan);
        return plan;
    }

    private ObjectPlan findRenderedPlanForPlacement(
            RenderedGroup rg,
            Placement placement,
            boolean allowCandidateFallback) {
        if (rg == null || placement == null) return null;
        ObjectPlan plan = renderedOnly(ownershipPlanByRenderFileKey.get(
                renderFileKey(rg.pageIndex(), placement, rg.id(), rg.file())));
        ObjectPlan renderPlan = renderedOnly(ownershipPlanByRenderKey.get(
                renderKey(rg.pageIndex(), placement, rg.id())));
        if (plan == null) plan = renderPlan;
        if (plan == null) {
            plan = renderedOnly(ownershipPlanByFileBoundsKey.get(
                    fileBoundsKey(rg.pageIndex(), placement, rg.file(), rg.bounds())));
            if (plan == null) plan = renderPlan;
        }
        if (plan == null) {
            plan = renderedOnly(ownershipPlanByFileKey.get(fileKey(rg.pageIndex(), placement, rg.file())));
            if (plan == null) plan = renderPlan;
        }
        if (plan == null) {
            plan = renderPlan;
        }
        if (plan == null) {
            plan = renderedOnly(ownershipPlanByDomKey.get(domKey(rg.pageIndex(), placement, rg.id())));
        }
        if (allowCandidateFallback && plan == null) {
            plan = renderedOnly(ownershipPlanByCandidateKey.get(
                    candidateKey(rg.pageIndex(), placement, rg.candidateId())));
        }
        return plan;
    }

    private static ObjectPlan renderedOnly(ObjectPlan plan) {
        return plan != null && plan.renderId != null ? plan : null;
    }

    private void ensureOwnershipPlanIndexes() {
        if (!ownershipPlanIndexDirty && ownershipPlanByRenderFileKey != null) return;
        ownershipPlanByRenderFileKey = new java.util.HashMap<>();
        ownershipPlanByRenderUnitKey = new java.util.HashMap<>();
        ownershipPlanByRenderKey = new java.util.HashMap<>();
        ownershipPlanByCandidateKey = new java.util.HashMap<>();
        ownershipPlanByDomKey = new java.util.HashMap<>();
        ownershipPlanByFileBoundsKey = new java.util.HashMap<>();
        ownershipPlanByFileKey = new java.util.HashMap<>();
        java.util.Map<String, RenderedGroup> renderedByCandidateId = renderedGroupsByCandidateId();
        for (ObjectPlan plan : ownershipPlans) {
            if (plan == null) continue;
            RenderedGroup rendered = plan.candidateId != null
                    ? renderedByCandidateId.get(plan.candidateId)
                    : null;
            if (rendered != null && rendered.renderUnitId() != null
                    && !rendered.renderUnitId().isEmpty()) {
                putPreferred(ownershipPlanByRenderUnitKey,
                        renderUnitKey(rendered.renderUnitId()),
                        plan);
            }
            if (plan.renderId != null) {
                putPreferred(ownershipPlanByRenderFileKey,
                        renderFileKey(plan.pageIndex, plan.placement, plan.renderId, plan.file),
                        plan);
                putPreferred(ownershipPlanByRenderKey,
                        renderKey(plan.pageIndex, plan.placement, plan.renderId),
                        plan);
            }
            putPreferred(ownershipPlanByCandidateKey,
                    candidateKey(plan.pageIndex, plan.placement, plan.candidateId),
                    plan);
            putPreferred(ownershipPlanByDomKey, domKey(plan.pageIndex, plan.placement, plan.domId), plan);
            putPreferred(ownershipPlanByFileBoundsKey,
                    fileBoundsKey(plan.pageIndex, plan.placement, plan.file, plan.bounds),
                    plan);
            putPreferred(ownershipPlanByFileKey, fileKey(plan.pageIndex, plan.placement, plan.file), plan);
        }
        ownershipPlanIndexDirty = false;
        ownershipPlanRenderedCache.clear();
    }

    private static int[] renderedSourceObjectIds(RenderedGroup rg) {
        if (rg == null) return new int[0];
        if (rg.sourceObjectIds() != null && rg.sourceObjectIds().length > 0) {
            return rg.sourceObjectIds();
        }
        if (rg.editableTextFrameIds() != null && rg.editableTextFrameIds().length > 0) {
            return parseSourceObjectIds(rg.editableTextFrameIds());
        }
        return new int[0];
    }

    private static int[] parseSourceObjectIds(String[] sourceIds) {
        if (sourceIds == null || sourceIds.length == 0) return new int[0];
        java.util.List<Integer> parsed = new java.util.ArrayList<>();
        for (String sourceId : sourceIds) {
            if (sourceId == null) continue;
            try {
                int parsedId = Integer.parseInt(sourceId);
                parsed.add(parsedId);
            } catch (NumberFormatException ignored) {
                // ignore non-decimal text-frame ids (e.g. malformed/hex-like ids)
            }
        }
        if (parsed.isEmpty()) return new int[0];
        int[] out = new int[parsed.size()];
        for (int i = 0; i < parsed.size(); i++) {
            out[i] = parsed.get(i);
        }
        return out;
    }

    private boolean isTextFrameOwnedButPlanMissingForRendered(RenderedGroup rg) {
        if (rg == null) return false;
        if (rg.hasEditableTextHiddenFromPng()) return true;
        if (Boolean.TRUE.equals(rg.containsEditableText()) && rg.sourceObjectIds() != null
                && rg.sourceObjectIds().length > 0
                && "hwpx_tf".equals(rg.textOwner())) {
            return true;
        }
        return hasHwpxOwnedTextSourceId(rg);
    }

    private boolean appearsToHaveHwpxTextOwnership(RenderedGroup rg) {
        if (rg == null || resolvedData == null) return false;
        if (rg.hasEditableTextHiddenFromPng()) return true;
        if (Boolean.TRUE.equals(rg.containsEditableText())
                && "hwpx_tf".equals(rg.textOwner())) {
            return true;
        }
        if (resolvedData.shouldUseCompletePngForSimpleButtonLabel(rg)) return false;
        for (int sourceId : renderedSourceObjectIds(rg)) {
            if (resolvedData.isEditableTextFrame(String.valueOf(sourceId))) return true;
        }
        return hasHwpxOwnedTextSourceId(rg);
    }

    private boolean hasHwpxOwnedTextSourceId(RenderedGroup rg) {
        if (resolvedData == null || rg == null) return false;
        for (int sourceId : renderedSourceObjectIds(rg)) {
            if (sourceId < 0) continue;
            if (resolvedData.isHwpxOwnedTextFrame(String.valueOf(sourceId))) {
                return true;
            }
        }
        return false;
    }

    private static void putPreferred(java.util.Map<String, ObjectPlan> map, String key, ObjectPlan plan) {
        if (key == null) return;
        ObjectPlan existing = map.get(key);
        if (existing == null || shouldPreferOwnershipPlan(plan, existing)) {
            map.put(key, plan);
        }
    }

    private static boolean shouldPreferOwnershipPlan(ObjectPlan candidate, ObjectPlan existing) {
        if (candidate == null) return false;
        if (existing == null) return true;
        if (candidate.hasVisibleVisual() != existing.hasVisibleVisual()) {
            return candidate.hasVisibleVisual();
        }
        boolean candidateSimple = candidate.kind != null && candidate.kind.startsWith("simple_button_label:");
        boolean existingSimple = existing.kind != null && existing.kind.startsWith("simple_button_label:");
        if (candidateSimple != existingSimple) return candidateSimple;
        return false;
    }

    private static String renderedPlanCacheKey(RenderedGroup rg) {
        Placement placement = placementOf(rg);
        return rg.pageIndex() + "|" + placement + "|" + rg.id() + "|"
                + nullSafe(rg.file()) + "|" + roundedBoundsKey(rg.bounds());
    }

    private static String renderFileKey(int pageIndex, Placement placement, int renderId, String file) {
        return pageIndex + "|" + placement + "|" + renderId + "|" + nullSafe(file);
    }

    private static String renderKey(int pageIndex, Placement placement, int renderId) {
        return pageIndex + "|" + placement + "|" + renderId;
    }

    private static String candidateKey(int pageIndex, Placement placement, String candidateId) {
        if (candidateId == null || candidateId.isEmpty()) return null;
        return pageIndex + "|" + placement + "|" + candidateId;
    }

    private java.util.Map<String, RenderedGroup> renderedGroupsByCandidateId() {
        java.util.Map<String, RenderedGroup> out = new java.util.HashMap<>();
        java.util.List<RenderedGroup> groups = resolvedData != null
                ? resolvedData.allRenderedFloatingItems()
                : null;
        if (groups == null) return out;
        for (RenderedGroup rg : groups) {
            if (rg == null || rg.candidateId() == null || rg.candidateId().isEmpty()) continue;
            out.putIfAbsent(rg.candidateId(), rg);
        }
        return out;
    }

    private static String renderUnitKey(String renderUnitId) {
        if (renderUnitId == null || renderUnitId.isEmpty()) return null;
        return renderUnitId;
    }

    private static String domKey(int pageIndex, Placement placement, int domId) {
        return pageIndex + "|" + placement + "|" + domId;
    }

    private static String fileBoundsKey(int pageIndex, Placement placement, String file, double[] bounds) {
        if (bounds == null || bounds.length < 4) return null;
        return pageIndex + "|" + placement + "|" + nullSafe(file) + "|" + roundedBoundsKey(bounds);
    }

    private static String fileKey(int pageIndex, Placement placement, String file) {
        return pageIndex + "|" + placement + "|" + nullSafe(file);
    }

    private static String roundedBoundsKey(double[] bounds) {
        if (bounds == null || bounds.length < 4) return "";
        return Math.round(bounds[0] * 100.0) + ","
                + Math.round(bounds[1] * 100.0) + ","
                + Math.round(bounds[2] * 100.0) + ","
                + Math.round(bounds[3] * 100.0);
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    private static Placement placementOf(RenderedGroup rg) {
        if (rg != null && ("inline_object".equals(rg.type()) || "inline_object".equals(rg.itemType()))) {
            return Placement.INLINE;
        }
        return Placement.FLOATING;
    }

    public void recordRenderedDecision(RenderedGroup rg, String phase, String decision, String detail) {
        recordRenderedDecision(rg, null, phase, decision, detail);
    }

    public void recordRenderedDecision(RenderedGroup rg, ObjectPlan explicitPlan, String phase, String decision, String detail) {
        if (rg == null) return;
        ObjectPlan plan = explicitPlan != null ? explicitPlan : findOwnershipPlanForRendered(rg);
        String executedFile = plan != null && plan.file != null && !plan.file.isEmpty()
                ? plan.file
                : rg.file();
        StringBuilder sb = new StringBuilder(256);
        sb.append('{')
                .append("\"phase\":\"").append(jsonEscape(phase)).append("\",")
                .append("\"decision\":\"").append(jsonEscape(decision)).append("\",")
                .append("\"detail\":\"").append(jsonEscape(detail)).append("\",")
                .append("\"id\":").append(rg.id()).append(',')
                .append("\"pageIndex\":").append(rg.pageIndex()).append(',')
                .append("\"file\":\"").append(jsonEscape(executedFile)).append("\",")
                .append("\"sourceFile\":\"").append(jsonEscape(rg.file())).append("\",")
                .append("\"exportUnitId\":\"").append(jsonEscape(rg.exportUnitId())).append("\",")
                .append("\"candidateId\":\"").append(jsonEscape(rg.candidateId())).append("\",")
                .append("\"planPassId\":\"").append(jsonEscape(rg.planPassId())).append("\",")
                .append("\"slotRole\":\"").append(jsonEscape(rg.slotRole())).append("\",")
                .append("\"reason\":\"").append(jsonEscape(rg.reason())).append("\",")
                .append("\"itemType\":\"").append(jsonEscape(rg.itemType())).append("\",")
                .append("\"visualOwner\":\"").append(jsonEscape(rg.visualOwner())).append("\",")
                .append("\"textOwner\":\"").append(jsonEscape(rg.textOwner())).append("\",")
                .append("\"placementAllowed\":").append(Boolean.FALSE.equals(rg.placementAllowed()) ? "false" : "true");
        if (plan != null) {
            sb.append(",\"planTextAction\":\"").append(plan.textAction).append("\",")
                    .append("\"planVisualAction\":\"").append(plan.visualAction).append("\",")
                    .append("\"planVisualLayer\":\"").append(plan.visualLayer).append("\",")
                    .append("\"planPolicyLayer\":\"").append(plan.visualPolicyLayer()).append("\",")
                    .append("\"planPlacement\":\"").append(plan.placement).append("\",")
                    .append("\"planMaterialization\":\"").append(plan.materialization).append("\",")
                    .append("\"objectPlanCandidateId\":\"").append(jsonEscape(plan.candidateId)).append("\",")
                    .append("\"objectPlanPassId\":\"").append(jsonEscape(plan.planPassId)).append("\",")
                    .append("\"objectPlanSlotRole\":\"").append(jsonEscape(plan.slotRole)).append("\",")
                    .append("\"planZOrder\":").append(plan.zOrder).append(',')
                    .append("\"planReason\":\"").append(jsonEscape(plan.reason)).append("\",")
                    .append("\"planFile\":\"").append(jsonEscape(plan.file)).append("\"");
        }
        double[] b = plan != null && plan.bounds != null && plan.bounds.length >= 4
                ? plan.bounds
                : rg.bounds();
        if (b != null && b.length >= 4) {
            sb.append(",\"bounds\":[")
                    .append(b[0]).append(',')
                    .append(b[1]).append(',')
                    .append(b[2]).append(',')
                    .append(b[3]).append(']');
        }
        double[] sourceBounds = rg.bounds();
        if (sourceBounds != null && sourceBounds.length >= 4 && !sameBounds(sourceBounds, b)) {
            sb.append(",\"sourceBounds\":[")
                    .append(sourceBounds[0]).append(',')
                    .append(sourceBounds[1]).append(',')
                    .append(sourceBounds[2]).append(',')
                    .append(sourceBounds[3]).append(']');
        }
        if (plan != null && plan.renderSourceBounds != null && plan.renderSourceBounds.length >= 4) {
            sb.append(",\"renderSourceBounds\":[")
                    .append(plan.renderSourceBounds[0]).append(',')
                    .append(plan.renderSourceBounds[1]).append(',')
                    .append(plan.renderSourceBounds[2]).append(',')
                    .append(plan.renderSourceBounds[3]).append(']');
        }
        sb.append('}');
        renderDecisionLines.add(sb.toString());
        ownershipTraceLines.add(renderedDecisionTraceLine(rg, plan, phase, decision, detail, executedFile));
    }

    private static String renderedDecisionTraceLine(
            RenderedGroup rg,
            ObjectPlan plan,
            String phase,
            String decision,
            String detail,
            String executedFile) {
        return tsv(phase)
                + "\t" + tsv(decision)
                + "\t" + rg.pageIndex()
                + "\t" + rg.id()
                + "\t" + tsv(rg.exportUnitId())
                + "\t" + tsv(rg.candidateId())
                + "\t" + tsv(rg.planPassId())
                + "\t" + tsv(rg.slotRole())
                + "\t" + tsv(rg.file())
                + "\t" + tsv(executedFile)
                + "\t" + (plan != null ? plan.domId : "")
                + "\t" + tsv(plan != null ? plan.candidateId : "")
                + "\t" + tsv(plan != null ? plan.textAction.name() : "")
                + "\t" + tsv(plan != null ? plan.visualAction.name() : "")
                + "\t" + tsv(plan != null ? plan.placement.name() : "")
                + "\t" + tsv(plan != null ? plan.visualLayer.name() : "")
                + "\t" + tsv(plan != null ? plan.materialization.name() : "")
                + "\t" + tsv(plan != null ? ObjectPlan.intArrayJson(plan.sourceObjectIds) : "")
                + "\t" + tsv(plan != null ? ObjectPlan.intArrayJson(plan.exportSourceObjectIds) : "")
                + "\t" + tsv(plan != null ? ObjectPlan.intArrayJson(plan.hiddenVisualSourceObjectIds) : "")
                + "\t" + tsv(detail);
    }

    private static String tsv(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static boolean sameBounds(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(a[i] - b[i]) > 0.0001) return false;
        }
        return true;
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
