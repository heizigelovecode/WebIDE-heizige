@echo off
"C:\\Users\\xxxxf\\AppData\\Local\\Android\\Sdk\\ndk\\28.0.13004108\\ndk-build.cmd" ^
  "NDK_PROJECT_PATH=null" ^
  "APP_BUILD_SCRIPT=D:\\Android\\AndroidStudioProjects\\SoraRunRun\\core\\terminal-emulator\\src\\main\\jni\\Android.mk" ^
  "APP_ABI=arm64-v8a" ^
  "NDK_ALL_ABIS=arm64-v8a" ^
  "NDK_DEBUG=1" ^
  "APP_PLATFORM=android-26" ^
  "NDK_OUT=D:\\Android\\AndroidStudioProjects\\SoraRunRun\\core\\terminal-emulator\\build\\intermediates\\cxx\\Debug\\1x6z2g6r/obj" ^
  "NDK_LIBS_OUT=D:\\Android\\AndroidStudioProjects\\SoraRunRun\\core\\terminal-emulator\\build\\intermediates\\cxx\\Debug\\1x6z2g6r/lib" ^
  "APP_CFLAGS+=-std=c11" ^
  "APP_CFLAGS+=-Wall" ^
  "APP_CFLAGS+=-Wextra" ^
  "APP_CFLAGS+=-Werror" ^
  "APP_CFLAGS+=-Os" ^
  "APP_CFLAGS+=-fno-stack-protector" ^
  "APP_CFLAGS+=-Wl,--gc-sections" ^
  termux
