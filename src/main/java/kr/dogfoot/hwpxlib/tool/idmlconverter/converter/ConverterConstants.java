package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

/**
 * IDML → HWPX 변환에 사용되는 상수 정의.
 */
public final class ConverterConstants {
    private ConverterConstants() {}

    // ===== 렌더링 DPI =====
    /** 벡터 그래픽 렌더링 기본 DPI */
    public static final int DEFAULT_VECTOR_DPI = 300;

    /** 이미지 처리 기본 DPI */
    public static final int DEFAULT_IMAGE_DPI = 72;

    // ===== 좌표 변환 =====
    /** 1 point = 100 HWPUNIT (7200 hwpunit/inch ÷ 72 points/inch) */
    public static final double HWPUNIT_PER_POINT = 100.0;

    /** 1 pixel (72 DPI) = 100 HWPUNIT */
    public static final int HWPUNIT_PER_PIXEL_72DPI = 100;

    /** 1 pixel (75 DPI) = 75 HWPUNIT (근사값) */
    public static final int HWPUNIT_PER_PIXEL_75DPI = 75;

    /** 1 inch = 7200 HWPUNIT */
    public static final int HWPUNIT_PER_INCH = 7200;

    // ===== 이미지 처리 =====
    /** 배경 이미지 판정 임계값 (페이지 면적의 80% 이상) */
    public static final double BACKGROUND_IMAGE_AREA_THRESHOLD = 0.8;

    /** 최소 이미지 크기 (HWPUNIT) */
    public static final int MIN_IMAGE_SIZE_HWPUNIT = 100;

    /** 이미지 리사이즈 임계값 (20% 이상 차이 시 리사이즈) */
    public static final double IMAGE_RESIZE_THRESHOLD = 1.2;

    // ===== 프레임 위치 =====
    /** 프레임 위치 허용 오차 (points) */
    public static final double POSITION_TOLERANCE_POINTS = 3.0;

    /** 프레임 크기 허용 오차 (points) */
    public static final double SIZE_TOLERANCE_POINTS = 5.0;

    // ===== HWPX ID 시작값 =====
    /** 단락 ID 시작값 */
    public static final long PARA_ID_START = 1000000000L;

    /** 도형 ID 시작값 */
    public static final long SHAPE_ID_START = 5000000L;

    // ===== 렌더링 품질 =====
    /** 벡터 렌더링 시 픽셀-HWPUNIT 비율 (300 DPI 기준) */
    public static final double PIXEL_TO_HWPUNIT_RATIO_300DPI = 7200.0 / 300.0; // = 24

    // ===== 글상자 최소 크기 =====
    /** 글상자 최소 너비 (HWPUNIT) */
    public static final long MIN_TEXT_BOX_WIDTH = 142;

    /** 글상자 최소 높이 — 한 줄 (HWPUNIT) */
    public static final long MIN_TEXT_BOX_HEIGHT = 1600;

    // ===== 인라인 객체 임계값 =====
    /**
     * 인라인 이미지 높이가 이 값(HWPUNIT)을 초과하면 자리차지(TOP_AND_BOTTOM)로 전환.
     * 5000 HWPUNIT = 50pt ≈ 17.6mm (약 4줄 높이).
     */
    public static final long INLINE_IMAGE_HEIGHT_THRESHOLD = 5000;

    // ===== 기본값 =====
    /** 기본 폰트 크기 (HWPUNIT) */
    public static final int DEFAULT_FONT_SIZE_HWPUNIT = 1000;

    /** 기본 줄 간격 (%) */
    public static final int DEFAULT_LINE_SPACING_PERCENT = 130;

    /** 기본 컬럼 간격 (points) */
    public static final double DEFAULT_COLUMN_GUTTER_POINTS = 12.0;

    // ===== SecPr 기본값 =====
    /** 탭 정지 간격 (HWPUNIT) */
    public static final int TAB_STOP = 8000;

    /** 탭 정지 값 (HWPUNIT) */
    public static final int TAB_STOP_VAL = 4000;

    /** 각주 사이 간격 (HWPUNIT) */
    public static final int FOOTNOTE_BETWEEN_NOTES = 283;

    /** 각주 줄 아래 간격 (HWPUNIT) */
    public static final int FOOTNOTE_BELOW_LINE = 567;

    /** 각주 줄 위 간격 (HWPUNIT) */
    public static final int FOOTNOTE_ABOVE_LINE = 850;

    // ===== 인라인 이미지 줄간격 =====
    /** 인라인 이미지/수식이 이 높이(HWPUNIT)를 넘으면 줄 간격 자동 조정 */
    public static final int INLINE_LINE_SPACING_THRESHOLD = 2000;
}
