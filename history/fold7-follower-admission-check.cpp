// SPDX-License-Identifier: Apache-2.0

#include <scheduler/FollowerFenceAdmission.h>

using android::scheduler::followerFenceAdmission;
using android::scheduler::kFold7CombinedRetireGuardNs;
using Result = android::scheduler::FollowerFenceAdmission;

constexpr int64_t kRetire = 100'000'000;

static_assert(followerFenceAdmission(kRetire + 499'999, false, kRetire) ==
              Result::kRetireSettling);
static_assert(followerFenceAdmission(kRetire + 500'000, false, kRetire) == Result::kReady);
static_assert(followerFenceAdmission(kRetire + 749'999, false, kRetire, kFold7CombinedRetireGuardNs) ==
              Result::kRetireSettling);
static_assert(followerFenceAdmission(kRetire + 750'000, false, kRetire, kFold7CombinedRetireGuardNs) ==
              Result::kReady);
static_assert(followerFenceAdmission(kRetire - 1, false, kRetire, kFold7CombinedRetireGuardNs) ==
              Result::kRetireSettling);
static_assert(followerFenceAdmission(kRetire + 750'000, true, kRetire, kFold7CombinedRetireGuardNs) ==
              Result::kWaitingForFence);
static_assert(followerFenceAdmission(kRetire, true, std::nullopt, kFold7CombinedRetireGuardNs) ==
              Result::kWaitingForFence);
static_assert(followerFenceAdmission(kRetire, false, std::nullopt, kFold7CombinedRetireGuardNs) ==
              Result::kReady);

int main() {}
