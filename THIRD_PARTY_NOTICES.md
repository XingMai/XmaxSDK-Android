# Third-Party Notices

## LLVM libc++ for Android

`xmax-sdk/src/main/jniLibs` contains `libc++_shared.so` binaries for
`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. They were obtained from Android
NDK `27.2.12479018` and are included because the VolcEngine RTC native libraries
link against the shared C++ runtime without packaging it in their Maven AAR.
The distributed copies were modified only by removing unneeded debugging and
symbol-table data with that NDK's `llvm-strip --strip-unneeded` command.

LLVM libc++ is licensed under the Apache License 2.0 with LLVM Exceptions. The
complete notice distributed with the LLVM toolchain is packaged in the SDK at
`META-INF/NOTICE-LLVM-libcxx.txt`.

Upstream source and license information:

- https://github.com/llvm/llvm-project
- https://llvm.org/LICENSE.txt
