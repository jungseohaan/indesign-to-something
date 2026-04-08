package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPEC-015: AST 노드 디버그 메타데이터.
 *
 * <p>{@code --debug-ast} CLI 옵션 또는 {@code ConvertOptions.debugAst} 활성화 시에만
 * 채워지며, 그 외에는 모든 AST 노드의 {@code debug} 필드가 null이라 메모리/성능 영향 없다.</p>
 *
 * <ul>
 *   <li>{@code createdAt} — 노드를 만든 단계 (예: "Phase2.placeTextFrames").</li>
 *   <li>{@code sourceId} — IDML/resolved 원본 ID 힌트 (이미 ASTBlock.sourceId가 있으면 중복).</li>
 *   <li>{@code appliedFrom} — 속성별 출처 ({@code "fontSize" → "resolved"}, {@code "color" → "paragraphStyle"}).
 *       SPEC-012의 RunPropertyResolver와 연동해서 채운다.</li>
 *   <li>{@code notes} — 자유 형식 메모. "wrap split applied", "linked frame merged" 등.</li>
 * </ul>
 */
public final class DebugMeta {
    public String createdAt;
    public String sourceId;
    public Map<String, String> appliedFrom;
    public List<String> notes;

    public DebugMeta() {}

    public DebugMeta(String createdAt) {
        this.createdAt = createdAt;
    }

    /** appliedFrom 한 항목 추가. */
    public DebugMeta applied(String property, String source) {
        if (appliedFrom == null) appliedFrom = new LinkedHashMap<>();
        appliedFrom.put(property, source);
        return this;
    }

    /** 자유 메모 한 줄 추가. */
    public DebugMeta note(String message) {
        if (notes == null) notes = new ArrayList<>();
        notes.add(message);
        return this;
    }

    public boolean isEmpty() {
        return createdAt == null && sourceId == null
                && (appliedFrom == null || appliedFrom.isEmpty())
                && (notes == null || notes.isEmpty());
    }
}
