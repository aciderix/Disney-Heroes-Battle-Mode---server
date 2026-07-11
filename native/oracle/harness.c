#include <dlfcn.h>
#include <stdio.h>
int main(int argc,char**argv){
  void* h = dlopen(argv[1], RTLD_NOW|RTLD_GLOBAL);
  if(!h){ printf("DLOPEN FAIL: %s\n", dlerror()); return 1; }
  printf("DLOPEN OK\n");
  void* s = dlsym(h, "spSkeletonBinary_create");
  printf("spSkeletonBinary_create = %p\n", s);
  return 0;
}
