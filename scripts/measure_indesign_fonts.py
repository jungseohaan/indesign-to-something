#!/usr/bin/env python3
"""
InDesign 폰트 파일에서 한글/영문 평균 글자 폭을 측정하여
font-mapping.json의 indesignFontMetrics에 반영하는 스크립트.

사용법:
    python3 scripts/measure_indesign_fonts.py ~/Documents/glbal-fonts/ font-mapping.json

의존성:
    pip3 install fonttools
"""

import sys
import os
import json
from fontTools.ttLib import TTFont
from fontTools.pens.boundsPen import BoundsPen

KOR_SAMPLE = "가나다라마바사아자차카타파하"
LAT_SAMPLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
TEST_SIZE = 10.0  # 10pt 기준


def get_avg_width(font, sample_chars, units_per_em):
    """글리프 advance width 평균을 pt 단위로 반환 (10pt 기준)"""
    widths = []
    cmap = font.getBestCmap()
    if not cmap:
        return 0.0

    hmtx = font.get('hmtx')
    if not hmtx:
        return 0.0

    glyf_table = font.get('glyf') if 'glyf' in font else None

    for ch in sample_chars:
        code = ord(ch)
        if code not in cmap:
            continue
        glyph_name = cmap[code]
        if glyph_name in hmtx.metrics:
            advance, _ = hmtx.metrics[glyph_name]
            # em 단위 → pt 변환 (10pt 기준)
            width_pt = (advance / units_per_em) * TEST_SIZE
            widths.append(width_pt)

    if not widths:
        return 0.0
    return round(sum(widths) / len(widths), 2)


def get_font_weight(font):
    """OS/2 테이블에서 weight class 추출"""
    os2 = font.get('OS/2')
    if os2:
        return os2.usWeightClass
    return 400


def get_font_family(font):
    """name 테이블에서 폰트 패밀리 이름 추출"""
    name_table = font.get('name')
    if not name_table:
        return None

    # nameID 1 = Font Family, nameID 16 = Typographic Family
    for name_id in [16, 1]:
        for record in name_table.names:
            if record.nameID == name_id:
                try:
                    return record.toUnicode().strip()
                except:
                    pass
    return None


def measure_font_file(filepath):
    """폰트 파일을 측정하여 메트릭 딕셔너리를 반환"""
    try:
        font = TTFont(filepath)
    except Exception as e:
        return None

    try:
        family = get_font_family(font)
        if not family:
            font.close()
            return None

        units_per_em = font['head'].unitsPerEm

        kor_width = get_avg_width(font, KOR_SAMPLE, units_per_em)
        lat_width = get_avg_width(font, LAT_SAMPLE, units_per_em)
        weight = get_font_weight(font)

        # ascent/descent
        os2 = font.get('OS/2')
        ascent = 0.0
        descent = 0.0
        if os2:
            ascent = round((os2.sTypoAscender / units_per_em) * TEST_SIZE, 1)
            descent = round((abs(os2.sTypoDescender) / units_per_em) * TEST_SIZE, 1)
    except Exception as e:
        try:
            font.close()
        except:
            pass
        return None

    font.close()

    return {
        'family': family,
        'korWidth': kor_width,
        'latWidth': lat_width,
        'weight': weight,
        'ascent': ascent,
        'descent': descent,
        'file': os.path.basename(filepath)
    }


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <font-dir> <font-mapping.json>")
        sys.exit(1)

    font_dir = sys.argv[1]
    mapping_path = sys.argv[2]

    # 폰트 파일 수집
    font_files = []
    for f in os.listdir(font_dir):
        if f.lower().endswith(('.otf', '.ttf')):
            font_files.append(os.path.join(font_dir, f))
    font_files.sort()

    print(f"측정 대상: {len(font_files)}개 폰트 파일")

    # 측정
    metrics = {}  # family → metric (같은 family는 첫 번째만)
    errors = 0
    for filepath in font_files:
        result = measure_font_file(filepath)
        if result:
            family = result['family']
            if family not in metrics:
                metrics[family] = result
                print(f"  {family}: kor={result['korWidth']} lat={result['latWidth']} w={result['weight']}")
        else:
            errors += 1

    print(f"\n측정 완료: {len(metrics)}개 패밀리, {errors}개 에러")

    # font-mapping.json 로드
    with open(mapping_path, 'r', encoding='utf-8') as f:
        mapping = json.load(f)

    # indesignFontMetrics 섹션 추가/업데이트
    if 'indesignFontMetrics' not in mapping:
        mapping['indesignFontMetrics'] = {}

    idml_metrics = mapping['indesignFontMetrics']
    added = 0
    for family, m in metrics.items():
        if family not in idml_metrics:
            idml_metrics[family] = {
                'korWidth': m['korWidth'],
                'latWidth': m['latWidth'],
                'weight': m['weight'],
                'ascent': m['ascent'],
                'descent': m['descent']
            }
            added += 1

    # 저장
    with open(mapping_path, 'w', encoding='utf-8') as f:
        json.dump(mapping, f, ensure_ascii=False, indent=2)

    print(f"\n{mapping_path}에 {added}개 InDesign 폰트 메트릭 추가 (총 {len(idml_metrics)}개)")


if __name__ == '__main__':
    main()
