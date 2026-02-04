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
    /** 1 point = 283.4645 HWPUNIT */
    public static final double HWPUNIT_PER_POINT = 283.4645;

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

    // ===== 기본값 =====
    /** 기본 폰트 크기 (HWPUNIT) */
    public static final int DEFAULT_FONT_SIZE_HWPUNIT = 1000;

    /** 기본 줄 간격 (%) */
    public static final int DEFAULT_LINE_SPACING_PERCENT = 130;

    /** 기본 컬럼 간격 (points) */
    public static final double DEFAULT_COLUMN_GUTTER_POINTS = 12.0;
}
