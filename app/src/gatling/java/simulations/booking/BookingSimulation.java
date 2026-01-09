package simulations.booking;

import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import simulations.booking.core.SessionStore;
import simulations.booking.scenario.*;
import simulations.booking.subscription.*;

import static io.gatling.javaapi.http.HttpDsl.http;
import static simulations.config.Config.*;
import static simulations.config.Config.Url.ROOT_URL_HTTP;

/**
 * 사용법:
 *   ./gradlew gatlingRun-simulations.booking.BookingSimulation
 * 설정:
 *   Config.java에서 SUBSCRIPTION_TYPE과 SCENARIO_MODE 등 동작 설정
 */
public class BookingSimulation extends Simulation {

    private final SubscriptionHandler subscription = createSubscriptionHandler();

    private final ScenarioExecutor scenario = createScenarioExecutor();

    private final HttpProtocolBuilder httpProtocol = subscription.configureProtocol(
            http.baseUrl(ROOT_URL_HTTP)
                    .acceptHeader("application/json")
                    .contentTypeHeader("application/json")
                    .userAgentHeader("Gatling/Performance Test")
                    .inferHtmlResources()
                    .silentResources()
    );

    {
        initialize();

        printConfiguration();
        scenario.printInfo();

        PopulationBuilder[] populations = scenario.build(subscription);
        setUp(populations).protocols(httpProtocol);
    }

    private void initialize() {
        if (TEST_ACCOUNT_ALREADY_STORED) {
            SessionStore.loadStoredSessions();
        }
    }

    private SubscriptionHandler createSubscriptionHandler() {
        return switch (SUBSCRIPTION_TYPE) {
            case SSE -> new SseHandler();
            case WS -> new WsHandler();
        };
    }

    private ScenarioExecutor createScenarioExecutor() {
        return switch (SCENARIO_MODE) {
            case DYNAMIC -> new DynamicScenario();
        };
    }

    private void printConfiguration() {
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║           Booking Simulation                   ║");
        System.out.println("╠════════════════════════════════════════════════╣");
        System.out.println("║  구독 방식: " + padRight(SUBSCRIPTION_TYPE.name(), 34) + "║");
        System.out.println("║  시나리오:  " + padRight(SCENARIO_MODE.name(), 34) + "║");
        System.out.println("║  대상 이벤트: " + padRight(String.valueOf(TARGET_EVENT), 32) + "║");
        System.out.println("║  사전 로그인: " + padRight(String.valueOf(TEST_ACCOUNT_ALREADY_STORED), 32) + "║");
        System.out.println("╚════════════════════════════════════════════════╝");
    }

    private String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }
}
