# Copyright 2019-2020, Collabora, Ltd.
# Copyright 2026, NVIDIA CORPORATION.
# Copyright 2026, Beyley Cardellio
# SPDX-License-Identifier: BSL-1.0

######
# This is where we collect all of the pieces from the different parts of
# the source tree and build a complete driver or integration part.

if(ANDROID AND CMAKE_SIZEOF_VOID_P EQUAL 8)
	# NDK r26 defaults to 4 KB ELF segments. Android also runs with 16 KB pages.
	add_link_options("LINKER:-z,max-page-size=16384" "LINKER:-z,common-page-size=16384")
endif()

add_subdirectory(helpers)
add_subdirectory(common)

# Monado management library
if(XRT_FEATURE_SERVICE AND XRT_HAVE_LINUX)
	add_subdirectory(libmonado)
endif()

if(XRT_FEATURE_OPENXR)
	add_subdirectory(openxr)
	add_subdirectory(binding_inspector)
endif()

if(ANDROID AND XRT_FEATURE_OPENXR AND XRT_BUILD_CV10_SPATIAL_LAUNCHER)
	add_subdirectory(openxr_android/src/main/cpp)
endif()

if(XRT_FEATURE_OPENVR)
	add_subdirectory(openvr)
endif()

if(XRT_MODULE_MONADO_CLI)
	add_subdirectory(cli)
endif()

if(XRT_MODULE_MONADO_GUI)
	add_subdirectory(gui)
endif()

if(XRT_FEATURE_SERVICE AND NOT WIN32)
	add_subdirectory(ctl)
endif()

if(XRT_FEATURE_SERVICE AND XRT_FEATURE_OPENXR)
	if(ANDROID)
		add_subdirectory(service-lib)
	else()
		add_subdirectory(service)
	endif()
endif()

if(XRT_FEATURE_STEAMVR_PLUGIN)
	add_subdirectory(steamvr_drv)
endif()

if(XRT_FEATURE_SERVICE
   AND XRT_HAVE_SDL2
   AND XRT_HAVE_VULKAN
	)
	add_subdirectory(sdl_test)
endif()
