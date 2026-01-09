package simulations.booking.core;

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
    
    private BookingActions() {}

    // ========== 유저 카운터 ==========
    
    public static int nextUserNum() {
        return userCounter.incrementAndGet();
    }
    
    public static void resetUserCounter() {
        userCounter.set(0);
    }

    // ========== 랜덤 딜레이 ==========
    
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

    // ========== 체인 빌더 ==========

    /**
     * 유저 번호 및 예매 수량 설정
     */
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

    /**
     * 로그인 요청 또는 저장된 쿠키 설정
     */
    public static ChainBuilder loginOrSetCookie() {
        if (TEST_ACCOUNT_ALREADY_STORED) {
            return exec(session -> {
                int userNum = session.getInt("userNum");
                String sessionId = SessionStore.getStoredTestAccountSession(userNum);
                
                if (sessionId == null) {
                    throw new RuntimeException("저장된 세션 ID를 찾을 수 없습니다: test" + userNum);
                }
                
                return session.set("storedSessionId", sessionId);
            }).exec(addCookie(Cookie("SID", "#{storedSessionId}").withPath("/")));
        } else {
            return exec(loginWithTestAccount());
        }
    }
    
    /**
     * 테스트 계정으로 로그인
     */
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
    
    /**
     * 예약 권한 확인
     */
    public static ActionBuilder checkPermission() {
        return http("예약 권한 확인")
                .get("/booking/permission/" + TARGET_EVENT)
                .check(status().in(200, 304));
    }
    
    /**
     * 예매 수량 설정
     */
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
    
    /**
     * 구독 + 좌석 상태 초기화
     */
    public static ActionBuilder subscribeSeats(SubscriptionHandler handler) {
        return handler.subscribeAndInitSeatStatus(TARGET_EVENT);
    }
    
    // ========== 대기 체인 ==========
    
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
    
    // ========== 좌석 선택 (Dynamic 모드) ==========
    
    /**
     * 빈 좌석 탐색 및 선택
     */
    public static ChainBuilder selectSingleSeat() {
        return exec(session -> {
            int[][] seatStatus = session.get("seatStatus");
            if (seatStatus == null || seatStatus.length == 0) {
                throw new RuntimeException("seatStatus를 읽는 데에 실패했습니다.");
            }
            
            int totalSections = seatStatus.length;
            int startSectionIdx = random.nextInt(totalSections);

            for (int i = 0; i < totalSections; i++) {
                int sectionIdx = (startSectionIdx + i) % totalSections;
                int[] section = seatStatus[sectionIdx];
                
                int sectionSize = section.length;
                int startSeatIdx = random.nextInt(sectionSize);
                
                for (int j = 0; j < sectionSize; j++) {
                    int seatIdx = (startSeatIdx + j) % sectionSize;

                    if (section[seatIdx] == 1) {
                        return session.set("selectedSeat", new int[]{sectionIdx, seatIdx});
                    }
                }
            }
            
            throw new RuntimeException("점유 가능한 좌석이 존재하지 않습니다.");
        });
    }
    
    /**
     * 좌석 점유 요청
     */
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
    
    /**
     * 점유된 좌석 저장
     */
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
    
    /**
     * 점유된 좌석 JSON으로 저장
     */
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
    
    /**
     * 예약 확정
     */
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
    
    /**
     * 재시도 포함 좌석 점유
     */
    public static ChainBuilder bookSeatsWithRetry(SubscriptionHandler handler) {
        return exec(
                repeat("#{bookingAmount}").on(
                        tryMax(MAX_RETRY_IN_BOOKING_CONFLICT).on(
                                pause(session -> betweenBookingDelay()),
                                exec(handler.reloadSeatStatus()),
                                exec(selectSingleSeat()).exitHereIfFailed(),
                                exec(bookSeat()),
                                exec(saveBookedSeat())
                        ).exitHereIfFailed()
                )
        );
    }
}
