package simulations.booking.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.ChainBuilder;
import simulations.booking.subscription.SubscriptionHandler;
import simulations.util.AsyncLogger;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static simulations.config.Config.*;
import static simulations.util.SkewedRandomDelay.generateSkewedDuration;

public final class BookingActions {

    private static final SecureRandom random = new SecureRandom();
    private static final AtomicInteger userCounter = new AtomicInteger(0);
    private static final ObjectMapper mapper = new ObjectMapper();

    private BookingActions() {
    }

    public static int nextUserNum() {
        return userCounter.incrementAndGet();
    }

    public static void resetUserCounter() {
        userCounter.set(0);
    }

    public static Duration staggeredLoginDelay() {
        return generateSkewedDuration(0, STAGGERED_LOGIN_TIME_RANGE_MILLIS, 1.0);
    }

    public static Duration afterLoginDelay() {
        return generateSkewedDuration(300, 5000);
    }

    public static Duration beforeBookingAmountSetDelay() {
        return generateSkewedDuration(300, 3000);
    }

    public static Duration betweenBookingDelay() {
        return generateSkewedDuration(200, 1500, 4.0);
    }

    public static Duration beforeConfirmReservationDelay() {
        return generateSkewedDuration(2000, 10000);
    }

    public static ChainBuilder setUpUserNum() {
        return exec(session -> {
            int userNum = nextUserNum();
            int bookingAmount;

            if (SCENARIO_MODE != ScenarioMode.DYNAMIC) {
                bookingAmount = PlanLoader.getSeatsPerUser();
            } else if (FIXED_BOOKING_AMOUNT >= 0) {
                bookingAmount = FIXED_BOOKING_AMOUNT;
            } else {
                bookingAmount = random.nextInt(4) + 1;
            }

            return session
                    .set("userNum", userNum)
                    .set("bookingAmount", bookingAmount);
        });
    }

    public static ChainBuilder loginOrSetCookie() {
        if (TEST_ACCOUNT_ALREADY_STORED) {
            return exec(session -> {
                int userNum = session.getInt("userNum");
                String sessionId = SessionStore.getStoredTestAccountSession(userNum);

                if (sessionId == null) {
                    throw new RuntimeException("Stored session ID not found: test" + userNum);
                }

                return session.set("storedSessionId", sessionId);
            }).exec(addCookie(Cookie("SID", "#{storedSessionId}").withPath("/")));
        } else {
            return exec(loginWithTestAccount());
        }
    }

    public static ActionBuilder loginWithTestAccount() {
        return http("로그인 요청")
                .post("/user/login")
                .body(StringBody(session -> {
                    int userNum = session.getInt("userNum");
                    return """
                        {
                          "loginId": "test%d",
                          "loginPassword": "testpw%d"
                        }
                        """.formatted(userNum, userNum);
                }))
                .check(
                        status().in(200, 201),
                        headerRegex("Set-Cookie", "SID=([^;]+)").saveAs("sessionId")
                );
    }

    public static ActionBuilder checkPermission() {
        return http("예약 권한 확인")
                .get("/booking/permission/" + TARGET_EVENT)
                .check(status().in(200, 304));
    }

    public static ActionBuilder setBookingAmount() {
        return http("예매 수량")
                .post("/booking/count")
                .body(StringBody(session -> {
                    int bookingAmount = session.getInt("bookingAmount");
                    return """
                        {
                          "bookingAmount": %d
                        }
                        """.formatted(bookingAmount);
                }))
                .check(status().in(200, 201));
    }

    public static ActionBuilder subscribeSeats(SubscriptionHandler handler) {
        return handler.subscribe(TARGET_EVENT);
    }

    public static ChainBuilder setStaggeredLogin() {
        if (ENABLE_STAGGERED_LOGIN) {
            return exec(session -> {
                Duration delay = staggeredLoginDelay();
                AsyncLogger.logf("beforeStaggeredLoginWaiting: %d", delay.toMillis());
                AsyncLogger.logf("afterStaggeredLoginWaiting: %d", STAGGERED_LOGIN_TIME_RANGE_MILLIS - delay.toMillis());
                return session
                        .set("beforeStaggeredLoginWaiting", delay)
                        .set("afterStaggeredLoginWaiting",
                                Duration.ofMillis(STAGGERED_LOGIN_TIME_RANGE_MILLIS).minus(delay));
            });
        }
        return exec(session -> session);
    }

