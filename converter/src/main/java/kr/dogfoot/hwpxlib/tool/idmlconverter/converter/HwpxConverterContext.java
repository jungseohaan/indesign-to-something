package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.FontRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    // 현재 페이지 마진 (HWPUNIT) — 배경 이미지 PAGE 기준 위치 보정용
    public long pageMarginTop;
    public long pageMarginLeft;

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
    // 현재 테이블 셀의 TABLE_STYLE_SLOT fill. 투명 inline shell 이미지 브러시가
    // HWPX에서 알파를 잃는 경우 셀 배경과 같은 색으로 합성하기 위한 실행 컨텍스트다.
    String currentCellFillColor;

    // 현재 컨테이너(글상자 셀)의 내부 콘텐츠 폭 (HWPUNIT) — 인라인 텍스트 프레임 균등 분배에 사용
    long currentContainerWidth;

    // 스페이서 이미지 캐시 (1x1 투명 PNG, 한 번 등록 후 재사용)
    String spacerImageId;

    // 변환 통계
    public int imagesConverted;
    public int equationsConverted;
    public int framesConverted;

    // 변환 경고 수집
    private final List<String> warnings = new ArrayList<>();

    // HWPX zOrder는 음수/큰 갭을 안전하게 보존하지 못할 수 있다. Stage 1의
    // raw zOrder 순서는 유지하되, 섹션별로 HWPX가 해석 가능한 0 이상의 rank로
    // 직렬화한다. 이 맵은 ownership/layer 재판정이 아니라 출력 인코딩이다.
    private final Map<ASTBlock, Integer> outputZOrderByBlock = new IdentityHashMap<>();
    private final Map<Integer, Integer> outputZOrderByRaw = new LinkedHashMap<>();
    private int foregroundOutputZOrder = 1;

    public void addWarning(String category, String detail) {
        warnings.add("[" + category + "] " + detail);
    }

    public List<String> warnings() { return warnings; }

    public void beginSectionZOrder(List<ASTBlock> blocks) {
        outputZOrderByBlock.clear();
        outputZOrderByRaw.clear();
        TreeMap<Integer, Boolean> rawZOrders = new TreeMap<>();
        if (blocks != null) {
            for (ASTBlock block : blocks) {
                int raw = rawZOrder(block);
                rawZOrders.put(raw, Boolean.TRUE);
                if (block instanceof ASTTextFrameBlock) {
                    Integer wrapperRaw = ((ASTTextFrameBlock) block).nativeWrapperZOrder();
                    if (wrapperRaw != null) {
                        rawZOrders.put(wrapperRaw, Boolean.TRUE);
                    }
                }
            }
        }
        int rank = 0;
        for (Integer raw : rawZOrders.keySet()) {
            outputZOrderByRaw.put(raw, rank++);
        }
        foregroundOutputZOrder = Math.max(1, rank);
        if (blocks != null) {
            for (ASTBlock block : blocks) {
                outputZOrderByBlock.put(block, outputZOrder(rawZOrder(block)));
            }
        }
    }

    public int outputZOrder(ASTBlock block) {
        if (block == null) return 0;
        Integer encoded = outputZOrderByBlock.get(block);
        return encoded != null ? encoded : outputZOrder(rawZOrder(block));
    }

    public int outputZOrder(int rawZOrder) {
        Integer encoded = outputZOrderByRaw.get(rawZOrder);
        if (encoded != null) return encoded;
        return Math.max(0, rawZOrder);
    }

    public int foregroundOutputZOrder() {
        return foregroundOutputZOrder;
    }

    private int rawZOrder(ASTBlock block) {
        if (block instanceof ASTTextFrameBlock) return ((ASTTextFrameBlock) block).zOrder();
        if (block instanceof ASTFigure) return ((ASTFigure) block).zOrder();
        if (block instanceof ASTTable) return ((ASTTable) block).zOrder();
        return 0;
    }

    /** 변환 설정 (spacing, orphan 등) */
    public kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig config;

    public HwpxConverterContext(HWPXFile hwpxFile, StyleRegistry styleRegistry,
                                FontRegistry fontRegistry, List<ASTStyleDef> paragraphStyles) {
        this.hwpxFile = hwpxFile;
        this.styleRegistry = styleRegistry;
        this.fontRegistry = fontRegistry;
        this.paragraphStyles = paragraphStyles;
        this.borderFillIdCounter = new AtomicInteger(3);
    }
}
