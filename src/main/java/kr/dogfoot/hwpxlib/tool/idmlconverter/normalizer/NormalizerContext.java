package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;

/**
 * Stage4 정규화 파이프라인의 공유 의존성 번들.
 * 반복적으로 전달되는 파라미터들을 하나의 컨텍스트로 묶는다.
 */
class NormalizerContext {
    final IDMLDocument idmlDoc;
    final FlattenedObjectPool pool;
    final ColorResolver colorResolver;
    final ASTImageLoader imageLoader;
    final ConvertOptions options;
    final ResolvedData resolvedData;

    NormalizerContext(IDMLDocument idmlDoc, FlattenedObjectPool pool,
                      ColorResolver colorResolver, ASTImageLoader imageLoader,
                      ConvertOptions options, ResolvedData resolvedData) {
        this.idmlDoc = idmlDoc;
        this.pool = pool;
        this.colorResolver = colorResolver;
        this.imageLoader = imageLoader;
        this.options = options;
        this.resolvedData = resolvedData;
    }
}
