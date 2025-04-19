package simulations.booking.booleanSeats;

import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static constants.Constants.FIXED_BOOKING_AMOUNT;
import static constants.Constants.Url.ROOT_URL_HTTP;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static util.SkewedRandomDelay.generateSkewedDuration;

public abstract class BooleanSeatsBasicBookingSimulation extends Simulation {
    protected static final int TARGET_EVENT = 1;
    protected static final int MAX_RETRY_IN_BOOKING_CONFLICT = 50;
    protected static final SecureRandom random = new SecureRandom();

    protected static class Counter {
        private static final AtomicInteger userCounter = new AtomicInteger(0);
        public static int nextUser() {
            return userCounter.incrementAndGet();
        }
    }

    protected static class RandDelay {
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
                .exec(setUpUserNum())
                .exec(loginWithTestAccount())
                .pause(RandDelay.afterLogin())

                .exec(checkPermission()).exitHereIfFailed()

                .pause(RandDelay.beforeBookingAmountSet())
                .exec(setBookingAmount())
                .exec(subscribeSeatsAndInitAvailableSeats())

                .exec(bookSeatsWithRetry())

                .exec(saveBookedSeatsAsJson())

                .pause(RandDelay.beforeConfirmReservation())
                .exec(confirmReservation());
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

    protected List<int[]> parseAvailableSeatsFromJsonStr(String seatStatusJsonStr) {
        List<int[]> availableSeats = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            boolean[][] seatStatus = mapper.readValue(seatStatusJsonStr, boolean[][].class);
            for (int sectionIdx = 0; sectionIdx < seatStatus.length; sectionIdx++) {
                for (int seatIdx = 0; seatIdx < seatStatus[sectionIdx].length; seatIdx++) {
                    if (seatStatus[sectionIdx][seatIdx]) {
                        availableSeats.add(new int[]{sectionIdx, seatIdx});
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("좌석 상태 파싱 오류: " + e.getMessage());
        }
        return availableSeats;
    }

    protected ChainBuilder bookSeatsWithRetry() {
        return exec(
                repeat("#{bookingAmount}").on(
                        tryMax(MAX_RETRY_IN_BOOKING_CONFLICT).on(
                                pause(RandDelay.betweenBooking()),
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
