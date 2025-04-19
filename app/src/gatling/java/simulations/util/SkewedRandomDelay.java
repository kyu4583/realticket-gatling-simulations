package simulations.util;

import java.security.SecureRandom;
import java.time.Duration;

public class SkewedRandomDelay {
    protected static final SecureRandom random = new SecureRandom();

    /**
     * 편향된 난수를 생성하는 함수 - 시작 범위에 더 많이 분포됨
     *
     * @param minMs        최소값 (밀리초)
     * @param maxMs        최대값 (밀리초)
     * @param skewFactor = 2.0: 편향 정도 (값이 클수록 min에 더 많이 분포됨, 기본값 2.0)
     * @param direction  = true: min에 더 많이 분포, false: max에 더 많이 분포
     * @return min과 max 사이의 편향된 난수 (밀리초)
     */
    public static int generateSkewedDelay(int minMs, int maxMs, double skewFactor, boolean direction) {
        if (minMs >= maxMs) {
            throw new IllegalArgumentException("최대값은 최소값보다 커야 합니다");
        }

        // 0~1 사이의 난수 생성
        double randomValue = random.nextDouble();

        // 거듭제곱을 사용하여 값을 편향시킴
        // skewFactor가 클수록 더 작은 값에 편향됨
        double skewedValue = Math.pow(randomValue, skewFactor);

        if (direction) {
            // 1에서 빼서 작은 값(min)에 가까운 쪽으로 편향시킴
            skewedValue = 1.0 - skewedValue;
        }

        // min~max 범위로 변환
        return minMs + (int) (skewedValue * (maxMs - minMs));
    }

    public static int generateSkewedDelay(int minMs, int maxMs, double skewFactor) {
        return generateSkewedDelay(minMs, maxMs, skewFactor, true);
    }

    public static int generateSkewedDelay(int minMs, int maxMs) {
        return generateSkewedDelay(minMs, maxMs, 2.0, true);
    }


    public static Duration generateSkewedDuration(int minMs, int maxMs, double skewFactor, boolean direction) {
        return Duration.ofMillis(generateSkewedDelay(minMs, maxMs, skewFactor, direction));
    }

    public static Duration generateSkewedDuration(int minMs, int maxMs, double skewFactor) {
        return Duration.ofMillis(generateSkewedDelay(minMs, maxMs, skewFactor));
    }

    public static Duration generateSkewedDuration(int minMs, int maxMs) {
        return Duration.ofMillis(generateSkewedDelay(minMs, maxMs));
    }
}
