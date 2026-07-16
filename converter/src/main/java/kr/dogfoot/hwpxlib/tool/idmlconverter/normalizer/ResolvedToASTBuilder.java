package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase0.InfraSetup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1.MasterHashiraPlacer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase1.PageLayoutBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2.FramePlacer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.StoryConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4.TableBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_5.BulletInserter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.OwnershipPlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.OwnershipPlanValidator;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.AnchoredTablePlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.CoordinateSpace;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.SimpleButtonLabelPlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextLayoutContract;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDiagnostics;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDiagnosticsBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDiagnosticsWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDocumentBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowIndex;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStoryParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * IDML-Free 파이프라인: resolved.json만으로 ASTDocument를 빌드한다.
 * 기존 4-stage 파이프라인(Stage1~4 + ResolvedMerger + ASTPageProcessor)을 대체.
 *
 * 좌표 흐름: resolved.json (page-relative, mm) → scaleFactor → points → pointsToHwpunits() → HWPUNIT
 * AST에는 최종 HWPUNIT 좌표만 저장.
 */
public class ResolvedToASTBuilder {

    private final ResolvedData resolvedData;
    private final String basePath;       // resolved.json 부모 디렉토리
    private final double scaleFactor;    // mm → pt 변환 (resolved 좌표 → points)
    private final File idmlDir;          // IDML 압축 해제 디렉토리 (Story XML 로드용)
    private final int pngExportDpi;      // ExtendScript PNG 내보내기 해상도
    private final Map<String, IDMLStory> idmlStoryCache = new HashMap<>();
    private StylePropertyResolver styleResolver;
    private ASTDocument astDoc; // Phase 0에서 스타일 정의 접근용
    private Map<Integer, Integer> pageDocOffsetToSection; // document pageIndex → section list index
    private ResolvedBuildContext ctx; // build() 시작 시 1회 생성, 모든 phase가 공유

    // SPEC-016 Phase 2: 매칭 신뢰도 카운터 — build() 종료 시 stderr에 비율 출력
    // [0]=HIGH, [1]=MEDIUM, [2]=LOW. ctx.spec016Counts와 같은 배열을 공유.
    private final int[] spec016Counts = new int[3];

    // SPEC-015: --debug-ast 활성화 여부. true면 phase별로 새로 만든 블록에 DebugMeta.createdAt 자동 부여.
    private boolean debugAst;
    public ResolvedToASTBuilder debugAst(boolean v) { this.debugAst = v; return this; }

    // SPEC-017: 테이블 셀 품질 게이트 정책 (ConversionConfig에서 주입).
    private kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig.TableQualityGateConfig tableQualityGate;
    public ResolvedToASTBuilder tableQualityGate(kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig.TableQualityGateConfig v) {
        this.tableQualityGate = v; return this;
    }


    // Lazy-loaded IDML 인프라 (색상 fallback / 테이블 셀 변환용)
    private kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument idmlDocument;
    private kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver colorResolver;
    private kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader imageLoader;

