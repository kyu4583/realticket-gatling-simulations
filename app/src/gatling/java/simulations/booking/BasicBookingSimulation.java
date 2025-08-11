package simulations.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

import java.util.concurrent.atomic.AtomicInteger;

import static simulations.config.Config.*;
import static simulations.config.Config.Url.ROOT_URL_HTTP;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static simulations.util.SkewedRandomDelay.generateSkewedDuration;

public abstract class BasicBookingSimulation extends Simulation {
    protected static final SecureRandom random = new SecureRandom();

    protected static class Counter {
        private static final AtomicInteger userCounter = new AtomicInteger(0);
        public static int nextUser() {
            return userCounter.incrementAndGet();
        }
    }

    protected static class RandDelay {
        public static Duration staggeredLoginWaiting() { return generateSkewedDuration(0, STAGGERED_LOGIN_TIME_RANGE_MILLIS, 1.0);}

        public static Duration afterLogin() {
            return generateSkewedDuration(300, 5000);
        }

        public static Duration beforeBookingAmountSet() {
            return generateSkewedDuration(300, 3000);
        }

        public static Duration betweenBooking() {
            return generateSkewedDuration(200, 1500, 4.0);
        }

        public static Duration beforeConfirmReservation() {
            return generateSkewedDuration(2000, 10000);
        }
    }

    protected HttpProtocolBuilder httpProtocol = http
            .baseUrl(ROOT_URL_HTTP)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("Gatling/Performance Test")
            // 자동 쿠키 관리 활성화 (요청 간 쿠키 유지)
            .inferHtmlResources()
            .silentResources();

    protected ScenarioBuilder scn = createScenario("이벤트=1 좌석 예매 시나리오");

    protected ScenarioBuilder createScenario(String scenarioName) {
        return scenario(scenarioName)
                .exec(setStaggeredLogin())
                .exec(waitBeforeStaggeredLogin())
                .exec(setUpUserNum())
                .exec(loginWithTestAccount())
                .exec(waitAfterStaggeredLogin())
                .pause(session -> RandDelay.afterLogin())

                .exec(waitBetweenActions())

                .exec(checkPermission()).exitHereIfFailed()
                .pause(session -> RandDelay.beforeBookingAmountSet())
                .exec(setBookingAmount())
                .exec(subscribeSeatsAndInitAvailableSeats())
                .exec(waitAfterSubscribe())
                .exec(bookSeatsWithRetry())

                .exec(saveBookedSeatsAsJson())

                .exec(waitBetweenActions())
                .exec(confirmReservationIfEnable(true))
                ;
    }

    protected ChainBuilder setStaggeredLogin() {
        if (ENABLE_STAGGERED_LOGIN) {
            return exec(session -> {
                Duration delay = RandDelay.staggeredLoginWaiting();
                return session
                        .set("beforeStaggeredLoginWaiting", delay)
                        .set("afterStaggeredLoginWaiting", Duration.ofMillis(STAGGERED_LOGIN_TIME_RANGE_MILLIS).minus(delay));
            });
        }
        return exec(session -> session);
    }

    protected ChainBuilder waitBeforeStaggeredLogin() {
        if (ENABLE_STAGGERED_LOGIN) {
            return pause("#{beforeStaggeredLoginWaiting}");
        }
        return exec(session -> session);
    }

    protected ChainBuilder waitAfterStaggeredLogin() {
        if (ENABLE_STAGGERED_LOGIN) {
            return pause("#{afterStaggeredLoginWaiting}");
        }
        return exec(session -> session);
    }

    protected ChainBuilder waitAfterSubscribe() {
        if (ENABLE_WAITING_AFTER_SUBS) {
            return pause(Duration.ofMillis(WAITING_AFTER_SUBS_MILLIS));
        }
        return exec(session -> session);
    }

    protected ChainBuilder waitBetweenActions() {
        if (ENABLE_WAITING_BETWEEN_ACTIONS) {
            return pause(Duration.ofMillis(WAITING_SECOND_BETWEEN_ACTIONS_MILLIS));
        }
        return exec(session -> session);
    }

    protected ChainBuilder setUpUserNum() {
        return exec(session -> {
            int userNum = Counter.nextUser();
            int bookingAmount;
            if (FIXED_BOOKING_AMOUNT >= 0) {
                bookingAmount = FIXED_BOOKING_AMOUNT;
            }
            else {
                bookingAmount = random.nextInt(4) + 1;
            }
            return session
                    .set("userNum", userNum)
                    .set("bookingAmount", bookingAmount);
        });
    }

    protected ActionBuilder loginWithTestAccount() {
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
                .check(status().in(200, 201));
    }

