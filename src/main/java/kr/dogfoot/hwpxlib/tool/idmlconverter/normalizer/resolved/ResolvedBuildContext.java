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

    public ResolvedBuildContext() {
    }
}
