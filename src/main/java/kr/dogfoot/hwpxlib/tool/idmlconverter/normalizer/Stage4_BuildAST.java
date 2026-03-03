package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;

import java.util.*;

/**
 * Stage 4: 스토리 우선 AST 구축.
 * IDMLDocument + FlattenedObjectPool → ASTDocument.
 *
 * 오케스트레이터: 실제 변환 로직은 아래 클래스에 위임한다.
 * - {@link ASTPageProcessor}: 페이지별 섹션 빌드 (텍스트 프레임, 이미지, 벡터, 마스터)
 * - {@link ASTMetadataBuilder}: 메타데이터 (폰트, 스타일, 색상)
 * - {@link ASTTextWrapSimulator}: 텍스트 감싸기 시뮬레이션
 * - {@link ASTStoryConverter}: 스토리/단락/런 변환
 * - {@link ASTMathGrouper}: BT 수식 폰트 런 그룹핑
 * - {@link ASTInlineObjectBuilder}: 인라인 그래픽, 이미지, 벡터, 테이블
 */
public class Stage4_BuildAST {

    public static ASTDocument build(FlattenedObjectPool pool, IDMLDocument idmlDoc,
                                     ConvertOptions options, String sourceFileName,
                                     ResolvedData resolvedData) {
        System.err.println("[Stage4_BuildAST] Building AST from stories...");

        ASTDocument doc = new ASTDocument();
        doc.sourceFile(sourceFileName);
        doc.sourceFormat("IDML");

        ColorResolver colorResolver = new ColorResolver(idmlDoc);
        Set<String> processedStories = new HashSet<>();

        // 이미지 로더 초기화
        ASTImageLoader imageLoader = options.includeImages()
                ? new ASTImageLoader(idmlDoc, options) : null;

        // 페이지별 섹션 구축
        for (IDMLSpread spread : idmlDoc.spreads()) {
            for (IDMLPage page : spread.pages()) {
                ASTSection section = ASTPageProcessor.processPage(
                        spread, page, pool, idmlDoc, colorResolver, imageLoader,
                        resolvedData, processedStories, doc);
                doc.addSection(section);
            }
        }

        // 메타데이터: 폰트, 스타일, 색상
        ASTMetadataBuilder.populateMetadata(doc, idmlDoc, colorResolver);

        System.err.println("[Stage4_BuildAST] Built " + doc.sections().size() + " sections.");
        return doc;
    }
}
