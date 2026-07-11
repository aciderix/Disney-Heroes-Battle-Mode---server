/* Symboles spécifiques bionic (Android) absents de la glibc, requis pour charger la lib d'origine
   sous qemu. Shims minimaux (extraction : le reste des symboles vient de la glibc via qemu -L). */
#include <errno.h>
#include <stdlib.h>
void* __stack_chk_guard = (void*)0x00000aff;
void __stack_chk_fail(void){ abort(); }
int __android_log_print(int p, const char* t, const char* f, ...){ (void)p;(void)t;(void)f; return 0; }
int* __errno(void){ return &errno; }
void __assert2(const char* fi,int li,const char* fn,const char* m){ (void)fi;(void)li;(void)fn;(void)m; abort(); }
void __cxa_finalize(void* d){ (void)d; }
