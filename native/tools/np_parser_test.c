/* np_parser_test.c — harnais de test du lecteur `.np` (native/src/np_parser.c), DEUX modes :
 *   np_parser_test <file.np>            -> dump détaillé (à comparer à NpFormatOracle sur le même fichier)
 *   np_parser_test verify <dossier...>  -> parse RÉCURSIVEMENT tous les .np sous chaque dossier donné,
 *                                          vérifie qu'aucun octet ne reste (EOF-exact, indépendamment de
 *                                          l'oracle Java) -> critère 535/535 revérifié côté C.
 * Build : gcc -O2 -o np_parser_test native/tools/np_parser_test.c native/src/np_parser.c -Inative/src
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../src/np_parser.h"

static unsigned char* readAll(const char* path, long* outLen) {
    FILE* f = fopen(path, "rb");
    if (!f) return 0;
    fseek(f, 0, SEEK_END); long len = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char* buf = (unsigned char*)malloc(len);
    if (fread(buf, 1, len, f) != (size_t)len) { fclose(f); free(buf); return 0; }
    fclose(f); *outLen = len; return buf;
}

static void dumpOne(const char* path) {
    long len; unsigned char* data = readAll(path, &len);
    if (!data) { printf("introuvable: %s\n", path); return; }
    printf("np file = %s (%ld bytes)\n", path, len);
    NpEffect* eff = np_parse(data, (int)len);
    if (!eff) { printf("PARSE ECHEC\n"); free(data); return; }
    printf("emitterCount=%d\n", eff->emitterCount);
    for (int i = 0; i < eff->emitterCount; i++) {
        NpEmitter* em = &eff->emitters[i];
        printf("--- emitter %d ---\n", i);
        printf("minParticleCount=%d maxParticleCount=%d\n", em->minParticleCount, em->maxParticleCount);
        printf("delay: active=%d min=%g max=%g linked=%d\n", em->delay.active, em->delay.lowMin, em->delay.lowMax, em->delay.lowUsesLinkedRange);
        printf("duration: active=%d min=%g max=%g linked=%d\n", em->duration.active, em->duration.lowMin, em->duration.lowMax, em->duration.lowUsesLinkedRange);
        printf("spawnShape: active=%d code=%d edges=%d side=%d\n", em->spawnShape.active, em->spawnShape.code, em->spawnShape.edges, em->spawnShape.side);
        printf("tint: active=%d N=%d offColors=%d offTimeline=%d\n", em->tint.active, em->tint.timelineN, em->tint.timelineOffColors, em->tint.timelineOffTimeline);
        printf("frameDuration=%g attached=%d continuous=%d aligned=%d additive=%d premultiplied=%d multiply=%d behind=%d\n",
            em->frameDuration, em->attached, em->continuous, em->aligned, em->additive, em->premultipliedAlpha, em->multiply, em->behind);
        printf("timelinePoolSize=%d atlasTag=\"%s\"\n", em->timelinePoolSize, em->atlasTag);
    }
    np_free(eff); free(data);
}

#include <sys/stat.h>
#if defined(_WIN32)
#include <io.h>
#define stat _stat
#endif

static int endsWith(const char* s, const char* suf) {
    size_t ls = strlen(s), lf = strlen(suf); return ls >= lf && !strcmp(s + ls - lf, suf);
}

/* Parcours récursif minimal (Windows/POSIX) via dirent (MinGW le fournit). */
#include <dirent.h>
static int g_ok = 0, g_total = 0;
static void walk(const char* dir) {
    DIR* d = opendir(dir);
    if (!d) return;
    struct dirent* e;
    char path[4096];
    while ((e = readdir(d))) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        snprintf(path, sizeof(path), "%s/%s", dir, e->d_name);
        struct stat st;
        if (stat(path, &st) != 0) continue;
        if (st.st_mode & S_IFDIR) { walk(path); continue; }
        if (!endsWith(path, ".np")) continue;
        long len; unsigned char* data = readAll(path, &len);
        g_total++;
        if (!data) { printf("FAIL (lecture) %s\n", path); continue; }
        NpEffect* eff = np_parse(data, (int)len);
        if (eff) { g_ok++; np_free(eff); }
        else printf("FAIL %s (%ld o)\n", path, len);
        free(data);
    }
    closedir(d);
}

int main(int argc, char** argv) {
    if (argc >= 2 && !strcmp(argv[1], "verify")) {
        for (int i = 2; i < argc; i++) walk(argv[i]);
        printf("=== RESULTAT C : %d/%d parses OK (EOF-exact garanti par construction du parseur) ===\n", g_ok, g_total);
        return g_ok == g_total ? 0 : 1;
    }
    if (argc >= 2) { dumpOne(argv[1]); return 0; }
    printf("usage: %s <file.np> | verify <dir...>\n", argv[0]);
    return 1;
}