    public static ChainBuilder waitBeforeStaggeredLogin() {
        if (ENABLE_STAGGERED_LOGIN) {
            return pause("#{beforeStaggeredLoginWaiting}");
        }
        return exec(session -> session);
    }

    public static ChainBuilder waitAfterStaggeredLogin() {
        if (ENABLE_STAGGERED_LOGIN) {
            return pause("#{afterStaggeredLoginWaiting}");
        }
        return exec(session -> session);
    }

    public static ChainBuilder waitBeforeSubscribe() {
        if (ENABLE_WAITING_BEFORE_SUBS) {
            return pause(Duration.ofMillis(WAITING_BEFORE_SUBS_MILLIS));
        }
        return exec(session -> session);
    }

    public static ChainBuilder waitAfterSubscribe() {
        if (ENABLE_WAITING_AFTER_SUBS) {
            return pause(Duration.ofMillis(WAITING_AFTER_SUBS_MILLIS));
        }
        return exec(session -> session);
    }

    public static ChainBuilder waitBetweenActions() {
        if (ENABLE_WAITING_BETWEEN_ACTIONS) {
            return pause(Duration.ofMillis(WAITING_SECOND_BETWEEN_ACTIONS_MILLIS));
        }
        return exec(session -> session);
    }

    public static ChainBuilder chooseRandomSection() {
        return exec(session -> session.set("targetSection", random.nextInt(DYNAMIC_SECTION_COUNT)));
    }

    public static ActionBuilder switchToTargetSection() {
        return switchToSection("targetSection", "대상 섹션 전환");
    }

    public static ActionBuilder switchToCurrentRequestSection() {
        return switchToSection("currentReqSection", "계획 섹션 전환");
    }

    public static ActionBuilder switchToCurrentRequestTargetSection() {
        return switchToSection("currentReqTargetSection", "계획 대상 섹션 전환");
    }

    public static ActionBuilder switchToLoserRequestSection() {
        return switchToSection("loserReqSection", "충돌 대체 섹션 전환");
    }

    public static ActionBuilder switchToReqSection() {
        return switchToSection("reqSection", "요청 섹션 전환");
    }

    public static ActionBuilder switchToReqTargetSection() {
        return switchToSection("reqTargetSection", "요청 대상 섹션 전환");
    }

    private static ActionBuilder switchToSection(String sectionSessionKey, String requestName) {
        return http(requestName)
                .patch("/booking/seat/section")
                .body(StringBody(session -> {
                    int sectionIndex = session.getInt(sectionSessionKey);
                    return """
                        {"sectionIndex":%d}
                        """.formatted(sectionIndex);
                }))
                .check(
                        status().saveAs("sectionSwitchStatus"),
                        status().in(200, 201),
                        responseTimeInMillis().saveAs("sectionSwitchResponseTime"),
                        jsonPath("$.data.sectionIndex").ofInt().saveAs("currentSection"),
                        jsonPath("$.data.seatStatus")
                                .transform(seatsStr -> {
                                    try {
                                        return mapper.readValue(seatsStr, int[].class);
                                    } catch (Exception e) {
                                        throw new RuntimeException("seatStatus parse error: " + e.getMessage());
                                    }
                                })
                                .saveAs("seatStatus")
                );
    }

    public static ChainBuilder selectSingleSeat() {
        return exec(session -> {
            int[] seatStatus = session.get("seatStatus");
            if (seatStatus == null || seatStatus.length == 0) {
                throw new RuntimeException("현재 섹션의 seatStatus가 비어 있습니다.");
            }

            int sectionIndex = session.getInt("currentSection");
            int startSeatIdx = random.nextInt(seatStatus.length);

            for (int i = 0; i < seatStatus.length; i++) {
                int seatIdx = (startSeatIdx + i) % seatStatus.length;
                if (seatStatus[seatIdx] == 1) {
                    return session.set("selectedSeat", new int[]{sectionIndex, seatIdx});
                }
            }

            throw new RuntimeException("섹션 " + sectionIndex + "에 점유 가능한 좌석이 없습니다.");
        });
    }

