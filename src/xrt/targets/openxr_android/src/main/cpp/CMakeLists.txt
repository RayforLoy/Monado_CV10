# SPDX-License-Identifier: BSL-1.0

find_package(OpenXR CONFIG REQUIRED)

add_library(cv10-spatial-launcher SHARED cv10_spatial_launcher.cpp)
target_compile_features(cv10-spatial-launcher PRIVATE cxx_std_17)
target_link_libraries(cv10-spatial-launcher PRIVATE OpenXR::openxr_loader EGL GLESv3 jnigraphics log)
