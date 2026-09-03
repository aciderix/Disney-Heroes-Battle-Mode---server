/*
 * np_parser.c — lecteur FIDÈLE du format binaire `.np` v3 (particules natives PerBlue).
 *
 * Format CERTIFIÉ 535/535 EOF-exact (2026-09-03, g261quater) par oracle d'exécution unidbg
 * (breakpoints sur le VRAI parseur ARM, native/unidbg/NpFormatOracle.java) — voir
 * desktop-port/NP_FORMAT.md pour la méthode et la preuve. Ce fichier traduit MÉCANIQUEMENT la
 * séquence de lecture certifiée en C : PAS de champ deviné, seulement des noms encore approximatifs
 * pour le bloc "valeurs milieu" (cf. TODO ci-dessous, non bloquant pour la fidélité du parsing —
 * seul l'ORDRE/la TAILLE des octets compte pour lire correctement, pas le nom qu'on leur donne).
 *
 * Vocabulaire confirmé par lecture directe de com.badlogic.gdx.graphics.g2d.ParticleEmitter
 * (game.jar, EN CLAIR, PRINCIPLES §4) — javap -c sur saveBinary(ParticleEffectPacker) :
 *   RangedNumericValue.saveBinary  = active(bool) + lowMin(f) + lowMax(f) + lowUsesLinkedRange(bool)      = 10 o
 *   ScaledNumericValue.saveBinary  = Ranged(10) + highMin(f) + highMax(f) + highLinked(bool) + relative(bool)
 *                                    + timelineN(i) + timelineOffA(i) + timelineOffB(i)                    = 32 o
 *   NumericValue.saveBinary        = active(bool) + value(f)                                                = 5 o
 *   GradientColorValue.saveBinary  = active(bool) + timelineN(i) + timelineOffColors(i) + timelineOffTimeline(i) = 13 o
 *   SpawnShapeValue.saveBinary     = active(bool) + code(byte) [+ si code==ELLIPSE(3): edges(bool)+side(byte)]
 * ⚠️ L'ORDRE de saveBinary (writer COURANT) ≠ l'ordre v3 certifié (le format a évolué, confirmé en
 * comparant les 2 — cf. NP_FORMAT.md) : on suit ICI l'ordre CERTIFIÉ par l'oracle, pas saveBinary.
 * La fin de séquence (frameDuration, attached/continuous/aligned/flags/behind) EST dans le même ordre
 * dans les deux (vérifié par recoupement des 5 derniers booléens + le float qui précède).
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "np_parser.h"

/* ------------------------------------------------------------ curseur de lecture (BE, comme le natif) */
typedef struct { const unsigned char* p; const unsigned char* end; int ok; } NpCursor;

static int32_t rd_i32(NpCursor* c) {
    if (c->p + 4 > c->end) { c->ok = 0; return 0; }
    int32_t v = ((int32_t)c->p[0] << 24) | ((int32_t)c->p[1] << 16) | ((int32_t)c->p[2] << 8) | (int32_t)c->p[3];
    c->p += 4; return v;
}
static float rd_f32(NpCursor* c) { int32_t i = rd_i32(c); float f; memcpy(&f, &i, 4); return f; }
static unsigned char rd_bool(NpCursor* c) {
    if (c->p + 1 > c->end) { c->ok = 0; return 0; }
    unsigned char v = *c->p; c->p += 1; return v;
}

static void rd_ranged(NpCursor* c, NpRanged* r) {
    r->active = rd_bool(c); r->lowMin = rd_f32(c); r->lowMax = rd_f32(c); r->lowUsesLinkedRange = rd_bool(c);
}
static void rd_scaled(NpCursor* c, NpScaled* s) {
    rd_ranged(c, &s->low);
    s->highMin = rd_f32(c); s->highMax = rd_f32(c); s->highUsesLinkedRange = rd_bool(c); s->relative = rd_bool(c);
    s->timelineN = rd_i32(c); s->timelineOffA = rd_i32(c); s->timelineOffB = rd_i32(c);
}
static void rd_numeric(NpCursor* c, NpNumeric* n) { n->active = rd_bool(c); n->value = rd_f32(c); }
static void rd_gradient(NpCursor* c, NpGradient* g) {
    g->active = rd_bool(c); g->timelineN = rd_i32(c); g->timelineOffColors = rd_i32(c); g->timelineOffTimeline = rd_i32(c);
}
static void rd_spawnshape(NpCursor* c, NpSpawnShape* s) {
    s->active = rd_bool(c); s->code = rd_bool(c);
    /* ellipse = code 3 (spSpawnShape enum POINT=0,LINE=1,SQUARE=2,ELLIPSE=3, cf. game.jar
       SpawnShape$1.class table de correspondance) -> 2 octets de plus, certifié par l'oracle
       (3 sites de lecture INLINE 0x19848/0x19874/0x19a2c, NP_FORMAT.md). */
    if (s->code == 3) { s->edges = rd_bool(c); s->side = rd_bool(c); }
    else { s->edges = 0; s->side = 0; }
}

