package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.FontRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ASTToHwpxConverter의 공유 상태를 보관한다.
 * 각 Builder 클래스가 이 컨텍스트를 통해 hwpxFile, 레지스트리, 캐시 등에 접근한다.
 */
public class HwpxConverterContext {
    final HWPXFile hwpxFile;
    final StyleRegistry styleRegistry;
    final FontRegistry fontRegistry;
    final ASTDocument doc;
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
    HwpxTableBuilder tableBuilderRef;

    // 변환 통계
    int imagesConverted;
    int equationsConverted;
    int framesConverted;

    public HwpxConverterContext(HWPXFile hwpxFile, StyleRegistry styleRegistry,
                                FontRegistry fontRegistry, ASTDocument doc) {
        this.hwpxFile = hwpxFile;
        this.styleRegistry = styleRegistry;
        this.fontRegistry = fontRegistry;
        this.doc = doc;
        this.borderFillIdCounter = new AtomicInteger(3);
    }
}
