#!/usr/bin/env python3
"""
좌석 예매 시뮬레이션 플래너 (Static 시나리오용 Plan 생성기)

실제 N명의 사용자 행동을 시뮬레이션하여 요청 계획을 생성한다.
각 사용자는 지연된 좌석 스냅샷을 기반으로 좌석을 선택하며,
같은 좌석에 여러 요청이 발생하면 충돌로 처리된다.

Plan.json 구조: stats(simulation_duration_ms 포함) · requests · collision_groups
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
    success: bool = False
    type: str = "book"                     # "book" | "section_move"
    target_section: Optional[int] = None   # section_move 전용

    def to_dict(self, user_override: Union[int, dict] = None) -> dict:
        d = {
            "id": self.id,
            "type": self.type,
            "time_ms": self.time_ms,
            "user": user_override if user_override is not None else self.user,
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
    winner_user: int

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "time_ms": self.time_ms,
            "section": self.section,
            "seat": self.seat,
            "requests": self.request_ids
        }


# 안전 상한 — 자연 종료가 못 일어나는 buggy 시나리오를 빨리 잡기 위한 가드.
# 정상적인 매니페스트는 num_users * seats_per_user * request_delay_mean_ms 수준에서 끝난다.
MAX_SIMULATION_DURATION_MS = 3_600_000  # 1 hour


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
            section_move_count: int = 0,
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
        self._section_move_count = section_move_count
        self.section_move_target_strategy = section_move_target_strategy

        self.seats: dict[tuple[int, int], Optional[int]] = {}
        for sec_idx, section in enumerate(sections):
            for seat_idx, available in enumerate(section["seats"]):
                if available == 1:
                    self.seats[(sec_idx, seat_idx)] = None

        self.total_available = len(self.seats)
        self.total_needed = num_users * seats_per_user

        if self.total_needed > self.total_available:
            raise ValueError(f"좌석 부족: 필요 {self.total_needed}, 가용 {self.total_available}")

        self.user_secured: dict[int, int] = defaultdict(int)
        self.user_last_snapshot_time: dict[int, int] = defaultdict(lambda: -9999)
        self.user_requested_seats: dict[int, set] = defaultdict(set)
        self.successful_requests: set[str] = set()

        self._reserved_seats: set[tuple[int, int]] = set()  # no_collision 모드 충돌 방지용

        self.snapshots: dict[int, dict] = {0: {k: None for k in self.seats}}
        self.requests: list[Request] = []
        self.request_counter = 0
        self.collision_counter = 0
        self.section_count = len(sections)
        self.section_move_counter = 0

    def _next_request_id(self) -> str:
        self.request_counter += 1
        return f"R{self.request_counter}"

    def _next_collision_id(self) -> str:
        self.collision_counter += 1
        return f"CG{self.collision_counter}"

    def _request_delay(self) -> int:
        min_delay = self.request_delay_min_ms
        mean_delay = self.request_delay_mean_ms
        skew = self.request_delay_skew
        if skew <= 0:
            delay = int(random.expovariate(1 / mean_delay))
            return max(min_delay, delay)
        else:
            range_delay = (mean_delay - min_delay) * 2
            u = random.random()
            delay = int(min_delay + (u ** skew) * range_delay)
            return max(min_delay, delay)

    def _network_delay(self) -> int:
        if self.network_delay_ms <= 0:
            return 0
        delay = int(random.expovariate(1 / self.network_delay_ms))
        return min(delay, self.network_delay_ms * 5)

    def _get_snapshot_time(self, current_time: int) -> int:
        return (current_time // self.snapshot_interval_ms) * self.snapshot_interval_ms

    def _get_user_view(self, user_id: int, current_time: int) -> dict:
        snapshot_time = self._get_snapshot_time(current_time)
        net_delay = self._network_delay()
        effective_snapshot_time = max(0, snapshot_time - net_delay)
        effective_snapshot_time = (effective_snapshot_time // self.snapshot_interval_ms) * self.snapshot_interval_ms
        if effective_snapshot_time not in self.snapshots:
            available_times = [t for t in self.snapshots if t <= effective_snapshot_time]
            effective_snapshot_time = max(available_times) if available_times else 0
        return dict(self.snapshots.get(effective_snapshot_time, self.snapshots[0]))

    def _choose_seat(self, user_id: int, current_time: int, current_section: int) -> Optional[tuple[int, int]]:
        already_requested = self.user_requested_seats[user_id]
        if self.no_collision:
            available = [
                seat for seat in self.seats
                if seat[0] == current_section
                   and seat not in self._reserved_seats
                   and seat not in already_requested
            ]
            if not available:
                return None
            chosen = random.choice(available)
            self.user_requested_seats[user_id].add(chosen)
            self._reserved_seats.add(chosen)
            return chosen
        else:
            view = self._get_user_view(user_id, current_time)
            available = [
                seat for seat, occupied_time in view.items()
                if occupied_time is None
                   and seat[0] == current_section
                   and seat not in already_requested
            ]
            if not available:
                return None
            chosen = random.choice(available)
            self.user_requested_seats[user_id].add(chosen)
            return chosen

    def _update_snapshot(self, time_ms: int):
        snapshot_time = self._get_snapshot_time(time_ms)
        if snapshot_time not in self.snapshots:
            self.snapshots[snapshot_time] = dict(self.seats)

    def _choose_target_section(self, current_section: int, user_id: int, move_idx: int) -> int:
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

    def _run_simulation(self):
        """통합 이벤트 큐 — book와 section_move를 동일 타임라인·딜레이로 처리.

        모든 유저가 seats_per_user 확보 + section_move_count 완료 시 종료.
        book/section_move 간 간격은 동일한 _request_delay()를 사용하며,
        남은 비율에 따라 두 액션을 확률적으로 인터리브한다.
        """
        event_queue: list = []
        user_sm_done: dict[int, int] = defaultdict(int)
        user_current_section: dict[int, int] = defaultdict(int)

        for user_id in range(1, self.num_users + 1):
            heapq.heappush(event_queue, Event(self._request_delay(), user_id))

        while event_queue:
            event = heapq.heappop(event_queue)
            if event.time_ms >= MAX_SIMULATION_DURATION_MS:
                raise RuntimeError(
                    f"시뮬레이션이 {MAX_SIMULATION_DURATION_MS}ms 안전 상한 초과 — "
                    f"자연 종료 불가. num_users / seats_per_user / request_delay 점검 필요."
                )
            user_id = event.user_id
            books_left = self.seats_per_user - self.user_secured[user_id]
            sm_left = self._section_move_count - user_sm_done[user_id]

            if books_left <= 0 and sm_left <= 0:
                continue

            # 남은 비율로 확률적 인터리브
            if sm_left > 0 and books_left > 0:
                do_sm = random.random() < (sm_left / (sm_left + books_left))
            else:
                do_sm = sm_left > 0

            if do_sm:
                current_section = user_current_section[user_id]
                target = self._choose_target_section(current_section, user_id, user_sm_done[user_id])
                self.section_move_counter += 1
                req = Request(
                    id=f"SM{self.section_move_counter}",
                    time_ms=event.time_ms,
                    user=user_id,
                    section=current_section,
                    seat=-1,
                    type="section_move",
                    target_section=target,
                )
                self.requests.append(req)
                user_current_section[user_id] = target
                user_sm_done[user_id] += 1
            else:
                self._update_snapshot(event.time_ms)
                seat = self._choose_seat(user_id, event.time_ms, user_current_section[user_id])
                if seat is None:
                    next_snapshot = self._get_snapshot_time(event.time_ms) + self.snapshot_interval_ms
                    retry_time = next_snapshot + random.randint(10, 100)
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
                )
                self.requests.append(request)
                if self.seats[(section, seat_num)] is None:
                    self.seats[(section, seat_num)] = event.time_ms
                    self.user_secured[user_id] += 1
                    self.successful_requests.add(request.id)

            if (self.seats_per_user - self.user_secured[user_id] > 0
                    or self._section_move_count - user_sm_done[user_id] > 0):
                heapq.heappush(event_queue, Event(event.time_ms + self._request_delay(), user_id))

    def run(self) -> dict:
        """시뮬레이션 실행. simulation_duration_ms 는 결과에서 derive 됨 (입력 X)."""
        self._run_simulation()
        return self._process_results()

    def _process_results(self) -> dict:
        user_requests: dict[int, list[Request]] = defaultdict(list)
        for req in self.requests:
            if req.type == "book":
                user_requests[req.user].append(req)
        for user_id in user_requests:
            user_requests[user_id].sort(key=lambda r: (r.time_ms, r.id))

        first_requests: set[str] = set()
        loser_chain: dict[str, str] = {}
        for user_id, reqs in user_requests.items():
            attempt_start = True
            prev_req = None
            for req in reqs:
                if attempt_start:
                    first_requests.add(req.id)
                    attempt_start = False
                else:
                    loser_chain[req.id] = prev_req.id
                prev_req = req
                if req.id in self.successful_requests:
                    attempt_start = True

        seat_requests: dict[tuple[int, int], list[Request]] = defaultdict(list)
        for req in self.requests:
            if req.type == "book":
                key = (req.section, req.seat)
                seat_requests[key].append(req)

        collision_groups: list[CollisionGroup] = []
        request_to_collision: dict[str, str] = {}
        for (section, seat), reqs in seat_requests.items():
            if len(reqs) > 1:
                reqs_sorted = sorted(reqs, key=lambda r: (r.time_ms, r.id))
                winner = next((r for r in reqs_sorted if r.id in self.successful_requests), reqs_sorted[0])
                collision_time = winner.time_ms
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

        loser_next_requests: dict[str, str] = {}
        for req_id, prev_req_id in loser_chain.items():
            if prev_req_id in request_to_collision:
                loser_next_requests[req_id] = request_to_collision[prev_req_id]
            else:
                chain_req_id = prev_req_id
                visited = set()
                while chain_req_id in loser_chain:
                    if chain_req_id in visited:
                        break
                    visited.add(chain_req_id)
                    chain_prev = loser_chain[chain_req_id]
                    if chain_prev in request_to_collision:
                        loser_next_requests[req_id] = request_to_collision[chain_prev]
                        break
                    chain_req_id = chain_prev

        final_requests = []
        for req in self.requests:
            if req.type == "section_move":
                final_requests.append(req.to_dict())
            elif req.type == "book":
                if req.id in loser_next_requests:
                    user_field = {"collision_loser": loser_next_requests[req.id]}
                else:
                    user_field = req.user
                final_requests.append(req.to_dict(user_field))
        final_requests.sort(key=lambda r: (r["time_ms"], r["id"]))

        collision_request_count = sum(len(cg.request_ids) for cg in collision_groups)
        loser_request_count = len(loser_next_requests)
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
            "num_sections": self.section_count,
            "simulation_duration_ms": max((r.time_ms for r in self.requests), default=0),
            "users_completed": sum(1 for c in self.user_secured.values() if c >= self.seats_per_user)
        }

        return {
            "stats": stats,
            "requests": final_requests,
            "collision_groups": [cg.to_dict() for cg in collision_groups]
        }


def validate_plan(plan: dict) -> list[str]:
    """계획 검증 (Rules 1-5: 완료·충돌·loser chain)."""
    errors = []
    stats = plan["stats"]

    if stats["users_completed"] != stats["num_users"]:
        errors.append(f"미완료 유저: {stats['num_users'] - stats['users_completed']}명")

    request_map = {req["id"]: req for req in plan["requests"]}
    for cg in plan["collision_groups"]:
        times = set()
        for req_id in cg["requests"]:
            if req_id in request_map:
                times.add(request_map[req_id]["time_ms"])
        if len(times) > 1:
            errors.append(f"충돌 그룹 {cg['id']}의 요청 시간 불일치: {sorted(times)}")

    collision_ids = {cg["id"] for cg in plan["collision_groups"]}
    for req in plan["requests"]:
        if isinstance(req["user"], dict):
            cg_id = req["user"].get("collision_loser")
            if cg_id not in collision_ids:
                errors.append(f"요청 {req['id']}의 collision_loser {cg_id}가 존재하지 않음")

    for cg in plan["collision_groups"]:
        for req_id in cg["requests"]:
            req = request_map.get(req_id)
            if req and isinstance(req["user"], dict):
                loser_cg = req["user"].get("collision_loser")
                if loser_cg == cg["id"]:
                    errors.append(f"요청 {req_id}가 자신이 속한 충돌 그룹 {cg['id']}를 참조함")

    user_normal_count = defaultdict(int)
    for req in plan["requests"]:
        if req.get("type", "book") == "book" and isinstance(req["user"], int):
            user_normal_count[req["user"]] += 1
    wrong_users = [u for u, c in user_normal_count.items() if c != stats["seats_per_user"]]
    if wrong_users:
        errors.append(f"일반 요청 수가 {stats['seats_per_user']}가 아닌 유저: {len(wrong_users)}명")

    return errors


def load_config(config_path: str) -> dict:
    with open(config_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    if isinstance(data, dict) and "sections" in data:
        return data
    if isinstance(data, list):
        return {"sections": data}
    raise ValueError(f"지원하지 않는 파일 형식: {config_path}")


def find_default_config() -> Optional[str]:
    import os
    script_dir = os.path.dirname(os.path.abspath(__file__))
    default_path = os.path.join(script_dir, "PlanConfig.json")
    if os.path.exists(default_path):
        return default_path
    return None


def _run_selftest(case_filter=None):
    import sys
    sections_small = [{"col_len": 10, "seats": [1] * 30} for _ in range(3)]
    all_cases = []

    def case(name):
        def deco(fn):
            all_cases.append((name, fn))
            return fn
        return deco

    @case("natural_termination_no_section_moves")
    def _default():
        sim = ReservationSimulator(sections=sections_small, num_users=5, seats_per_user=2,
                                   seed=42, no_collision=True)
        plan = sim.run()
        errs = []
        sim_dur = plan["stats"]["simulation_duration_ms"]
        # 자연 종료: simulation_duration_ms = max(book request time_ms). 0보다 크고 안전 상한보다 작아야.
        if sim_dur <= 0:
            errs.append(f"simulation_duration_ms={sim_dur} 가 0 이하 (자연 종료 못함)")
        if sim_dur >= MAX_SIMULATION_DURATION_MS:
            errs.append(f"simulation_duration_ms={sim_dur} 가 안전 상한 도달")
        if any(r.get("type") == "section_move" for r in plan["requests"]):
            errs.append("section_move 요청이 있으나 section_move_count=0")
        # users_completed 검증 — 모든 user 가 자연 종료까지 좌석 확보
        if plan["stats"]["users_completed"] != plan["stats"]["num_users"]:
            errs.append(f"users_completed={plan['stats']['users_completed']} != num_users={plan['stats']['num_users']}")
        errs.extend(validate_plan(plan))
        return errs

    @case("with_section_moves")
    def _with_sm():
        sim = ReservationSimulator(sections=sections_small, num_users=3, seats_per_user=2,
                                   seed=42, no_collision=True, section_move_count=2)
        plan = sim.run()
        errs = []
        sm = [r for r in plan["requests"] if r.get("type") == "section_move"]
        if not sm:
            errs.append("section_move 요청 0건 (section_move_count=2인데)")
        errs.extend(validate_plan(plan))
        return errs

    @case("no_region_fields")
    def _no_region():
        sim = ReservationSimulator(sections=sections_small, num_users=3, seats_per_user=2,
                                   seed=42, no_collision=True)
        plan = sim.run()
        errs = []
        if "regions" in plan["stats"]:
            errs.append("stats.regions 가 여전히 존재함 (제거 대상)")
        if any("region" in r for r in plan["requests"]):
            errs.append("requests[].region 필드가 여전히 존재함 (제거 대상)")
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
  python3 PlanGenerator.py                          # PlanConfig.json 자동 로드
  python3 PlanGenerator.py -c PlanConfig.json       # 설정 파일 지정
  python3 PlanGenerator.py -n 100 -m 4              # 유저 수, 좌석 수 지정
  python3 PlanGenerator.py --section-move-count 3   # 유저당 section 이동 횟수
  python3 PlanGenerator.py --no-collision           # 충돌 없는 계획 생성
  python3 PlanGenerator.py --selftest               # 전체 selftest 실행
        """
    )
    parser.add_argument("--config", "-c", type=str, help="설정 파일")
    parser.add_argument("--users", "-n", type=int, help="유저 수")
    parser.add_argument("--seats-per-user", "-m", type=int, help="유저당 좌석 수")
    parser.add_argument("--seed", type=int, help="랜덤 시드")
    parser.add_argument("--snapshot-interval", type=int, default=None)
    parser.add_argument("--network-delay", type=int, default=None)
    parser.add_argument("--request-delay-mean", type=int, default=None)
    parser.add_argument("--request-delay-min", type=int, default=None)
    parser.add_argument("--request-delay-skew", type=float, default=None)
    parser.add_argument("--no-collision", action="store_true")
    parser.add_argument("--section-move-count", type=int, default=None,
                        help="유저당 section 이동 횟수 (기본: 0)")
    parser.add_argument("--section-move-target-strategy", type=str, default=None,
                        choices=["round_robin", "random"])
    parser.add_argument("--output", "-o", type=str, help="출력 파일")
    parser.add_argument("--validate", action="store_true", help="결과 검증")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--case", type=str, default=None)

    args = parser.parse_args()

    if args.selftest:
        success = _run_selftest(case_filter=args.case)
        sys.exit(0 if success else 1)

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
        "section_move_count": 0,
        "section_move_target_strategy": "round_robin",
    }
    sections = None

    config_path = args.config or find_default_config()
    if config_path:
        print(f"설정 파일: {config_path}", file=sys.stderr)
        loaded = load_config(config_path)
        sections = loaded.get("sections")
        if "config" in loaded:
            config.update(loaded["config"])

    if args.users is not None: config["num_users"] = args.users
    if args.seats_per_user is not None: config["seats_per_user"] = args.seats_per_user
    if args.seed is not None: config["seed"] = args.seed
    if args.snapshot_interval is not None: config["snapshot_interval"] = args.snapshot_interval
    if args.network_delay is not None: config["network_delay"] = args.network_delay
    if args.request_delay_mean is not None: config["request_delay_mean"] = args.request_delay_mean
    if args.request_delay_min is not None: config["request_delay_min"] = args.request_delay_min
    if args.request_delay_skew is not None: config["request_delay_skew"] = args.request_delay_skew
    if args.no_collision: config["no_collision"] = True
    if args.section_move_count is not None: config["section_move_count"] = args.section_move_count
    if args.section_move_target_strategy is not None: config["section_move_target_strategy"] = args.section_move_target_strategy

    if sections is None:
        print("오류: 좌석 데이터가 없습니다.", file=sys.stderr)
        sys.exit(1)

    total_seats = sum(sum(s["seats"]) for s in sections)
    print(f"설정: users={config['num_users']}, seats_per_user={config['seats_per_user']}, seed={config['seed']}", file=sys.stderr)
    print(f"좌석: {len(sections)}개 섹션, 총 {total_seats}석 가용", file=sys.stderr)
    print(f"시뮬레이션: section_move_count={config['section_move_count']} (총 duration 은 자연 종료로 결정)", file=sys.stderr)

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
        section_move_count=config["section_move_count"],
        section_move_target_strategy=config["section_move_target_strategy"],
    )

    plan = simulator.run()

    if args.validate:
        errors = validate_plan(plan)
        if errors:
            print("검증 실패:", file=sys.stderr)
            for err in errors[:10]:
                print(f"  {err}", file=sys.stderr)
            sys.exit(1)
        print("검증 통과", file=sys.stderr)

    import os
    output_path = args.output or os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "Plan.json"
    )
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(plan, f, indent=2, ensure_ascii=False)

    print(f"\n=== 결과 ===", file=sys.stderr)
    print(f"출력: {output_path}", file=sys.stderr)
    for key, value in plan["stats"].items():
        print(f"  {key}: {value}", file=sys.stderr)


if __name__ == "__main__":
    main()
