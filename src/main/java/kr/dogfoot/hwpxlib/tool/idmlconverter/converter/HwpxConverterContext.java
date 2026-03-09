package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.FontRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ASTToHwpxConverter의 공유 상태를 보관한다.
 * 각 Builder 클래스가 이 컨텍스트를 통해 hwpxFile, 레지스트리, 캐시 등에 접근한다.
 */
public class HwpxConverterContext {
    final HWPXFile hwpxFile;
    public final StyleRegistry styleRegistry;
    public final FontRegistry fontRegistry;
    public final List<ASTStyleDef> paragraphStyles;
    final AtomicInteger borderFillIdCounter;

    // CharPr 캐시: 동일한 인라인 오버라이드 조합을 재사용
    final Map<String, String> charPrCache = new LinkedHashMap<>();
    // 수식 폰트 CharPr 캐시
    final Map<String, String> eqFontCharPrCache = new LinkedHashMap<>();
    // 1pt 폰트 CharPr ID (셀 높이가 작은 경우 사용)
    String tinyCharPrId;
    // cellHeight → tinyParaPr ID 매핑
    final Map<Long, String> tinyParaPrCache = new LinkedHashMap<>();

    // HwpxTextBoxBuilder.addInlineTextFrame() 에서 인라인 테이블 처리를 위한 참조
    public HwpxTableBuilder tableBuilderRef;

    // 현재 섹션의 컬럼 너비 (HWPUNIT) — 오버레이 위치 계산에 사용
    public long currentColumnWidth;

    // 연결 글상자 링크 추적
    // storyId → 사전 할당된 linkListIDRef 배열 (블록 순서대로)
    public final Map<String, java.util.List<String>> storyLinkIds = new LinkedHashMap<>();
    // storyId → 현재까지 변환된 블록 인덱스
    public final Map<String, Integer> storyLinkIndex = new LinkedHashMap<>();

    // ── 오버레이 페이지 레벨 승격 ──
    // 테이블 셀 내부의 오버레이 rect는 한글에서 정상 렌더링되지 않으므로
    // 페이지 레벨(PAPER-relative)로 승격하여 렌더링한다.
    public static class DeferredOverlay {
        public ASTInlineObject overlay;
        public long pageX, pageY;
    }
    public final List<DeferredOverlay> deferredOverlays = new ArrayList<>();

    // 현재 처리 중인 블록의 페이지 좌표 (오버레이 좌표 계산용)
    long blockPageX, blockPageY;
    long blockInsetLeft, blockInsetTop;
    long cellContentYCursor; // 셀 내 처리된 단락의 누적 높이

    // 현재 테이블 셀 내부 처리 중 여부 (오버레이 승격 판별용)
    boolean insideTableCell;

    // 스페이서 이미지 캐시 (1x1 투명 PNG, 한 번 등록 후 재사용)
    String spacerImageId;

    // 변환 통계
    public int imagesConverted;
    public int equationsConverted;
    public int framesConverted;

    public HwpxConverterContext(HWPXFile hwpxFile, StyleRegistry styleRegistry,
                                FontRegistry fontRegistry, List<ASTStyleDef> paragraphStyles) {
        this.hwpxFile = hwpxFile;
        this.styleRegistry = styleRegistry;
        this.fontRegistry = fontRegistry;
        this.paragraphStyles = paragraphStyles;
        this.borderFillIdCounter = new AtomicInteger(3);
    }
}