    protected ActionBuilder checkPermission() {
        return http("예약 권한 확인")
                .get("/booking/permission/" + TARGET_EVENT)
                .check(status().in(200, 304));
    }

    protected ActionBuilder setBookingAmount() {
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

    protected abstract ActionBuilder subscribeSeatsAndInitAvailableSeats();

    protected List<int[]> parseAvailableSeatsFromJson(JsonNode seatStatusJson) {
        List<int[]> availableSeats = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            if (ENABLE_BOOLEAN_SEATS_FORMAT) {
                boolean[][] seatStatus = mapper.treeToValue(seatStatusJson, boolean[][].class);
                availableSeats = getAvailableSeatsFromBooleanSeats(seatStatus);
            } else {
                int[][] seatStatus = mapper.treeToValue(seatStatusJson, int[][].class);
                availableSeats = getAvailableSeatsFromBitSeats(seatStatus);
            }
        } catch (Exception e) {
            System.err.println("좌석 상태 파싱 오류: " + e.getMessage());
        }
        return availableSeats;
    }

    protected List<int[]> getAvailableSeatsFromBitSeats(int[][] seatStatus) throws JsonProcessingException {
        List<int[]> availableSeats = new ArrayList<>();
        for (int sectionIdx = 0; sectionIdx < seatStatus.length; sectionIdx++) {
            for (int seatIdx = 0; seatIdx < seatStatus[sectionIdx].length; seatIdx++) {
                if (seatStatus[sectionIdx][seatIdx] == 1) {
                    availableSeats.add(new int[]{sectionIdx, seatIdx});
                }
            }
        }
        return availableSeats;
    }

    protected List<int[]> getAvailableSeatsFromBooleanSeats(boolean[][] seatStatus) throws JsonProcessingException {
        List<int[]> availableSeats = new ArrayList<>();
        for (int sectionIdx = 0; sectionIdx < seatStatus.length; sectionIdx++) {
            for (int seatIdx = 0; seatIdx < seatStatus[sectionIdx].length; seatIdx++) {
                if (seatStatus[sectionIdx][seatIdx]) {
                    availableSeats.add(new int[]{sectionIdx, seatIdx});
                }
            }
        }
        return availableSeats;
    }

    protected ChainBuilder bookSeatsWithRetry() {
        return exec(
                repeat("#{bookingAmount}").on(
                        tryMax(MAX_RETRY_IN_BOOKING_CONFLICT).on(
                                pause(session -> RandDelay.betweenBooking()),
                                exec(reloadAvailableSeats()),
                                exec(selectSingleSeat()).exitHereIfFailed(),
                                http("좌석 점유")
                                        .post("/booking")
                                        .body(
                                                StringBody(
                                                        """
                                                        {
                                                            "eventId": %d,
                                                            "sectionIndex": #{selectedSeat(0)},
                                                            "seatIndex": #{selectedSeat(1)},
                                                            "expectedStatus": "reserved"
                                                        }
                                                        """.formatted(TARGET_EVENT)
                                                )
                                        ).check(status().in(200, 201)),
                                exec(saveBookedSeat("selectedSeat"))
                        ).exitHereIfFailed()
                )
        );
    }

    protected abstract ActionBuilder reloadAvailableSeats();


    protected ChainBuilder selectSingleSeat() {
        return exec(session -> {
            List<int[]> availableSeats = session.get("availableSeats");
            if (availableSeats == null || availableSeats.isEmpty()) {
                throw new RuntimeException("No available seats");
            }
            int randomIndex = random.nextInt(availableSeats.size());
            int[] selectedSeat = availableSeats.get(randomIndex);
            return session.set("selectedSeat", selectedSeat);
        });
    }


    protected ChainBuilder saveBookedSeat(String bookedSeatKey) {
        return exec(session -> {
            List<int[]> bookedSeats = session.get("bookedSeats");
            if (bookedSeats == null) {
                bookedSeats = new ArrayList<>();
            }
            bookedSeats.add(session.get(bookedSeatKey));
            return session.set("bookedSeats", bookedSeats);
        });
    }

    protected ChainBuilder saveBookedSeatsAsJson() {
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

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
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

    protected ChainBuilder confirmReservationIfEnable(boolean enableDelayBefore) {
        ChainBuilder result = exec(session -> session);
        if (enableDelayBefore) {
            result = pause(session -> RandDelay.beforeConfirmReservation());
        }
        if (ENABLE_SKIP_CONFIRM_RESERVATIONS) {
            return result;
        }
        return result.exec(confirmReservation());
    }

    protected ActionBuilder confirmReservation() {
        return http("예약 확정")
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
                .check(status().in(200, 201));
    }
}
