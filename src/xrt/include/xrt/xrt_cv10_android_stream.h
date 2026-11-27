// SPDX-License-Identifier: BSL-1.0
#pragma once

#include <openxr/openxr.h>

struct AHardwareBuffer;

// Private contract between the bundled Android launcher and Monado CV10.
// The caller must hold an acquired producer image, wait for its acquire fence before submitting,
// and keep the image until xrCv10PollAndroidStreamImageMONADO succeeds after its last submission.
typedef XrResult(XRAPI_PTR *PFN_xrCv10ImportAndroidStreamImageMONADO)(XrSession session,
                                                                  struct AHardwareBuffer *buffer,
                                                                  XrSwapchain *out_swapchain);

typedef XrResult(XRAPI_PTR *PFN_xrCv10PollAndroidStreamImageMONADO)(XrSwapchain swapchain);
