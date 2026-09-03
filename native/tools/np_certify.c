/*
 * np_certify.c — HARNAIS DE CERTIFICATION DIFFÉRENTIELLE du moteur de particules C (g263).
 *
 * Principe (identique à CompareBackend pour spine, méthode validée §8) : comparer la sortie RENDUE de
 * notre simulation C à la RÉFÉRENCE ABSOLUE = l'oracle unidbg (le VRAI binaire ARM PerBlue), capturée
 * dans un fichier "golden" (NpFormatOracle mode `golden`). Un mapping de champ faux ou une physique
 * approximative produit forcément un DIFF LOCALISÉ (frame/sommet/composante) -> l'outil dit exactement
 * quoi corriger, on itère jusqu'à 0 diff. La sortie ne peut PAS être trompée : elle est comparée au réel.
 *
 * MODE : np_certify <fichier.golden> <fichier.np>
 *   -> parse le .np, lance la sim C sur N frames (N/dt lus du golden), compare aux sommets du golden,
 *      imprime un rapport structuré (par frame : nb sommets attendu/obtenu, 1ère composante divergente,
 *      écart max) + un verdict PASS/FAIL global.
 *
 * ÉTAT (g263) : la sim C est encore un STUB (np_sim_run renvoie 0 sommet) -> la boucle de vérification
 * est posée et fonctionnelle AVANT d'écrire la simulation ; le rapport montrera "oracle N vs C 0" par
 * frame, ce qui est le comportement attendu du squelette. La vraie sim se branchera dans np_sim.c.
 *
 * BUILD : gcc -O2 -o np_certify native/tools/np_certify.c native/src/np_parser.c -Inative/src
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "../src/np_parser.h"
#include "../src/np_sim.h"

/* ------------------------------------------------------------------ lecture golden (LE, cf. writeGolden) */
typedef struct { int vertCount; float* verts; } GFrame;   /* verts = vertCount*6 (x,y,light,dark,u,v) */
typedef struct { int nframes; float dt; GFrame* frames; } Golden;

static unsigned char* readAll(const char* path, long* len) {
    FILE* f = fopen(path, "rb"); if (!f) return 0;
    fseek(f, 0, SEEK_END); *len = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char* b = malloc(*len);
    if (fread(b, 1, *len, f) != (size_t)*len) { fclose(f); free(b); return 0; }
    fclose(f); return b;
}
static int rd_i32(const unsigned char* p) { return p[0] | (p[1]<<8) | (p[2]<<16) | (p[3]<<24); } /* little-endian */
static float rd_f32(const unsigned char* p) { int i = rd_i32(p); float f; memcpy(&f,&i,4); return f; }

static int loadGolden(const char* path, Golden* g) {
    long len; unsigned char* b = readAll(path, &len);
    if (!b || len < 16) { free(b); return 0; }
    if (memcmp(b, "NPGL", 4) != 0) { fprintf(stderr, "golden: magic invalide\n"); free(b); return 0; }
    int ver = rd_i32(b + 4); (void)ver;
    g->nframes = rd_i32(b + 8);
    g->dt = rd_f32(b + 12);
    g->frames = calloc(g->nframes, sizeof(GFrame));
    const unsigned char* p = b + 16;
    for (int f = 0; f < g->nframes; f++) {
        int vc = rd_i32(p); p += 4;
        g->frames[f].vertCount = vc;
        g->frames[f].verts = malloc(sizeof(float) * vc * 6);
        for (int i = 0; i < vc * 6; i++) { g->frames[f].verts[i] = rd_f32(p); p += 4; }
    }
    free(b);
    return 1;
}

/* ---------------------------------------------------------------------- interface simulation (à brancher)
 * Contrat : remplit frames[0..nframes-1] avec les sommets produits par la sim C pour ce .np.
 * frames[f].verts doit être malloc'd par l'implémentation (vertCount*6 floats), ou vertCount=0.
 * STUB actuel (g263) : parse le .np (prouve que le parseur tourne dans ce contexte) puis 0 sommet.
 */
