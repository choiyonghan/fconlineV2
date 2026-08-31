package com.fconline.domain.match.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 골든 테스트 — site-root/report.js의 calcXg와 이 클래스가 항상 같은 결과를 내는지 확인한다.
 *
 * 두 구현은 "반드시 같은 공식이어야 한다"고 코드 주석에만 적혀있었는데, 실제로 2026-08-31에
 * 슛 유형별 난이도 배율이 report.js에만 있고 이쪽엔 빠진 채로 며칠간 어긋나 있었다(ADR §11
 * 참고) — 코드 주석만으론 이런 드리프트를 못 막는다는 게 실증됐다. 이 테스트는 그 재발을
 * 막기 위한 것이라, **여기 hardcode된 기대값은 사람이 계산한 게 아니라 report.js의 calcXg를
 * Node로 그대로 실행해서 뽑은 값**이다(아래 각 케이스에 좌표/슛유형을 그대로 옮겨 적었다).
 * report.js의 calcXg를 고치면(상수, 배율, 공식 자체) 이 값들도 같은 방법으로 다시 뽑아서
 * 갱신해야 한다 — 자동 동기화 안 됨.
 */
class ExpectedGoalsCalculatorTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                // x, y, shootType, report.js calcXg(x, y, shootType) 결과
                arguments(0.95, 0.5, "일반", 0.96),
                arguments(0.85, 0.1, "ZD", 0.02),
                arguments(0.97, 0.5, "헤더", 0.70),
                arguments(0.895238, 0.5, "PK", 0.77),
                arguments(0.5, 0.5, "DD", 0.0),
                arguments(0.9, 0.3, "발리", 0.17),
                arguments(0.75, 0.5, "프리킥", 0.05),
                arguments(0.6, 0.9, "바이시클킥", 0.0)
        );
    }

    @ParameterizedTest(name = "x={0}, y={1}, shootType={2} -> xg={3}")
    @MethodSource("cases")
    void matchesReportJsCalcXg(double x, double y, String shootType, double expectedXg) {
        assertThat(ExpectedGoalsCalculator.calcXg(x, y, shootType)).isEqualTo(expectedXg);
    }

    /** 맵에 없는 슛 유형(일반/ZD/DD/파워샷 등)은 배율 1.0 — report.js의 `|| 1` 폴백과 동일해야 한다. */
    @Test
    void unknownShootTypeDefaultsToMultiplierOne() {
        double withKnownDefault = ExpectedGoalsCalculator.calcXg(0.9, 0.5, "존재하지않는유형");
        double withExplicitNormal = ExpectedGoalsCalculator.calcXg(0.9, 0.5, "일반");
        assertThat(withKnownDefault).isEqualTo(withExplicitNormal);
    }
}
