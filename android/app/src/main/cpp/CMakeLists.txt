# 「应用侧绕过」原生层：libusb + JNI
#
# 为什么不用 libusb 自带的 CMake/Android.mk：
#   它需要 configure 生成的 config.h；而 libusb 仓库里的 android/config.h
#   就是为 Android 准备好的现成配置，直接把源文件编进来最简单、最少坑。
#
# libusb 源码位置：默认本机 E:/libusb；换机器/进仓库时用
#   ./gradlew assembleRelease -PLIBUSB_DIR=/path/to/libusb
# 覆盖（后续若要长期维护，应把 libusb 源码 vendor 进仓库）。

cmake_minimum_required(VERSION 3.18)
project(vrcforwarder_rawusb C)

if(NOT DEFINED LIBUSB_DIR)
  set(LIBUSB_DIR "E:/libusb")
endif()

set(LIBUSB_SRC
  ${LIBUSB_DIR}/libusb/core.c
  ${LIBUSB_DIR}/libusb/descriptor.c
  ${LIBUSB_DIR}/libusb/io.c
  ${LIBUSB_DIR}/libusb/sync.c
  ${LIBUSB_DIR}/libusb/strerror.c
  ${LIBUSB_DIR}/libusb/hotplug.c
  ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
  ${LIBUSB_DIR}/libusb/os/linux_netlink.c
  ${LIBUSB_DIR}/libusb/os/events_posix.c
  ${LIBUSB_DIR}/libusb/os/threads_posix.c
)

add_library(rawusb SHARED rawusb_jni.c ${LIBUSB_SRC})

target_include_directories(rawusb PRIVATE
  ${LIBUSB_DIR}              # <libusb.h> 的根
  ${LIBUSB_DIR}/libusb       # 内部头文件
  ${LIBUSB_DIR}/android      # ★ 现成的 android config.h
  ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(rawusb PRIVATE HAVE_CONFIG_H=1)
target_link_libraries(rawusb log)
