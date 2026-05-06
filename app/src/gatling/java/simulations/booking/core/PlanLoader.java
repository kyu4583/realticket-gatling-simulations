package simulations.booking.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import simulations.util.AsyncLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static simulations.config.Config.TARGET_EVENT;

public final class PlanLoader {

    public enum RequestType {
        BOOK,
        SECTION_MOVE
    }

    public static class PlannedRequest {
        public final String id;
        public final RequestType type;
        public final long timeMs;
        public final int section;
        public final int seat;
        public final int targetSection;
        public final int userId;
        public final String collisionLoserId;
        public final String requestBody;

        public PlannedRequest(
                String id,
                RequestType type,
                long timeMs,
                int section,
                int seat,
                int targetSection,
                int userId,
                String collisionLoserId
        ) {
            this.id = id;
            this.type = type;
            this.timeMs = timeMs;
            this.section = section;
            this.seat = seat;
            this.targetSection = targetSection;
            this.userId = userId;
            this.collisionLoserId = collisionLoserId;
            this.requestBody = switch (type) {
                case BOOK -> """
                    {"eventId":%d,"sectionIndex":%d,"seatIndex":%d,"expectedStatus":"reserved"}
                    """.formatted(TARGET_EVENT, section, seat).trim();
                case SECTION_MOVE -> """
                    {"sectionIndex":%d}
                    """.formatted(targetSection).trim();
            };
        }

        public boolean isBook() {
            return type == RequestType.BOOK;
        }

        public boolean isSectionMove() {
            return type == RequestType.SECTION_MOVE;
        }

        public boolean isCollisionLoser() {
            return collisionLoserId != null;
        }
    }

    public static class CollisionGroup {
        public final String id;
        public final long timeMs;
        public final int section;
        public final int seat;
        public final List<String> requestIds;

        public CollisionGroup(String id, long timeMs, int section, int seat, List<String> requestIds) {
            this.id = id;
            this.timeMs = timeMs;
            this.section = section;
            this.seat = seat;
            this.requestIds = requestIds;
        }
    }

    private static final Map<Integer, List<PlannedRequest>> userPlans = new ConcurrentHashMap<>();
    private static final Map<String, ConcurrentLinkedQueue<PlannedRequest>> loserRequestQueues = new ConcurrentHashMap<>();
    private static final Map<String, CollisionGroup> collisionGroups = new ConcurrentHashMap<>();
    private static final Map<String, String> requestToCollision = new ConcurrentHashMap<>();

    private static List<PlannedRequest> allRequestsSorted = null;
    private static int[] requestUserIds = null;

    private static int numUsers = 0;
    private static int seatsPerUser = 0;
    private static int totalPlannedRequests = 0;
    private static int totalBookRequests = 0;
    private static int totalSectionMoveRequests = 0;
    private static boolean noCollisionMode = false;
    private static boolean loaded = false;

