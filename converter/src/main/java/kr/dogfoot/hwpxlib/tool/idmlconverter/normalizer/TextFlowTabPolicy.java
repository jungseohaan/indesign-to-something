package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;

import java.util.List;

/** Source TextFlow tab policy shared by IDML and resolved story paths. */
public final class TextFlowTabPolicy {
    private TextFlowTabPolicy() {}

    public static boolean paragraphEndsWithTab(ASTParagraph paragraph) {
        if (paragraph == null) return false;
        return hasTabImmediatelyBefore(paragraph.items(), paragraph.items() != null ? paragraph.items().size() : 0);
    }

    public static boolean hasTabImmediatelyBefore(List<ASTInlineItem> items, int index) {
        if (items == null || items.isEmpty()) return false;
        int start = Math.min(Math.max(index, 0), items.size()) - 1;
        for (int i = start; i >= 0; i--) {
            ASTInlineItem item = items.get(i);
            if (item == null) continue;
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                return textEndsWithTab(((ASTTextRun) item).text());
            }
            return false;
        }
        return false;
    }

    private static boolean textEndsWithTab(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = text.length() - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '\u00A0') continue;
            return ch == '\t';
        }
        return false;
    }
}
