package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

/** Source ownership policy: final owner of text pixels and character data. */
public enum TextAction {
    OWNED_BY_HWPX_TEXT,
    OWNED_BY_PNG,
    HIDDEN_SEMANTIC,
    DROP_TEXT
}
