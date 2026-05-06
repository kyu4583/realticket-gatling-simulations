package simulations.config;

public final class Config {
    private Config() {
    }

    public enum SubscriptionType { SSE, WS }

    public static final SubscriptionType SUBSCRIPTION_TYPE = SubscriptionType.SSE;

    public enum ScenarioMode {
        DYNAMIC,
        STATIC,
        PARALLEL
    }

    public static final ScenarioMode SCENARIO_MODE = ScenarioMode.DYNAMIC;

    public static final class Url {
        public static final String ROOT_URL = "localhost:8080";
        public static final String ROOT_URL_HTTP = "http://" + ROOT_URL;
        public static final String ROOT_URL_WS = "ws://" + ROOT_URL;
    }

    public static final boolean DEBUG_LOGGING = true;

    public static final boolean TEST_ACCOUNT_ALREADY_STORED = false;

    public static final boolean ENABLE_WAITING_BETWEEN_ACTIONS = false;
    public static final int WAITING_SECOND_BETWEEN_ACTIONS_MILLIS = 20000;

    public static final boolean ENABLE_STAGGERED_LOGIN = false;
    public static final int STAGGERED_LOGIN_TIME_RANGE_MILLIS = 30000;

    public static final boolean ENABLE_WAITING_BEFORE_SUBS = false;
    public static final int WAITING_BEFORE_SUBS_MILLIS = 60000;

    // The new seats SSE contract does not emit an initial seat map.
    // Seat data is loaded by PATCH /booking/seat/section instead.
    public static final boolean ENABLE_WAITING_AFTER_SUBS = false;
    public static final int WAITING_AFTER_SUBS_MILLIS = 60000;

    public static final boolean ENABLE_SKIP_CONFIRM_RESERVATIONS = true;
    public static final int TARGET_EVENT = 1;
    public static final int MAX_RETRY_IN_BOOKING_CONFLICT = 100;

    public static final int DYNAMIC_USER_COUNT = 1;
    public static final int FIXED_BOOKING_AMOUNT = 4;

    // Dynamic mode can no longer infer the full section count from the first SSE event.
    // Keep this aligned with the target event's place layout.
    public static final int DYNAMIC_SECTION_COUNT = 3;
}
