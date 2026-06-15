package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved;

/**
 * Legacy Phase가 각 DOM 객체(TF/Group/PNG)에 내린 처리 소유권 결정.
 *
 * <p>SPEC-035의 목표 구조에서는 {@code OwnershipPlanner/ObjectPlan}이
 * textAction과 visualAction을 한 번에 결정한다. 이 enum은 그 전까지
 * Phase 2/3/4/legacy Stage 3 visual executor 사이의 중복 배치를 막기 위한 임시 bridge 상태다.</p>
 *
 * <p>Phase 2 {@link phase2.FramePlacer} 또는 Phase 4가
 * {@link ResolvedBuildContext#setDisposition}으로 등록하면,
 * 이후 Phase 3 / Stage 3 visual executor가 {@link ResolvedBuildContext#isDisposed}로 읽어
 * 중복 처리 없이 올바른 경로로 분기한다.</p>
 *
 * <p>새로운 ownership 규칙을 이 enum에 추가하지 않는다. 새 규칙은 먼저
 * OwnershipPlanner의 ObjectPlan 모델로 표현하고, legacy Phase는 그 plan을
 * 실행하는 방향으로 옮긴다.</p>
 *
 * <p>등록되지 않은 DOM ID의 기본 처리 경로:
 * <ul>
 *   <li>TextFrame → Phase 3 StoryConverter가 스토리 텍스트로 변환</li>
 *   <li>RenderedGroup PNG → Stage 3 visual executor가 ObjectPlan에 따라 배치</li>
 * </ul>
 * </p>
 */
public enum FrameDisposition {

    /**
     * Phase 2 또는 Phase 4가 해당 렌더된 객체(TF/Group)를 이미
     * 텍스트 글상자(ASTTextFrameBlock)로 배치한 경우.
     *
     * <ul>
     *   <li>Phase 3 ({@code loadInlineObject}): 인라인 PIC 생성 억제</li>
     *   <li>Stage 3 visual executor: PNG floating 중복 배치 건너뜀</li>
     * </ul>
     */
    TEXT_BLOCK_PLACED,

    /**
     * Phase 2 또는 Phase 3가 inline_object PNG를 floating으로 전환하도록 결정한 경우.
     *
     * <ul>
     *   <li>Phase 3 ({@code loadInlineObject}): inline PNG 배치 억제 (return null)</li>
     *   <li>Stage 3 visual executor: floating ASTFigure로 재배치</li>
     * </ul>
     */
    PNG_CONVERT_TO_FLOATING,
}
