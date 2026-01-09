package simulations.config;

public final class Config {
    private Config() {}

    // ========== 구독 방식 선택 ==========
    public enum SubscriptionType { SSE, WS}

    public static final SubscriptionType SUBSCRIPTION_TYPE = SubscriptionType.WS;

    // ========== 시나리오 모드 선택 ==========
    public enum ScenarioMode {
        DYNAMIC,   // 동적 좌석 선택 (실시간 seatStatus 기반)
    }
    public static final ScenarioMode SCENARIO_MODE = ScenarioMode.DYNAMIC;

    // ========== URL 설정 ==========
    public static final class Url {
        // 기본 URL = FE(nginx)의 api 프록시 경로: "http://{ip}:30000/api"
        // (선택) BE로 다이렉트: "http://{ip}:8080"
        public static final String ROOT_URL = "192.168.138.2:8080";
        public static final String ROOT_URL_HTTP = "http://" + ROOT_URL;
        public static final String ROOT_URL_WS = "ws://" + ROOT_URL;
    }

    // ========== 로깅 설정 ==========
    public static final boolean DEBUG_LOGGING = true;

    // ========== 테스트 계정 설정 ==========
    public static final boolean TEST_ACCOUNT_ALREADY_STORED = true;

    // ========== 대기 시간 설정 ==========
    public static final boolean ENABLE_WAITING_BETWEEN_ACTIONS = false;
    public static final int WAITING_SECOND_BETWEEN_ACTIONS_MILLIS = 20000;

    public static final boolean ENABLE_STAGGERED_LOGIN = false;
    public static final int STAGGERED_LOGIN_TIME_RANGE_MILLIS = 30000;

    public static final boolean ENABLE_WAITING_BEFORE_SUBS = true;
    public static final int WAITING_BEFORE_SUBS_MILLIS = 70000;

    public static final boolean ENABLE_WAITING_AFTER_SUBS = true;
    public static final int WAITING_AFTER_SUBS_MILLIS = 60000;

    // ========== 예약 설정 ==========
    public static final boolean ENABLE_SKIP_CONFIRM_RESERVATIONS = true;
    public static final int TARGET_EVENT = 1;
    public static final int MAX_RETRY_IN_BOOKING_CONFLICT = 100;

    // ========== 유저 수 (Dynamic 모드용) ==========
    public static final int DYNAMIC_USER_COUNT = 100;
    public static final int FIXED_BOOKING_AMOUNT = 4;  // 음수면 랜덤
}
