package simulations.constants;

public final class Constants {
    private Constants() {
    }

    public static final class Url {
        // 기본 URL = FE(nginx)의 api 프록시 경로: "http://{ip}:30000/api"
        // (선택) BE로 다이렉트: "http://{ip}:8080"
        public static final String ROOT_URL = "192.168.138.2:30000/api";
        public static final String ROOT_URL_HTTP = "http://" + ROOT_URL;
        public static final String ROOT_URL_WS = "ws://" + ROOT_URL;
    }

    public static final boolean ENABLE_WAITING_BETWEEN_ACTIONS = true;
    public static final int WAITING_SECOND_BETWEEN_ACTIONS_MILLIS = 60000;

    public static final boolean ENABLE_STAGGERED_LOGIN = true;
    public static final int STAGGERED_LOGIN_TIME_RANGE_MILLIS = 240000;

    // 음수 대입 시 랜덤으로 설정됨
    public static final int FIXED_BOOKING_AMOUNT = 3;

    public static final int TARGET_EVENT = 1;

    public static final int MAX_RETRY_IN_BOOKING_CONFLICT = 50;
}