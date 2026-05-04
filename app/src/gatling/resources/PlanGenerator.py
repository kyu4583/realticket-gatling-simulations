#!/usr/bin/env python3
"""
좌석 예매 시뮬레이션 플래너 (Static 시나리오용 Plan 생성기)

실제 N명의 사용자 행동을 시뮬레이션하여 요청 계획을 생성한다.
각 사용자는 지연된 좌석 스냅샷을 기반으로 좌석을 선택하며,
같은 좌석에 여러 요청이 발생하면 충돌로 처리된다.

"""

import json
import random
import heapq
from dataclasses import dataclass, field
from typing import Optional, Union
from collections import defaultdict


@dataclass(order=True)
class Event:
    """시뮬레이션 이벤트"""
    time_ms: int
    user_id: int = field(compare=False)


@dataclass
class Request:
    """예매 요청"""
    id: str
    time_ms: int
    user: int
    section: int
    seat: int
    success: bool = False  # 시뮬레이션에서의 성공 여부
    type: str = "book"                    # "book" | "section_move"  (GI-03)
    region: str = "default"               # region 라벨 (GI-03)
    target_section: Optional[int] = None  # section_move 전용

    def to_dict(self, user_override: Union[int, dict] = None) -> dict:
        d = {
            "id": self.id,
            "type": self.type,
            "time_ms": self.time_ms,
            "user": user_override if user_override is not None else self.user,
            "region": self.region,
        }
        if self.type == "book":
            d["section"] = self.section
            d["seat"] = self.seat
        elif self.type == "section_move":
            d["section"] = self.section
            d["target_section"] = self.target_section
        return d


@dataclass
class CollisionGroup:
    """충돌 그룹"""
    id: str
    time_ms: int
    section: int
    seat: int
    request_ids: list[str]
    winner_user: int  # 시뮬레이션에서의 승자 (결과 출력에선 제외)

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "time_ms": self.time_ms,
            "section": self.section,
            "seat": self.seat,
            "requests": self.request_ids
        }


