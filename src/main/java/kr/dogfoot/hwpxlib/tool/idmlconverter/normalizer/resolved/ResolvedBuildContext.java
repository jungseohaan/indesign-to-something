package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.StylePropertyResolver;
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

    /**
     * Phase 2 가 non-editable inline TF 를 inlineToFloating 으로 전환할 때,
     * 해당 TF 의 조상 inline_object 를 여기에 등록한다.
     * Phase 3 (loadInlineObject) 는 이 집합에 있는 ID 의 inline PNG 를 inline 배치에서 억제하고,
     * Phase 7 이 동일 PNG 를 플로팅 ASTFigure 로 재배치한다.
     */
    public java.util.Set<Integer> inlineObjectsToConvertToFloating = new java.util.HashSet<>();

    /**
     * inline_object ID → TF 의 pageIndex 매핑.
     * inline_object의 renderedGroup.pageIndex()가 TF의 실제 섹션과 다를 수 있으므로
     * Phase 2 에서 TF 의 pageIndex 를 함께 저장해 Phase 7b 가 올바른 섹션에 배치.
     */
    public java.util.Map<Integer, Integer> inlineObjectTfPageIndex = new java.util.HashMap<>();

    /**
     * Phase 2 가 non-editable 플로팅 TF 를 텍스트 글상자로 배치할 때 등록.
     * Phase 7 은 이 집합에 있는 ID 의 PNG 를 건너뜀 (텍스트 글상자가 이미 배치됨).
     */
    public java.util.Set<Integer> renderedTfPlacedAsText = new java.util.HashSet<>();


    public ResolvedBuildContext() {
    }
}
