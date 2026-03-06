package kr.dogfoot.hwpxlib.tool.idmlconverter;

/**
 * IDML -> HWPX 변환 옵션.
 */
public class ConvertOptions {
    private int startPage;
    private int endPage;
    private boolean includeImages;
    private boolean includeEquations;
    private boolean includeStyles;
    private String imageOutputDir;
    private int imageDpi;
    private int vectorDpi;
    private boolean singlePagePerSpread;
    private boolean mergeAllPages;
    private boolean spreadBasedConversion;
    private boolean drawPageBoundary;
    private String layoutMode;
    private String imageCacheDir;
    private String resolvedJsonPath;
    private String fontMapPath;
    private String linksDirectory;

    public ConvertOptions() {
        this.startPage = 0;
        this.endPage = 0;
        this.includeImages = true;
        this.includeEquations = true;
        this.includeStyles = true;
        this.imageOutputDir = null;
        this.imageDpi = 220;
        this.vectorDpi = 300;
        this.singlePagePerSpread = false;
        this.mergeAllPages = false;
        this.spreadBasedConversion = false;
        this.drawPageBoundary = false;
        this.layoutMode = "preserve";
    }

    public static ConvertOptions defaults() {
        return new ConvertOptions();
    }

    public int startPage() {
        return startPage;
    }

    public ConvertOptions startPage(int startPage) {
        this.startPage = startPage;
        return this;
    }

    public int endPage() {
        return endPage;
    }

    public ConvertOptions endPage(int endPage) {
        this.endPage = endPage;
        return this;
    }

    public boolean includeImages() {
        return includeImages;
    }

    public ConvertOptions includeImages(boolean includeImages) {
        this.includeImages = includeImages;
        return this;
    }

    public boolean includeEquations() {
        return includeEquations;
    }

    public ConvertOptions includeEquations(boolean includeEquations) {
        this.includeEquations = includeEquations;
        return this;
    }

    public boolean includeStyles() {
        return includeStyles;
    }

    public ConvertOptions includeStyles(boolean includeStyles) {
        this.includeStyles = includeStyles;
        return this;
    }

    public String imageOutputDir() {
        return imageOutputDir;
    }

    public ConvertOptions imageOutputDir(String imageOutputDir) {
        this.imageOutputDir = imageOutputDir;
        return this;
    }

    public int imageDpi() {
        return imageDpi;
    }

    public ConvertOptions imageDpi(int imageDpi) {
        this.imageDpi = imageDpi;
        return this;
    }

    /**
     * 벡터 그래픽 렌더링 DPI.
     * IDML의 벡터 도형을 PNG로 래스터화할 때 사용하는 해상도.
     * 기본값: 300
     */
    public int vectorDpi() {
        return vectorDpi;
    }

    public ConvertOptions vectorDpi(int vectorDpi) {
        this.vectorDpi = vectorDpi;
        return this;
    }

    /**
     * 스프레드 단위로 하나의 페이지로 합칠지 여부.
     * true이면 facing pages(양면 페이지)를 하나의 페이지로 합친다.
     * 이 옵션을 사용하면 스프레드의 모든 프레임이 첫 번째 페이지에 배치된다.
     */
    public boolean singlePagePerSpread() {
        return singlePagePerSpread;
    }

    public ConvertOptions singlePagePerSpread(boolean singlePagePerSpread) {
        this.singlePagePerSpread = singlePagePerSpread;
        return this;
    }

    /**
     * 모든 페이지를 하나의 페이지로 합칠지 여부.
     * true이면 선택된 페이지 범위 전체를 하나의 페이지로 합친다.
     * 스프레드 경계와 관계없이 모든 프레임이 첫 번째 페이지에 배치된다.
     */
    public boolean mergeAllPages() {
        return mergeAllPages;
    }

    public ConvertOptions mergeAllPages(boolean mergeAllPages) {
        this.mergeAllPages = mergeAllPages;
        return this;
    }

    /**
     * 스프레드 기반 변환 모드.
     * true이면 IDML 스프레드를 HWPX 페이지로 1:1 매핑한다.
     * 스프레드의 전체 크기가 HWPX 용지 크기가 되고,
     * 모든 프레임은 스프레드 좌표계로 변환된다.
     * 페이지 경계 판정이 불필요하며, 경계에 걸친 객체도 자연스럽게 처리된다.
     */
    public boolean spreadBasedConversion() {
        return spreadBasedConversion;
    }

    public ConvertOptions spreadBasedConversion(boolean spreadBasedConversion) {
        this.spreadBasedConversion = spreadBasedConversion;
        return this;
    }

    /**
     * 페이지 경계선 그리기 옵션.
     * true이면 PNG 배경에 페이지 경계선을 그리고,
     * HWPX에도 페이지 경계를 나타내는 벡터 사각형을 추가한다.
     * 인디자인 스프레드에서 개별 페이지 영역을 시각적으로 표시한다.
     */
    public boolean drawPageBoundary() {
        return drawPageBoundary;
    }

    public ConvertOptions drawPageBoundary(boolean drawPageBoundary) {
        this.drawPageBoundary = drawPageBoundary;
        return this;
    }

    /**
     * 레이아웃 모드.
     * "preserve": 원본 레이아웃 유지 (분할 글상자)
     * "editable": 편집 우선 (1단 병합) — 향후 구현 예정
     */
    public String layoutMode() {
        return layoutMode;
    }

    public ConvertOptions layoutMode(String layoutMode) {
        this.layoutMode = layoutMode;
        return this;
    }

    /**
     * 디자인 파일(PSD/AI/EPS) PNG 변환 캐시 디렉토리.
     * 설정하면 Links 폴더 대신 이 디렉토리에 캐시 파일을 저장한다.
     */
    public String imageCacheDir() {
        return imageCacheDir;
    }

    public ConvertOptions imageCacheDir(String imageCacheDir) {
        this.imageCacheDir = imageCacheDir;
        return this;
    }

    /**
     * resolved.json 경로.
     * InDesign ExtendScript에서 수집한 resolved 데이터 (색상 hex, per-run 속성 등).
     * 설정하면 AST 빌드 후 resolved 데이터로 보강한다.
     */
    public String resolvedJsonPath() {
        return resolvedJsonPath;
    }

    public ConvertOptions resolvedJsonPath(String resolvedJsonPath) {
        this.resolvedJsonPath = resolvedJsonPath;
        return this;
    }

    /**
     * 사용자 지정 폰트 매핑 JSON 파일 경로.
     * JSON 형식: {"IDML폰트명": "HWPX폰트명", ...}
     * 설정하면 FontMapper 기본 매핑보다 우선 적용된다.
     */
    public String fontMapPath() {
        return fontMapPath;
    }

    public ConvertOptions fontMapPath(String fontMapPath) {
        this.fontMapPath = fontMapPath;
        return this;
    }

    /**
     * 외부 Links 디렉토리 경로.
     * INDD에서 추출한 IDML이 임시 디렉토리에 있을 때,
     * 원본 INDD 옆의 Links 폴더 경로를 지정한다.
     */
    public String linksDirectory() {
        return linksDirectory;
    }

    public ConvertOptions linksDirectory(String linksDirectory) {
        this.linksDirectory = linksDirectory;
        return this;
    }

}