class ReservationSimulator:
    def __init__(
            self,
            sections: list[dict],
            num_users: int,
            seats_per_user: int,
            seed: int = None,
            snapshot_interval_ms: int = 500,
            network_delay_ms: int = 50,
            request_delay_mean_ms: int = 500,
            request_delay_min_ms: int = 50,
            request_delay_skew: float = 0.0,
            no_collision: bool = False,
            # === Phase 6 신규 (GI-01) ===
            regions: Optional[list[dict]] = None,
            section_move_delay_mean_ms: int = 1500,
            section_move_delay_min_ms: int = 500,
            section_move_delay_skew: float = 0.0,
            section_move_target_strategy: str = "round_robin",
    ):
        if seed is not None:
            random.seed(seed)

        self.num_users = num_users
        self.seats_per_user = seats_per_user
        self.snapshot_interval_ms = snapshot_interval_ms
        self.network_delay_ms = network_delay_ms
        self.request_delay_mean_ms = request_delay_mean_ms
        self.request_delay_min_ms = request_delay_min_ms
        self.request_delay_skew = request_delay_skew
        self.no_collision = no_collision

        # 좌석 초기화: (section, seat) -> 점유 시각 (None이면 미점유)
        self.seats: dict[tuple[int, int], Optional[int]] = {}
        for sec_idx, section in enumerate(sections):
            for seat_idx, available in enumerate(section["seats"]):
                if available == 1:
                    self.seats[(sec_idx, seat_idx)] = None

        self.total_available = len(self.seats)
        self.total_needed = num_users * seats_per_user

        if self.total_needed > self.total_available:
            raise ValueError(f"좌석 부족: 필요 {self.total_needed}, 가용 {self.total_available}")

        # 유저별 상태
        self.user_secured: dict[int, int] = defaultdict(int)
        self.user_last_snapshot_time: dict[int, int] = defaultdict(lambda: -9999)
        self.user_requested_seats: dict[int, set] = defaultdict(set)  # 유저별 이미 요청한 좌석
        self.successful_requests: set[str] = set()  # 성공한 요청 ID

        # no_collision 모드: 유저별 좌석 미리 할당
        self.user_assigned_seats: dict[int, list] = {}
        if self.no_collision:
            self._assign_seats_to_users()

        # 스냅샷 히스토리: time -> 좌석 상태
        self.snapshots: dict[int, dict] = {0: {k: None for k in self.seats}}

        # 결과 저장
        self.requests: list[Request] = []
        self.request_counter = 0
        self.collision_counter = 0

        # === Phase 6 인스턴스 변수 ===
        self.section_move_delay_mean_ms = section_move_delay_mean_ms
        self.section_move_delay_min_ms = section_move_delay_min_ms
        self.section_move_delay_skew = section_move_delay_skew
        self.section_move_target_strategy = section_move_target_strategy
        self.section_count = len(sections)
        self.section_move_counter = 0  # SM ID 카운터

        # === Phase 6: regions 정규화 + window 계산 (Lock #4 단일 진실) ===
        self._normalized_regions = self._normalize_regions(regions)
        self._sim_max_time_ms = sum(r["duration_ms"] for r in self._normalized_regions)
        self._region_windows = self._compute_region_windows(self._normalized_regions)

    def _assign_seats_to_users(self):
        """no_collision 모드: 좌석을 유저별로 미리 할당"""
        all_seats = list(self.seats.keys())
        random.shuffle(all_seats)

        idx = 0
        for user_id in range(1, self.num_users + 1):
            self.user_assigned_seats[user_id] = []
            for _ in range(self.seats_per_user):
                if idx < len(all_seats):
                    self.user_assigned_seats[user_id].append(all_seats[idx])
                    idx += 1

    def _next_request_id(self) -> str:
        self.request_counter += 1
        return f"R{self.request_counter}"

    def _next_collision_id(self) -> str:
        self.collision_counter += 1
        return f"CG{self.collision_counter}"

    def _request_delay(self) -> int:
        """
        요청 간 딜레이 생성

        skew 값에 따른 분포:
        - skew = 1.0: 균등 분포 (1차 함수)
        - skew = 2.0: 제곱 분포 (2차 함수, 작은 값 쪽으로 치우침)
        - skew = 3.0: 세제곱 분포 (3차 함수)
        - skew = 0.0 (기본값): 지수 분포 (기존 동작)
        """
        min_delay = self.request_delay_min_ms
        mean_delay = self.request_delay_mean_ms
        skew = self.request_delay_skew

        if skew <= 0:
            # 지수 분포 (기존 동작)
            delay = int(random.expovariate(1 / mean_delay))
            return max(min_delay, delay)
        else:
            # 거듭제곱 분포: random()^skew * range + min
            # skew=1: 균등, skew>1: 작은 값 쪽으로 치우침
            range_delay = (mean_delay - min_delay) * 2  # mean이 중앙이 되도록
            u = random.random()
            delay = int(min_delay + (u ** skew) * range_delay)
            return max(min_delay, delay)

    def _network_delay(self) -> int:
        """네트워크 지연 (지수분포 기반)"""
        if self.network_delay_ms <= 0:
            return 0
        delay = int(random.expovariate(1 / self.network_delay_ms))
        return min(delay, self.network_delay_ms * 5)

    def _get_snapshot_time(self, current_time: int) -> int:
        """현재 시각에서 가장 최근 스냅샷 시각"""
        return (current_time // self.snapshot_interval_ms) * self.snapshot_interval_ms

    def _get_user_view(self, user_id: int, current_time: int) -> dict:
        """
        유저가 보는 좌석 현황
        - 스냅샷 주기 + 네트워크 지연 고려
        """
        # 이 유저에게 도달한 가장 최근 스냅샷
        snapshot_time = self._get_snapshot_time(current_time)
        net_delay = self._network_delay()
        effective_snapshot_time = max(0, snapshot_time - net_delay)

        # 스냅샷 주기에 맞춤
        effective_snapshot_time = (effective_snapshot_time // self.snapshot_interval_ms) * self.snapshot_interval_ms

        # 스냅샷이 없으면 가장 가까운 이전 스냅샷 사용
        if effective_snapshot_time not in self.snapshots:
            available_times = [t for t in self.snapshots if t <= effective_snapshot_time]
            effective_snapshot_time = max(available_times) if available_times else 0

        return dict(self.snapshots.get(effective_snapshot_time, self.snapshots[0]))

    def _choose_seat(self, user_id: int, current_time: int) -> Optional[tuple[int, int]]:
        """유저가 좌석 선택"""
        if self.no_collision:
            # no_collision 모드: 미리 할당된 좌석에서 순차 선택
            assigned = self.user_assigned_seats.get(user_id, [])
            already_requested = self.user_requested_seats[user_id]
            available = [seat for seat in assigned if seat not in already_requested]

            if not available:
                return None

            chosen = available[0]  # 순차 선택
            self.user_requested_seats[user_id].add(chosen)
            return chosen
        else:
            # 일반 모드: 스냅샷 기준 빈 좌석 중 랜덤 선택
            view = self._get_user_view(user_id, current_time)
            available = [seat for seat, occupied_time in view.items() if occupied_time is None]

            # 이미 요청한 좌석 제외
            already_requested = self.user_requested_seats[user_id]
            available = [seat for seat in available if seat not in already_requested]

            if not available:
                return None

            chosen = random.choice(available)
            self.user_requested_seats[user_id].add(chosen)
            return chosen

    def _update_snapshot(self, time_ms: int):
        """스냅샷 갱신"""
        snapshot_time = self._get_snapshot_time(time_ms)
        if snapshot_time not in self.snapshots:
            self.snapshots[snapshot_time] = dict(self.seats)

    # === Phase 6 신규 메서드 ===

    def _normalize_regions(self, regions: Optional[list[dict]]) -> list[dict]:
        """regions 미정의/빈 배열 시 단일 default region 자동 생성 (D-04).
        정의된 경우 6개 표준 키워드(D-03)와 region 이름 형식을 검증한다.
        """
        import re
        if not regions:
            return [{
                "name": "default",
                "duration_ms": 300000,  # 기존 max_time 5분과 동일 (하위 호환성)
                "actions": [{"kind": "book_seats"}],
            }]
        valid_kinds = {"wait", "subscribe", "book_seats", "section_move", "confirm", "login"}
        out = []
        for idx, r in enumerate(regions):
            if "name" not in r:
                raise ValueError(f"regions[{idx}]에 name 필드 없음")
            if "duration_ms" not in r or not isinstance(r["duration_ms"], int) or r["duration_ms"] <= 0:
                raise ValueError(f"regions[{idx}]의 duration_ms가 양의 정수가 아님: {r.get('duration_ms')}")
            if "actions" not in r or not isinstance(r["actions"], list):
                raise ValueError(f"regions[{idx}]에 actions 배열 없음")
            for a_idx, a in enumerate(r["actions"]):
                if not isinstance(a, dict) or "kind" not in a:
                    raise ValueError(f"regions[{idx}].actions[{a_idx}]에 kind 필드 없음")
                if a["kind"] not in valid_kinds:
                    raise ValueError(
                        f"regions[{idx}].actions[{a_idx}]의 kind '{a['kind']}'이 표준 키워드 셋 외: {valid_kinds}"
                    )
            if not re.match(r"^[a-z][a-z0-9_]*$", r["name"]):
                raise ValueError(f"region 이름 '{r['name']}'은 ^[a-z][a-z0-9_]*$ 형식이어야 함")
            out.append(dict(r))
        return out

    def _compute_region_windows(self, regions: list[dict]) -> list[dict]:
        """duration_ms 직렬 누적, start_ms·end_ms 계산 (GI-01)"""
        cursor = 0
        out = []
        for r in regions:
            out.append({
                **r,
                "start_ms": cursor,
                "end_ms": cursor + r["duration_ms"],
            })
            cursor += r["duration_ms"]
        return out

    def _section_move_delay(self) -> int:
        """section_move 간 딜레이 (_request_delay 패턴 복제)"""
        min_d = self.section_move_delay_min_ms
        mean_d = self.section_move_delay_mean_ms
        skew = self.section_move_delay_skew
        if skew <= 0:
            delay = int(random.expovariate(1 / mean_d))
            return max(min_d, delay)
        else:
            range_d = (mean_d - min_d) * 2
            u = random.random()
            delay = int(min_d + (u ** skew) * range_d)
            return max(min_d, delay)

    def _choose_target_section(self, current_section: int, user_id: int, move_idx: int) -> int:
        """target_section 결정 (round_robin | random)"""
        strategy = self.section_move_target_strategy
        sc = self.section_count
        if sc < 2:
            raise ValueError(f"section_move를 위해서는 2개 이상의 섹션 필요 (현재 {sc})")
        if strategy == "round_robin":
            return (current_section + 1) % sc
        elif strategy == "random":
            candidates = [s for s in range(sc) if s != current_section]
            return random.choice(candidates)
        else:
            raise ValueError(f"section_move_target_strategy='{strategy}' 미지원 (round_robin | random)")

    def _generate_section_moves_for_region(self, region: dict, region_window: dict) -> list:
        """region.actions 중 section_move kind에 대해 user당 count개 entry 생성"""
        sm_actions = [a for a in region["actions"] if a["kind"] == "section_move"]
        if not sm_actions:
            return []
        out: list[Request] = []
        for a in sm_actions:
            count = a.get("count", 0)
            if count <= 0:
                continue
            for user_id in range(1, self.num_users + 1):
                current_section = 0
                t = region_window["start_ms"] + self._section_move_delay()
                if t >= region_window["end_ms"]:
                    continue  # 초기 딜레이가 window를 초과 → 이 유저의 section_move는 수용 불가, 생략
                for move_idx in range(count):
                    if t >= region_window["end_ms"]:
                        break
                    target = self._choose_target_section(current_section, user_id, move_idx)
                    self.section_move_counter += 1
                    req = Request(
                        id=f"SM{self.section_move_counter}",
                        time_ms=t,
                        user=user_id,
                        section=current_section,
                        seat=-1,
                        type="section_move",
                        region=region["name"],
                        target_section=target,
                    )
                    out.append(req)
                    current_section = target
                    t += self._section_move_delay()
        return out

    def _run_book_seats_in_region(self, region_window: dict):
        """기존 event_queue 알고리즘을 region window 내부에서만 동작하도록 제한 (GI-01)"""
        event_queue: list = []
        region_start = region_window["start_ms"]
        region_end = region_window["end_ms"]
        region_name = region_window["name"]

        for user_id in range(1, self.num_users + 1):
            if self.user_secured[user_id] >= self.seats_per_user:
                continue
            start_time = region_start + self._request_delay()
            if start_time < region_end:
                heapq.heappush(event_queue, Event(start_time, user_id))

        while event_queue:
            event = heapq.heappop(event_queue)
            if event.time_ms >= region_end:
                break
            user_id = event.user_id
            if self.user_secured[user_id] >= self.seats_per_user:
                continue
            self._update_snapshot(event.time_ms)
            seat = self._choose_seat(user_id, event.time_ms)
            if seat is None:
                next_snapshot = self._get_snapshot_time(event.time_ms) + self.snapshot_interval_ms
                retry_time = next_snapshot + random.randint(10, 100)
                if retry_time < region_end:
                    heapq.heappush(event_queue, Event(retry_time, user_id))
                continue
            section, seat_num = seat
            request = Request(
                id=self._next_request_id(),
                time_ms=event.time_ms,
                user=user_id,
                section=section,
                seat=seat_num,
                type="book",
                region=region_name,           # GI-03: region 라벨 주입
            )
            self.requests.append(request)
            if self.seats[(section, seat_num)] is None:
                self.seats[(section, seat_num)] = event.time_ms
                self.user_secured[user_id] += 1
                self.successful_requests.add(request.id)
            if self.user_secured[user_id] < self.seats_per_user:
                delay = self._request_delay()
                next_time = event.time_ms + delay
                if next_time < region_end:
                    heapq.heappush(event_queue, Event(next_time, user_id))

    def run(self) -> dict:
        """시뮬레이션 실행 (Phase 6: regions 통합 — Lock #4 단일 진실)"""
        # 1. book_seats kind가 있는 region들에 대해 booking 실행
        book_seats_regions = [w for w in self._region_windows
                              if any(a["kind"] == "book_seats" for a in w["actions"])]
        for region_window in book_seats_regions:
            self._run_book_seats_in_region(region_window)

        # 2. section_move entry 생성 (모든 region 순회)
        section_move_requests: list[Request] = []
        for region_window in self._region_windows:
            section_move_requests.extend(
                self._generate_section_moves_for_region(region_window, region_window)
            )

        # 3. _process_results 호출 (booking 결과만 충돌 처리 — 내부 수정 X: Pitfall 1)
        result = self._process_results()

        # 4. section_move entry를 결과 requests에 시간순 머지
        sm_dicts = [r.to_dict() for r in section_move_requests]
        result["requests"].extend(sm_dicts)
        result["requests"].sort(key=lambda r: (r["time_ms"], r["id"]))

        # 5. stats에 regions 메타 부착 (GI-02)
        result["stats"]["regions"] = [
            {
                "name": w["name"],
                "duration_ms": w["duration_ms"],
                "start_ms": w["start_ms"],
                "end_ms": w["end_ms"],
                "actions": w["actions"],
            }
            for w in self._region_windows
        ]
        # simulation_duration_ms를 regions 합으로 갱신 (Pitfall 3 회피)
        result["stats"]["simulation_duration_ms"] = self._sim_max_time_ms

        return result

    def _process_results(self) -> dict:
        """
        결과 후처리: collision_loser 식별 → 충돌 그룹 생성

        핵심 원칙:
        - 각 유저는 M개 좌석을 확보하며, M개의 독립적인 "좌석 확보 시도"를 함
        - 각 시도의 첫 번째 요청 = 일반 요청 (user: 숫자)
        - 시도 중 실패 후 재시도 = collision_loser 요청
        - 충돌 그룹은 일반 요청만으로 구성 (collision_loser 요청은 제외)
        """
        # 1. 유저별 요청을 시간순 정렬 (book type만)
        user_requests: dict[int, list[Request]] = defaultdict(list)
        for req in self.requests:
            if req.type == "book":
                user_requests[req.user].append(req)

        for user_id in user_requests:
            user_requests[user_id].sort(key=lambda r: (r.time_ms, r.id))

        # 2. 각 유저의 "좌석 확보 시도" 분리 → collision_loser 식별
        # 첫 요청 = 일반, 이후 요청 = collision_loser (이전 요청이 속한 충돌의)
        first_requests: set[str] = set()  # 각 시도의 첫 번째 요청 ID
        loser_chain: dict[str, str] = {}  # request_id -> previous_request_id

        for user_id, reqs in user_requests.items():
            attempt_start = True
            prev_req = None

            for req in reqs:
                if attempt_start:
                    # 새로운 시도 시작
                    first_requests.add(req.id)
                    attempt_start = False
                else:
                    # 이전 요청의 재시도
                    loser_chain[req.id] = prev_req.id

                prev_req = req

                # 이 요청이 성공했는지 확인
                if req.id in self.successful_requests:
                    # 성공 → 현재 시도 완료, 다음 시도 시작
                    attempt_start = True

        # 3. 충돌 그룹 생성 (book type 요청 대상)
        # 같은 좌석에 2개 이상 요청이 있으면 충돌 그룹
        seat_requests: dict[tuple[int, int], list[Request]] = defaultdict(list)
        for req in self.requests:
            if req.type == "book":
                key = (req.section, req.seat)
                seat_requests[key].append(req)

        collision_groups: list[CollisionGroup] = []
        request_to_collision: dict[str, str] = {}

        for (section, seat), reqs in seat_requests.items():
            if len(reqs) > 1:
                # 충돌 발생: 시간순 정렬 (동시면 랜덤)
                reqs_sorted = sorted(reqs, key=lambda r: (r.time_ms, r.id))

                # 승자 = 첫 번째 성공한 요청
                winner = next((r for r in reqs_sorted if r.id in self.successful_requests), reqs_sorted[0])
                collision_time = winner.time_ms

                # 모든 요청을 충돌 시각으로 조정
                for req in reqs_sorted:
                    req.time_ms = collision_time

                collision = CollisionGroup(
                    id=self._next_collision_id(),
                    time_ms=collision_time,
                    section=section,
                    seat=seat,
                    request_ids=[r.id for r in reqs_sorted],
                    winner_user=winner.user
                )
                collision_groups.append(collision)

                for req in reqs_sorted:
                    request_to_collision[req.id] = collision.id

        # 4. collision_loser 변환: loser_chain을 따라가서 이전 요청이 속한 충돌 찾기
        loser_next_requests: dict[str, str] = {}

        for req_id, prev_req_id in loser_chain.items():
            # 이전 요청이 충돌에 참여했으면 그 충돌의 loser
            if prev_req_id in request_to_collision:
                loser_next_requests[req_id] = request_to_collision[prev_req_id]
            else:
                # 이전 요청도 collision_loser였으면 체인 따라가기
                chain_req_id = prev_req_id
                while chain_req_id in loser_chain:
                    chain_prev = loser_chain[chain_req_id]
                    if chain_prev in request_to_collision:
                        loser_next_requests[req_id] = request_to_collision[chain_prev]
                        break
                    chain_req_id = chain_prev

        # 5. 최종 요청 목록 생성 (book type만 — section_move는 run()에서 외부 머지)
        final_requests = []
        for req in self.requests:
            if req.type != "book":
                continue
            if req.id in loser_next_requests:
                user_field = {"collision_loser": loser_next_requests[req.id]}
            else:
                user_field = req.user
            final_requests.append(req.to_dict(user_field))

        final_requests.sort(key=lambda r: (r["time_ms"], r["id"]))

        # 6. 통계 계산
        collision_request_count = sum(len(cg.request_ids) for cg in collision_groups)
        loser_request_count = len(loser_next_requests)

        # 검증: 각 유저의 일반 요청 수 (book type만)
        user_normal_count = defaultdict(int)
        for req in final_requests:
            if req.get("type", "book") == "book" and isinstance(req["user"], int):
                user_normal_count[req["user"]] += 1

        stats = {
            "num_users": self.num_users,
            "seats_per_user": self.seats_per_user,
            "total_seats": self.total_needed,
            "total_available_seats": self.total_available,
            "total_requests": len(self.requests),
            "collision_groups": len(collision_groups),
            "collision_requests": collision_request_count,
            "conditional_requests": loser_request_count,
            "snapshot_interval_ms": self.snapshot_interval_ms,
            "network_delay_ms": self.network_delay_ms,
            "request_delay_mean_ms": self.request_delay_mean_ms,
            "request_delay_min_ms": self.request_delay_min_ms,
            "request_delay_skew": self.request_delay_skew,
            "no_collision": self.no_collision,
            "simulation_duration_ms": max((r["time_ms"] for r in final_requests), default=0),
            "users_completed": sum(1 for c in self.user_secured.values() if c >= self.seats_per_user)
        }

        return {
            "stats": stats,
            "requests": final_requests,
            "collision_groups": [cg.to_dict() for cg in collision_groups]
        }


def validate_plan(plan: dict) -> list[str]:
    """계획 검증"""
    errors = []
    stats = plan["stats"]

    # 1. 모든 유저 완료 확인
    if stats["users_completed"] != stats["num_users"]:
        errors.append(f"미완료 유저: {stats['num_users'] - stats['users_completed']}명")

    # 2. 충돌 그룹 내 요청들은 같은 시간이어야 함
    request_map = {req["id"]: req for req in plan["requests"]}
    for cg in plan["collision_groups"]:
        times = set()
        for req_id in cg["requests"]:
            if req_id in request_map:
                times.add(request_map[req_id]["time_ms"])
        if len(times) > 1:
            errors.append(f"충돌 그룹 {cg['id']}의 요청 시간 불일치: {sorted(times)}")

    # 3. collision_loser 요청의 collision_id가 유효한지 확인
    collision_ids = {cg["id"] for cg in plan["collision_groups"]}
    for req in plan["requests"]:
        if isinstance(req["user"], dict):
            cg_id = req["user"].get("collision_loser")
            if cg_id not in collision_ids:
                errors.append(f"요청 {req['id']}의 collision_loser {cg_id}가 존재하지 않음")

    # 4. collision_loser가 자신이 속한 충돌 그룹을 참조하면 안 됨
    for cg in plan["collision_groups"]:
        for req_id in cg["requests"]:
            req = request_map.get(req_id)
            if req and isinstance(req["user"], dict):
                loser_cg = req["user"].get("collision_loser")
                if loser_cg == cg["id"]:
                    errors.append(f"요청 {req_id}가 자신이 속한 충돌 그룹 {cg['id']}를 참조함")

    # 5. 각 유저의 일반 요청 수 = seats_per_user 확인 (book type만 카운트, section_move 제외)
    user_normal_count = defaultdict(int)
    for req in plan["requests"]:
        if req.get("type", "book") == "book" and isinstance(req["user"], int):
            user_normal_count[req["user"]] += 1

    wrong_users = [u for u, c in user_normal_count.items() if c != stats["seats_per_user"]]
    if wrong_users:
        errors.append(f"일반 요청 수가 {stats['seats_per_user']}가 아닌 유저: {len(wrong_users)}명")

    # ===== Phase 6 신규 규칙 (6-11) =====

    # 규칙 6: 모든 request entry에 region 라벨 존재
    for req in plan["requests"]:
        if "region" not in req:
            errors.append(f"요청 {req['id']}에 region 라벨 없음")

    # 규칙 7: region 이름이 stats.regions 집합 안
    valid_regions = {r["name"] for r in stats.get("regions", [])} | {"default"}
    for req in plan["requests"]:
        rname = req.get("region")
        if rname is not None and rname not in valid_regions:
            errors.append(f"요청 {req['id']}의 region '{rname}'이 정의되지 않음")

    # 규칙 8: section_move target_section 범위 검증
    book_sections = [r.get("section") for r in plan["requests"]
                     if r.get("type", "book") == "book" and isinstance(r.get("section"), int) and r.get("section") >= 0]
    section_count_estimate = max(book_sections) + 1 if book_sections else stats.get("num_sections", 0)
    for req in plan["requests"]:
        if req.get("type") == "section_move":
            ts = req.get("target_section")
            if ts is None or ts < 0 or (section_count_estimate > 0 and ts >= section_count_estimate):
                errors.append(f"section_move {req['id']}의 target_section={ts}이 범위 밖")
            elif ts == req.get("section"):
                errors.append(f"section_move {req['id']}의 target_section이 현재 section과 동일")

    # 규칙 9-10: time_ms가 region window 안
    region_windows_map = {r["name"]: (r["start_ms"], r["end_ms"]) for r in stats.get("regions", [])}
    region_windows_map.setdefault("default", (0, stats.get("simulation_duration_ms", 300000)))
    for req in plan["requests"]:
        rname = req.get("region", "default")
        if rname in region_windows_map:
            start, end = region_windows_map[rname]
            if not (start <= req["time_ms"] < end):
                errors.append(f"{req.get('type','book')} {req['id']} time_ms={req['time_ms']} 가 region '{rname}'의 [{start},{end}) 밖")

    # 규칙 11: region duration 합 >= simulation_duration_ms
    regions_list = stats.get("regions", [])
    if regions_list:
        total_duration = sum(r.get("duration_ms", 0) for r in regions_list)
        sim_dur = stats.get("simulation_duration_ms", 0)
        if total_duration < sim_dur:
            errors.append(f"region 총 duration {total_duration}ms < 시뮬레이션 duration {sim_dur}ms")

    return errors


def load_config(config_path: str) -> dict:
    """설정 파일 로드"""
    with open(config_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    if isinstance(data, dict) and "sections" in data:
        return data
    if isinstance(data, list):
        return {"sections": data}

    raise ValueError(f"지원하지 않는 파일 형식: {config_path}")


def find_default_config() -> Optional[str]:
    """스크립트와 같은 경로에서 PlanConfig.json 찾기"""
    import os
    script_dir = os.path.dirname(os.path.abspath(__file__))
    default_path = os.path.join(script_dir, "PlanConfig.json")
    if os.path.exists(default_path):
        return default_path
    return None


def _run_selftest(case_filter=None):
    """GI-01/02/03 핵심 3 selftest 케이스 (regions_default, regions_serial, region_label_on_requests)"""
    import sys
    sections_small = [{"col_len": 10, "seats": [1] * 30} for _ in range(3)]
    all_cases = []

    def case(name):
        def deco(fn):
            all_cases.append((name, fn))
            return fn
        return deco

    @case("regions_default")
    def _regions_default():
        sim = ReservationSimulator(sections=sections_small, num_users=5, seats_per_user=2,
                                   seed=42, no_collision=True)
        plan = sim.run()
        errs = []
        if plan["stats"]["regions"][0]["name"] != "default":
            errs.append(f"regions[0].name={plan['stats']['regions'][0]['name']} != 'default'")
        if not all(r.get("region") == "default" for r in plan["requests"]):
            errs.append("일부 request의 region이 'default'가 아님")
        errs.extend(validate_plan(plan))
        return errs

    @case("regions_serial")
    def _regions_serial():
        regions = [
            {"name": "a", "duration_ms": 10000, "actions": [{"kind": "subscribe"}]},
            {"name": "b", "duration_ms": 20000, "actions": [{"kind": "book_seats"}]}
        ]
        sim = ReservationSimulator(sections=sections_small, num_users=3, seats_per_user=2,
                                   seed=42, no_collision=True, regions=regions)
        plan = sim.run()
        rs = plan["stats"]["regions"]
        errs = []
        if rs[0]["start_ms"] != 0 or rs[0]["end_ms"] != 10000:
            errs.append(f"regions[0] window 오류: start={rs[0]['start_ms']}, end={rs[0]['end_ms']}")
        if rs[1]["start_ms"] != 10000 or rs[1]["end_ms"] != 30000:
            errs.append(f"regions[1] window 오류: start={rs[1]['start_ms']}, end={rs[1]['end_ms']}")
        errs.extend(validate_plan(plan))
        return errs

    @case("region_label_on_requests")
    def _region_label():
        sim = ReservationSimulator(sections=sections_small, num_users=3, seats_per_user=2,
                                   seed=42, no_collision=True)
        plan = sim.run()
        errs = []
        if not all("region" in r for r in plan["requests"]):
            errs.append("일부 request에 region 필드 없음")
        return errs

    cases_to_run = [(n, fn) for n, fn in all_cases if (case_filter is None or case_filter == n)]
    if not cases_to_run:
        print(f"FAIL: --case '{case_filter}' 매칭 케이스 없음", file=sys.stderr)
        return False
    all_pass = True
    for name, fn in cases_to_run:
        try:
            errs = fn()
            if errs:
                print(f"FAIL [{name}]: {errs[:3]}", file=sys.stderr)
                all_pass = False
            else:
                print(f"PASS [{name}]", file=sys.stderr)
        except Exception as e:
            import traceback
            print(f"FAIL [{name}]: 예외 {type(e).__name__}: {e}", file=sys.stderr)
            traceback.print_exc(file=sys.stderr)
            all_pass = False
    return all_pass


def main():
    import argparse
    import sys

    parser = argparse.ArgumentParser(
        description="좌석 예매 시뮬레이션 플래너",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
예시:
  python3 planner.py                          # PlanConfig.json 자동 로드
  python3 planner.py -c PlanConfig.json       # 설정 파일 지정
  python3 planner.py -n 100 -m 4              # 유저 수, 좌석 수 지정
  python3 planner.py --network-delay 100      # 네트워크 지연 증가
  python3 planner.py --request-delay-mean 300 # 요청 간 평균 딜레이 조정
  python3 planner.py --request-delay-skew 1   # 균등 분포 딜레이
  python3 planner.py --no-collision           # 충돌 없는 계획 생성
  python3 planner.py --selftest               # 전체 selftest 실행
  python3 planner.py --selftest --case regions_default  # 특정 케이스

request_delay_skew 값에 따른 분포:
  0 (기본): 지수 분포 (짧은 딜레이가 많고 긴 딜레이가 드묾)
  1: 균등 분포 (모든 딜레이가 고르게 분포)
  2: 제곱 분포 (짧은 딜레이 쪽으로 치우침)
  3: 세제곱 분포 (더 짧은 딜레이 쪽으로 치우침)
        """
    )
    parser.add_argument("--config", "-c", type=str, help="설정 파일")
    parser.add_argument("--users", "-n", type=int, help="유저 수")
    parser.add_argument("--seats-per-user", "-m", type=int, help="유저당 좌석 수")
    parser.add_argument("--seed", type=int, help="랜덤 시드")
    parser.add_argument("--snapshot-interval", type=int, default=None,
                        help="서버 좌석 현황 갱신 주기 (ms, 기본: 500)")
    parser.add_argument("--network-delay", type=int, default=None,
                        help="네트워크 지연 평균 (ms, 기본: 50)")
    parser.add_argument("--request-delay-mean", type=int, default=None,
                        help="요청 간 평균 딜레이 (ms, 기본: 500)")
    parser.add_argument("--request-delay-min", type=int, default=None,
                        help="요청 간 최소 딜레이 (ms, 기본: 50)")
    parser.add_argument("--request-delay-skew", type=float, default=None,
                        help="딜레이 분포 형태 (0=지수, 1=균등, 2+=치우침, 기본: 0)")
    parser.add_argument("--no-collision", action="store_true",
                        help="충돌 없는 계획 생성 (좌석을 유저별로 미리 할당)")
    parser.add_argument("--output", "-o", type=str, help="출력 파일")
    parser.add_argument("--validate", action="store_true", help="결과 검증")
    # === Phase 6 신규 CLI 옵션 ===
    parser.add_argument("--section-move-delay-mean", type=int, default=None)
    parser.add_argument("--section-move-delay-min", type=int, default=None)
    parser.add_argument("--section-move-delay-skew", type=float, default=None)
    parser.add_argument("--section-move-target-strategy", type=str, default=None,
                        choices=["round_robin", "random"])
    parser.add_argument("--regions", type=str, default=None,
                        help="regions 정의 JSON 문자열")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--case", type=str, default=None)

    args = parser.parse_args()

    # --selftest 분기 (args 파싱 직후, sections 로딩 전)
    if args.selftest:
        success = _run_selftest(case_filter=args.case)
        sys.exit(0 if success else 1)

    # 기본값
    config = {
        "num_users": 10,
        "seats_per_user": 2,
        "seed": None,
        "snapshot_interval": 500,
        "network_delay": 50,
        "request_delay_mean": 500,
        "request_delay_min": 50,
        "request_delay_skew": 0.0,
        "no_collision": False,
        # Phase 6 신규
        "section_move_delay_mean_ms": 1500,
        "section_move_delay_min_ms": 500,
        "section_move_delay_skew": 0.0,
        "section_move_target_strategy": "round_robin",
    }
    sections = None
    regions = None  # Phase 6: PlanConfig.regions 최상위 키

    # 설정 파일 로드
    config_path = args.config or find_default_config()
    if config_path:
        print(f"설정 파일: {config_path}", file=sys.stderr)
        loaded = load_config(config_path)
        sections = loaded.get("sections")
        regions = loaded.get("regions")  # Phase 6: 최상위 regions 키
        if "config" in loaded:
            config.update(loaded["config"])
            # config 블록의 4필드도 override
            for key in ("section_move_delay_mean_ms", "section_move_delay_min_ms",
                        "section_move_delay_skew", "section_move_target_strategy"):
                if key in loaded.get("config", {}):
                    config[key] = loaded["config"][key]

    # CLI 파라미터로 덮어쓰기
    if args.users is not None:
        config["num_users"] = args.users
    if args.seats_per_user is not None:
        config["seats_per_user"] = args.seats_per_user
    if args.seed is not None:
        config["seed"] = args.seed
    if args.snapshot_interval is not None:
        config["snapshot_interval"] = args.snapshot_interval
    if args.network_delay is not None:
        config["network_delay"] = args.network_delay
    if args.request_delay_mean is not None:
        config["request_delay_mean"] = args.request_delay_mean
    if args.request_delay_min is not None:
        config["request_delay_min"] = args.request_delay_min
    if args.request_delay_skew is not None:
        config["request_delay_skew"] = args.request_delay_skew
    if args.no_collision:
        config["no_collision"] = True
    # Phase 6 CLI override
    if args.section_move_delay_mean is not None:
        config["section_move_delay_mean_ms"] = args.section_move_delay_mean
    if args.section_move_delay_min is not None:
        config["section_move_delay_min_ms"] = args.section_move_delay_min
    if args.section_move_delay_skew is not None:
        config["section_move_delay_skew"] = args.section_move_delay_skew
    if args.section_move_target_strategy is not None:
        config["section_move_target_strategy"] = args.section_move_target_strategy
    if args.regions is not None:
        regions = json.loads(args.regions)

    if sections is None:
        print("오류: 좌석 데이터가 없습니다.", file=sys.stderr)
        sys.exit(1)

    total_seats = sum(sum(s["seats"]) for s in sections)
    print(f"설정: users={config['num_users']}, seats_per_user={config['seats_per_user']}, "
          f"seed={config['seed']}", file=sys.stderr)
    print(f"좌석: {len(sections)}개 섹션, 총 {total_seats}석 가용", file=sys.stderr)
    print(f"네트워크: snapshot_interval={config['snapshot_interval']}ms, "
          f"network_delay={config['network_delay']}ms", file=sys.stderr)
    print(f"요청 딜레이: mean={config['request_delay_mean']}ms, "
          f"min={config['request_delay_min']}ms, skew={config['request_delay_skew']}", file=sys.stderr)
    if config["no_collision"]:
        print("모드: 충돌 없음 (좌석 미리 할당)", file=sys.stderr)

    simulator = ReservationSimulator(
        sections=sections,
        num_users=config["num_users"],
        seats_per_user=config["seats_per_user"],
        seed=config["seed"],
        snapshot_interval_ms=config["snapshot_interval"],
        network_delay_ms=config["network_delay"],
        request_delay_mean_ms=config["request_delay_mean"],
        request_delay_min_ms=config["request_delay_min"],
        request_delay_skew=config["request_delay_skew"],
        no_collision=config["no_collision"],
        # Phase 6 신규
        regions=regions,
        section_move_delay_mean_ms=config["section_move_delay_mean_ms"],
        section_move_delay_min_ms=config["section_move_delay_min_ms"],
        section_move_delay_skew=config["section_move_delay_skew"],
        section_move_target_strategy=config["section_move_target_strategy"],
    )

    plan = simulator.run()

    # 검증
    if args.validate:
        errors = validate_plan(plan)
        if errors:
            print("검증 실패:", file=sys.stderr)
            for err in errors[:10]:
                print(f"  {err}", file=sys.stderr)
            sys.exit(1)
        print("검증 통과", file=sys.stderr)

    # 출력
    import os
    output_path = args.output or os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "Plan.json"
    )

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(plan, f, indent=2, ensure_ascii=False)

    print(f"\n=== 결과 ===", file=sys.stderr)
    print(f"출력: {output_path}", file=sys.stderr)
    print(file=sys.stderr)
    for key, value in plan["stats"].items():
        print(f"  {key}: {value}", file=sys.stderr)


if __name__ == "__main__":
    main()
