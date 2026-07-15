package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.StylePropertyResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.AnchoredTablePlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.CoordinateSpace;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.PolicyLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.SimpleButtonLabelPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
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
        public final String fontStyle;
        public final Double fontSize;
        public final Double horizontalScale;
        public final String underlineColor;

        public ParagraphStyleContext(String fillColor, Double tracking, String fontFamily,
                                     String fontStyle, Double fontSize, Double horizontalScale, String underlineColor) {
            this.fillColor = fillColor;
            this.tracking = tracking;
            this.fontFamily = fontFamily;
            this.fontStyle = fontStyle;
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
    private java.util.Map<String, ObjectPlan> ownershipPlanByCandidateId;
    private java.util.Map<String, ObjectPlan> ownershipPlanByObjectPlanId;
    private java.util.Map<String, ObjectPlan> ownershipPlanByDomKey;
    private java.util.Map<String, ObjectPlan> ownershipPlanByFileBoundsKey;
    private java.util.Map<String, ObjectPlan> ownershipPlanByFileKey;
    private java.util.Set<String> ambiguousRenderedCandidateIds;
    private java.util.Set<String> ambiguousRenderedRenderUnitIds;
    private java.util.Map<Integer, java.util.List<ObjectPlan>> ownershipPlansByExactDomId;
    private java.util.Map<Integer, java.util.List<ObjectPlan>> ownershipPlansByRenderId;
    private java.util.Map<Integer, java.util.List<ObjectPlan>> ownershipPlansBySourceObjectId;
    private java.util.Map<Integer, java.util.List<ObjectPlan>> ownershipPlansByVisualSourceObjectId;
    private java.util.Map<Integer, java.util.List<ObjectPlan>> ownershipPlansByStyleSourceObjectId;
    private java.util.Map<Integer, java.util.List<ObjectPlan>> ownershipPlansByOwnedTextFrameId;
    private java.util.Map<String, java.util.List<ObjectPlan>> ownershipPlansByOwnedTextFrameKey;
    private java.util.Map<Integer, java.util.List<ObjectPlan>> ownershipPlansByAnyObjectId;
    private final java.util.Map<String, java.util.Set<String>> descendantSetCache = new java.util.HashMap<>();
    private boolean ownershipPlanIndexDirty = true;

    public boolean hasStage1ObjectPlans() {
        return ownershipPlans != null && !ownershipPlans.isEmpty();
    }

    /**
     * Stage 1 ObjectPlan이 있는 실행에서는 synthetic master/off-canvas clone
     * TextFrame을 실행 단계 fallback만으로 살리지 않는다.
     *
     * <p>Clone id는 보통 {@code 2453_pi20}, {@code 17037_oc24}처럼 비숫자
     * key를 갖는다. Stage 1이 {@code ownedTextFrameIdKeys} 또는
     * {@code sourceBundleKey}로 명시한 plan이 있으면 그대로 실행하고, plan이
     * 없으면 visible HWPX text owner가 아니므로 skip한다. 단, extractor가
     * {@code masterSpecialType=pagenum}으로 확정한 페이지 번호 clone은 source
     * metadata의 진실로 보존한다.</p>
     */
    public boolean shouldSkipPlanlessSyntheticCloneTextFrame(
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame tf) {
        if (tf == null || !hasStage1ObjectPlans()) return false;
        String textFrameId = tf.id();
        if (!isSyntheticCloneTextFrameId(textFrameId)) return false;
        if (isSyntheticPageNumberClone(tf)) return false;
        if (findAnyTextFrameOwnershipPlan(textFrameId) != null) return false;
        return tf.isMasterInstance() || textFrameId.contains("_oc");
    }

    private static boolean isSyntheticCloneTextFrameId(String textFrameId) {
        if (textFrameId == null || textFrameId.isEmpty()) return false;
        return textFrameId.contains("_pi") || textFrameId.contains("_oc");
    }

    private static boolean isSyntheticPageNumberClone(
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame tf) {
        if (tf == null) return false;
        return "pagenum".equals(tf.masterSpecialType());
    }

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

    public boolean isTableStyleOwnedByObjectPlan(String tableSourceId) {
        Integer parsed = parseDecimalId(tableSourceId);
        if (parsed == null || ownershipPlans == null || ownershipPlans.isEmpty()) return false;
        ensureOwnershipPlanIndexes();
        if (hasTableStylePlan(ownershipPlansBySourceObjectId.get(parsed), parsed)) return true;
        if (hasTableStylePlan(ownershipPlansByStyleSourceObjectId.get(parsed), parsed)) return true;
        if (hasTableStylePlan(ownershipPlansByAnyObjectId.get(parsed), parsed)) return true;
        return false;
    }

    private static boolean hasTableStylePlan(java.util.List<ObjectPlan> plans, int sourceId) {
        if (plans == null || plans.isEmpty()) return false;
        for (ObjectPlan plan : plans) {
            if (!isTableStyleObjectPlan(plan)) continue;
            if (plan.domId == sourceId
                    || (plan.renderId != null && plan.renderId == sourceId)) {
                return true;
            }
            if (containsInt(plan.sourceObjectIds, sourceId)) return true;
            if (containsInt(plan.visualSourceObjectIds, sourceId)) return true;
            if (containsInt(plan.styleSourceObjectIds, sourceId)) return true;
            if (containsInt(plan.ownedTextFrameIds, sourceId)) return true;
        }
        return false;
    }

    private static boolean isTableStyleObjectPlan(ObjectPlan plan) {
        return plan != null
                && (plan.materialization == Materialization.HWPX_TABLE_STYLE
                || plan.visualAction == VisualAction.PLACE_TABLE_STYLE);
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
        descendantSetCache.clear();
        ownershipPlanByRenderFileKey = null;
        ownershipPlanByRenderUnitKey = null;
        ownershipPlanByRenderKey = null;
        ownershipPlanByCandidateKey = null;
        ownershipPlanByCandidateId = null;
        ownershipPlanByObjectPlanId = null;
        ownershipPlanByDomKey = null;
        ownershipPlanByFileBoundsKey = null;
        ownershipPlanByFileKey = null;
        ambiguousRenderedCandidateIds = null;
        ambiguousRenderedRenderUnitIds = null;
        ownershipPlansByExactDomId = null;
        ownershipPlansByRenderId = null;
        ownershipPlansBySourceObjectId = null;
        ownershipPlansByVisualSourceObjectId = null;
        ownershipPlansByStyleSourceObjectId = null;
        ownershipPlansByOwnedTextFrameId = null;
        ownershipPlansByOwnedTextFrameKey = null;
        ownershipPlansByAnyObjectId = null;
    }

    public void rebuildOwnershipPlanLinesFromPlans() {
        ownershipPlanLines.clear();
        for (java.util.List<AnchoredTablePlan> grouped : anchoredTablePlansByOwnerTextFrameId.values()) {
            if (grouped == null) continue;
            for (AnchoredTablePlan plan : grouped) {
                if (plan != null) ownershipPlanLines.add(plan.toJson());
            }
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
        if (hasStage1ObjectPlans()) {
            return false;
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
        ensureOwnershipPlanIndexes();
        ObjectPlan exact = firstPlan(ownershipPlansByExactDomId.get(domId));
        if (exact != null) return exact;
        ObjectPlan source = firstPlan(ownershipPlansBySourceObjectId.get(domId));
        if (source != null) return source;
        return null;
    }

    public ObjectPlan findTextFrameOwnershipPlan(int domId) {
        return findHwpxTextFrameOwnershipPlan(domId);
    }

    public ObjectPlan findAnyTextFrameOwnershipPlan(int domId) {
        if (domId < 0) return null;
        ObjectPlan fallback = null;
        ensureOwnershipPlanIndexes();
        java.util.List<ObjectPlan> candidates = ownershipPlansByExactDomId.get(domId);
        if (candidates == null || candidates.isEmpty()) return null;
        for (ObjectPlan plan : candidates) {
            if (plan == null) continue;
            if (!isTextFrameOwnershipPlan(plan, domId)) continue;
            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) return plan;
            if (fallback == null) fallback = plan;
        }
        return fallback;
    }

    public ObjectPlan findAnyTextFrameOwnershipPlan(String textFrameId) {
        if (textFrameId == null || textFrameId.isEmpty()) return null;
        ensureOwnershipPlanIndexes();
        ObjectPlan byKey = findAnyTextFrameOwnershipPlanByKey(textFrameId);
        if (byKey != null) return byKey;
        Integer parsed = parseDecimalId(textFrameId);
        if (parsed != null) return findAnyTextFrameOwnershipPlan(parsed);
        return null;
    }

    private ObjectPlan findAnyTextFrameOwnershipPlanByKey(String textFrameId) {
        if (textFrameId == null || textFrameId.isEmpty()) return null;
        ObjectPlan fallback = null;
        java.util.List<ObjectPlan> candidates = ownershipPlansByOwnedTextFrameKey.get(textFrameId);
        if (candidates == null || candidates.isEmpty()) return null;
        for (ObjectPlan plan : candidates) {
            if (plan == null) continue;
            if (!isTextFrameOwnershipPlan(plan, textFrameId)) continue;
            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) return plan;
            if (fallback == null) fallback = plan;
        }
        return fallback;
    }

    public ObjectPlan findHwpxTextFrameOwnershipPlan(int domId) {
        if (domId < 0) return null;
        ensureOwnershipPlanIndexes();
        java.util.List<ObjectPlan> candidates = ownershipPlansByExactDomId.get(domId);
        if (candidates == null || candidates.isEmpty()) return null;
        for (ObjectPlan plan : candidates) {
            if (plan == null) continue;
            if (!isTextFrameOwnershipPlan(plan, domId)) continue;
            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) return plan;
        }
        return null;
    }

    public ObjectPlan findHwpxTextFrameOwnershipPlan(String textFrameId) {
        if (textFrameId == null || textFrameId.isEmpty()) return null;
        ensureOwnershipPlanIndexes();
        ObjectPlan byKey = findHwpxTextFrameOwnershipPlanByKey(textFrameId);
        if (byKey != null) return byKey;
        Integer parsed = parseDecimalId(textFrameId);
        if (parsed != null) return findHwpxTextFrameOwnershipPlan(parsed);
        return null;
    }

    private ObjectPlan findHwpxTextFrameOwnershipPlanByKey(String textFrameId) {
        if (textFrameId == null || textFrameId.isEmpty()) return null;
        java.util.List<ObjectPlan> candidates = ownershipPlansByOwnedTextFrameKey.get(textFrameId);
        if (candidates == null || candidates.isEmpty()) return null;
        for (ObjectPlan plan : candidates) {
            if (plan == null) continue;
            if (!isTextFrameOwnershipPlan(plan, textFrameId)) continue;
            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) return plan;
        }
        return null;
    }

    private static boolean isTextFrameOwnershipPlan(ObjectPlan plan, int domId) {
        if (plan == null || domId < 0) return false;
        if (plan.kind != null) {
            String kind = plan.kind;
            if (kind.startsWith("text_frame")
                    || kind.startsWith("planner_declared_text_frame")
                    || kind.contains(":TextFrame")) {
                return true;
            }
        }
        if (plan.materialization == Materialization.HWPX_TEXT
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) {
            return true;
        }
        if (plan.ownedTextFrameIds != null) {
            for (int textFrameId : plan.ownedTextFrameIds) {
                if (textFrameId == domId) return true;
            }
        }
        return plan.sourceBundleKey != null
                && plan.sourceBundleKey.equals("textFrame." + domId);
    }

    private static boolean isTextFrameOwnershipPlan(ObjectPlan plan, String textFrameId) {
        if (plan == null || textFrameId == null || textFrameId.isEmpty()) return false;
        Integer parsed = parseDecimalId(textFrameId);
        if (parsed != null) return isTextFrameOwnershipPlan(plan, parsed);
        if (containsString(plan.ownedTextFrameIdKeys, textFrameId)) return true;
        return plan.sourceBundleKey != null
                && plan.sourceBundleKey.equals("textFrame." + textFrameId);
    }

    public boolean ownershipPlanPlacesFloatingHwpxText(int domId) {
        if (isTextFrameOwnedByTextShellPlanWithPlacement(domId, Placement.INLINE)) {
            return false;
        }
        if (isTextFrameOwnedByTextShellPlanWithPlacement(domId, Placement.FLOATING)) {
            return true;
        }
        ObjectPlan plan = findHwpxTextFrameOwnershipPlan(domId);
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.placement == Placement.FLOATING;
    }

    public boolean ownershipPlanPlacesFloatingHwpxText(String textFrameId) {
        Integer parsed = parseDecimalId(textFrameId);
        if (parsed != null) return ownershipPlanPlacesFloatingHwpxText(parsed);
        ObjectPlan plan = findHwpxTextFrameOwnershipPlan(textFrameId);
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
        ObjectPlan plan = findHwpxTextFrameOwnershipPlan(domId);
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.placement == Placement.INLINE;
    }

    public boolean isTextFrameOwnedByTextShellPlan(int domId) {
        if (domId < 0 || ownershipPlans == null) return false;
        ensureOwnershipPlanIndexes();
        for (ObjectPlan plan : ownershipPlansForTextShellSource(domId)) {
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
        ensureOwnershipPlanIndexes();
        java.util.List<ObjectPlan> candidates = ownershipPlansByStyleSourceObjectId.get(domId);
        if (candidates == null || candidates.isEmpty()) return false;
        for (ObjectPlan plan : candidates) {
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
        ensureOwnershipPlanIndexes();
        for (ObjectPlan plan : ownershipPlansForTextShellSource(domId)) {
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
        if (plan == null) return false;
        if (ShellRole.isTextShell(plan)
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0
                && (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                || plan.visualAction == VisualAction.DROP_VISUAL
                || plan.hasVisibleVisual())) {
            return true;
        }
        return plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.visualAction == VisualAction.DROP_VISUAL
                && "container_backdrop_absorbed_by_table_style".equals(plan.reason)
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    public boolean isVisualSourceClaimedByVisibleTextShellPlan(int sourceId) {
        if (sourceId < 0 || ownershipPlans == null) return false;
        ensureOwnershipPlanIndexes();
        java.util.List<ObjectPlan> candidates = ownershipPlansByVisualSourceObjectId.get(sourceId);
        if (candidates == null || candidates.isEmpty()) return false;
        for (ObjectPlan plan : candidates) {
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
                && plan.visualAction == VisualAction.PLACE_INLINE_PNG
                && plan.placement == Placement.INLINE
                && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                && plan.materialization == Materialization.COMPLETE_PNG;
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
        return plan.visualLayer != VisualLayer.PAGE_BACKGROUND;
    }

    public ObjectPlan findOwnershipPlanForRendered(RenderedGroup rg) {
        if (rg == null) return null;
        String cacheKey = renderedPlanCacheKey(rg);
        if (ownershipPlanRenderedCache.containsKey(cacheKey)) {
            return ownershipPlanRenderedCache.get(cacheKey);
        }
        ensureOwnershipPlanIndexes();
        Placement placement = placementOf(rg);
        boolean ambiguousCandidate = isAmbiguousRenderedCandidate(rg.candidateId());
        boolean ambiguousRenderUnit = isAmbiguousRenderedRenderUnit(rg.renderUnitId());
        ObjectPlan plan = plannerDeclaredRenderUnitPlanForRendered(rg, placement);
        if (plan == null) {
            plan = plannerDeclaredCandidatePlanForRendered(rg, placement);
        }
        if (plan == null) {
            plan = findRenderedPlanForPlacement(rg, placement, false);
        }
        if (plan == null) {
            plan = findRenderedPlanForPlacement(rg, placement,
                    !ambiguousCandidate && !ambiguousRenderUnit);
        }
        if (plan == null && !ambiguousRenderUnit && !ambiguousCandidate) {
            ObjectPlan renderUnitPlan = renderedOnly(ownershipPlanByRenderUnitKey.get(renderUnitKey(rg.renderUnitId())));
            if (renderUnitPlan != null && renderUnitPlan.placement == placement) {
                plan = renderUnitPlan;
            }
        }
        if (plan == null && !ambiguousCandidate) {
            plan = renderedOnly(ownershipPlanByCandidateId.get(candidateIdKey(rg.candidateId())));
        }
        plan = localizeRenderedPlan(plan, rg, ambiguousCandidate || ambiguousRenderUnit);
        ownershipPlanRenderedCache.put(cacheKey, plan);
        return plan;
    }

    private ObjectPlan plannerDeclaredRenderUnitPlanForRendered(RenderedGroup rg, Placement placement) {
        if (rg == null || placement == null) return null;
        String objectPlanId = objectPlanIdFromRenderUnitId(rg.renderUnitId());
        if (objectPlanId == null || objectPlanId.isEmpty()) return null;
        ObjectPlan plan = renderedOnly(ownershipPlanByObjectPlanId.get(objectPlanId));
        if (plan == null) {
            plan = renderedOnly(ownershipPlanByObjectPlanId.get(sourceBundleKeyFromObjectPlanId(objectPlanId)));
        }
        if (isPlannerDeclaredOwnershipPlan(plan)
                && plannerDeclaredPlanCompatibleWithRendered(plan, rg)) {
            return plan;
        }
        return null;
    }

    private ObjectPlan plannerDeclaredCandidatePlanForRendered(RenderedGroup rg, Placement placement) {
        if (rg == null || placement == null) return null;
        ObjectPlan plan = renderedOnly(ownershipPlanByCandidateKey.get(
                candidateKey(rg.pageIndex(), placement, rg.candidateId())));
        if (isPlannerDeclaredOwnershipPlan(plan)
                && plannerDeclaredPlanCompatibleWithRendered(plan, rg)) {
            return plan;
        }
        plan = renderedOnly(ownershipPlanByCandidateId.get(candidateIdKey(rg.candidateId())));
        if (isPlannerDeclaredOwnershipPlan(plan)
                && plannerDeclaredPlanCompatibleWithRendered(plan, rg)) {
            return plan;
        }
        return null;
    }

    private static boolean plannerDeclaredPlanCompatibleWithRendered(ObjectPlan plan, RenderedGroup rg) {
        if (plan == null || rg == null) return false;
        SlotChannel renderedChannel = slotChannelOf(rg);
        SlotChannel plannedChannel = slotChannelOf(plan);
        if (renderedChannel != SlotChannel.UNKNOWN
                && plannedChannel != SlotChannel.UNKNOWN
                && renderedChannel != plannedChannel) {
            return false;
        }
        if (renderedChannel == SlotChannel.CONTENT
                && renderedIsStrictSubsetOfPlannedVisualSources(plan, rg)) {
            return false;
        }
        return true;
    }

    private static boolean renderedIsStrictSubsetOfPlannedVisualSources(ObjectPlan plan, RenderedGroup rg) {
        if (plan == null || rg == null) return false;
        int[] renderedSourceIds = normalizedRenderedSourceObjectIds(rg);
        if (renderedSourceIds == null || renderedSourceIds.length == 0) return false;
        int[] plannedSourceIds = plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length > 0
                ? plan.visualSourceObjectIds
                : plan.sourceObjectIds;
        if (plannedSourceIds == null || plannedSourceIds.length == 0) return false;
        if (renderedSourceIds.length >= plannedSourceIds.length) return false;
        java.util.Set<Integer> planned = new java.util.HashSet<>();
        for (int id : plannedSourceIds) planned.add(id);
        boolean strictSubset = false;
        for (int id : renderedSourceIds) {
            if (!planned.contains(id)) return false;
            strictSubset = true;
        }
        return strictSubset;
    }

    private static SlotChannel slotChannelOf(RenderedGroup rg) {
        if (rg == null) return SlotChannel.UNKNOWN;
        String slotRole = nullSafe(rg.slotRole());
        if (slotRole.contains("CONTENT_VISUAL_SLOT")) return SlotChannel.CONTENT;
        if (slotRole.contains("SHELL_SLOT")
                || slotRole.contains("TEXTLESS_SHELL_SLOT")
                || slotRole.contains("shell_slot")) {
            return SlotChannel.SHELL;
        }
        String exportUnitId = nullSafe(rg.exportUnitId());
        if (exportUnitId.contains(".CONTENT_VISUAL_SLOT.")) return SlotChannel.CONTENT;
        if (exportUnitId.contains(".SHELL_SLOT.")
                || exportUnitId.contains(".TEXTLESS_SHELL_SLOT.")) {
            return SlotChannel.SHELL;
        }
        String textOwner = nullSafe(rg.textOwner());
        if ("hwpx_tf".equals(textOwner)) return SlotChannel.SHELL;
        if ("none".equals(textOwner)) return SlotChannel.CONTENT;
        return SlotChannel.UNKNOWN;
    }

    private static SlotChannel slotChannelOf(ObjectPlan plan) {
        if (plan == null) return SlotChannel.UNKNOWN;
        String slotRole = nullSafe(plan.slotRole);
        if (slotRole.contains("CONTENT_VISUAL_SLOT")) return SlotChannel.CONTENT;
        if (slotRole.contains("SHELL_SLOT")
                || slotRole.contains("TEXTLESS_SHELL_SLOT")
                || slotRole.contains("shell_slot")) {
            return SlotChannel.SHELL;
        }
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) return SlotChannel.SHELL;
        if (plan.visualAction == VisualAction.PLACE_FLOATING_PNG
                || plan.visualAction == VisualAction.PLACE_INLINE_PNG) {
            return SlotChannel.CONTENT;
        }
        return SlotChannel.UNKNOWN;
    }

    private enum SlotChannel {
        UNKNOWN,
        SHELL,
        CONTENT
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
        if (plan == null || plan.renderId == null) return null;
        if (plan.hasVisibleVisual() && !hasExecutableRenderedVisualContract(plan)) {
            return null;
        }
        return plan;
    }

    private static boolean hasExecutableRenderedVisualContract(ObjectPlan plan) {
        return plan != null
                && plan.textAction != null
                && plan.visualAction != null
                && plan.visualLayer != null
                && plan.placement != null
                && plan.materialization != null
                && plan.coordinateSpace != null;
    }

    private void ensureOwnershipPlanIndexes() {
        if (!ownershipPlanIndexDirty && ownershipPlanByRenderFileKey != null) return;
        ownershipPlanByRenderFileKey = new java.util.HashMap<>();
        ownershipPlanByRenderUnitKey = new java.util.HashMap<>();
        ownershipPlanByRenderKey = new java.util.HashMap<>();
        ownershipPlanByCandidateKey = new java.util.HashMap<>();
        ownershipPlanByCandidateId = new java.util.HashMap<>();
        ownershipPlanByObjectPlanId = new java.util.HashMap<>();
        ownershipPlanByDomKey = new java.util.HashMap<>();
        ownershipPlanByFileBoundsKey = new java.util.HashMap<>();
        ownershipPlanByFileKey = new java.util.HashMap<>();
        ambiguousRenderedCandidateIds = new java.util.HashSet<>();
        ambiguousRenderedRenderUnitIds = new java.util.HashSet<>();
        ownershipPlansByExactDomId = new java.util.HashMap<>();
        ownershipPlansByRenderId = new java.util.HashMap<>();
        ownershipPlansBySourceObjectId = new java.util.HashMap<>();
        ownershipPlansByVisualSourceObjectId = new java.util.HashMap<>();
        ownershipPlansByStyleSourceObjectId = new java.util.HashMap<>();
        ownershipPlansByOwnedTextFrameId = new java.util.HashMap<>();
        ownershipPlansByOwnedTextFrameKey = new java.util.HashMap<>();
        ownershipPlansByAnyObjectId = new java.util.HashMap<>();
        java.util.Map<String, RenderedGroup> renderedByCandidateId = renderedGroupsByCandidateId();
        for (ObjectPlan plan : ownershipPlans) {
            if (plan == null) continue;
            indexPlan(ownershipPlansByExactDomId, plan.domId, plan);
            indexPlan(ownershipPlansByAnyObjectId, plan.domId, plan);
            if (plan.renderId != null) {
                indexPlan(ownershipPlansByRenderId, plan.renderId, plan);
                indexPlan(ownershipPlansByAnyObjectId, plan.renderId, plan);
            }
            indexPlanArray(ownershipPlansBySourceObjectId, plan.sourceObjectIds, plan);
            indexPlanArray(ownershipPlansByAnyObjectId, plan.sourceObjectIds, plan);
            indexPlanArray(ownershipPlansByVisualSourceObjectId, plan.visualSourceObjectIds, plan);
            indexPlanArray(ownershipPlansByAnyObjectId, plan.visualSourceObjectIds, plan);
            indexPlanArray(ownershipPlansByStyleSourceObjectId, plan.styleSourceObjectIds, plan);
            indexPlanArray(ownershipPlansByAnyObjectId, plan.styleSourceObjectIds, plan);
            indexPlanArray(ownershipPlansByOwnedTextFrameId, plan.ownedTextFrameIds, plan);
            indexPlanStringArray(ownershipPlansByOwnedTextFrameKey, plan.ownedTextFrameIdKeys, plan);
            indexPlanArray(ownershipPlansByAnyObjectId, plan.ownedTextFrameIds, plan);
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
            putPreferred(ownershipPlanByCandidateId,
                    candidateIdKey(plan.candidateId),
                    plan);
            indexObjectPlanIdAliases(ownershipPlanByObjectPlanId, plan);
            putPreferred(ownershipPlanByDomKey, domKey(plan.pageIndex, plan.placement, plan.domId), plan);
            putPreferred(ownershipPlanByFileBoundsKey,
                    fileBoundsKey(plan.pageIndex, plan.placement, plan.file, plan.bounds),
                    plan);
            putPreferred(ownershipPlanByFileKey, fileKey(plan.pageIndex, plan.placement, plan.file), plan);
        }
        ownershipPlanIndexDirty = false;
        ownershipPlanRenderedCache.clear();
    }

    private ObjectPlan localizeRenderedPlan(ObjectPlan plan, RenderedGroup rg, boolean forceExactFragment) {
        if (plan == null || rg == null || !plan.hasVisibleVisual()) return plan;
        boolean fileDiffers = !nullSafe(plan.file).equals(nullSafe(rg.file()));
        boolean boundsDiffer = !sameBounds(plan.bounds, rg.bounds());
        boolean sourceDiffers = !sameIntArray(normalizedRenderedSourceObjectIds(rg), plan.visualSourceObjectIds)
                && !sameIntArray(normalizedRenderedSourceObjectIds(rg), plan.sourceObjectIds);
        if (!forceExactFragment && !fileDiffers && !boundsDiffer && !sourceDiffers) {
            return plan;
        }
        int[] localSourceIds = normalizedRenderedSourceObjectIds(rg);
        if (localSourceIds == null || localSourceIds.length == 0) {
            localSourceIds = plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length > 0
                    ? plan.visualSourceObjectIds
                    : plan.sourceObjectIds;
        }
        int[] localExportSourceIds = rg.exportSourceObjectIds();
        if (localExportSourceIds == null || localExportSourceIds.length == 0) {
            localExportSourceIds = localSourceIds;
        }
        ObjectPlan localized = plan.withRenderedVisual(
                plan.visualLayer,
                localSourceIds,
                plan.zOrder,
                appendPlanReason(plan.reason, "localized_render_fragment"),
                rg.file(),
                rg.bounds());
        localized = localized.withExtractionSourceObjectIds(localExportSourceIds, rg.hiddenVisualSourceObjectIds());
        String localizedLayerId = rg.layerId();
        String localizedLayerName = rg.layerName();
        int localizedLayerIndex = rg.layerIndex();
        if (localizedLayerId == null || localizedLayerId.isEmpty()) {
            localizedLayerId = plan.sourceLayerId;
        }
        if (localizedLayerName == null || localizedLayerName.isEmpty()) {
            localizedLayerName = plan.sourceLayerName;
        }
        if (localizedLayerIndex < 0) {
            localizedLayerIndex = plan.sourceLayerIndex;
        }
        if ((localizedLayerId == null || localizedLayerId.isEmpty()
                || localizedLayerName == null || localizedLayerName.isEmpty()
                || localizedLayerIndex < 0)
                && resolvedData != null) {
            for (int sourceId : localSourceIds) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem item =
                        resolvedData.getPageItem(String.valueOf(sourceId));
                if (item == null) continue;
                if ((localizedLayerId == null || localizedLayerId.isEmpty())
                        && item.layerId() != null && !item.layerId().isEmpty()) {
                    localizedLayerId = item.layerId();
                }
                if ((localizedLayerName == null || localizedLayerName.isEmpty())
                        && item.layerName() != null && !item.layerName().isEmpty()) {
                    localizedLayerName = item.layerName();
                }
                if (localizedLayerIndex < 0 && item.layerIndex() >= 0) {
                    localizedLayerIndex = item.layerIndex();
                }
                if (localizedLayerId != null && !localizedLayerId.isEmpty()
                        && localizedLayerName != null && !localizedLayerName.isEmpty()
                        && localizedLayerIndex >= 0) {
                    break;
                }
            }
        }
        localized = localized.withSourceLayerMetadata(
                localizedLayerId,
                localizedLayerName,
                localizedLayerIndex);
        if (rg.cropSourceBounds() != null && rg.cropSourceBounds().length >= 4) {
            localized = localized.withCropSourceBounds(rg.cropSourceBounds());
        }
        return localized;
    }

    private void ensureRenderedFallbackAmbiguityIndexes() {
        if (ambiguousRenderedCandidateIds != null && ambiguousRenderedRenderUnitIds != null) return;
        ambiguousRenderedCandidateIds = new java.util.HashSet<>();
        ambiguousRenderedRenderUnitIds = new java.util.HashSet<>();
        java.util.Map<String, RenderedGroup> candidateRepresentative = new java.util.HashMap<>();
        java.util.Map<String, RenderedGroup> renderUnitRepresentative = new java.util.HashMap<>();
        java.util.List<RenderedGroup> groups = resolvedData != null
                ? resolvedData.allRenderedFloatingItems()
                : null;
        if (groups == null) return;
        for (RenderedGroup rg : groups) {
            if (rg == null) continue;
            markRenderedAmbiguity(candidateRepresentative, ambiguousRenderedCandidateIds, rg.candidateId(), rg);
            markRenderedAmbiguity(renderUnitRepresentative, ambiguousRenderedRenderUnitIds, rg.renderUnitId(), rg);
        }
    }

    private boolean isAmbiguousRenderedCandidate(String candidateId) {
        ensureRenderedFallbackAmbiguityIndexes();
        return candidateId != null && !candidateId.isEmpty()
                && ambiguousRenderedCandidateIds.contains(candidateId);
    }

    private boolean isAmbiguousRenderedRenderUnit(String renderUnitId) {
        ensureRenderedFallbackAmbiguityIndexes();
        return renderUnitId != null && !renderUnitId.isEmpty()
                && ambiguousRenderedRenderUnitIds.contains(renderUnitId);
    }

    private static void markRenderedAmbiguity(
            java.util.Map<String, RenderedGroup> representativeByKey,
            java.util.Set<String> ambiguousKeys,
            String key,
            RenderedGroup rg) {
        if (key == null || key.isEmpty() || ambiguousKeys.contains(key)) return;
        RenderedGroup existing = representativeByKey.get(key);
        if (existing == null) {
            representativeByKey.put(key, rg);
            return;
        }
        if (sameRenderedGroup(existing, rg)) return;
        ambiguousKeys.add(key);
        representativeByKey.remove(key);
    }

    private static int[] normalizedRenderedSourceObjectIds(RenderedGroup rg) {
        if (rg == null) return null;
        int[] ids = rg.exportSourceObjectIds();
        if (ids == null || ids.length == 0) ids = rg.sourceObjectIds();
        if (ids == null || ids.length == 0) return null;
        int[] copy = java.util.Arrays.copyOf(ids, ids.length);
        java.util.Arrays.sort(copy);
        return copy;
    }

    private static boolean sameIntArray(int[] a, int[] b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        int[] aa = java.util.Arrays.copyOf(a, a.length);
        int[] bb = java.util.Arrays.copyOf(b, b.length);
        java.util.Arrays.sort(aa);
        java.util.Arrays.sort(bb);
        return java.util.Arrays.equals(aa, bb);
    }

    private static boolean sameBounds(double[] a, double[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(a[i] - b[i]) > 0.01) return false;
        }
        return true;
    }

    private static String appendPlanReason(String reason, String suffix) {
        if (suffix == null || suffix.isEmpty()) return reason;
        if (reason == null || reason.isEmpty()) return suffix;
        return reason.contains(suffix) ? reason : reason + ":" + suffix;
    }

    public java.util.List<ObjectPlan> ownershipPlansForObjectId(int objectId) {
        if (objectId < 0 || ownershipPlans == null || ownershipPlans.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        ensureOwnershipPlanIndexes();
        java.util.List<ObjectPlan> plans = ownershipPlansByAnyObjectId.get(objectId);
        if (plans == null || plans.isEmpty()) return java.util.Collections.emptyList();
        return plans;
    }

    public java.util.List<ObjectPlan> ownershipPlansForObjectTree(int objectId, int depth) {
        if (objectId < 0 || ownershipPlans == null || ownershipPlans.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        ensureOwnershipPlanIndexes();
        java.util.LinkedHashSet<ObjectPlan> out = new java.util.LinkedHashSet<>();
        addPlans(out, ownershipPlansByAnyObjectId.get(objectId));
        if (resolvedData != null && depth > 0) {
            java.util.Set<String> descendants = descendantSet(objectId, depth);
            if (descendants != null && !descendants.isEmpty()) {
                for (String idText : descendants) {
                    try {
                        int id = Integer.parseInt(idText);
                        addPlans(out, ownershipPlansByAnyObjectId.get(id));
                    } catch (NumberFormatException ignored) {
                        // resolved DOM ids are numeric in normal extraction output.
                    }
                }
            }
        }
        if (out.isEmpty()) return java.util.Collections.emptyList();
        return new java.util.ArrayList<>(out);
    }

    public java.util.List<ObjectPlan> ownershipPlansForOwnedTextFrame(int textFrameId) {
        if (textFrameId < 0 || ownershipPlans == null || ownershipPlans.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        ensureOwnershipPlanIndexes();
        java.util.List<ObjectPlan> plans = ownershipPlansByOwnedTextFrameId.get(textFrameId);
        if (plans == null || plans.isEmpty()) return java.util.Collections.emptyList();
        return plans;
    }

    public java.util.Set<String> descendantSet(int objectId, int depth) {
        if (objectId < 0) return java.util.Collections.emptySet();
        return descendantSet(String.valueOf(objectId), depth);
    }

    public java.util.Set<String> descendantSet(String objectId, int depth) {
        if (resolvedData == null || objectId == null || objectId.isEmpty() || depth <= 0) {
            return java.util.Collections.emptySet();
        }
        String key = objectId + "#" + depth;
        java.util.Set<String> cached = descendantSetCache.get(key);
        if (cached != null) return cached;
        java.util.Set<String> descendants = resolvedData.buildDescendantSet(objectId, depth);
        if (descendants == null || descendants.isEmpty()) {
            descendants = java.util.Collections.emptySet();
        } else {
            descendants = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(descendants));
        }
        descendantSetCache.put(key, descendants);
        return descendants;
    }

    private java.util.List<ObjectPlan> ownershipPlansForTextShellSource(int domId) {
        java.util.LinkedHashSet<ObjectPlan> out = new java.util.LinkedHashSet<>();
        addPlans(out, ownershipPlansByExactDomId.get(domId));
        addPlans(out, ownershipPlansByOwnedTextFrameId.get(domId));
        addPlans(out, ownershipPlansBySourceObjectId.get(domId));
        if (out.isEmpty()) return java.util.Collections.emptyList();
        return new java.util.ArrayList<>(out);
    }

    private static ObjectPlan firstPlan(java.util.List<ObjectPlan> plans) {
        if (plans == null || plans.isEmpty()) return null;
        for (ObjectPlan plan : plans) {
            if (plan != null) return plan;
        }
        return null;
    }

    private static void indexPlan(
            java.util.Map<Integer, java.util.List<ObjectPlan>> index,
            int id,
            ObjectPlan plan) {
        if (index == null || id < 0 || plan == null) return;
        java.util.List<ObjectPlan> plans = index.computeIfAbsent(id, k -> new java.util.ArrayList<>());
        if (!plans.contains(plan)) plans.add(plan);
    }

    private static void indexPlanArray(
            java.util.Map<Integer, java.util.List<ObjectPlan>> index,
            int[] ids,
            ObjectPlan plan) {
        if (index == null || ids == null || plan == null) return;
        for (int id : ids) {
            indexPlan(index, id, plan);
        }
    }

    private static void indexPlanStringArray(
            java.util.Map<String, java.util.List<ObjectPlan>> index,
            String[] ids,
            ObjectPlan plan) {
        if (index == null || ids == null || plan == null) return;
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            java.util.List<ObjectPlan> plans = index.computeIfAbsent(id, k -> new java.util.ArrayList<>());
            if (!plans.contains(plan)) plans.add(plan);
        }
    }

    private static Integer parseDecimalId(String value) {
        if (value == null || value.isEmpty()) return null;
        int hexStart = -1;
        int iIdx = value.indexOf('i');
        if (iIdx >= 0 && iIdx + 1 < value.length()) {
            hexStart = iIdx + 1;
        } else if (value.charAt(0) == 'u' || value.charAt(0) == 'U') {
            hexStart = 1;
        }
        if (hexStart >= 0) {
            int end = hexStart;
            while (end < value.length()) {
                char c = value.charAt(end);
                boolean hex = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if (!hex) break;
                end++;
            }
            if (end <= hexStart) return null;
            try {
                return Integer.parseInt(value.substring(hexStart, end), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean containsString(String[] values, String expected) {
        if (values == null || expected == null) return false;
        for (String value : values) {
            if (expected.equals(value)) return true;
        }
        return false;
    }

    private static boolean containsInt(int[] values, int expected) {
        if (values == null) return false;
        for (int value : values) {
            if (value == expected) return true;
        }
        return false;
    }

    private static void addPlans(
            java.util.LinkedHashSet<ObjectPlan> out,
            java.util.List<ObjectPlan> plans) {
        if (out == null || plans == null || plans.isEmpty()) return;
        for (ObjectPlan plan : plans) {
            if (plan != null) out.add(plan);
        }
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
        if (hasStage1ObjectPlans()) return false;
        if (rg.hasEditableTextHiddenFromPng()) return true;
        if (Boolean.TRUE.equals(rg.containsEditableText()) && rg.sourceObjectIds() != null
                && rg.sourceObjectIds().length > 0
                && "hwpx_tf".equals(rg.textOwner())) {
            return true;
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

    private static void indexObjectPlanIdAliases(java.util.Map<String, ObjectPlan> map, ObjectPlan plan) {
        if (map == null || plan == null) return;
        putPreferred(map, plan.objectPlanId, plan);
        String sourceBundleKey = plan.sourceBundleKey;
        if (sourceBundleKey == null || sourceBundleKey.isEmpty()) return;
        putPreferred(map, sourceBundleKey, plan);
        putPreferred(map, "objectPlan." + sourceBundleKey, plan);
    }

    private static String objectPlanIdFromRenderUnitId(String renderUnitId) {
        if (renderUnitId == null || renderUnitId.isEmpty()) return null;
        int index = renderUnitId.indexOf("objectPlan.");
        if (index < 0) return null;
        return renderUnitId.substring(index);
    }

    private static String sourceBundleKeyFromObjectPlanId(String objectPlanId) {
        if (objectPlanId == null || objectPlanId.isEmpty()) return null;
        String prefix = "objectPlan.";
        if (objectPlanId.startsWith(prefix)) {
            return objectPlanId.substring(prefix.length());
        }
        return objectPlanId;
    }

    private static boolean shouldPreferOwnershipPlan(ObjectPlan candidate, ObjectPlan existing) {
        if (candidate == null) return false;
        if (existing == null) return true;
        boolean candidatePlannerDeclared = isPlannerDeclaredOwnershipPlan(candidate);
        boolean existingPlannerDeclared = isPlannerDeclaredOwnershipPlan(existing);
        if (candidatePlannerDeclared != existingPlannerDeclared) {
            return candidatePlannerDeclared;
        }
        if (candidate.hasVisibleVisual() != existing.hasVisibleVisual()) {
            return candidate.hasVisibleVisual();
        }
        boolean candidateSimple = candidate.kind != null && candidate.kind.startsWith("simple_button_label:");
        boolean existingSimple = existing.kind != null && existing.kind.startsWith("simple_button_label:");
        if (candidateSimple != existingSimple) return candidateSimple;
        return false;
    }

    private static boolean isPlannerDeclaredOwnershipPlan(ObjectPlan plan) {
        if (plan == null) return false;
        if ("planner_declared_object_plan".equals(plan.reason)) return true;
        if ("direct_compact_story_inline_visual".equals(plan.reason)) return true;
        if ("direct_story_inline_text_style_marker".equals(plan.reason)) return true;
        return "direct_story_flow_inline_graphic_owner".equals(plan.reason)
                && plan.kind != null
                && plan.kind.startsWith("planner_declared_rendered:");
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

    private static String candidateIdKey(String candidateId) {
        if (candidateId == null || candidateId.isEmpty()) return null;
        return candidateId;
    }

    private java.util.Map<String, RenderedGroup> renderedGroupsByCandidateId() {
        java.util.Map<String, RenderedGroup> out = new java.util.HashMap<>();
        java.util.Set<String> ambiguous = new java.util.HashSet<>();
        java.util.List<RenderedGroup> groups = resolvedData != null
                ? resolvedData.allRenderedFloatingItems()
                : null;
        if (groups == null) return out;
        for (RenderedGroup rg : groups) {
            if (rg == null || rg.candidateId() == null || rg.candidateId().isEmpty()) continue;
            String candidateId = rg.candidateId();
            if (ambiguous.contains(candidateId)) continue;
            RenderedGroup existing = out.get(candidateId);
            if (existing == null) {
                out.put(candidateId, rg);
                continue;
            }
            if (sameRenderedGroup(existing, rg)) continue;
            out.remove(candidateId);
            ambiguous.add(candidateId);
        }
        return out;
    }

    private static boolean sameRenderedGroup(RenderedGroup a, RenderedGroup b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.pageIndex() != b.pageIndex()) return false;
        String aRenderUnitId = nullSafe(a.renderUnitId());
        String bRenderUnitId = nullSafe(b.renderUnitId());
        if (!aRenderUnitId.isEmpty() && aRenderUnitId.equals(bRenderUnitId)) return true;
        String aSlotIdentity = nullSafe(a.renderUnitSlotIdentityKey());
        String bSlotIdentity = nullSafe(b.renderUnitSlotIdentityKey());
        if (!aSlotIdentity.isEmpty() && aSlotIdentity.equals(bSlotIdentity)) return true;
        String aExportUnitId = nullSafe(a.exportUnitId());
        String bExportUnitId = nullSafe(b.exportUnitId());
        if (!aExportUnitId.isEmpty() && aExportUnitId.equals(bExportUnitId)) return true;
        if (a.id() != b.id()) return false;
        return nullSafe(a.file()).equals(nullSafe(b.file()));
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
        String placement = nullSafe(rg != null ? rg.placementRole() : null)
                .toUpperCase(java.util.Locale.ROOT);
        if ("INLINE".equals(placement)) return Placement.INLINE;
        if ("FLOATING".equals(placement)) return Placement.FLOATING;
        String renderUnitId = nullSafe(rg != null ? rg.renderUnitId() : null)
                .toUpperCase(java.util.Locale.ROOT);
        if (renderUnitId.contains("_INLINE_")) return Placement.INLINE;
        if (renderUnitId.contains("_FLOATING_")) return Placement.FLOATING;
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
        if (plan != null && plan.cropSourceBounds != null && plan.cropSourceBounds.length >= 4) {
            sb.append(",\"cropSourceBounds\":[")
                    .append(plan.cropSourceBounds[0]).append(',')
                    .append(plan.cropSourceBounds[1]).append(',')
                    .append(plan.cropSourceBounds[2]).append(',')
                    .append(plan.cropSourceBounds[3]).append(']');
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
                + "\t" + tsv(enumName(plan != null ? plan.textAction : null))
                + "\t" + tsv(enumName(plan != null ? plan.visualAction : null))
                + "\t" + tsv(enumName(plan != null ? plan.placement : null))
                + "\t" + tsv(enumName(plan != null ? plan.visualLayer : null))
                + "\t" + tsv(enumName(plan != null ? plan.materialization : null))
                + "\t" + tsv(plan != null ? ObjectPlan.intArrayJson(plan.sourceObjectIds) : "")
                + "\t" + tsv(plan != null ? ObjectPlan.intArrayJson(plan.exportSourceObjectIds) : "")
                + "\t" + tsv(plan != null ? ObjectPlan.intArrayJson(plan.hiddenVisualSourceObjectIds) : "")
                + "\t" + tsv(detail);
    }

    private static String enumName(Enum<?> value) {
        return value != null ? value.name() : "";
    }

    private static String tsv(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
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
