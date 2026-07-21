package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.HorizontalAlign2;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * HWPX 변환에 필요한 공유 유틸리티.
 * 4대 빌더(Paragraph, TextBox, Table, Image)와
 * FlatToHwpxConverter가 공통으로 사용한다.
 */
public final class HwpxUtil {

    private HwpxUtil() {}

    private static final AtomicLong PARA_ID_COUNTER = new AtomicLong(2000000000L);
    private static final AtomicLong SHAPE_ID_COUNTER = new AtomicLong(8000000L);

    public static String nextParaId() {
        return String.valueOf(PARA_ID_COUNTER.incrementAndGet());
    }

    public static String nextShapeId() {
        return String.valueOf(SHAPE_ID_COUNTER.incrementAndGet());
    }

    /**
     * AST 단락의 스타일 참조를 StyleRegistry 키로 변환.
     * AST에서는 "01_발문" 형태이고, StyleRegistry는 "ParagraphStyle/01_발문"으로 등록됨.
     */
    static String resolveStyleRef(String ref, StyleRegistry styleRegistry) {
        if (ref == null) return null;
        if (styleRegistry.getParaPrId(ref) != null) return ref;
        String withPrefix = "ParagraphStyle/" + ref;
        if (styleRegistry.getParaPrId(withPrefix) != null) return withPrefix;
        String withCharPrefix = "CharacterStyle/" + ref;
        if (styleRegistry.getCharPrId(withCharPrefix) != null) return withCharPrefix;
        return ref;
    }

    static String sanitizeText(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uE285' || c == '\uE287' || c == '\uE288' || c == '\uE22D') { c = '\u25A1'; }
            if (c == '\t' || c == '\n' || c == '\r'
                    || (c >= 0x20 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static HorizontalAlign2 mapAlignment(String alignment) {
        return HwpxEnumMapper.mapAlignment(alignment);
    }

    static Para createFloatingObjectPara(SectionXMLFile section) {
        Para para = section.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT();
        para.createLineSegArray();
        kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg lineSeg =
                para.lineSegArray().addNew();
        lineSeg.textposAnd(0).vertposAnd(0).vertsizeAnd(1000)
                .textheightAnd(1000).baselineAnd(850).spacingAnd(600)
                .horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
        return para;
    }
}
