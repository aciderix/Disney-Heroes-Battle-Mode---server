#!/usr/bin/env bash
# Construit l'oracle : la lib native ARM D'ORIGINE (native/reference/libspine-native.so) exécutable
# sous qemu-arm via un shim bionic généré. Sert à capturer les sorties EXACTES (formats .np, sommets,
# drawCalls) pour reconstruire fidèlement le rebuild x86_64 et le valider bit-à-bit. Voir NATIVE_PLAN.md.
set -e; cd "$(dirname "$0")"
CC=arm-linux-gnueabi-gcc
mkdir -p sysroot
: > empty.c
for l in m log android dl; do $CC -shared -nostdlib -Wl,-soname,lib$l.so -o sysroot/lib$l.so empty.c; done
python3 gen_shim.py
$CC -shared -fPIC -fno-builtin -fno-stack-protector -nostdlib -Wl,-soname,libc.so \
    -Wl,--version-script=libc.ver -o sysroot/libc.so libc_shim.c
$CC -o harness harness.c -ldl
echo "[oracle] prêt. Test :"
qemu-arm-static -L /usr/arm-linux-gnueabi -E LD_LIBRARY_PATH=sysroot ./harness ../reference/libspine-native.so
