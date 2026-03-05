package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;

import java.util.List;

/**
 * 수식 패턴 감지 기반 변환기.
 * 폰트에 의존하지 않고, 유니코드 텍스트를 HWP 수식 스크립트로 변환한다.
 * EHFontEquationConverter의 유니코드 매핑 로직을 재사용.
 */
public class PatternEquationConverter {

    /**
     * 수식 패턴으로 감지된 런 그룹을 HWP 수식 스크립트로 변환한다.
     *
     * @param runs 수식 패턴으로 감지된 연속 런
     * @return HWP 수식 스크립트 문자열, 또는 변환 불가하면 null
     */
    public static String convert(List<IDMLCharacterRun> runs) {
        if (runs == null || runs.isEmpty()) return null;

        StringBuilder rawBuilder = new StringBuilder();
        for (IDMLCharacterRun run : runs) {
            String text = run.content();
            if (text != null) {
                rawBuilder.append(text);
            }
        }
        String raw = rawBuilder.toString().trim();
        if (raw.isEmpty()) return null;

        // EHFontEquationConverter의 유니코드→HWP 변환 로직 재사용
        String hwpScript = EHFontEquationConverter.convertToHwpScript(raw);

        if (hwpScript == null || hwpScript.trim().isEmpty()) return null;
        String trimmed = hwpScript.trim();

        // 글자나 숫자가 없으면 수식 아님
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isLetterOrDigit(trimmed.charAt(i))) {
                hasLetterOrDigit = true;
                break;
            }
        }
        if (!hasLetterOrDigit) return null;

        return trimmed;
    }
}