/* Libère le pool de timelines + tag d'un emitter (alloués par np_parse_emitter). */
static void emitter_free_owned(NpEmitter* em) {
    free(em->timelinePool); em->timelinePool = 0;
    free(em->atlasTag); em->atlasTag = 0;
}

/* Lit UN emitter (2308 o de struct natif, taille FICHIER variable) dans l'ordre CERTIFIÉ 535/535.
 * Retourne 0 si la lecture dépasse la fin du buffer (fichier corrompu/tronqué) — jamais de valeur inventée. */
static int np_read_emitter(NpCursor* c, NpEmitter* em) {
    memset(em, 0, sizeof(*em));
    em->minParticleCount = rd_i32(c);
    em->maxParticleCount = rd_i32(c);
    rd_ranged(c, &em->delay);
    rd_ranged(c, &em->duration);
    for (int i = 0; i < 6; i++) rd_scaled(c, &em->scaledA[i]);
    rd_numeric(c, &em->numeric0);
    rd_spawnshape(c, &em->spawnShape);
    for (int i = 0; i < 12; i++) rd_scaled(c, &em->scaledB[i]);
    rd_ranged(c, &em->rangedC[0]);
    rd_scaled(c, &em->scaledC[0]);
    rd_scaled(c, &em->scaledC[1]);
    rd_ranged(c, &em->rangedC[1]);
    rd_scaled(c, &em->scaledC[2]);
    rd_gradient(c, &em->tint);
    rd_scaled(c, &em->scaledD);
    em->frameDuration = rd_f32(c);
    em->attached = rd_bool(c);
    em->continuous = rd_bool(c);
    em->aligned = rd_bool(c);
    { unsigned char flags = rd_bool(c);         /* additive | premultipliedAlpha<<1 | multiply<<2 (1 octet packé, cf. saveBinary) */
      em->additive = (flags & 1) != 0; em->premultipliedAlpha = (flags & 2) != 0; em->multiply = (flags & 4) != 0; }
    em->behind = rd_bool(c);
    if (!c->ok) return 0;
    /* Trailer (writeTimelines) : poolSize(i) + tagLen(i) + pool[poolSize] floats BE + tag[tagLen] octets UTF-8. */
    int32_t poolSize = rd_i32(c);
    int32_t tagLen = rd_i32(c);
    if (!c->ok || poolSize < 0 || poolSize > 100000 || tagLen < 0 || tagLen > 1000) return 0;
    if (c->p + (long)poolSize * 4 + tagLen > c->end) return 0;
    em->timelinePoolSize = poolSize;
    if (poolSize > 0) {
        em->timelinePool = (float*)malloc(sizeof(float) * poolSize);
        for (int i = 0; i < poolSize; i++) em->timelinePool[i] = rd_f32(c);
    }
    em->atlasTagLen = tagLen;
    if (tagLen > 0) { em->atlasTag = (char*)malloc(tagLen + 1); memcpy(em->atlasTag, c->p, tagLen); em->atlasTag[tagLen] = 0; c->p += tagLen; }
    else { em->atlasTag = (char*)malloc(1); em->atlasTag[0] = 0; }
    return 1;
}

NpEffect* np_parse(const unsigned char* data, int len) {
    if (len < 6 || data[0] != 0 || data[1] != 3) return 0;   /* magie+version (test direct, hors primitives, cf. oracle) */
    NpCursor c = { data + 2, data + len, 1 };
    int32_t emitterCount = rd_i32(&c);
    if (!c.ok || emitterCount < 0 || emitterCount > 64) return 0;   /* borne large mais raisonnable, jamais vu >~10 en pratique */
    NpEffect* eff = (NpEffect*)calloc(1, sizeof(NpEffect));
    eff->emitterCount = emitterCount;
    eff->emitters = (NpEmitter*)calloc(emitterCount, sizeof(NpEmitter));
    for (int i = 0; i < emitterCount; i++) {
        if (!np_read_emitter(&c, &eff->emitters[i])) { np_free(eff); return 0; }
    }
    return eff;   /* EOF-exact garanti par construction (même grammaire que le vérificateur 535/535) */
}

void np_free(NpEffect* eff) {
    if (!eff) return;
    for (int i = 0; i < eff->emitterCount; i++) emitter_free_owned(&eff->emitters[i]);
    free(eff->emitters); free(eff);
}
