# SPDX-License-Identifier: BSL-1.0

if(XRT_BUILD_CV10_SPATIAL_LAUNCHER)
	find_package(OpenXR CONFIG REQUIRED)
	add_library(cv10-spatial-launcher SHARED cv10_spatial_launcher.cpp)
	target_compile_features(cv10-spatial-launcher PRIVATE cxx_std_17)
	target_link_libraries(cv10-spatial-launcher PRIVATE xrt-interfaces OpenXR::openxr_loader EGL GLESv3 android dl jnigraphics log mediandk)
endif()

add_library(cv10-kit-slam SHARED cv10_kit_slam.cpp)
target_compile_features(cv10-kit-slam PRIVATE cxx_std_17)
target_link_libraries(cv10-kit-slam PRIVATE dl)
target_link_options(cv10-kit-slam PRIVATE -Wl,-z,max-page-size=16384)