    private void ensureIdmlDocument() {
        if (idmlDocument != null || idmlDir == null) return;
        try {
            try (ConversionTiming.Scope ignored = ConversionTiming.time("infra.idmlDocumentLoad")) {
                idmlDocument = kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLLoader.loadFromDirectory(idmlDir);
            }
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] IDML document load failed: " + e.getMessage());
        }
    }

    private void ensureColorInfra() {
        if (colorResolver != null || idmlDir == null) return;
        ensureIdmlDocument();
        if (idmlDocument == null) return;
        try {
            try (ConversionTiming.Scope ignored = ConversionTiming.time("infra.colorResolverInit")) {
                colorResolver = new kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver(idmlDocument);
            }
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] color resolver init failed: " + e.getMessage());
        }
    }

    private void ensureImageInfra() {
        if (imageLoader != null || idmlDir == null) return;
        ensureColorInfra();
        if (idmlDocument == null) return;
        try {
            // imageLoader: 테이블 셀 내 인라인 이미지/그래픽 렌더링용
            kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions opts = new kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions();
            opts.includeImages(true);
            if (resolvedData.basePath() != null) {
                opts.linksDirectory(resolvedData.basePath() + "/Links");
            }
            try (ConversionTiming.Scope ignored = ConversionTiming.time("infra.imageLoaderInit")) {
                imageLoader = new kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader(idmlDocument, opts);
            }
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] image infra init failed: " + e.getMessage());
        }
    }

    private void ensureIdmlInfra() {
        ensureImageInfra();
    }

    public ResolvedToASTBuilder(ResolvedData resolvedData) {
        this(resolvedData, null, 300);
    }

    public ResolvedToASTBuilder(ResolvedData resolvedData, File idmlDir) {
        this(resolvedData, idmlDir, 300);
    }

    public ResolvedToASTBuilder(ResolvedData resolvedData, File idmlDir, int pngExportDpi) {
        this.resolvedData = resolvedData;
        this.basePath = resolvedData.basePath();
        this.scaleFactor = resolvedData.scaleFactor();
        this.idmlDir = idmlDir;
        this.pngExportDpi = pngExportDpi;
    }

    /**
     * resolved.json → ASTDocument 변환.
     */
    public ASTDocument build() {
        ConversionTiming.Scope totalScope = ConversionTiming.time("phase2.resolvedAstBuilder.total");
        try {
        ASTDocument doc = new ASTDocument();
        this.astDoc = doc;
        doc.sourceFormat("Resolved");
        try (ConversionTiming.Scope ignored = ConversionTiming.time("phase2.resolvedAstBuilder.styleResolver")) {
            this.styleResolver = StylePropertyResolver.fromIdmlDir(idmlDir);
        }
        this.ctx = newContext();

        List<ASTSection> sections;
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage0.inputPrepare")) {
            sections = prepareInput(doc);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage1.textFlowIndex")) {
            prepareTextFlowIndex();
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage1.ownershipPlanner")) {
            planOwnership();
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2.textFlowDiagnostics")) {
            writeTextFlowDiagnostics();
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2.textBuilder")) {
            buildTextContent(sections);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2.textBuilder.masterHashiraPlaceholderResolver")) {
            Set<ASTBlock> before = snapshotBlocks(sections);
            MasterHashiraPlacer.resolveTextVariablePlaceholders(this.ctx, sections);
            traceAstProduction("Stage2.TextBuilder.masterHashiraPlaceholderResolver", sections, before);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.layoutPostprocess")) {
            postprocessLayout(sections);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage3.visualBuilder")) {
            placeVisuals(sections);
        }

        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.validate.ownershipPlan")) {
            OwnershipPlanValidator.validate(this.ctx);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.validate.writeDecisionLogs")) {
            writeOwnershipPlanLog();
            writeRenderDecisionLog();
        }
        System.err.println("[ResolvedToASTBuilder] Built " + sections.size() + " sections");
        reportSpec016Counts();
        return doc;
        } finally {
            totalScope.close();
        }
    }

    /**
     * Stage 0: 입력 인덱스와 페이지 골격만 만든다.
     *
     * <p>이 단계에서는 객체 ownership, PNG 배치 여부, TextFrame 배치 여부를
     * 결정하지 않는다. 마스터 페이지 객체도 장기적으로는 OwnershipPlanner의
     * 입력으로 넘겨 Stage 2/3에서 실행한다.</p>
     */
    private List<ASTSection> prepareInput(ASTDocument doc) {
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage0.inputPrepare.copyIdmlDefinitions")) {
            InfraSetup.copyIDMLDefinitions(this.ctx);
        }
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage0.inputPrepare.enrichStyleAlignment")) {
            InfraSetup.enrichStyleAlignmentFromResolved(this.ctx);
        }

        List<ASTSection> sections;
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage0.inputPrepare.pageLayoutBuilder")) {
            sections = PageLayoutBuilder.build(this.ctx);
        }
        this.pageDocOffsetToSection = this.ctx.pageDocOffsetToSection; // toSectionIndex() 호환
        for (ASTSection sec : sections) {
            doc.addSection(sec);
        }

        return sections;
    }

    /**
     * Stage 1: source ownership policy ObjectPlan bridge.
     *
     * <p>추출기가 선언한 ObjectPlan을 먼저 import한 뒤, 아직 Java로 남아 있는
     * legacy ownership bridge가 text/table 실행에 필요한 보조 plan을 보강한다.
     * 장기 목표는 이 보강 로직을 Stage 1 추출 ObjectPlan으로 이동하고 Java
     * OwnershipPlanner를 validate/write-only 단계로 줄이는 것이다.</p>
     */
    private void planOwnership() {
        importPlannerDeclaredObjectPlans();
        AnchoredTablePlanner.plan(this.ctx);
        SimpleButtonLabelPlanner.plan(this.ctx);
        OwnershipPlanner.runObservation(this.ctx);
        applyResolvedTextWrapContracts();
        if (this.resolvedData != null) {
            this.resolvedData.ownershipPlans(this.ctx.ownershipPlans);
        }
    }

    private void importPlannerDeclaredObjectPlans() {
        if (basePath == null || basePath.isEmpty() || ctx == null) return;
        Path objectPlans = Path.of(basePath, "object-plans.json");
        if (!Files.isRegularFile(objectPlans)) return;
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage1.ownershipPlanner.importObjectPlans")) {
            JsonObject root = JsonParser.parseString(Files.readString(objectPlans, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray plans = root.has("objectPlans") && root.get("objectPlans").isJsonArray()
                    ? root.getAsJsonArray("objectPlans")
                    : null;
            if (plans == null) return;
            Map<String, int[]> sourceSetRefs = objectPlanSourceSetRefs(root);
            Map<String, RenderedGroup> renderedByObjectPlanId = renderedGroupsByObjectPlanId();
            Map<String, RenderedGroup> renderedByCandidateId = renderedGroupsByCandidateId();
            Map<String, RenderedGroup> renderedByPageAndId = renderedGroupsByPageAndId();
            Set<String> dropClipParentSourceSetCandidateIds = clipParentSourceSetDropCandidateIds(plans);
            int imported = 0;
            for (JsonElement element : plans) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject planJson = element.getAsJsonObject();
                String importContractIssue = plannerDeclaredImportContractIssue(planJson);
                if (importContractIssue != null) {
                    warnPlannerDeclaredImportSkipped(planJson, importContractIssue);
                    continue;
                }
                ObjectPlan plan = layoutOnlyInlineSlotPlanFromJson(planJson);
                if (plan == null) {
                    plan = textOnlyObjectPlanFromJson(planJson);
                }
                if (plan == null) {
                    plan = tableTextObjectPlanFromJson(planJson);
                }
                if (plan == null) {
                    plan = nativeInlineTextFrameShellPlanFromJson(planJson);
                }
                if (plan == null) {
                    String materialIssue = plannerDeclaredRenderedMaterialIssue(
                            planJson, renderedByObjectPlanId, renderedByCandidateId, renderedByPageAndId,
                            dropClipParentSourceSetCandidateIds);
                    if (materialIssue != null) {
                        warnPlannerDeclaredImportSkipped(planJson, materialIssue);
                        continue;
                    }
                    plan = renderedObjectPlanFromJson(planJson, renderedByObjectPlanId, renderedByCandidateId,
                            renderedByPageAndId, dropClipParentSourceSetCandidateIds, sourceSetRefs);
                }
                if (plan == null) {
                    plan = nativeVectorShapePlanFromJson(planJson);
                }
                if (plan == null) continue;
                plan.withObjectPlanId(jsonString(planJson, "objectPlanId"));
                plan.withTextLayoutContract(textLayoutContractFromJson(planJson));
                if (isSupersededMasterInstanceTextPlan(plan)) {
                    warnSupersededMasterInstanceTextPlanSkipped(plan);
                    continue;
                }
                ctx.addOwnershipPlan(plan);
                imported++;
            }
            reportClosedInlineCarrierFlowTextPlanMismatches();
            if (imported > 0) {
                System.err.println("[ResolvedToASTBuilder] imported planner-declared object plans=" + imported);
            }
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] object-plans import skipped: "
                    + e.getMessage());
        }
    }

    private void applyResolvedTextWrapContracts() {
        if (ctx == null || resolvedData == null || ctx.ownershipPlans == null || ctx.ownershipPlans.isEmpty()) {
            return;
        }
        int attached = 0;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.textLayoutContract != null) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT
                    || plan.visualAction != VisualAction.DROP_VISUAL
                    || plan.materialization != Materialization.HWPX_TEXT) {
                continue;
            }
            int textFrameId = primaryOwnedTextFrameId(plan);
            if (textFrameId < 0) continue;
            ResolvedTextFrame tf = resolvedData.getTextFrame(String.valueOf(textFrameId));
            TextLayoutContract contract = sourceTextWrapContractFor(plan, tf);
            if (contract == null) continue;
            plan.withTextLayoutContract(contract);
            attached++;
        }
        if (attached > 0) {
            System.err.println("[ResolvedToASTBuilder] attached source TextWrap contracts=" + attached);
        }
    }

    private int primaryOwnedTextFrameId(ObjectPlan plan) {
        if (plan == null) return -1;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) {
            return plan.ownedTextFrameIds[0];
        }
        if (plan.domId >= 0) return plan.domId;
        return -1;
    }

    private TextLayoutContract sourceTextWrapContractFor(ObjectPlan textPlan, ResolvedTextFrame tf) {
        if (textPlan == null || tf == null || tf.composedLines() == null || tf.composedLines().size() < 2) {
            return null;
        }
        double scale = resolvedData != null && resolvedData.scaleFactor() > 0.0 ? resolvedData.scaleFactor() : 1.0;
        int rightWrapped = 0;
        int leftWrapped = 0;
        final double threshold = 6.0;
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null) continue;
            if (line.wrapIndentRight() / scale >= threshold) rightWrapped++;
            if (line.wrapIndentLeft() / scale >= threshold) leftWrapped++;
        }
        if (rightWrapped == 0 && leftWrapped == 0) return null;
        String wrapSide = rightWrapped >= leftWrapped ? "RIGHT_OBSTACLE" : "LEFT_OBSTACLE";
        int[] obstacleIds = overlappingTextWrapObstacleSourceIds(tf, wrapSide);
        if (obstacleIds.length == 0) {
            return null;
        }
        return new TextLayoutContract(
                TextLayoutContract.SOURCE_TEXT_WRAP,
                "resolved.composedLines",
                parseInt(tf.id(), primaryOwnedTextFrameId(textPlan)),
                wrapSide,
                obstacleIds,
                objectPlanIdsForSources(obstacleIds),
                tf.composedLines().size(),
                "resolved_composed_lines_wrap_indent_with_overlapping_source_visual");
    }

    private int[] overlappingTextWrapObstacleSourceIds(ResolvedTextFrame tf, String wrapSide) {
        if (tf == null || resolvedData == null || resolvedData.pageItems() == null) return new int[0];
        double[] tfBounds = tf.geometricBounds();
        if (!validBounds(tfBounds)) tfBounds = tf.pageRelativeBounds();
        if (!validBounds(tfBounds)) return new int[0];
        double tfCenter = (tfBounds[1] + tfBounds[3]) / 2.0;
        List<ObstacleCandidate> candidates = new ArrayList<>();
        for (ResolvedPageItem item : resolvedData.pageItems()) {
            if (!isSourceTextWrapObstacleCandidate(tf, item)) continue;
            double[] itemBounds = item.geometricBounds();
            if (!validBounds(itemBounds)) itemBounds = item.pageRelativeBounds();
            if (!validBounds(itemBounds)) continue;
            double overlap = objectPlanOverlapArea(tfBounds, itemBounds);
            if (overlap <= 0.0) continue;
            double itemCenter = (itemBounds[1] + itemBounds[3]) / 2.0;
            if ("RIGHT_OBSTACLE".equals(wrapSide) && itemCenter <= tfCenter) continue;
            if ("LEFT_OBSTACLE".equals(wrapSide) && itemCenter >= tfCenter) continue;
            int id = parseInt(item.id(), -1);
            if (id < 0) continue;
            candidates.add(new ObstacleCandidate(id, overlap));
        }
        if (candidates.isEmpty()) return new int[0];
        candidates.sort((a, b) -> Double.compare(b.overlapArea, a.overlapArea));
        int limit = Math.min(8, candidates.size());
        int[] out = new int[limit];
        for (int i = 0; i < limit; i++) out[i] = candidates.get(i).sourceId;
        return out;
    }

    private boolean isSourceTextWrapObstacleCandidate(ResolvedTextFrame tf, ResolvedPageItem item) {
        if (tf == null || item == null) return false;
        if (item.sourceHidden()) return false;
        if (item.pageIndex() != tf.pageIndex()) return false;
        if ("TextFrame".equals(item.type())) return false;
        if (item.isInline() || item.storyTextInlineSlot()) return false;
        String anchored = item.anchoredPosition();
        if (anchored != null && anchored.toUpperCase(Locale.ROOT).contains("INLINE")) return false;
        String storyPlacement = item.storyAnchorPlacement();
        if (storyPlacement != null && storyPlacement.toUpperCase(Locale.ROOT).contains("INLINE")) return false;
        if (item.parentId() != null && !item.parentId().isEmpty()) return false;
        return item.zOrder() > tf.zOrder();
    }

    private String[] objectPlanIdsForSources(int[] sourceIds) {
        if (sourceIds == null || sourceIds.length == 0 || ctx == null || ctx.ownershipPlans == null) {
            return new String[0];
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.objectPlanId == null || !plan.hasVisibleVisual()) continue;
            if (containsAny(plan.sourceObjectIds, sourceIds)
                    || containsAny(plan.visualSourceObjectIds, sourceIds)
                    || containsAny(plan.exportSourceObjectIds, sourceIds)
                    || containsAny(plan.hiddenVisualSourceObjectIds, sourceIds)
                    || containsAny(plan.clusterSourceObjectIds, sourceIds)) {
                out.add(plan.objectPlanId);
            }
        }
        return out.toArray(new String[0]);
    }

    private static boolean containsAny(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return false;
        for (int av : a) {
            for (int bv : b) {
                if (av == bv) return true;
            }
        }
        return false;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class ObstacleCandidate {
        final int sourceId;
        final double overlapArea;

        ObstacleCandidate(int sourceId, double overlapArea) {
            this.sourceId = sourceId;
            this.overlapArea = overlapArea;
        }
    }

    private boolean isSupersededMasterInstanceTextPlan(ObjectPlan plan) {
        if (plan == null || resolvedData == null) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT
                || plan.visualAction != VisualAction.DROP_VISUAL
                || plan.materialization != Materialization.HWPX_TEXT) {
            return false;
        }
        String[] keys = plan.ownedTextFrameIdKeys;
        if (keys == null || keys.length == 0) return false;
        for (String key : keys) {
            ResolvedTextFrame masterTf = resolvedData.getTextFrame(key);
            if (!isSuppressibleMasterInstanceTextFrame(masterTf)) continue;
            if (hasPageLocalTextFrameReplacingMasterSlot(masterTf)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSuppressibleMasterInstanceTextFrame(ResolvedTextFrame tf) {
        if (tf == null || !tf.isMasterInstance()) return false;
        if ("pagenum".equals(tf.masterSpecialType())) return false;
        return tf.pageIndex() >= 0 && tf.id() != null && !tf.id().isEmpty();
    }

    private boolean hasPageLocalTextFrameReplacingMasterSlot(ResolvedTextFrame masterTf) {
        if (masterTf == null || resolvedData == null) return false;
        ResolvedPageItem masterItem = resolvedData.getPageItem(masterTf.id());
        double[] masterBounds = bestSourceBounds(masterTf, masterItem);
        if (masterBounds == null) return false;
        String masterLayer = sourceLayerName(masterTf, masterItem);
        for (ResolvedTextFrame candidate : resolvedData.textFrames()) {
            if (candidate == null || candidate == masterTf) continue;
            if (candidate.isMasterInstance()) continue;
            if (candidate.sourceHidden()) continue;
            if (candidate.pageIndex() != masterTf.pageIndex()) continue;
            ResolvedPageItem candidateItem = resolvedData.getPageItem(candidate.id());
            if (!sameSourceLayer(masterLayer, sourceLayerName(candidate, candidateItem))) continue;
            if (sameSourceSlotBounds(masterBounds, bestSourceBounds(candidate, candidateItem))) {
                return true;
            }
        }
        return false;
    }

    private static double[] bestSourceBounds(ResolvedTextFrame tf, ResolvedPageItem item) {
        if (item != null && validBounds(item.geometricBounds())) return item.geometricBounds();
        if (tf != null && validBounds(tf.pageRelativeBounds())) return tf.pageRelativeBounds();
        if (tf != null && validBounds(tf.geometricBounds())) return tf.geometricBounds();
        return null;
    }

    private static boolean validBounds(double[] bounds) {
        return bounds != null && bounds.length >= 4
                && bounds[2] > bounds[0] && bounds[3] > bounds[1];
    }

    private static String sourceLayerName(ResolvedTextFrame tf, ResolvedPageItem item) {
        if (item != null && item.layerName() != null && !item.layerName().isEmpty()) {
            return item.layerName();
        }
        return tf != null ? tf.layerName() : null;
    }

    private static boolean sameSourceLayer(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return false;
        return a.equals(b);
    }

    private static boolean sameSourceSlotBounds(double[] a, double[] b) {
        if (!validBounds(a) || !validBounds(b)) return false;
        double maxDelta = 0.75;
        if (Math.abs(a[0] - b[0]) <= maxDelta
                && Math.abs(a[1] - b[1]) <= maxDelta
                && Math.abs(a[2] - b[2]) <= maxDelta
                && Math.abs(a[3] - b[3]) <= maxDelta) {
            return true;
        }
        double overlap = objectPlanOverlapArea(a, b);
        double minArea = Math.min(objectPlanArea(a), objectPlanArea(b));
        return minArea > 0 && overlap / minArea >= 0.95;
    }

    private static double objectPlanArea(double[] bounds) {
        if (!validBounds(bounds)) return 0;
        return Math.abs(bounds[2] - bounds[0]) * Math.abs(bounds[3] - bounds[1]);
    }

    private static double objectPlanOverlapArea(double[] a, double[] b) {
        if (!validBounds(a) || !validBounds(b)) return 0;
        double top = Math.max(a[0], b[0]);
        double left = Math.max(a[1], b[1]);
        double bottom = Math.min(a[2], b[2]);
        double right = Math.min(a[3], b[3]);
        if (bottom <= top || right <= left) return 0;
        return (bottom - top) * (right - left);
    }

    private void warnSupersededMasterInstanceTextPlanSkipped(ObjectPlan plan) {
        if (ctx == null || ctx.ownershipWarningLines == null || plan == null) return;
        ctx.ownershipWarningLines.add("{\"code\":\"STAGE1_SUPERSEDED_MASTER_INSTANCE_TEXT_PLAN_SKIPPED\""
                + ",\"stage\":\"stage1\",\"detail\":\"plan="
                + ObjectPlan.escape(plan.kind + ":" + plan.domId)
                + " ownedTextFrameIdKeys="
                + ObjectPlan.escape(Arrays.toString(plan.ownedTextFrameIdKeys))
                + "\"}");
    }

    private void warnPlannerDeclaredImportSkipped(JsonObject planJson, String issue) {
        if (ctx == null || ctx.ownershipWarningLines == null) return;
        ctx.ownershipWarningLines.add("{\"code\":\"STAGE1_PLANNER_DECLARED_OBJECT_PLAN_IMPORT_CONTRACT_INVALID\""
                + ",\"stage\":\"stage1\",\"detail\":\"objectPlan="
                + ObjectPlan.escape(jsonString(planJson, "objectPlanId"))
                + " candidateId=" + ObjectPlan.escape(jsonString(planJson, "candidateId"))
                + " passId=" + ObjectPlan.escape(jsonString(planJson, "passId"))
                + " issue=" + ObjectPlan.escape(issue) + "\"}");
    }

    private static String plannerDeclaredImportContractIssue(JsonObject o) {
        if (!isPlannerDeclaredImportCandidate(o)) return null;
        String[] required = {
                "textAction",
                "visualAction",
                "materialization",
                "placement",
                "coordinateSpace",
                "visualLayer"
        };
        for (String key : required) {
            String value = jsonString(o, key);
            if (value == null || value.isEmpty()) {
                return "missing_" + key;
            }
        }
        if (strictEnumValue(TextAction.class, jsonString(o, "textAction")) == null) return "invalid_textAction";
        if (strictEnumValue(VisualAction.class, jsonString(o, "visualAction")) == null) return "invalid_visualAction";
        if (strictEnumValue(Materialization.class, jsonString(o, "materialization")) == null) return "invalid_materialization";
        if (strictEnumValue(Placement.class, jsonString(o, "placement")) == null) return "invalid_placement";
        if (strictEnumValue(CoordinateSpace.class, jsonString(o, "coordinateSpace")) == null) return "invalid_coordinateSpace";
        if (strictEnumValue(VisualLayer.class, jsonString(o, "visualLayer")) == null) return "invalid_visualLayer";
        return null;
    }

    private static boolean isPlannerDeclaredImportCandidate(JsonObject o) {
        if (o == null) return false;
        String passId = jsonString(o, "passId");
        if ("pass.editable_text_frames".equals(passId)
                || "pass.visible_text_frames".equals(passId)
                || "pass.empty_editable_text_frames".equals(passId)
                || "pass.textframe_cleanup".equals(passId)
                || "pass.table_only_text_frames".equals(passId)
                || "pass.vector_shape_frames".equals(passId)) {
            return true;
        }
        if (jsonBoolean(o, "layoutOnlyInlineSlot", false)) return true;
        if ("READY_FOR_STAGE1_IMPORT".equals(jsonString(o, "contractStatus"))) return true;
        if ("clip_parent_source_set".equals(jsonString(o, "compositeRole"))) return true;
        return isPlannerDeclaredTextShellImport(o) || isPlannerDeclaredStyleOnlyImport(o);
    }

    private static String plannerDeclaredRenderedMaterialIssue(
            JsonObject o,
            Map<String, RenderedGroup> renderedByObjectPlanId,
            Map<String, RenderedGroup> renderedByCandidateId,
            Map<String, RenderedGroup> renderedByPageAndId,
            Set<String> dropClipParentSourceSetCandidateIds) {
        if (!requiresPlannerDeclaredRenderedMaterial(o, dropClipParentSourceSetCandidateIds)) return null;
        RenderedGroup rg = exactRenderedGroupForPlan(
                o, renderedByObjectPlanId, renderedByCandidateId, renderedByPageAndId);
        if (rg == null) return "missing_exact_rendered_material";
        if (rg.file() == null || rg.file().isEmpty()) return "missing_rendered_material_file";
        return null;
    }

    private static boolean requiresPlannerDeclaredRenderedMaterial(
            JsonObject o,
            Set<String> dropClipParentSourceSetCandidateIds) {
        if (o == null) return false;
        if (isPlannerDeclaredStyleOnlyImport(o)) return false;
        if ("clip_parent_source_set".equals(jsonString(o, "compositeRole"))
                && dropClipParentSourceSetCandidateIds != null
                && dropClipParentSourceSetCandidateIds.contains(jsonString(o, "candidateId"))) {
            return false;
        }
        if ("pass.vector_shape_frames".equals(jsonString(o, "passId"))
                && "NATIVE_SOURCE_SHAPE".equals(jsonString(o, "materialization"))) {
            return false;
        }
        if (isPlannerDeclaredNativeInlineTextShellImport(o)) return false;
        String visualAction = jsonString(o, "visualAction");
        if ("ABSORB_TEXT_STYLE".equals(visualAction) || "PLACE_TABLE_STYLE".equals(visualAction)) return false;
        if ("DROP_VISUAL".equals(visualAction)
                && !"clip_parent_source_set".equals(jsonString(o, "compositeRole"))) {
            return false;
        }
        return "READY_FOR_STAGE1_IMPORT".equals(jsonString(o, "contractStatus"))
                || isPlannerDeclaredTextShellImport(o)
                || "clip_parent_source_set".equals(jsonString(o, "compositeRole"));
    }

    private void reportClosedInlineCarrierFlowTextPlanMismatches() {
        if (ctx == null || ctx.ownershipPlans == null || ctx.ownershipPlans.isEmpty()
                || resolvedData == null) {
            return;
        }
        Set<Integer> flowTextFrameIds = closedInlineCarrierFlowTextFrameIds();
        if (flowTextFrameIds.isEmpty()) return;
        for (int i = 0; i < ctx.ownershipPlans.size(); i++) {
            ObjectPlan plan = ctx.ownershipPlans.get(i);
            if (!isFloatingHwpxTextPlan(plan)) continue;
            if (!containsAny(plan.ownedTextFrameIds, flowTextFrameIds)
                    && !flowTextFrameIds.contains(plan.domId)) {
                continue;
            }
            ctx.ownershipWarningLines.add("{\"code\":\"STAGE1_IMPORTED_PLAN_INLINE_FLOW_TEXT_PLACEMENT_MISMATCH\""
                    + ",\"stage\":\"stage1\",\"detail\":\"plan="
                    + ObjectPlan.escape(plan.kind + ":" + plan.domId)
                    + " placement=" + ObjectPlan.escape(String.valueOf(plan.placement))
                    + " coordinateSpace=" + ObjectPlan.escape(String.valueOf(plan.coordinateSpace))
                    + " expectedPlacement=INLINE expectedCoordinateSpace=STORY_FLOW\"}");
        }
    }

    private Set<Integer> closedInlineCarrierFlowTextFrameIds() {
        Set<Integer> out = new HashSet<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null
                    || plan.placement != Placement.INLINE
                    || plan.visualAction != VisualAction.PLACE_INLINE_PNG
                    || !plan.inlineSourceTreeClosed) {
                continue;
            }
            if (plan.inlineFlowSourceObjectIds == null || plan.inlineFlowSourceObjectIds.length == 0) {
                if (plan.hiddenVisualSourceObjectIds != null && plan.hiddenVisualSourceObjectIds.length > 0) {
                    ctx.ownershipWarningLines.add("{\"code\":\"STAGE1_CLOSED_INLINE_CARRIER_FLOW_ORDER_MISSING\""
                            + ",\"stage\":\"stage1\",\"detail\":\"plan="
                            + ObjectPlan.escape(plan.kind + ":" + plan.domId)
                            + " has hiddenVisualSourceObjectIds but no inlineFlowSourceObjectIds\"}");
                }
                continue;
            }
            for (int sourceId : plan.inlineFlowSourceObjectIds) {
                if (!contains(plan.hiddenVisualSourceObjectIds, sourceId)) continue;
                ResolvedTextFrame tf = resolvedData.getTextFrame(String.valueOf(sourceId));
                if (tf == null || tf.sourceHidden()) continue;
                if (ctx.ownershipPlanPlacesInlineHwpxText(sourceId)) {
                    out.add(sourceId);
                }
            }
        }
        return out;
    }

    private static boolean isFloatingHwpxTextPlan(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.visualAction == VisualAction.DROP_VISUAL
                && plan.materialization == Materialization.HWPX_TEXT
                && plan.placement == Placement.FLOATING;
    }

    private static boolean containsAny(int[] ids, Set<Integer> candidates) {
        if (ids == null || candidates == null || candidates.isEmpty()) return false;
        for (int id : ids) {
            if (candidates.contains(id)) return true;
        }
        return false;
    }

    private static boolean contains(int[] values, int target) {
        if (values == null || values.length == 0) return false;
        for (int value : values) {
            if (value == target) return true;
        }
        return false;
    }

    private static Set<String> clipParentSourceSetDropCandidateIds(JsonArray plans) {
        Set<String> out = new HashSet<>();
        if (plans == null) return out;
        List<JsonObject> objects = new ArrayList<>();
        for (JsonElement element : plans) {
            if (element != null && element.isJsonObject()) {
                objects.add(element.getAsJsonObject());
            }
        }
        for (JsonObject clip : objects) {
            if (!"clip_parent_source_set".equals(jsonString(clip, "compositeRole"))) continue;
            String candidateId = jsonString(clip, "candidateId");
            if (candidateId == null || candidateId.isBlank()) continue;
            int[] clipSources = jsonIntArray(clip, "sourceObjectIds");
            if (clipSources.length == 0) continue;
            int clipPage = jsonInt(clip, "pageIndex", -1);
            if (clipPage < 0) continue;

            Integer nearestDelta = null;
            for (JsonObject carrier : objects) {
                if (carrier == clip) continue;
                if (!isDirectChildTextShellCarrierObjectPlan(carrier)) continue;
                int carrierPage = jsonInt(carrier, "pageIndex", -1);
                if (carrierPage < 0) continue;
                int[] carrierSources = jsonIntArray(carrier, "sourceObjectIds");
                if (carrierSources.length <= clipSources.length) continue;
                if (!containsAll(carrierSources, clipSources)) continue;
                int delta = carrierPage - clipPage;
                if (nearestDelta == null
                        || Math.abs(delta) < Math.abs(nearestDelta)
                        || (Math.abs(delta) == Math.abs(nearestDelta) && delta > nearestDelta)) {
                    nearestDelta = delta;
                }
            }

            if (nearestDelta != null && nearestDelta >= 0) {
                out.add(candidateId);
            }
        }
        return out;
    }

    private static boolean isDirectChildTextShellCarrierObjectPlan(JsonObject o) {
        if (o == null) return false;
        if (!"direct_child_shell_slot".equals(jsonString(o, "slotRole"))) return false;
        if (!"PLACE_TEXT_SHELL".equals(jsonString(o, "visualAction"))) return false;
        if (jsonIntArray(o, "ownedTextFrameIds").length == 0) return false;
        String contractStatus = jsonString(o, "contractStatus");
        return "READY_FOR_STAGE1_IMPORT".equals(contractStatus)
                || isPlannerDeclaredTextShellImport(o);
    }

    private static boolean containsAll(int[] values, int[] candidates) {
        if (candidates == null || candidates.length == 0) return true;
        if (values == null || values.length == 0) return false;
        for (int candidate : candidates) {
            boolean found = false;
            for (int value : values) {
                if (value == candidate) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private Map<String, RenderedGroup> renderedGroupsByCandidateId() {
        Map<String, RenderedGroup> out = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        if (resolvedData == null) return out;
        indexRenderedGroupsByCandidateId(out, ambiguous, resolvedData.allRenderedFloatingItems());
        indexRenderedGroupsByCandidateId(out, ambiguous, resolvedData.allRenderedGraphicFrames());
        indexRenderedGroupsByCandidateId(out, ambiguous, resolvedData.allRenderedImageFrames());
        indexRenderedGroupsByCandidateId(out, ambiguous, resolvedData.allRenderedPdfFrames());
        return out;
    }

    private Map<String, RenderedGroup> renderedGroupsByObjectPlanId() {
        Map<String, RenderedGroup> out = new HashMap<>();
        if (resolvedData == null) return out;
        indexRenderedGroupsByObjectPlanId(out, resolvedData.allRenderedFloatingItems());
        indexRenderedGroupsByObjectPlanId(out, resolvedData.allRenderedGraphicFrames());
        indexRenderedGroupsByObjectPlanId(out, resolvedData.allRenderedImageFrames());
        indexRenderedGroupsByObjectPlanId(out, resolvedData.allRenderedPdfFrames());
        return out;
    }

    private Map<String, RenderedGroup> renderedGroupsByPageAndId() {
        Map<String, RenderedGroup> out = new HashMap<>();
        if (resolvedData == null) return out;
        indexRenderedGroupsByPageAndId(out, resolvedData.allRenderedFloatingItems());
        indexRenderedGroupsByPageAndId(out, resolvedData.allRenderedGraphicFrames());
        indexRenderedGroupsByPageAndId(out, resolvedData.allRenderedImageFrames());
        indexRenderedGroupsByPageAndId(out, resolvedData.allRenderedPdfFrames());
        return out;
    }

    private static void indexRenderedGroupsByCandidateId(
            Map<String, RenderedGroup> out,
            Set<String> ambiguous,
            Collection<RenderedGroup> renderedGroups) {
        if (out == null || ambiguous == null || renderedGroups == null) return;
        for (RenderedGroup rg : renderedGroups) {
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
    }

    private static boolean sameRenderedGroup(RenderedGroup a, RenderedGroup b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.pageIndex() != b.pageIndex()) return false;
        String aRenderUnitId = a.renderUnitId() != null ? a.renderUnitId() : "";
        String bRenderUnitId = b.renderUnitId() != null ? b.renderUnitId() : "";
        if (!aRenderUnitId.isEmpty() && aRenderUnitId.equals(bRenderUnitId)) return true;
        String aSlotIdentity = a.renderUnitSlotIdentityKey() != null ? a.renderUnitSlotIdentityKey() : "";
        String bSlotIdentity = b.renderUnitSlotIdentityKey() != null ? b.renderUnitSlotIdentityKey() : "";
        if (!aSlotIdentity.isEmpty() && aSlotIdentity.equals(bSlotIdentity)) return true;
        String aExportUnitId = a.exportUnitId() != null ? a.exportUnitId() : "";
        String bExportUnitId = b.exportUnitId() != null ? b.exportUnitId() : "";
        if (!aExportUnitId.isEmpty() && aExportUnitId.equals(bExportUnitId)) return true;
        if (a.id() != b.id()) return false;
        String aFile = a.file() != null ? a.file() : "";
        String bFile = b.file() != null ? b.file() : "";
        return aFile.equals(bFile);
    }

    private static void indexRenderedGroupsByObjectPlanId(
            Map<String, RenderedGroup> out,
            Collection<RenderedGroup> renderedGroups) {
        if (out == null || renderedGroups == null) return;
        for (RenderedGroup rg : renderedGroups) {
            if (rg == null) continue;
            String objectPlanId = objectPlanIdFromRenderUnitId(rg.renderUnitId());
            if (objectPlanId == null || objectPlanId.isEmpty()) continue;
            out.putIfAbsent(objectPlanId, rg);
        }
    }

    private static void indexRenderedGroupsByPageAndId(
            Map<String, RenderedGroup> out,
            Collection<RenderedGroup> renderedGroups) {
        if (out == null || renderedGroups == null) return;
        for (RenderedGroup rg : renderedGroups) {
            if (rg == null) continue;
            out.putIfAbsent(renderedPageIdKey(rg.pageIndex(), rg.id()), rg);
        }
    }

    private static ObjectPlan textOnlyObjectPlanFromJson(JsonObject o) {
        if (o == null) return null;
        String passId = jsonString(o, "passId");
        if (!"pass.editable_text_frames".equals(passId)
                && !"pass.visible_text_frames".equals(passId)
                && !"pass.empty_editable_text_frames".equals(passId)
                && !"pass.textframe_cleanup".equals(passId)) {
            return null;
        }
        String textAction = jsonString(o, "textAction");
        if ("pass.textframe_cleanup".equals(passId)) {
            if (!"DROP_TEXT".equals(textAction)) return null;
        } else if (!"OWNED_BY_HWPX_TEXT".equals(textAction)) {
            return null;
        }
        if (!"DROP_VISUAL".equals(jsonString(o, "visualAction"))) return null;
        if (!"HWPX_TEXT".equals(jsonString(o, "materialization"))) return null;
        int[] ownedTextFrameIds = jsonIntArray(o, "ownedTextFrameIds");
        String[] ownedTextFrameIdKeys = jsonStringArray(o, "ownedTextFrameIdKeys");
        if (ownedTextFrameIds.length == 0 && ownedTextFrameIdKeys.length == 0) return null;
        int[] sourceIds = jsonIntArray(o, "sourceObjectIds");
        if (sourceIds.length == 0) sourceIds = ownedTextFrameIds;
        int domId = jsonInt(o, "primarySourceObjectId",
                ownedTextFrameIds.length > 0 ? ownedTextFrameIds[0] : -1);
        int pageIndex = jsonInt(o, "pageIndex", -1);
        if (domId < 0 && ownedTextFrameIdKeys.length == 0) return null;
        TextAction textActionValue = strictEnumValue(TextAction.class, textAction);
        VisualLayer visualLayer = strictEnumValue(VisualLayer.class, jsonString(o, "visualLayer"));
        Placement placement = strictEnumValue(Placement.class, jsonString(o, "placement"));
        CoordinateSpace coordinateSpace = strictEnumValue(CoordinateSpace.class, jsonString(o, "coordinateSpace"));
        if (textActionValue == null || visualLayer == null || placement == null || coordinateSpace == null) {
            return null;
        }
        return new ObjectPlan(
                domId,
                "planner_declared_text_frame:" + jsonString(o, "kind"),
                pageIndex,
                textActionValue,
                VisualAction.DROP_VISUAL,
                visualLayer,
                placement,
                null,
                sourceIds,
                jsonIntArray(o, "visualSourceObjectIds"),
                jsonIntArray(o, "styleSourceObjectIds"),
                ownedTextFrameIds,
                jsonIntArray(o, "descendantVisualObjectIds"),
                jsonString(o, "bundleId"),
                Materialization.HWPX_TEXT,
                coordinateSpace,
                jsonString(o, "anchorOwner"),
                jsonInt(o, "zOrder", 0),
                "planner_declared_text_frame",
                null,
                jsonDoubleArray(o, "bounds"),
                null,
                jsonString(o, "sourceLayerId"),
                jsonString(o, "sourceLayerName"),
                jsonInt(o, "sourceLayerIndex", -1))
                .withOwnedTextFrameIdKeys(ownedTextFrameIdKeys);
    }

    private static ObjectPlan tableTextObjectPlanFromJson(JsonObject o) {
        if (o == null) return null;
        if (!"pass.table_only_text_frames".equals(jsonString(o, "passId"))) return null;
        if (!"OWNED_BY_HWPX_TEXT".equals(jsonString(o, "textAction"))) return null;
        if (!"DROP_VISUAL".equals(jsonString(o, "visualAction"))) return null;
        if (!"HWPX_TEXT".equals(jsonString(o, "materialization"))) return null;
        int[] ownedTextFrameIds = jsonIntArray(o, "ownedTextFrameIds");
        if (ownedTextFrameIds.length == 0) return null;
        int[] sourceIds = jsonIntArray(o, "sourceObjectIds");
        if (sourceIds.length == 0) sourceIds = ownedTextFrameIds;
        int domId = jsonInt(o, "primarySourceObjectId", ownedTextFrameIds[0]);
        int pageIndex = jsonInt(o, "pageIndex", -1);
        if (domId < 0) return null;
        VisualLayer visualLayer = strictEnumValue(VisualLayer.class, jsonString(o, "visualLayer"));
        Placement placement = strictEnumValue(Placement.class, jsonString(o, "placement"));
        CoordinateSpace coordinateSpace = strictEnumValue(CoordinateSpace.class, jsonString(o, "coordinateSpace"));
        if (visualLayer == null || placement == null || coordinateSpace == null) return null;
        return new ObjectPlan(
                domId,
                "text_frame:table_only",
                pageIndex,
                TextAction.OWNED_BY_HWPX_TEXT,
                VisualAction.DROP_VISUAL,
                visualLayer,
                placement,
                null,
                sourceIds,
                jsonIntArray(o, "visualSourceObjectIds"),
                jsonIntArray(o, "styleSourceObjectIds"),
                ownedTextFrameIds,
                jsonIntArray(o, "descendantVisualObjectIds"),
                jsonString(o, "bundleId"),
                Materialization.HWPX_TEXT,
                coordinateSpace,
                jsonString(o, "anchorOwner"),
                jsonInt(o, "zOrder", 0),
                "planner_declared_table_text",
                null,
                jsonDoubleArray(o, "bounds"),
                null,
                jsonString(o, "sourceLayerId"),
                jsonString(o, "sourceLayerName"),
                jsonInt(o, "sourceLayerIndex", -1));
    }

    private ObjectPlan renderedObjectPlanFromJson(
            JsonObject o,
            Map<String, RenderedGroup> renderedByObjectPlanId,
            Map<String, RenderedGroup> renderedByCandidateId,
            Map<String, RenderedGroup> renderedByPageAndId,
            Set<String> dropClipParentSourceSetCandidateIds,
            Map<String, int[]> sourceSetRefs) {
        if (o == null) return null;
        boolean clipParentSourceSet = "clip_parent_source_set".equals(jsonString(o, "compositeRole"));
        if (!"READY_FOR_STAGE1_IMPORT".equals(jsonString(o, "contractStatus"))
                && !isPlannerDeclaredTextShellImport(o)
                && !isPlannerDeclaredStyleOnlyImport(o)
                && !clipParentSourceSet) {
            return null;
        }
        VisualAction visualAction = strictEnumValue(VisualAction.class, jsonString(o, "visualAction"));
        if (visualAction == null) return null;
        if (isPlannerDeclaredStyleOnlyImport(o)) {
            return styleOnlyObjectPlanFromJson(o, visualAction);
        }
        String candidateId = jsonString(o, "candidateId");
        RenderedGroup rg = exactRenderedGroupForPlan(
                o, renderedByObjectPlanId, renderedByCandidateId, renderedByPageAndId);
        if (rg == null || rg.file() == null || rg.file().isEmpty()) return null;
        if (clipParentSourceSet
                && dropClipParentSourceSetCandidateIds != null
                && dropClipParentSourceSetCandidateIds.contains(candidateId)) {
            visualAction = VisualAction.DROP_VISUAL;
        }
        if (visualAction == VisualAction.DROP_VISUAL
                && !clipParentSourceSet) {
            return null;
        }
        if (visualAction == VisualAction.PLACE_TABLE_STYLE) {
            return null;
        }
        int[] sourceIds = jsonIntArray(o, "sourceObjectIds");
        if (sourceIds.length == 0) {
            sourceIds = rg.sourceObjectIds() != null ? rg.sourceObjectIds() : new int[0];
        }
        if (sourceIds.length == 0) return null;
        int[] visualSourceIds = jsonIntArray(o, "visualSourceObjectIds");
        if (visualSourceIds.length == 0) visualSourceIds = sourceIds;
        TextAction textAction = strictEnumValue(TextAction.class, jsonString(o, "textAction"));
        VisualLayer visualLayer = strictEnumValue(VisualLayer.class, jsonString(o, "visualLayer"));
        Placement placement = strictEnumValue(Placement.class, jsonString(o, "placement"));
        Materialization materialization = strictEnumValue(Materialization.class, jsonString(o, "materialization"));
        CoordinateSpace coordinateSpace = strictEnumValue(CoordinateSpace.class, jsonString(o, "coordinateSpace"));
        if (textAction == null || visualLayer == null || placement == null
                || materialization == null || coordinateSpace == null) {
            return null;
        }
        double[] renderSourceBounds = jsonDoubleArray(o, "renderSourceBounds");
        double[] plannedBounds = jsonDoubleArray(o, "bounds");
        if (plannedBounds == null || plannedBounds.length < 4) {
            plannedBounds = rg.bounds();
        }
        double[] cropSourceBounds = jsonDoubleArray(o, "cropSourceBounds");
        if ((cropSourceBounds == null || cropSourceBounds.length < 4)
                && rg.cropSourceBounds() != null && rg.cropSourceBounds().length >= 4) {
            cropSourceBounds = rg.cropSourceBounds();
        }
        String sourceLayerId = rg.layerId();
        String sourceLayerName = rg.layerName();
        int sourceLayerIndex = rg.layerIndex();
        if ((sourceLayerId == null || sourceLayerId.isEmpty()
                || sourceLayerName == null || sourceLayerName.isEmpty()
                || sourceLayerIndex < 0)
                && resolvedData != null) {
            for (int sourceId : sourceIds) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem item =
                        resolvedData.getPageItem(String.valueOf(sourceId));
                if (item == null) continue;
                if ((sourceLayerId == null || sourceLayerId.isEmpty())
                        && item.layerId() != null && !item.layerId().isEmpty()) {
                    sourceLayerId = item.layerId();
                }
                if ((sourceLayerName == null || sourceLayerName.isEmpty())
                        && item.layerName() != null && !item.layerName().isEmpty()) {
                    sourceLayerName = item.layerName();
                }
                if (sourceLayerIndex < 0 && item.layerIndex() >= 0) {
                    sourceLayerIndex = item.layerIndex();
                }
                if (sourceLayerId != null && !sourceLayerId.isEmpty()
                        && sourceLayerName != null && !sourceLayerName.isEmpty()
                        && sourceLayerIndex >= 0) {
                    break;
                }
            }
        }
        ObjectPlan plan = new ObjectPlan(
                rg.id(),
                "planner_declared_rendered:" + jsonString(o, "passId") + ":" + jsonString(o, "kind"),
                rg.pageIndex(),
                textAction,
                visualAction,
                visualLayer,
                placement,
                rg.id(),
                sourceIds,
                visualSourceIds,
                jsonIntArray(o, "styleSourceObjectIds"),
                jsonIntArray(o, "ownedTextFrameIds"),
                jsonIntArray(o, "descendantVisualObjectIds"),
                jsonString(o, "bundleId"),
                materialization,
                coordinateSpace,
                jsonString(o, "anchorOwner"),
                jsonInt(o, "zOrder", rg.zOrder()),
                "planner_declared_object_plan",
                rg.file(),
                plannedBounds,
                renderSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
        return plan
                .withCropSourceBounds(cropSourceBounds)
                .withExtractionCandidate(
                        jsonString(o, "candidateId"),
                        jsonString(o, "passId"),
                        jsonString(o, "slotRole"))
                .withExtractionSourceObjectIds(
                        jsonIntArray(o, "exportSourceObjectIds"),
                        jsonIntArray(o, "hiddenVisualSourceObjectIds"))
                .withSourceTreeDiagnostics(
                        jsonSourceSetArray(o, "sourceRootObjectIds", "sourceRootSetId", sourceSetRefs),
                        jsonSourceSetArray(o, "clusterSourceObjectIds", "clusterSourceSetId", sourceSetRefs),
                        jsonSourceSetArray(o, "omittedClusterSourceObjectIds", "omittedClusterSourceSetId", sourceSetRefs))
                .withInlineFlowContract(
                        jsonBoolean(o, "inlineSourceTreeClosed", false),
                        jsonIntArray(o, "inlineFlowSourceObjectIds"));
    }

    private static RenderedGroup exactRenderedGroupForPlan(
            JsonObject o,
            Map<String, RenderedGroup> renderedByObjectPlanId,
            Map<String, RenderedGroup> renderedByCandidateId,
            Map<String, RenderedGroup> renderedByPageAndId) {
        if (o == null) return null;
        String objectPlanId = jsonString(o, "objectPlanId");
        if (objectPlanId != null && !objectPlanId.isEmpty() && renderedByObjectPlanId != null) {
            RenderedGroup rg = renderedByObjectPlanId.get(objectPlanId);
            if (rg != null) return rg;
        }
        String candidateId = jsonString(o, "candidateId");
        if (candidateId != null && !candidateId.isEmpty() && renderedByCandidateId != null) {
            RenderedGroup rg = renderedByCandidateId.get(candidateId);
            if (rg != null) return rg;
        }
        if (renderedByPageAndId == null) return null;
        int[] sourceIds = jsonIntArray(o, "sourceObjectIds");
        int primarySourceId = jsonInt(o, "primarySourceObjectId",
                jsonInt(o, "domId", sourceIds.length > 0 ? sourceIds[0] : -1));
        int pageIndex = jsonInt(o, "pageIndex", -1);
        if (primarySourceId < 0 || pageIndex < 0) return null;
        return renderedByPageAndId.get(renderedPageIdKey(pageIndex, primarySourceId));
    }

    private static String renderedPageIdKey(int pageIndex, int id) {
        return pageIndex + ":" + id;
    }

    private static String objectPlanIdFromRenderUnitId(String renderUnitId) {
        if (renderUnitId == null || renderUnitId.isEmpty()) return null;
        int index = renderUnitId.indexOf("objectPlan.");
        if (index < 0) return null;
        return renderUnitId.substring(index);
    }

    private static boolean isPlannerDeclaredTextShellImport(JsonObject o) {
        if (o == null) return false;
        if (!"PLACE_TEXT_SHELL".equals(jsonString(o, "visualAction"))) return false;
        String placement = jsonString(o, "placement");
        if (!"INLINE".equals(placement) && !"FLOATING".equals(placement)) return false;
        String coordinateSpace = jsonString(o, "coordinateSpace");
        if (!"STORY_FLOW".equals(coordinateSpace) && !"PAGE".equals(coordinateSpace)) return false;
        if (jsonIntArray(o, "ownedTextFrameIds").length == 0) return false;
        return jsonIntArray(o, "visualSourceObjectIds").length > 0
                || jsonIntArray(o, "exportSourceObjectIds").length > 0;
    }

    private static boolean isPlannerDeclaredNativeInlineTextShellImport(JsonObject o) {
        if (o == null) return false;
        if (!"NATIVE_SOURCE_SHAPE".equals(jsonString(o, "materialization"))) return false;
        if (!"PLACE_TEXT_SHELL".equals(jsonString(o, "visualAction"))) return false;
        if (!"INLINE".equals(jsonString(o, "placement"))) return false;
        if (!"STORY_FLOW".equals(jsonString(o, "coordinateSpace"))) return false;
        if (jsonIntArray(o, "ownedTextFrameIds").length == 0) return false;
        return jsonIntArray(o, "sourceObjectIds").length > 0
                || jsonIntArray(o, "visualSourceObjectIds").length > 0;
    }

    private static boolean isPlannerDeclaredStyleOnlyImport(JsonObject o) {
        if (o == null) return false;
        String visualAction = jsonString(o, "visualAction");
        String materialization = jsonString(o, "materialization");
        boolean absorbTextStyle = "ABSORB_TEXT_STYLE".equals(visualAction);
        boolean tableStyle = "HWPX_TABLE_STYLE".equals(materialization)
                && ("DROP_VISUAL".equals(visualAction) || "PLACE_TABLE_STYLE".equals(visualAction));
        if (!absorbTextStyle && !tableStyle) return false;
        if (absorbTextStyle && !"INLINE".equals(jsonString(o, "placement"))) return false;
        return jsonIntArray(o, "styleSourceObjectIds").length > 0
                || jsonIntArray(o, "sourceObjectIds").length > 0;
    }

    private static ObjectPlan styleOnlyObjectPlanFromJson(JsonObject o, VisualAction visualAction) {
        int[] sourceIds = jsonIntArray(o, "sourceObjectIds");
        int[] styleSourceIds = jsonIntArray(o, "styleSourceObjectIds");
        if (sourceIds.length == 0) sourceIds = styleSourceIds;
        if (styleSourceIds.length == 0) styleSourceIds = sourceIds;
        if (sourceIds.length == 0) return null;
        int domId = jsonInt(o, "primarySourceObjectId",
                jsonInt(o, "domId", sourceIds[0]));
        int pageIndex = jsonInt(o, "pageIndex", -1);
        if (domId < 0 || pageIndex < 0) return null;
        TextAction textAction = strictEnumValue(TextAction.class, jsonString(o, "textAction"));
        VisualLayer visualLayer = strictEnumValue(VisualLayer.class, jsonString(o, "visualLayer"));
        Placement placement = strictEnumValue(Placement.class, jsonString(o, "placement"));
        Materialization materialization = strictEnumValue(Materialization.class, jsonString(o, "materialization"));
        CoordinateSpace coordinateSpace = strictEnumValue(CoordinateSpace.class, jsonString(o, "coordinateSpace"));
        if (textAction == null || visualLayer == null || placement == null
                || materialization == null || coordinateSpace == null) {
            return null;
        }
        return new ObjectPlan(
                domId,
                "planner_declared_style_only:" + jsonString(o, "passId") + ":" + jsonString(o, "kind"),
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                null,
                sourceIds,
                new int[0],
                styleSourceIds,
                jsonIntArray(o, "ownedTextFrameIds"),
                jsonIntArray(o, "descendantVisualObjectIds"),
                jsonString(o, "bundleId"),
                materialization,
                coordinateSpace,
                jsonString(o, "anchorOwner"),
                jsonInt(o, "zOrder", 0),
                jsonString(o, "reason"),
                null,
                jsonDoubleArray(o, "bounds"),
                null,
                jsonString(o, "sourceLayerId"),
                jsonString(o, "sourceLayerName"),
                jsonInt(o, "sourceLayerIndex", -1))
                .withExtractionCandidate(
                        jsonString(o, "candidateId"),
                        jsonString(o, "passId"),
                        jsonString(o, "slotRole"))
                .withExtractionSourceObjectIds(
                        jsonIntArray(o, "exportSourceObjectIds"),
                        jsonIntArray(o, "hiddenVisualSourceObjectIds"));
    }

    private static ObjectPlan layoutOnlyInlineSlotPlanFromJson(JsonObject o) {
        if (o == null) return null;
        boolean explicitLayoutOnly = jsonBoolean(o, "layoutOnlyInlineSlot", false);
        boolean plannerInlineDropSlot = "pass.inline_objects".equals(jsonString(o, "passId"))
                && "INLINE".equals(jsonString(o, "placement"))
                && "STORY_FLOW".equals(jsonString(o, "coordinateSpace"))
                && "DROP_VISUAL".equals(jsonString(o, "visualAction"))
                && "HWPX_TEXT".equals(jsonString(o, "materialization"));
        if (!explicitLayoutOnly && !plannerInlineDropSlot) return null;
        if (!"INLINE".equals(jsonString(o, "placement"))) return null;
        if (!"DROP_VISUAL".equals(jsonString(o, "visualAction"))) return null;
        int[] sourceIds = jsonIntArray(o, "sourceObjectIds");
        if (sourceIds.length == 0) return null;
        int domId = jsonInt(o, "primarySourceObjectId", sourceIds[0]);
        int pageIndex = jsonInt(o, "pageIndex", -1);
        if (domId < 0 || pageIndex < 0) return null;
        int[] visualSourceIds = jsonIntArray(o, "visualSourceObjectIds");
        if (visualSourceIds.length == 0) visualSourceIds = sourceIds;
        TextAction textAction = strictEnumValue(TextAction.class, jsonString(o, "textAction"));
        VisualLayer visualLayer = strictEnumValue(VisualLayer.class, jsonString(o, "visualLayer"));
        Materialization materialization = strictEnumValue(Materialization.class, jsonString(o, "materialization"));
        CoordinateSpace coordinateSpace = strictEnumValue(CoordinateSpace.class, jsonString(o, "coordinateSpace"));
        if (textAction == null || visualLayer == null || materialization == null
                || coordinateSpace != CoordinateSpace.STORY_FLOW) {
            return null;
        }
        return new ObjectPlan(
                domId,
                "layout_only_inline_slot:" + jsonString(o, "kind"),
                pageIndex,
                textAction,
                VisualAction.DROP_VISUAL,
                visualLayer,
                Placement.INLINE,
                null,
                sourceIds,
                visualSourceIds,
                jsonIntArray(o, "styleSourceObjectIds"),
                jsonIntArray(o, "ownedTextFrameIds"),
                jsonIntArray(o, "descendantVisualObjectIds"),
                jsonString(o, "bundleId"),
                materialization,
                coordinateSpace,
                jsonString(o, "anchorOwner"),
                jsonInt(o, "zOrder", 0),
                "planner_declared_layout_only_inline_slot",
                null,
                jsonDoubleArray(o, "bounds"),
                null,
                jsonString(o, "sourceLayerId"),
                jsonString(o, "sourceLayerName"),
                jsonInt(o, "sourceLayerIndex", -1))
                .withExtractionCandidate(
                        jsonString(o, "candidateId"),
                        jsonString(o, "passId"),
                        jsonString(o, "slotRole"))
                .withExtractionSourceObjectIds(
                        jsonIntArray(o, "exportSourceObjectIds"),
                        jsonIntArray(o, "hiddenVisualSourceObjectIds"));
    }

    private static ObjectPlan nativeVectorShapePlanFromJson(JsonObject o) {
        if (o == null) return null;
        if (!"pass.vector_shape_frames".equals(jsonString(o, "passId"))) return null;
        if (!"NATIVE_SOURCE_SHAPE".equals(jsonString(o, "materialization"))) return null;
        String visualActionName = jsonString(o, "visualAction");
        if (!"PLACE_TEXT_SHELL".equals(visualActionName)
                && !"PLACE_FLOATING_PNG".equals(visualActionName)
                && !"PLACE_INLINE_PNG".equals(visualActionName)) {
            return null;
        }
        int[] sourceIds = jsonIntArray(o, "sourceObjectIds");
        if (sourceIds.length == 0) return null;
        int domId = jsonInt(o, "domId", -1);
        if (domId < 0) {
            domId = jsonInt(o, "primarySourceObjectId", sourceIds[0]);
        }
        int pageIndex = jsonInt(o, "pageIndex", -1);
        if (domId < 0 || pageIndex < 0) return null;
        int[] visualSourceIds = jsonIntArray(o, "visualSourceObjectIds");
        if (visualSourceIds.length == 0) visualSourceIds = sourceIds;
        TextAction textAction = strictEnumValue(TextAction.class, jsonString(o, "textAction"));
        VisualAction visualAction = strictEnumValue(VisualAction.class, visualActionName);
        VisualLayer visualLayer = strictEnumValue(VisualLayer.class, jsonString(o, "visualLayer"));
        Placement placement = strictEnumValue(Placement.class, jsonString(o, "placement"));
        CoordinateSpace coordinateSpace = strictEnumValue(CoordinateSpace.class, jsonString(o, "coordinateSpace"));
        if (textAction == null || visualAction == null || visualLayer == null
                || placement == null || coordinateSpace == null) {
            return null;
        }
        return new ObjectPlan(
                domId,
                "native_source_shape:" + jsonString(o, "kind"),
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                null,
                sourceIds,
                visualSourceIds,
                jsonIntArray(o, "styleSourceObjectIds"),
                jsonIntArray(o, "ownedTextFrameIds"),
                jsonIntArray(o, "descendantVisualObjectIds"),
                jsonString(o, "bundleId"),
                Materialization.NATIVE_SOURCE_SHAPE,
                coordinateSpace,
                jsonString(o, "anchorOwner"),
                jsonInt(o, "zOrder", 0),
                "planner_native_source_shape",
                null,
                jsonDoubleArray(o, "bounds"),
                null,
                jsonString(o, "sourceLayerId"),
                jsonString(o, "sourceLayerName"),
                jsonInt(o, "sourceLayerIndex", -1));
    }

    private static ObjectPlan nativeInlineTextFrameShellPlanFromJson(JsonObject o) {
        if (!isPlannerDeclaredNativeInlineTextShellImport(o)) return null;
        int[] sourceIds = jsonIntArray(o, "sourceObjectIds");
        if (sourceIds.length == 0) sourceIds = jsonIntArray(o, "visualSourceObjectIds");
        if (sourceIds.length == 0) return null;
        int domId = jsonInt(o, "domId", -1);
        if (domId < 0) {
            domId = jsonInt(o, "primarySourceObjectId", sourceIds[0]);
        }
        int pageIndex = jsonInt(o, "pageIndex", -1);
        if (domId < 0 || pageIndex < 0) return null;
        int[] visualSourceIds = jsonIntArray(o, "visualSourceObjectIds");
        if (visualSourceIds.length == 0) visualSourceIds = sourceIds;
        TextAction textAction = strictEnumValue(TextAction.class, jsonString(o, "textAction"));
        VisualAction visualAction = strictEnumValue(VisualAction.class, jsonString(o, "visualAction"));
        VisualLayer visualLayer = strictEnumValue(VisualLayer.class, jsonString(o, "visualLayer"));
        CoordinateSpace coordinateSpace = strictEnumValue(CoordinateSpace.class, jsonString(o, "coordinateSpace"));
        if (textAction == null || visualAction == null || visualLayer == null
                || coordinateSpace != CoordinateSpace.STORY_FLOW) {
            return null;
        }
        return new ObjectPlan(
                domId,
                "native_inline_text_frame_shell:" + jsonString(o, "kind"),
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                Placement.INLINE,
                null,
                sourceIds,
                visualSourceIds,
                jsonIntArray(o, "styleSourceObjectIds"),
                jsonIntArray(o, "ownedTextFrameIds"),
                jsonIntArray(o, "descendantVisualObjectIds"),
                jsonString(o, "bundleId"),
                Materialization.NATIVE_SOURCE_SHAPE,
                CoordinateSpace.STORY_FLOW,
                jsonString(o, "anchorOwner"),
                jsonInt(o, "zOrder", 0),
                "planner_native_inline_text_frame_shell",
                null,
                jsonDoubleArray(o, "bounds"),
                null,
                jsonString(o, "sourceLayerId"),
                jsonString(o, "sourceLayerName"),
                jsonInt(o, "sourceLayerIndex", -1))
                .withExtractionCandidate(
                        jsonString(o, "candidateId"),
                        jsonString(o, "passId"),
                        jsonString(o, "slotRole"))
                .withExtractionSourceObjectIds(
                        jsonIntArray(o, "exportSourceObjectIds"),
                        jsonIntArray(o, "hiddenVisualSourceObjectIds"));
    }

    private static String jsonString(JsonObject o, String key) {
        if (o == null || key == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    private static TextLayoutContract textLayoutContractFromJson(JsonObject o) {
        if (o == null || !o.has("textLayoutContract") || o.get("textLayoutContract").isJsonNull()) {
            return null;
        }
        JsonElement element = o.get("textLayoutContract");
        if (element == null || !element.isJsonObject()) return null;
        JsonObject contract = element.getAsJsonObject();
        String type = jsonString(contract, "type");
        if (!TextLayoutContract.SOURCE_TEXT_WRAP.equals(type)) return null;
        int textFrameId = jsonInt(contract, "textFrameId", -1);
        if (textFrameId < 0) return null;
        return new TextLayoutContract(
                type,
                jsonString(contract, "source"),
                textFrameId,
                jsonString(contract, "wrapSide"),
                jsonIntArray(contract, "obstacleSourceObjectIds"),
                jsonStringArray(contract, "obstacleObjectPlanIds"),
                jsonInt(contract, "lineCount", 0),
                jsonString(contract, "reason"));
    }

    private static int jsonInt(JsonObject o, String key, int fallback) {
        if (o == null || key == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try {
            return o.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean jsonBoolean(JsonObject o, String key, boolean fallback) {
        if (o == null || key == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try {
            return o.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int[] jsonIntArray(JsonObject o, String key) {
        if (o == null || key == null || !o.has(key) || !o.get(key).isJsonArray()) return new int[0];
        JsonArray arr = o.getAsJsonArray(key);
        int[] out = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = arr.get(i).isJsonNull() ? 0 : arr.get(i).getAsInt();
        }
        return out;
    }

    private static String[] jsonStringArray(JsonObject o, String key) {
        if (o == null || key == null || !o.has(key) || !o.get(key).isJsonArray()) return new String[0];
        JsonArray arr = o.getAsJsonArray(key);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonNull()) continue;
            String value = arr.get(i).getAsString();
            if (value == null || value.isEmpty()) continue;
            out.add(value);
        }
        return out.toArray(new String[0]);
    }

    private static int[] jsonSourceSetArray(
            JsonObject o,
            String arrayKey,
            String refKey,
            Map<String, int[]> sourceSetRefs) {
        int[] direct = jsonIntArray(o, arrayKey);
        if (direct.length > 0 || o == null || refKey == null || sourceSetRefs == null) {
            return direct;
        }
        String ref = jsonString(o, refKey);
        if (ref == null || ref.isEmpty()) return direct;
        int[] resolved = sourceSetRefs.get(ref);
        return resolved != null ? Arrays.copyOf(resolved, resolved.length) : direct;
    }

    private static Map<String, int[]> objectPlanSourceSetRefs(JsonObject root) {
        Map<String, int[]> out = new HashMap<>();
        if (root == null || !root.has("sourceSetRefs") || !root.get("sourceSetRefs").isJsonObject()) {
            return out;
        }
        JsonObject refs = root.getAsJsonObject("sourceSetRefs");
        if (!refs.has("sourceSets") || !refs.get("sourceSets").isJsonArray()) {
            return out;
        }
        JsonArray sets = refs.getAsJsonArray("sourceSets");
        for (JsonElement element : sets) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            String id = jsonString(row, "sourceSetId");
            if (id == null || id.isEmpty()) continue;
            out.put(id, jsonIntArray(row, "sourceObjectIds"));
        }
        return out;
    }

    private static double[] jsonDoubleArray(JsonObject o, String key) {
        if (o == null || key == null || !o.has(key) || !o.get(key).isJsonArray()) return null;
        JsonArray arr = o.getAsJsonArray(key);
        double[] out = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = arr.get(i).isJsonNull() ? 0.0 : arr.get(i).getAsDouble();
        }
        return out;
    }

    private static <E extends Enum<E>> E strictEnumValue(Class<E> type, String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void writeTextFlowDiagnostics() {
        refreshTextFlowSnapshot();
        TextFlowDiagnosticsWriter.write(this.ctx, this.ctx.textFlowDiagnostics);
    }

    private void prepareTextFlowIndex() {
        refreshTextFlowSnapshot();
    }

    private void refreshTextFlowSnapshot() {
        TextFlowDiagnostics diagnostics = TextFlowDiagnosticsBuilder.build(this.ctx);
        this.ctx.textFlowDiagnostics = diagnostics;
        this.ctx.textFlowIndex = TextFlowIndex.from(diagnostics);
        this.ctx.textFlowDocument = TextFlowDocumentBuilder.build(diagnostics, this.ctx.textFlowIndex);
    }

    /**
     * Stage 2.5 (source ownership policy): Stage 2(텍스트/인라인 분류) 이후, 시각 배치(Phase 6/7) 이전에
     * visual ownership을 refine한다. Phase 0 planner는 Stage 2 산출물(셀 인라인 임베드, 컨셉
     * 다이어그램 TF 등)을 볼 수 없으므로, 그에 의존하는 suppress 결정을 여기서 plan(DROP_VISUAL)으로
     * 확정한다. 이렇게 하면 Phase 6/7은 휴리스틱 대신 plan 권위만 실행한다.
     *
     * <p>현재 이전 완료: cellInlineEmbeddedDomIds(셀에 인라인 임베드된 배지의 원본 floating PNG).
     * 후속: childOfGroup(부모에 구워진 자식) 등 — 각 클래스 골든디프 게이트로 단계 이전.</p>
     */
    /**
     * Stage 1/2: 현재는 legacy Phase들이 ownership 일부와 text/table 생성을
     * 함께 수행한다.
     *
     * <p>목표 구조에서는 Stage 1 OwnershipPlanner가 먼저 ObjectPlan을 만들고,
     * 이 메서드는 HWPX가 소유하는 텍스트/테이블만 생성한다. 따라서 새 분류
     * 로직은 이 메서드 안의 legacy Phase가 아니라 Planner로 이동해야 한다.</p>
     */
    private void buildTextContent(List<ASTSection> sections) {
        Set<ASTBlock> before;
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2.textBuilder.framePlacer")) {
            before = snapshotBlocks(sections);
            FramePlacer.placeTextFrames(this.ctx, sections);
        }
        traceAstProduction("Stage2.TextBuilder.legacyFramePlacer", sections, before);
        tagPhase(sections, "Stage2.TextBuilder.legacyFramePlacer");

        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2.textBuilder.storyConverter")) {
            before = snapshotBlocks(sections);
            StoryConverter.convertStories(this.ctx, sections);
        }
        traceAstProduction("Stage2.TextBuilder.legacyStoryConverter", sections, before);
        tagPhase(sections, "Stage2.TextBuilder.legacyStoryConverter");

        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage2.textBuilder.tableBuilder")) {
            before = snapshotBlocks(sections);
            TableBuilder.placeTablesFromIDML(this.ctx, sections);
        }
        traceAstProduction("Stage2.TextBuilder.legacyTableBuilder", sections, before);
        tagPhase(sections, "Stage2.TextBuilder.legacyTableBuilder");
    }

    /**
     * Stage 4: 생성된 레이아웃을 보정한다.
     *
     * <p>이 단계는 줄바꿈/불릿/폭 보정처럼 이미 생성된 AST의 형태만 조정한다.
     * 새 visible 객체를 만들거나 ownership을 뒤집는 로직을 추가하지 않는다.</p>
     */
    private void postprocessLayout(List<ASTSection> sections) {
        Set<ASTBlock> before;
        try (ConversionTiming.Scope ignored = ConversionTiming.time("stage4.layoutPostprocess.bulletInserter")) {
            before = snapshotBlocks(sections);
            BulletInserter.run(this.ctx, sections);
        }
        traceAstProduction("Stage4.LayoutPostprocess.insertBullets", sections, before);
        tagPhase(sections, "Stage4.LayoutPostprocess.insertBullets");

    }

    /**
     * Stage 3: 시각 객체를 배치한다.
     *
     * <p>VisualBuilder가 ObjectPlan의 visualAction/zOrder 실행 책임을 단일
     * 진입점으로 갖는다. 내부 legacy executor는 단계적으로 흡수 후 제거한다.</p>
     */
    private void placeVisuals(List<ASTSection> sections) {
        Set<ASTBlock> before = snapshotBlocks(sections);
        VisualBuilder.place(this.ctx, sections);
        traceAstProduction("Stage3.VisualBuilder", sections, before);
        tagPhase(sections, "Stage3.VisualBuilder");
    }

    private void writeRenderDecisionLog() {
        if (basePath == null || ctx == null || ctx.renderDecisionLines == null
                || ctx.renderDecisionLines.isEmpty()) {
            return;
        }
        try {
            java.nio.file.Path out = java.nio.file.Paths.get(basePath, "render-decisions.jsonl");
            java.nio.file.Files.write(out, ctx.renderDecisionLines,
                    java.nio.charset.StandardCharsets.UTF_8);
            System.err.println("[ResolvedToASTBuilder] render decisions: " + out);
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] render decision log write failed: " + e.getMessage());
        }
    }

    private void writeOwnershipPlanLog() {
        if (basePath == null || ctx == null) {
            return;
        }
        ctx.rebuildOwnershipPlanLinesFromPlans();
        writeJsonLines("ownership-plan.jsonl", ctx.ownershipPlanLines, "ownership plan");
        writeJsonLines("ownership-warnings.jsonl", ctx.ownershipWarningLines, "ownership warnings");
        writeJsonLines("ownership-legacy-bridge-added.jsonl",
                ctx.legacyBridgeAddedPlanLines,
                "ownership legacy bridge added plans");
        writeJsonLines("ownership-legacy-bridge-mutated.jsonl",
                ctx.legacyBridgeMutatedPlanLines,
                "ownership legacy bridge mutated plans");
        writeJsonLines("ownership-legacy-bridge-summary.jsonl",
                ctx.legacyBridgeSummaryLines,
                "ownership legacy bridge summary");
        writeJsonLines("ast-production.jsonl", ctx.astProductionLines, "ast production");
        writeOwnershipTraceLog();
    }

    private Set<ASTBlock> snapshotBlocks(List<ASTSection> sections) {
        Set<ASTBlock> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (sections == null) return seen;
        for (ASTSection section : sections) {
            if (section == null || section.blocks() == null) continue;
            seen.addAll(section.blocks());
        }
        return seen;
    }

    private void traceAstProduction(String producer, List<ASTSection> sections, Set<ASTBlock> before) {
        if (ctx == null || ctx.astProductionLines == null || sections == null) return;
        Set<ASTBlock> oldBlocks = before != null ? before : Collections.emptySet();
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            ASTSection section = sections.get(sectionIndex);
            if (section == null || section.blocks() == null) continue;
            for (int blockIndex = 0; blockIndex < section.blocks().size(); blockIndex++) {
                ASTBlock block = section.blocks().get(blockIndex);
                if (block == null || oldBlocks.contains(block)) continue;
                ctx.astProductionLines.add(astProductionLine(producer, sectionIndex, blockIndex, block));
            }
        }
    }

    private String astProductionLine(String producer, int sectionIndex, int blockIndex, ASTBlock block) {
        JsonObject row = new JsonObject();
        row.addProperty("producer", producer);
        row.addProperty("sectionIndex", sectionIndex);
        row.addProperty("blockIndex", blockIndex);
        row.addProperty("blockType", block.blockType() != null ? block.blockType().name() : null);
        row.addProperty("sourceId", block.sourceId());
        addBlockGeometry(row, block);
        addBlockSpecifics(row, block);
        row.addProperty("preview", previewBlock(block));
        return row.toString();
    }

    private void addBlockGeometry(JsonObject row, ASTBlock block) {
        if (block instanceof ASTTextFrameBlock) {
            ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
            row.addProperty("x", tf.x());
            row.addProperty("y", tf.y());
            row.addProperty("width", tf.width());
            row.addProperty("height", tf.height());
            row.addProperty("zOrder", tf.zOrder());
            row.addProperty("storyId", tf.storyId());
            row.addProperty("plannedShellVisualLayer", tf.plannedShellVisualLayer());
            row.addProperty("plannedVisualTextOverlay", tf.plannedVisualTextOverlay());
            row.addProperty("inlineToFloating", tf.inlineToFloating());
        } else if (block instanceof ASTTable) {
            ASTTable table = (ASTTable) block;
            row.addProperty("x", table.x());
            row.addProperty("y", table.y());
            row.addProperty("width", table.width());
            row.addProperty("height", table.height());
            row.addProperty("zOrder", table.zOrder());
            row.addProperty("rowCount", table.rowCount());
            row.addProperty("colCount", table.colCount());
            row.addProperty("flowWithText", table.flowWithText());
            row.addProperty("anchoredFlowWithText", table.anchoredFlowWithText());
        } else if (block instanceof ASTFigure) {
            ASTFigure figure = (ASTFigure) block;
            row.addProperty("x", figure.x());
            row.addProperty("y", figure.y());
            row.addProperty("width", figure.width());
            row.addProperty("height", figure.height());
            row.addProperty("zOrder", figure.zOrder());
            row.addProperty("kind", figure.kind() != null ? figure.kind().name() : null);
            row.addProperty("visualLayer", figure.visualLayer());
            row.addProperty("sourceLayerIndex", figure.sourceLayerIndex());
        }
    }

    private void addBlockSpecifics(JsonObject row, ASTBlock block) {
        if (block instanceof ASTFigure) {
            ASTFigure figure = (ASTFigure) block;
            row.addProperty("imagePath", figure.imagePath());
            row.addProperty("bundlePath", figure.bundlePath());
            row.addProperty("extractionCandidateId", figure.extractionCandidateId());
            row.addProperty("extractionPlanPassId", figure.extractionPlanPassId());
            row.addProperty("extractionSlotRole", figure.extractionSlotRole());
            row.addProperty("parentGroupId", figure.parentGroupId());
            row.addProperty("fromGroup", figure.fromGroup());
        }
    }

    private String previewBlock(ASTBlock block) {
        StringBuilder sb = new StringBuilder();
        if (block instanceof ASTTextFrameBlock) {
            ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
            appendParagraphsPreview(sb, tf.paragraphs());
        } else if (block instanceof ASTTable) {
            ASTTable table = (ASTTable) block;
            appendTablePreview(sb, table);
        } else if (block instanceof ASTFigure) {
            ASTFigure figure = (ASTFigure) block;
            appendPreviewToken(sb, figure.bundlePath());
            appendPreviewToken(sb, figure.imagePath());
        }
        String preview = sb.toString().replace('\n', ' ').replace('\r', ' ').trim();
        return preview.length() > 240 ? preview.substring(0, 240) : preview;
    }

    private void appendTablePreview(StringBuilder sb, ASTTable table) {
        if (table == null || table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                appendParagraphsPreview(sb, cell.paragraphs());
                if (sb.length() > 260) return;
            }
        }
    }

    private void appendParagraphsPreview(StringBuilder sb, List<ASTParagraph> paragraphs) {
        if (paragraphs == null) return;
        for (ASTParagraph paragraph : paragraphs) {
            appendParagraphPreview(sb, paragraph);
            appendPreviewToken(sb, " ");
            if (sb.length() > 260) return;
        }
    }

    private void appendParagraphPreview(StringBuilder sb, ASTParagraph paragraph) {
        if (paragraph == null) return;
        if (paragraph.items() != null) {
            for (ASTInlineItem item : paragraph.items()) {
                appendInlineItemPreview(sb, item);
                if (sb.length() > 260) return;
            }
        }
        if (paragraph.inlineTable() != null) {
            appendPreviewToken(sb, "[inline-table]");
            appendTablePreview(sb, paragraph.inlineTable());
        }
    }

    private void appendInlineItemPreview(StringBuilder sb, ASTInlineItem item) {
        if (item == null) return;
        if (item instanceof ASTTextRun) {
            ASTTextRun run = (ASTTextRun) item;
            appendPreviewToken(sb, run.text());
        } else if (item instanceof ASTEquation) {
            ASTEquation equation = (ASTEquation) item;
            appendPreviewToken(sb, "[eq:" + safePreview(equation.hwpScript()) + "]");
        } else if (item instanceof ASTInlineObject) {
            ASTInlineObject object = (ASTInlineObject) item;
            appendPreviewToken(sb, "[inline:" + safePreview(object.sourceId()) + "]");
            if (object.paragraphs() != null) {
                appendParagraphsPreview(sb, object.paragraphs());
            }
        } else if (item instanceof ASTBreak) {
            appendPreviewToken(sb, " ");
        }
    }

    private void appendPreviewToken(StringBuilder sb, String text) {
        if (text == null || text.isEmpty()) return;
        sb.append(text);
    }

    private String safePreview(String text) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 48 ? t.substring(0, 48) : t;
    }

    private void writeOwnershipTraceLog() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("phase\tdecision\tpageIndex\trenderId\texportUnitId\tcandidateId\tplanPassId\tslotRole\tsourceFile\thwpxFile\tobjectPlanDomId\tobjectPlanCandidateId\ttextAction\tvisualAction\tplacement\tvisualLayer\tmaterialization\tsourceObjectIds\texportSourceObjectIds\thiddenVisualSourceObjectIds\tdetail");
        if (ctx != null && ctx.ownershipTraceLines != null) {
            lines.addAll(ctx.ownershipTraceLines);
        }
        writeJsonLines("ownership-trace.tsv", lines, "ownership trace");
    }

    private void writeJsonLines(String fileName, java.util.List<String> lines, String label) {
        try {
            java.nio.file.Path out = java.nio.file.Paths.get(basePath, fileName);
            java.nio.file.Files.write(out,
                    lines != null ? lines : java.util.Collections.emptyList(),
                    java.nio.charset.StandardCharsets.UTF_8);
            System.err.println("[ResolvedToASTBuilder] " + label + ": " + out);
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] " + label + " write failed: " + e.getMessage());
        }
    }

    /** SPEC-016: 매칭 신뢰도 비율을 stderr에 리포트. */
    private void reportSpec016Counts() {
        int total = spec016Counts[0] + spec016Counts[1] + spec016Counts[2];
        if (total > 0) {
            System.err.println("[SPEC-016] HIGH=" + spec016Counts[0]
                    + " MEDIUM=" + spec016Counts[1]
                    + " LOW=" + spec016Counts[2]
                    + " (total=" + total + ")");
        }
    }

    /**
     * SPEC-015: 디버그 모드일 때 phase 종료 후 createdAt 미설정 블록을 현재 phase로 태그.
     * 새로 추가된 블록만 식별하기 어려우므로 "처음 발견 시점"을 createdAt으로 본다 — 한 번
     * 태그된 블록은 다음 phase가 덮어쓰지 않으므로 "최초로 만든 phase"가 정확히 기록된다.
     */
    private void tagPhase(List<ASTSection> sections, String phase) {
        if (!debugAst) return;
        for (ASTSection s : sections) {
            for (ASTBlock b : s.blocks()) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.DebugMeta dm = b.debug();
                if (dm == null || dm.createdAt == null) {
                    b.debugOrNew().createdAt = phase;
                }
            }
        }
    }

    // ─── ctx에 노출되는 인스턴스 헬퍼들 ─────────────────────

    /**
     * document pageIndex (페이지 오프셋) → sections 리스트 인덱스 변환.
     * 부분 추출 시 pageIndex가 4,5,6이지만 sections는 0,1,2인 경우 매핑.
     * ctx.toSectionIndex로 phase에 노출.
     */
    private int toSectionIndex(int docPageIndex) {
        if (pageDocOffsetToSection == null) return docPageIndex;
        Integer secIdx = pageDocOffsetToSection.get(docPageIndex);
        return secIdx != null ? secIdx : docPageIndex;
    }

    /**
     * IDML Story XML을 로드하고 캐시한다. ctx.loadIDMLStory로 phase에 노출.
     */
    private IDMLStory loadIDMLStory(String storyId) {
        if (idmlStoryCache.containsKey(storyId)) return idmlStoryCache.get(storyId);

        String sourceStoryId = sourceStoryId(storyId);
        String hexId;
        try {
            if (sourceStoryId.startsWith("u") || sourceStoryId.startsWith("U")) {
                // IDML hex format: "u4daf" → hexId = "4daf"
                hexId = sourceStoryId.substring(1).toLowerCase();
            } else {
                // decimal format (from resolved.json): "17203" → hex
                hexId = Integer.toHexString(Integer.parseInt(sourceStoryId));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        java.io.File storyFile = new java.io.File(idmlDir, "Stories/Story_u" + hexId + ".xml");
        if (!storyFile.exists()) return null;

        try {
            ensureIdmlDocument();
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            org.w3c.dom.Document xmlDoc = factory.newDocumentBuilder().parse(storyFile);
            IDMLStory story = IDMLStoryParser.parseStory(xmlDoc, "u" + hexId, idmlDocument);
            idmlStoryCache.put(storyId, story);
            if (!sourceStoryId.equals(storyId)) idmlStoryCache.put(sourceStoryId, story);
            idmlStoryCache.put("u" + hexId, story);
            return story;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sourceStoryId(String storyId) {
        if (storyId == null) return "";
        int cut = -1;
        int pi = storyId.indexOf("_pi");
        int oc = storyId.indexOf("_oc");
        if (pi >= 0) cut = pi;
        if (oc >= 0) cut = cut < 0 ? oc : Math.min(cut, oc);
        return cut >= 0 ? storyId.substring(0, cut) : storyId;
    }

    // 텍스트 기반 resolved 런 매칭 — 매칭 성공 시 인덱스 추적용 (Phase 3에서 ctx.lastMatchResult로 공유)
    private final int[] lastMatchResult = new int[]{0}; // [0]=matched index

    /**
     * build() 시작 시 1회 호출되는 컨텍스트 생성기.
     * 모든 phase가 this.ctx를 공유하며, 메서드 ref/Supplier로 빌드 도중 변하는 상태
     * (idmlDocument, pageDocOffsetToSection 등)를 동적으로 반영한다.
     */
    private ResolvedBuildContext newContext() {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = this.resolvedData;
        ctx.basePath = this.basePath;
        ctx.scaleFactor = this.scaleFactor;
        ctx.idmlDir = this.idmlDir;
        ctx.pngExportDpi = this.pngExportDpi;
        ctx.astDocument = this.astDoc;
        ctx.styleResolver = this.styleResolver;
        ctx.pageDocOffsetToSection = this.pageDocOffsetToSection;
        ctx.toSectionIndex = this::toSectionIndex;
        ctx.ensureIdmlInfra = this::ensureIdmlInfra;
        ctx.ensureColorInfra = this::ensureColorInfra;
        ctx.ensureImageInfra = this::ensureImageInfra;
        ctx.idmlDocumentSupplier = () -> this.idmlDocument;
        ctx.colorResolverSupplier = () -> this.colorResolver;
        ctx.imageLoaderSupplier = () -> this.imageLoader;
        ctx.loadIDMLStory = this::loadIDMLStory;
        ctx.spec016Counts = this.spec016Counts;
        ctx.idmlStoryCache = this.idmlStoryCache;
        ctx.lastMatchResult = this.lastMatchResult;
        ctx.tableQualityGate = this.tableQualityGate;
        ctx.debugAst = this.debugAst;
        return ctx;
    }
}
