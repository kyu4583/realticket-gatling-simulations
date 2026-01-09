package simulations.booking.scenario;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import simulations.util.AsyncLogger;
import simulations.booking.core.BookingActions;
import simulations.booking.core.PlanLoader;
import simulations.booking.core.PlanLoader.PlannedRequest;
import simulations.booking.subscription.SubscriptionHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static simulations.config.Config.*;

/**
 * Static 시나리오
 * 
 * Plan.json에서 사전 계획된 요청을 시간순으로 실행한다.
 * 충돌(계획된)이 발생하면 collision_loser 체이닝을 수행한다.
 */
public class StaticScenario implements ScenarioExecutor {

    private static final AtomicLong simulationStartTime = new AtomicLong(0);
    private static final AtomicInteger readyUserCount = new AtomicInteger(0);
    
    @Override
    public PopulationBuilder[] build(SubscriptionHandler subscription) {

        PlanLoader.load();
        
        int numUsers = PlanLoader.getNumUsers();
        
        ScenarioBuilder scn = scenario("이벤트=" + TARGET_EVENT + " Static 예매 시나리오")

                .exec(BookingActions.setUpUserNum())
                .exec(BookingActions.setStaggeredLogin())
                .exec(BookingActions.waitBeforeStaggeredLogin())
                .exec(BookingActions.loginOrSetCookie())
                .exec(BookingActions.waitAfterStaggeredLogin())

                .tryMax(10).on(exec(BookingActions.checkPermission())).exitHereIfFailed()
                .exec(BookingActions.setBookingAmount())
                .exec(BookingActions.subscribeSeats(subscription))
                .exec(BookingActions.waitAfterSubscribe())

                .exec(loadUserPlan())

                .exec(waitForAllUsersReady(numUsers))

                .exec(staticBookingLoop())

                .exec(BookingActions.saveBookedSeatsAsJson())
                .exec(BookingActions.confirmReservation(true))

                .exec(subscription.close());
        
        return new PopulationBuilder[] {
                scn.injectOpen(atOnceUsers(numUsers))
        };
    }
    
    @Override
    public void printInfo() {
        System.out.println("=== Static 시나리오 ===");
        System.out.println("  유저 수: " + PlanLoader.getNumUsers());
        System.out.println("  유저당 좌석: " + PlanLoader.getSeatsPerUser());
        System.out.println("  충돌 그룹: " + PlanLoader.getCollisionGroups().size());
        System.out.println("  총 요청: " + PlanLoader.getTotalPlannedRequests());
    }
    
    // ========== 체인 빌더 ==========
    
    private ChainBuilder loadUserPlan() {
        return exec(session -> {
            int userNum = session.getInt("userNum");
            List<PlannedRequest> myRequests = PlanLoader.getUserPlans().get(userNum);
            
            if (myRequests == null || myRequests.isEmpty()) {
                AsyncLogger.log("유저 " + userNum + "의 계획이 없습니다.");
                return session
                        .set("myRequests", Collections.emptyList())
                        .set("currentRequestIndex", 0)
                        .set("hasMoreRequests", false)
                        .set("bookedSeats", new ArrayList<int[]>());
            }
            
            return session
                    .set("myRequests", new ArrayList<>(myRequests))
                    .set("currentRequestIndex", 0)
                    .set("hasMoreRequests", true)
                    .set("bookedSeats", new ArrayList<int[]>());
        });
    }
    
    private ChainBuilder waitForAllUsersReady(int numUsers) {
        return exec(session -> {
            int userNum = session.getInt("userNum");
            int ready = readyUserCount.incrementAndGet();
            AsyncLogger.logf("유저 %d 준비 완료 (%d/%d)", userNum, ready, numUsers);
            return session;
        })
        .rendezVous(numUsers)
        .exec(session -> {
            if (simulationStartTime.compareAndSet(0, System.currentTimeMillis() + 500)) {
                long startTime = simulationStartTime.get();
                AsyncLogger.log("=== 시뮬레이션 시작 시간: " + startTime + " ===");
            }
            return session.set("simStartTime", simulationStartTime.get());
        });
    }
    
