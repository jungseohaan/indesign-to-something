package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.legacy;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLFontDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTStoryConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AST 문서에 메타데이터(폰트, 스타일, 색상)를 채우는 헬퍼.
 * Stage4_BuildAST에서 분리됨.
 */
class ASTMetadataBuilder {

    /**
     * 메타데이터 (폰트, 스타일, 색상) 채우기.
     */
    static void populateMetadata(ASTDocument doc, IDMLDocument idmlDoc, ColorResolver colorResolver) {
        // 폰트
        int fontIdx = 0;
        for (Map.Entry<String, IDMLFontDef> entry : idmlDoc.fonts().entrySet()) {
            ASTFontDef fd = new ASTFontDef();
            fd.fontId(String.valueOf(fontIdx++));
            fd.fontFamily(entry.getValue().fontFamily());
            fd.fontType(entry.getValue().fontType());
            doc.addFont(fd);
        }

        // 단락 스타일
        for (Map.Entry<String, IDMLStyleDef> entry : idmlDoc.paraStyles().entrySet()) {
            IDMLStyleDef s = entry.getValue();
            ASTStyleDef sd = new ASTStyleDef();
            sd.styleId(entry.getKey());
            sd.styleName(s.simpleName());
            sd.basedOnStyleRef(s.basedOn());
            sd.alignment(s.textAlignment());
            sd.fontFamily(s.fontFamily());
            sd.fontStyle(s.fontStyle());
            if (s.fontSize() != null) sd.fontSizeHwpunits((int)(s.fontSize() * 100));
            if (s.fillColor() != null) sd.textColor(colorResolver.resolve(s.fillColor()));
            if (s.firstLineIndent() != null) sd.firstLineIndent((long)(s.firstLineIndent() * 100));
            if (s.leftIndent() != null) sd.leftMargin((long)(s.leftIndent() * 100));
            if (s.rightIndent() != null) sd.rightMargin((long)(s.rightIndent() * 100));
            if (s.spaceBefore() != null) sd.spaceBefore((long)(s.spaceBefore() * 100));
            if (s.spaceAfter() != null) sd.spaceAfter((long)(s.spaceAfter() * 100));
            if (s.tracking() != null) sd.letterSpacing((short) Math.round(s.tracking() / 10.0));
            // bold / italic
            sd.bold(s.bold());
            sd.italic(s.italic());
            // 장평
            if (s.horizontalScale() != null) sd.horizontalScale((short) Math.round(s.horizontalScale()));
            // 어간
            if (s.desiredWordSpacing() != null) sd.wordSpacing(s.desiredWordSpacing());
            // leading → lineSpacing
            if (s.leading() != null) {
                sd.lineSpacingType("fixed");
                sd.lineSpacing((int) CoordinateConverter.pointsToHwpunits(s.leading()));
            } else if ("Auto".equals(s.leadingType())) {
                sd.lineSpacingType("percent");
                double autoLd = s.autoLeading() != null ? s.autoLeading() : 120;
                sd.lineSpacing((int) Math.round(autoLd));
            }
            // autoLeading 비율 보존
            if (s.autoLeading() != null) sd.autoLeading(s.autoLeading());
            // 밑줄 / 취소선
            sd.underline(s.underline());
            sd.underlineType(s.underlineType());
            if (s.underlineColor() != null) sd.underlineColor(colorResolver.resolve(s.underlineColor()));
            sd.strikeThrough(s.strikeThrough());
            // 두문자 (DropCap)
            if (s.dropCapLines() != null && s.dropCapLines() > 0) {
                sd.dropCapLines(s.dropCapLines());
                sd.dropCapCharacters(s.dropCapCharacters());
            }
            // 스타일 내 탭 정지점
            if (s.tabStops() != null && !s.tabStops().isEmpty()) {
                List<ASTTabStop> astTabs = new ArrayList<>();
                for (IDMLStyleDef.TabStop ts : s.tabStops()) {
                    ASTTabStop at = new ASTTabStop();
                    at.position((long)(ts.position() * 100));
                    at.alignment(ASTStoryConverter.mapTabAlignment(ts.alignment()));
                    at.leader(ts.leader());
                    astTabs.add(at);
                }
                sd.tabStops(astTabs);
            }
            doc.addParagraphStyle(sd);
        }

        // 문자 스타일
        for (Map.Entry<String, IDMLStyleDef> entry : idmlDoc.charStyles().entrySet()) {
            IDMLStyleDef s = entry.getValue();
            ASTStyleDef sd = new ASTStyleDef();
            sd.styleId(entry.getKey());
            sd.styleName(s.simpleName());
            sd.basedOnStyleRef(s.basedOn());
            sd.fontFamily(s.fontFamily());
            sd.fontStyle(s.fontStyle());
            if (s.fontSize() != null) sd.fontSizeHwpunits((int)(s.fontSize() * 100));
            if (s.fillColor() != null) sd.textColor(colorResolver.resolve(s.fillColor()));
            if (s.tracking() != null) sd.letterSpacing((short) Math.round(s.tracking() / 10.0));
            sd.bold(s.bold());
            sd.italic(s.italic());
            if (s.horizontalScale() != null) sd.horizontalScale((short) Math.round(s.horizontalScale()));
            sd.underline(s.underline());
            sd.underlineType(s.underlineType());
            if (s.underlineColor() != null) sd.underlineColor(colorResolver.resolve(s.underlineColor()));
            sd.strikeThrough(s.strikeThrough());
            doc.addCharacterStyle(sd);
        }

        // 색상
        for (Map.Entry<String, String> entry : idmlDoc.colors().entrySet()) {
            doc.putColor(entry.getKey(), entry.getValue());
        }
    }
}
