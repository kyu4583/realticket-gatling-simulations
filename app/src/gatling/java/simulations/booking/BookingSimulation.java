package simulations.booking;

import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import simulations.booking.core.PlanLoader;
import simulations.booking.core.SessionStore;
import simulations.booking.scenario.DynamicScenario;
import simulations.booking.scenario.ParallelScenario;
import simulations.booking.scenario.ScenarioExecutor;
import simulations.booking.scenario.SseReconnectScenario;
import simulations.booking.scenario.StaticScenario;
import simulations.booking.subscription.SseHandler;
import simulations.booking.subscription.SubscriptionHandler;
import simulations.booking.subscription.WsHandler;

import static io.gatling.javaapi.http.HttpDsl.http;
import static simulations.config.Config.*;
import static simulations.config.Config.Url.ROOT_URL_HTTP;

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

        if (SCENARIO_MODE != ScenarioMode.DYNAMIC) {
            PlanLoader.load();
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
            case STATIC -> new StaticScenario();
            case PARALLEL -> new ParallelScenario();
            case SSE_RECONNECT -> new SseReconnectScenario();
        };
    }

    private void printConfiguration() {
        System.out.println("==================================================");
        System.out.println("예매 시뮬레이션");
        System.out.println("==================================================");
        System.out.println("구독 방식: " + SUBSCRIPTION_TYPE.name());
        System.out.println("시나리오: " + SCENARIO_MODE.name());
        System.out.println("대상 이벤트: " + TARGET_EVENT);
        System.out.println("사전 로그인: " + TEST_ACCOUNT_ALREADY_STORED);
        if (SCENARIO_MODE == ScenarioMode.DYNAMIC) {
            System.out.println("동적 섹션 수: " + DYNAMIC_SECTION_COUNT);
        }
        System.out.println("==================================================");
    }
}