    private ChainBuilder staticBookingLoop() {
        Map<String, String> requestToCollision = PlanLoader.getRequestToCollision();
        
        return asLongAs(session -> session.getBoolean("hasMoreRequests")).on(
                exec(session -> {
                    List<PlannedRequest> myRequests = session.get("myRequests");
                    int index = session.getInt("currentRequestIndex");
                    
                    if (index >= myRequests.size()) {
                        return session.set("hasMoreRequests", false).set("waitTimeMs", 0L);
                    }
                    
                    PlannedRequest request = myRequests.get(index);
                    long simStartTime = session.getLong("simStartTime");
                    long targetTime = simStartTime + request.timeMs;
                    long now = System.currentTimeMillis();
                    long waitTime = Math.max(0, targetTime - now);
                    
                    AsyncLogger.logf("좌석 점유 요청 [%s] - Section: %d, Seat: %d (wait: %dms)",
                            request.id, request.section, request.seat, waitTime);
                    
                    return session
                            .set("waitTimeMs", waitTime)
                            .set("currentReqId", request.id)
                            .set("currentReqSection", request.section)
                            .set("currentReqSeat", request.seat)
                            .set("currentReqBody", request.requestBody);
                }),
                pause(session -> Duration.ofMillis(session.getLong("waitTimeMs"))),
                exec(
                        http("좌석 점유")
                                .post("/booking")
                                .body(StringBody("#{currentReqBody}"))
                                .check(
                                        status().saveAs("lastResponseStatus"),
                                        status().in(200, 201)
                                )
                ),
                exec(session -> {
                    int status = session.getInt("lastResponseStatus");
                    boolean success = (status == 200 || status == 201);
                    String reqId = session.getString("currentReqId");
                    String collisionId = requestToCollision.get(reqId);
                    
                    Session updatedSession = session;
                    
                    if (success) {
                        List<int[]> bookedSeats = session.get("bookedSeats");
                        bookedSeats.add(new int[]{
                                session.getInt("currentReqSection"),
                                session.getInt("currentReqSeat")
                        });
                        updatedSession = session
                                .set("bookedSeats", bookedSeats)
                                .set("pendingCollisionId", null);
                    } else {
                        updatedSession = session.set("pendingCollisionId", collisionId);
                    }
                    
                    int nextIndex = session.getInt("currentRequestIndex") + 1;
                    List<PlannedRequest> myRequests = session.get("myRequests");
                    
                    return updatedSession
                            .set("currentRequestIndex", nextIndex)
                            .set("hasMoreRequests", nextIndex < myRequests.size());
                }),
                exec(handleCollisionLoserChain())
        );
    }
    
    private ChainBuilder handleCollisionLoserChain() {
        Map<String, ConcurrentLinkedQueue<PlannedRequest>> loserQueues = PlanLoader.getLoserRequestQueues();
        Map<String, String> requestToCollision = PlanLoader.getRequestToCollision();
        
        return asLongAs(session -> {
            String collisionId = session.get("pendingCollisionId");
            return collisionId != null;
        }).on(
                exec(session -> {
                    String collisionId = session.getString("pendingCollisionId");
                    ConcurrentLinkedQueue<PlannedRequest> queue = loserQueues.get(collisionId);
                    
                    if (queue == null || queue.isEmpty()) {
                        AsyncLogger.logf("충돌 그룹 %s: 더 이상 대체 요청 없음", collisionId);
                        return session.set("pendingCollisionId", null);
                    }
                    
                    PlannedRequest loserReq = queue.poll();
                    if (loserReq == null) {
                        return session.set("pendingCollisionId", null);
                    }
                    
                    AsyncLogger.logf("충돌 패자 체인 [%s] - Section: %d, Seat: %d",
                            loserReq.id, loserReq.section, loserReq.seat);
                    
                    return session
                            .set("loserReqId", loserReq.id)
                            .set("loserReqSection", loserReq.section)
                            .set("loserReqSeat", loserReq.seat)
                            .set("loserReqBody", loserReq.requestBody);
                }),
                doIf(session -> session.get("pendingCollisionId") != null).then(
                        exec(
                                http("좌석 점유")
                                        .post("/booking")
                                        .body(StringBody("#{loserReqBody}"))
                                        .check(
                                                status().saveAs("loserResponseStatus"),
                                                status().in(200, 201)
                                        )
                        ),
                        exec(session -> {
                            int status = session.getInt("loserResponseStatus");
                            boolean success = (status == 200 || status == 201);
                            String reqId = session.getString("loserReqId");
                            int section = session.getInt("loserReqSection");
                            int seat = session.getInt("loserReqSeat");
                            
                            if (success) {
                                List<int[]> bookedSeats = session.get("bookedSeats");
                                bookedSeats.add(new int[]{section, seat});
                                return session
                                        .set("bookedSeats", bookedSeats)
                                        .set("pendingCollisionId", null);
                            } else {
                                String nextCollisionId = requestToCollision.get(reqId);
                                return session.set("pendingCollisionId", nextCollisionId);
                            }
                        })
                )
        );
    }
}
