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
    private String linksDirectory;

    public ConvertOptions() {
        this.startPage = 0;
        this.endPage = 0;
        this.includeImages = true;
        this.includeEquations = true;
        this.includeStyles = true;
        this.imageOutputDir = null;
        this.imageDpi = 72;
        this.linksDirectory = null;
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
     * 이미지 파일이 위치한 Links 디렉토리 경로.
     * IDML 파일에 저장된 이미지 경로가 다른 컴퓨터의 절대 경로인 경우,
     * 이 옵션으로 실제 Links 폴더 위치를 지정할 수 있다.
     */
    public String linksDirectory() {
        return linksDirectory;
    }

    public ConvertOptions linksDirectory(String linksDirectory) {
        this.linksDirectory = linksDirectory;
        return this;
    }
}