    public static ActionBuilder bookSeat() {
        return http("좌석 점유")
                .post("/booking")
                .body(StringBody(session -> {
                    int[] selectedSeat = session.get("selectedSeat");
                    return """
                        {"eventId":%d,"sectionIndex":%d,"seatIndex":%d,"expectedStatus":"reserved"}
                        """.formatted(TARGET_EVENT, selectedSeat[0], selectedSeat[1]);
                }))
                .check(status().in(200, 201));
    }

    public static ChainBuilder saveBookedSeat() {
        return exec(session -> {
            int[] selectedSeat = session.get("selectedSeat");
            List<int[]> bookedSeats = session.get("bookedSeats");
            if (bookedSeats == null) {
                bookedSeats = new ArrayList<>();
            }
            bookedSeats.add(selectedSeat);
            return session.set("bookedSeats", bookedSeats);
        });
    }

    public static ChainBuilder markSelectedSeatUnavailableLocally() {
        return exec(session -> {
            int[] selectedSeat = session.get("selectedSeat");
            int[] seatStatus = session.get("seatStatus");
            Integer currentSection = session.get("currentSection");

            if (selectedSeat != null
                    && seatStatus != null
                    && currentSection != null
                    && selectedSeat[0] == currentSection
                    && selectedSeat[1] >= 0
                    && selectedSeat[1] < seatStatus.length) {
                seatStatus[selectedSeat[1]] = 0;
                return session.set("seatStatus", seatStatus);
            }

            return session;
        });
    }

    public static ChainBuilder saveBookedSeatsAsJson() {
        return exec(session -> {
            List<int[]> bookedSeats = session.get("bookedSeats");

            if (bookedSeats != null && !bookedSeats.isEmpty()) {
                List<Map<String, Object>> seatsJsonList = new ArrayList<>();

                for (int[] seat : bookedSeats) {
                    Map<String, Object> seatMap = new HashMap<>();
                    seatMap.put("sectionIndex", seat[0]);
                    seatMap.put("seatIndex", seat[1]);
                    seatsJsonList.add(seatMap);
                }

                try {
                    String seatsJson = mapper.writeValueAsString(seatsJsonList);
                    return session.set("seatsJson", seatsJson);
                } catch (Exception e) {
                    System.err.println("좌석 JSON 변환 오류: " + e.getMessage());
                }
            }
            return session;
        });
    }

    public static ChainBuilder confirmReservation(boolean enableDelayBefore) {
        ChainBuilder result = exec(session -> session);

        if (enableDelayBefore) {
            result = pause(session -> beforeConfirmReservationDelay());
        }

        if (ENABLE_SKIP_CONFIRM_RESERVATIONS) {
            return result;
        }

        return result.exec(
                http("예약 확정")
                        .post("/reservation")
                        .body(StringBody(session -> {
                            String seatsJson = session.getString("seatsJson");
                            return """
                                {
                                    "eventId": %d,
                                    "seats": %s
                                }
                                """.formatted(TARGET_EVENT, seatsJson);
                        }))
                        .check(status().in(200, 201))
        );
    }

    public static ChainBuilder bookSeatsWithRetry(SubscriptionHandler handler) {
        return exec(
                repeat("#{bookingAmount}").on(
                        tryMax(MAX_RETRY_IN_BOOKING_CONFLICT).on(
                                pause(session -> betweenBookingDelay()),
                                exec(chooseRandomSection()),
                                exec(switchToTargetSection()),
                                exec(handler.reloadSeatStatus()),
                                exec(selectSingleSeat()),
                                exec(bookSeat()),
                                exec(saveBookedSeat()),
                                exec(markSelectedSeatUnavailableLocally())
                        ).exitHereIfFailed()
                )
        );
    }
}