    private PlanLoader() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }

        try {
            JsonNode planRoot = loadPlanJsonFile();
            parseStatsFromPlan(planRoot);
            parseCollisionGroupsFromPlan(planRoot);
            parseRequestsFromPlan(planRoot);
            printLoadSummary();
            loaded = true;
        } catch (Exception e) {
            System.err.println("정적 시나리오 로드 실패: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private static JsonNode loadPlanJsonFile() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var inputStream = PlanLoader.class.getResourceAsStream("/Plan.json");

        if (inputStream == null) {
            throw new RuntimeException("정적 시나리오 파일을 찾을 수 없음: /Plan.json");
        }

        JsonNode root = mapper.readTree(inputStream);
        inputStream.close();
        return root;
    }

    private static void parseStatsFromPlan(JsonNode planRoot) {
        JsonNode statsNode = planRoot.get("stats");
        if (statsNode != null) {
            seatsPerUser = statsNode.path("seats_per_user").asInt(0);
            numUsers = statsNode.path("num_users").asInt(0);
            noCollisionMode = statsNode.path("no_collision").asBoolean(false);
        }
    }

    private static void parseCollisionGroupsFromPlan(JsonNode planRoot) {
        JsonNode collisionGroupsNode = planRoot.get("collision_groups");
        if (collisionGroupsNode == null || !collisionGroupsNode.isArray()) {
            return;
        }

        for (JsonNode cgNode : collisionGroupsNode) {
            String id = cgNode.get("id").asText();
            long timeMs = cgNode.get("time_ms").asLong();
            int section = cgNode.get("section").asInt();
            int seat = cgNode.get("seat").asInt();

            List<String> reqIds = new ArrayList<>();
            for (JsonNode reqIdNode : cgNode.get("requests")) {
                String reqId = reqIdNode.asText();
                reqIds.add(reqId);
                requestToCollision.put(reqId, id);
            }

            collisionGroups.put(id, new CollisionGroup(id, timeMs, section, seat, reqIds));
            loserRequestQueues.put(id, new ConcurrentLinkedQueue<>());
        }
    }

    private static void parseRequestsFromPlan(JsonNode planRoot) {
        JsonNode requestsNode = planRoot.get("requests");
        if (requestsNode == null || !requestsNode.isArray()) {
            throw new RuntimeException("Plan.json에 requests 배열이 없습니다.");
        }

        Map<Integer, List<PlannedRequest>> tempUserPlans = new HashMap<>();

        for (JsonNode reqNode : requestsNode) {
            PlannedRequest request = parseRequest(reqNode);

            if (request.isBook()) {
                totalBookRequests++;
            } else if (request.isSectionMove()) {
                totalSectionMoveRequests++;
            }

            if (request.userId > 0) {
                tempUserPlans.computeIfAbsent(request.userId, k -> new ArrayList<>()).add(request);
            } else if (request.collisionLoserId != null && request.isBook()) {
                ConcurrentLinkedQueue<PlannedRequest> queue = loserRequestQueues.get(request.collisionLoserId);
                if (queue != null) {
                    queue.add(request);
                }
            }
        }

        sortAndStoreUserPlans(tempUserPlans);
    }

    private static PlannedRequest parseRequest(JsonNode reqNode) {
        String id = reqNode.get("id").asText();
        RequestType type = parseRequestType(reqNode.path("type").asText("book"));
        long timeMs = reqNode.get("time_ms").asLong();
        int section = reqNode.path("section").asInt(0);
        int seat = type == RequestType.BOOK ? reqNode.get("seat").asInt() : -1;
        int targetSection = type == RequestType.SECTION_MOVE
                ? reqNode.get("target_section").asInt()
                : section;

        JsonNode userNode = reqNode.get("user");
        int userId;
        String collisionLoserId = null;

        if (userNode.isInt()) {
            userId = userNode.asInt();
        } else if (userNode.isObject() && userNode.has("collision_loser")) {
            userId = -1;
            collisionLoserId = userNode.get("collision_loser").asText();
        } else {
            throw new RuntimeException("알 수 없는 user 형식: " + userNode);
        }

        return new PlannedRequest(id, type, timeMs, section, seat, targetSection, userId, collisionLoserId);
    }

    private static RequestType parseRequestType(String rawType) {
        return switch (rawType) {
            case "book" -> RequestType.BOOK;
            case "section_move" -> RequestType.SECTION_MOVE;
            default -> throw new RuntimeException("알 수 없는 request type: " + rawType);
        };
    }

    private static void sortAndStoreUserPlans(Map<Integer, List<PlannedRequest>> tempUserPlans) {
        List<Integer> sortedUserIds = new ArrayList<>(tempUserPlans.keySet());
        Collections.sort(sortedUserIds);

        for (Integer userId : sortedUserIds) {
            List<PlannedRequest> requests = tempUserPlans.get(userId);
            requests.sort(Comparator.comparingLong(r -> r.timeMs));
            userPlans.put(userId, requests);
        }

        totalPlannedRequests = userPlans.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    private static void printLoadSummary() {
        int totalLoserRequests = loserRequestQueues.values().stream()
                .mapToInt(ConcurrentLinkedQueue::size)
                .sum();

        System.out.println("=== 정적 시나리오 로드 완료 ===");
        System.out.println("  유저 수: " + numUsers);
        System.out.println("  유저당 좌석: " + seatsPerUser);
        System.out.println("  충돌 그룹: " + collisionGroups.size());
        System.out.println("  collision_loser 요청: " + totalLoserRequests);
        System.out.println("  no_collision 모드: " + noCollisionMode);
        System.out.println("  계획 요청: " + totalPlannedRequests);
        System.out.println("  좌석 점유 요청: " + totalBookRequests);
        System.out.println("  섹션 전환 요청: " + totalSectionMoveRequests);
    }

    public static synchronized void initializeParallelData() {
        if (allRequestsSorted != null) {
            return;
        }

        List<PlannedRequest> allReqs = new ArrayList<>();
        List<Integer> userIds = new ArrayList<>();

        for (Map.Entry<Integer, List<PlannedRequest>> entry : userPlans.entrySet()) {
            int userId = entry.getKey();
            for (PlannedRequest req : entry.getValue()) {
                allReqs.add(req);
                userIds.add(userId);
            }
        }

        Integer[] indices = new Integer[allReqs.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }

        Arrays.sort(indices, (a, b) -> Long.compare(allReqs.get(a).timeMs, allReqs.get(b).timeMs));

        allRequestsSorted = new ArrayList<>(allReqs.size());
        requestUserIds = new int[allReqs.size()];
        for (int i = 0; i < indices.length; i++) {
            allRequestsSorted.add(allReqs.get(indices[i]));
            requestUserIds[i] = userIds.get(indices[i]);
        }

        AsyncLogger.log("병렬 데이터 초기화 완료: " + allRequestsSorted.size() + "개 요청");
    }

    public static int getNumUsers() {
        return numUsers;
    }

    public static int getSeatsPerUser() {
        return seatsPerUser;
    }

    public static int getTotalPlannedRequests() {
        return totalPlannedRequests;
    }

    public static int getTotalBookRequests() {
        return totalBookRequests;
    }

    public static int getTotalSectionMoveRequests() {
        return totalSectionMoveRequests;
    }

    public static boolean isNoCollisionMode() {
        return noCollisionMode;
    }

    public static Map<Integer, List<PlannedRequest>> getUserPlans() {
        return userPlans;
    }

    public static Map<String, CollisionGroup> getCollisionGroups() {
        return collisionGroups;
    }

    public static Map<String, String> getRequestToCollision() {
        return requestToCollision;
    }

    public static Map<String, ConcurrentLinkedQueue<PlannedRequest>> getLoserRequestQueues() {
        return loserRequestQueues;
    }

    public static List<PlannedRequest> getAllRequestsSorted() {
        return allRequestsSorted;
    }

    public static int[] getRequestUserIds() {
        return requestUserIds;
    }
}
