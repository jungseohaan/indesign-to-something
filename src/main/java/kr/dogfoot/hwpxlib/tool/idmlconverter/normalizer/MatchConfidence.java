package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

/**
 * IDML 런 세그먼트와 resolved 런의 매칭 신뢰도.
 *
 * <p>SPEC-016: `splitIdmlRunByResolvedRuns`의 매칭 결과를 분류하여, 매칭이 충분히
 * 신뢰할 수 있을 때만 resolved 속성으로 IDML CR을 오버라이드한다.
 *
 * <ul>
 *   <li>{@link #HIGH}: 텍스트 경계가 정확히 일치 (정규화 후). fontFamily/fontSize/
 *       textColor 모두 resolved로 덮어쓴다.</li>
 *   <li>{@link #MEDIUM}: 부분 매칭 후 다음 런 키워드로 분할 성공. fontSize/textColor
 *       만 덮어쓴다 (fontFamily/letterSpacing은 IDML CR 유지).</li>
 *   <li>{@link #LOW}: 매칭 실패로 인한 폴백(findDefaultResolvedRun, 건너뛰기 등).
 *       IDML CR 값을 그대로 사용. resolved는 IDML에 값이 없는 경우의 폴백으로만.</li>
 * </ul>
 */
public enum MatchConfidence {
    HIGH,
    MEDIUM,
    LOW
}
