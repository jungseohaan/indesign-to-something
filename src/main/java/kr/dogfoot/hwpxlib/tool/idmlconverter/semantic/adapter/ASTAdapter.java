package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter;

import java.util.List;

/**
 * ASTAdapter — SLA 코어와 AST 사이의 인터페이스 경계.
 *
 * <p>FeatureExtractor / RuleEngine / RelationBuilder 는 이 인터페이스를
 * 통해서만 AST 데이터에 접근한다. 구체 구현은 ASTDocument 객체 또는
 * AST JSON 둘 다 가능.</p>
 *
 * <p>TS {@code ASTAdapter} 인터페이스와 1:1.</p>
 */
public interface ASTAdapter {
    // 문서 구조
    List<PageInfo> getPages();
    List<BlockInfo> getBlocks(int pageNumber);
    List<ParagraphInfo> getParagraphs(String blockId);

    // 스토리
    List<StoryInfo> getStories();
    StoryInfo getStory(String storyId);

    // 스타일
    StyleInfo getStyleByRef(String ref);

    // 메타
    String getDocumentHash();
}
