package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

/** SPEC-035: 텍스트 픽셀/문자 데이터의 최종 소유자. */
public enum TextAction {
    OWNED_BY_HWPX_TEXT,
    OWNED_BY_PNG,
    HIDDEN_SEMANTIC,
    DROP_TEXT
}