int np_sim_run(const unsigned char* npData, int npLen, int nframes, float dt, GFrame* frames) {
    int* counts = malloc(sizeof(int) * nframes);
    float** verts = malloc(sizeof(float*) * nframes);
    np_sim_run_frames(npData, npLen, nframes, dt, counts, verts);
    for (int f = 0; f < nframes; f++) { frames[f].vertCount = counts[f]; frames[f].verts = verts[f]; }
    free(counts); free(verts);
    return 0;
}

/* --------------------------------------------------------------------------------------- comparaison */
int main(int argc, char** argv) {
    if (argc < 3) { printf("usage: %s <fichier.golden> <fichier.np>\n", argv[0]); return 2; }
    Golden g;
    if (!loadGolden(argv[1], &g)) { fprintf(stderr, "golden illisible: %s\n", argv[1]); return 2; }
    long npLen; unsigned char* np = readAll(argv[2], &npLen);
    if (!np) { fprintf(stderr, ".np illisible: %s\n", argv[2]); return 2; }

    GFrame* sim = calloc(g.nframes, sizeof(GFrame));
    if (np_sim_run(np, (int)npLen, g.nframes, g.dt, sim) != 0) { fprintf(stderr, "sim échec\n"); return 3; }

    if (getenv("NP_DBG_CENTERS")) {
        int f = 0, oc = g.frames[f].vertCount / 4, sc = sim[f].vertCount / 4;
        printf("--- frame %d centres (oracle %d part, sim %d part) ---\n", f, oc, sc);
        for (int i = 0; i < oc; i++) {
            float ox=0,oy=0; for (int v=0;v<4;v++){ ox+=g.frames[f].verts[(i*4+v)*6]; oy+=g.frames[f].verts[(i*4+v)*6+1]; }
            printf("  O[%d] (%.2f, %.2f)\n", i, ox/4, oy/4);
        }
        for (int i = 0; i < sc; i++) {
            float sx=0,sy=0; for (int v=0;v<4;v++){ sx+=sim[f].verts[(i*4+v)*6]; sy+=sim[f].verts[(i*4+v)*6+1]; }
            printf("  S[%d] (%.2f, %.2f)\n", i, sx/4, sy/4);
        }
    }
    printf("=== np_certify : %s vs %s (%d frames, dt=%.4f) ===\n", argv[2], argv[1], g.nframes, g.dt);
    int fails = 0;
    double worst = 0;
    for (int f = 0; f < g.nframes; f++) {
        int oc = g.frames[f].vertCount, sc = sim[f].vertCount;
        if (oc != sc) {
            printf("frame %2d: COUNT oracle=%d sim=%d  <<< divergence de nombre de sommets\n", f, oc, sc);
            fails++;
            continue;
        }
        /* diff séparé : POSITION (x,y = comp 0,1) vs COULEUR/UV (comp 2..5) pour itérer proprement */
        double maxPos = 0, maxCol = 0; int worstPI = -1, worstPC = -1;
        for (int i = 0; i < oc * 6; i++) {
            double d = fabs((double)g.frames[f].verts[i] - (double)sim[f].verts[i]);
            int c = i % 6;
            if (c < 2) { if (d > maxPos) { maxPos = d; worstPI = i/6; worstPC = c; } }
            else       { if (d > maxCol) maxCol = d; }
        }
        const char* comp[] = {"x","y"};
        int posOk = maxPos <= 0.05;
        if (!posOk || maxCol > 0.01) {
            printf("frame %2d: %d som, POS %s (max %.3f%s) | COL/UV max %.3g\n",
                f, oc, posOk ? "OK" : "DIFF", maxPos,
                posOk ? "" : (worstPC>=0 ? (worstPC==0?" x@som":" y@som") : ""), maxCol);
            if (!posOk) fails++;
            if (maxPos > worst) worst = maxPos;
        } else {
            printf("frame %2d: %d som, POS OK COL OK\n", f, oc);
        }
    }
    printf("=== VERDICT : %s (%d/%d frames divergentes, écart max %.4f) ===\n",
        fails == 0 ? "PASS" : "FAIL", fails, g.nframes, worst);
    return fails == 0 ? 0 : 1;
}
