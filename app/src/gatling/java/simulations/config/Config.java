package simulations.config;

public final class Config {
    private Config() {}

    public enum SubscriptionType { SSE, WS }

    public static final SubscriptionType SUBSCRIPTION_TYPE =
            parseEnum(SubscriptionType.class, prop("subscriptionType", "SSE"), SubscriptionType.SSE);

    public enum ScenarioMode {
        DYNAMIC,
        STATIC,
        PARALLEL,
        SSE_RECONNECT
    }

    public static final ScenarioMode SCENARIO_MODE =
            parseEnum(ScenarioMode.class, prop("scenarioMode", "DYNAMIC"), ScenarioMode.DYNAMIC);

    public static final class Url {
        public static final String ROOT_URL      = Config.prop("targetUrl", "localhost:8080");
        public static final String ROOT_URL_HTTP = "http://" + ROOT_URL;
        public static final String ROOT_URL_WS   = "ws://" + ROOT_URL;
    }

    public static final boolean DEBUG_LOGGING = true;

    // bench 브랜치 고정: alpha_test_account=true → 사전 로그인 계정 사용
    public static final boolean TEST_ACCOUNT_ALREADY_STORED = true;

    public static final boolean ENABLE_WAITING_BETWEEN_ACTIONS = false;
    public static final int WAITING_SECOND_BETWEEN_ACTIONS_MILLIS = 20000;

    public static final boolean ENABLE_STAGGERED_LOGIN = false;
    public static final int STAGGERED_LOGIN_TIME_RANGE_MILLIS = 30000;

    // auth_check → 50s 대기 → subscribe (이번 매니페스트 region 정의)
    public static final boolean ENABLE_WAITING_BEFORE_SUBS = true;
    public static final int WAITING_BEFORE_SUBS_MILLIS = 50000;

    public static final boolean ENABLE_WAITING_AFTER_SUBS = false;
    public static final int WAITING_AFTER_SUBS_MILLIS = 60000;

    public static final boolean ENABLE_SKIP_CONFIRM_RESERVATIONS = true;
    public static final int TARGET_EVENT =
            parseInt(prop("targetEvent", "1"), 1);
    public static final int MAX_RETRY_IN_BOOKING_CONFLICT =
            parseInt(prop("maxRetryInBookingConflict", "100"), 100);

    public static final int DYNAMIC_USER_COUNT =
            parseInt(prop("dynamicUserCount", "200"), 200);
    public static final int FIXED_BOOKING_AMOUNT =
            parseInt(prop("fixedBookingAmount", "4"), 4);

    public static final int DYNAMIC_SECTION_COUNT = 3;

    static {
        System.out.println("=== Bench -P injection ===");
        System.out.println("EFFECTIVE_CONFIG:");
        System.out.println("  scenarioMode=" + SCENARIO_MODE);
        System.out.println("  targetUrl=" + Url.ROOT_URL);
        System.out.println("  subscriptionType=" + SUBSCRIPTION_TYPE);
        System.out.println("  targetEvent=" + TARGET_EVENT);
        System.out.println("  testAccountAlreadyStored=" + TEST_ACCOUNT_ALREADY_STORED);
        System.out.println("  enableWaitingBeforeSubs=" + ENABLE_WAITING_BEFORE_SUBS);
        System.out.println("  waitingBeforeSubsMillis=" + WAITING_BEFORE_SUBS_MILLIS);
        System.out.println("=========================");
    }

    // ─── -P 주입 헬퍼 ────────────────────────────────────────────────────────

    static String prop(String key, String defaultVal) {
        String v = System.getProperty(key);
        return (v != null && !v.isEmpty()) ? v : defaultVal;
    }

    private static int parseInt(String value, int defaultVal) {
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E defaultVal) {
        try { return Enum.valueOf(type, value.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return defaultVal; }
    }
}
