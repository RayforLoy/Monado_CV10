// SPDX-License-Identifier: Apache-2.0

#include <scheduler/FollowerSlot.h>

#include <iostream>

using namespace android::scheduler;

constexpr auto kTarget = 36'000'000;
constexpr auto kPeriod = 16'000'000;
constexpr auto kWake = 26'000'000;
constexpr auto kSlot = planFollowerSlot(20'000'000, kTarget, kPeriod, kWake);
static_assert(!planFollowerSlot(20'000'000, kTarget, kPeriod, std::nullopt));
static_assert(!planFollowerSlot(20'000'000, kTarget, 0, kWake));
static_assert(!planFollowerSlot(23'000'000, kTarget, kPeriod, kWake));
static_assert(kSlot && kSlot->wakeNs == 21'000'000 && kSlot->latestStartNs == 22'750'000);
static_assert(planFollowerSlot(kSlot->latestStartNs, kTarget, kPeriod, kWake));

using Result = FollowerSlotResult;
constexpr auto kStart = kSlot->wakeNs;
constexpr auto kRetire = 20'500'000;
constexpr Result checkSlot(int64_t now, bool pending, std::optional<int64_t> retire,
                           std::optional<int64_t> wake, int64_t updatedTarget = kTarget) {
    return recheckFollowerSlot(*kSlot, now, pending, retire, wake, updatedTarget);
}
static_assert(checkSlot(kStart, false, kRetire, kWake) == Result::kReady);
static_assert(checkSlot(kStart - 1, false, kRetire, kWake) == Result::kOutsideReservation);
static_assert(checkSlot(kSlot->latestStartNs + 1, false, kRetire, kWake) ==
              Result::kOutsideReservation);
static_assert(checkSlot(kStart, true, kRetire, kWake) == Result::kFenceUnavailable);
static_assert(checkSlot(kStart, false, std::nullopt, kWake) == Result::kFenceUnavailable);
static_assert(checkSlot(kStart, false, kRetire + 1, kWake) == Result::kRetireSettling);
static_assert(checkSlot(kStart, false, kStart + 1, kWake) == Result::kRetireSettling);
static_assert(checkSlot(kStart, false, kRetire, std::nullopt) == Result::kPacesetterWakeUnavailable);
static_assert(checkSlot(kStart, false, kRetire, 24'249'999) == Result::kPacesetterWakeTooClose);
static_assert(checkSlot(kStart, false, kRetire, kWake, kTarget + kPeriod) == Result::kFollowerVsyncMoved);
static_assert(checkSlot(kSlot->latestStartNs, false, kRetire, kWake) == Result::kReady);

int main() {
    char command;
    while (std::cin >> command) {
        if (command == 'C') {
            std::cout << kFollowerStartLeadNs << ' ' << kFollowerPlanningLeadNs << '\n';
        } else if (command == 'P') {
            int64_t now, target, period, nextWake;
            if (!(std::cin >> now >> target >> period >> nextWake)) return 2;
            const auto slot = planFollowerSlot(now, target, period, nextWake);
            if (slot) {
                std::cout << slot->wakeNs << ' ' << slot->latestStartNs << '\n';
            } else {
                std::cout << "-\n";
            }
        } else if (command == 'R') {
            FollowerSlot slot{};
            int64_t now, retire, nextWake, updatedTarget;
            bool pending;
            if (!(std::cin >> slot.targetPresentNs >> slot.wakeNs >> slot.latestStartNs >> now >>
                  pending >> retire >> nextWake >> updatedTarget)) {
                return 2;
            }
            const auto result = recheckFollowerSlot(slot, now, pending, retire, nextWake, updatedTarget);
            std::cout << static_cast<int>(result) << '\n';
        } else {
            return 2;
        }
    }
    return std::cin.eof() ? 0 : 2;
}
