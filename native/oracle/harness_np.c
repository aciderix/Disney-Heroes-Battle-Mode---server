/* Oracle .np : exécute le VRAI lecteur natif ParticleEmitter::load (ARM, sous qemu) sur un emitter
 * d'un vrai .np, imprime le nombre d'octets consommés + un dump de la struct parsée (2308 o). Vérité
 * bit-à-bit de l'ordre/tailles des champs v3 (PRINCIPLES §4 : extraction, jamais devinette). */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef void  (*ctor_t)(void* self);
typedef void* (*load_t)(void* self, unsigned char** cursor, unsigned int* remaining);

static unsigned rd_be32(const unsigned char* p){ return (p[0]<<24)|(p[1]<<16)|(p[2]<<8)|p[3]; }

int main(int argc, char** argv){
    if(argc<3){ fprintf(stderr,"usage: %s <lib.so> <file.np> [emitterIndex]\n",argv[0]); return 2; }
    void* h = dlopen(argv[1], RTLD_NOW|RTLD_GLOBAL);
    if(!h){ printf("DLOPEN FAIL: %s\n", dlerror()); return 1; }
    ctor_t ctor = (ctor_t)dlsym(h, "_ZN15ParticleEmitterC2Ev");
    load_t load = (load_t)dlsym(h, "_ZN15ParticleEmitter4loadERPhRj");
    printf("ctor=%p load=%p\n", (void*)ctor, (void*)load);
    if(!load){ printf("no load symbol\n"); return 1; }

    FILE* f=fopen(argv[2],"rb"); if(!f){ perror("open"); return 1; }
    fseek(f,0,SEEK_END); long len=ftell(f); fseek(f,0,SEEK_SET);
    unsigned char* data=malloc(len); if(fread(data,1,len,f)!=(size_t)len){return 1;} fclose(f);
    if(len<6 || data[0]!=0 || data[1]!=3){ printf("bad magic\n"); return 1; }
    unsigned count = rd_be32(data+2);
    printf("file=%s len=%ld emitters=%u\n", argv[2], len, count);

    int want = argc>3 ? atoi(argv[3]) : 0;
    unsigned char* cursor = data+6;
    unsigned int remaining = (unsigned)(len-6);

    /* struct emitter = 0x904 = 2308 octets ; on aligne large */
    for(unsigned e=0;e<count;e++){
        unsigned char* start = cursor;
        void* em = calloc(1, 4096);
        if(ctor) ctor(em);
        /* remise à zéro APRÈS ctor pour voir seulement ce que load écrit ? non : ctor pose les
           défauts, load écrase. On garde ctor (comportement réel). */
        load(em, &cursor, &remaining);
        long consumed = cursor - start;
        if((int)e==want){
            printf("=== emitter %u consumed=%ld bytes, remaining=%u ===\n", e, consumed, remaining);
            /* dump struct: 2308 octets en lignes de 16 */
            unsigned char* s=(unsigned char*)em;
            for(int i=0;i<0x904;i+=16){
                printf("%04x: ",i);
                for(int j=0;j<16;j++) printf("%02x ", s[i+j]);
                printf("\n");
            }
            /* aussi : dump des octets .np bruts de cet emitter */
            printf("=== raw .np emitter %u bytes (%ld) ===\n", e, consumed);
            for(long i=0;i<consumed;i+=16){
                printf("%04lx: ", i);
                for(long j=0;j<16 && i+j<consumed;j++) printf("%02x ", start[i+j]);
                printf("\n");
            }
            free(em); break;
        }
        free(em);
    }
    return 0;
}
