#!/usr/bin/env python3
"""Génère le shim bionic (libc.so) pour exécuter libspine-native.so (Android) sous qemu-arm.
Extrait les symboles UND de la lib d'origine et produit des trampolines (forward glibc via dlsym)
+ stubs pour les symboles spécifiques bionic + tampons pour les objets data. AUCUNE écriture
manuelle de liste : tout est dérivé du binaire (PRINCIPLES §4)."""
import subprocess, sys
so = sys.argv[1] if len(sys.argv) > 1 else '../reference/libspine-native.so'
out = subprocess.check_output(['readelf', '-sW', so]).decode()
funcs, objs = set(), set()
for line in out.splitlines():
    p = line.split()
    if len(p) < 8 or p[6] != 'UND':
        continue
    nm = p[7].split('@')[0]
    if not nm or nm.startswith('_ITM') or nm.startswith('__gnu'):
        continue
    (funcs if p[3] == 'FUNC' else objs if p[3] == 'OBJECT' else set()).add(nm)
bionic = {'__android_log_print', '__errno', '__assert2', '__stack_chk_fail', '__cxa_finalize'}
c = ['#include <errno.h>', 'extern void* dlsym(void*,const char*); typedef void* Ptr;',
     '#define RTLD_NEXT ((void*)-1L)', '',
     'void* __stack_chk_guard=(void*)0xaff;', 'void __stack_chk_fail(void){for(;;);}',
     'int __android_log_print(int p,const char*t,const char*f,...){return 0;}',
     'int* __errno(void){return &errno;}', 'void __assert2(const char*a,int b,const char*cc,const char*d){for(;;);}',
     'void __cxa_finalize(void*d){}', '']
for o in sorted(objs - {'__stack_chk_guard'}):
    c.append(f'char {o}[4096];')
fwd = sorted(funcs - bionic)
for s in fwd:
    c.append(f'Ptr p_{s};')
    c.append(f'__attribute__((naked)) void {s}(void){{__asm__("ldr r12,=p_{s}\\n\\tldr r12,[r12]\\n\\tbx r12");}}')
c.append('__attribute__((constructor)) static void _init_shim(void){')
c += [f'  p_{s}=dlsym(RTLD_NEXT,"{s}");' for s in fwd]
c.append('}')
open('libc_shim.c', 'w').write('\n'.join(c))
open('libc.ver', 'w').write('LIBC {\n global:\n' + ''.join(f'  {s};\n' for s in sorted(funcs | objs)) + ' local: *;\n};\n')
print(f'shim: {len(fwd)} trampolines, {len(objs)} objets, {len(bionic)} stubs bionic')
