/*
 * np_sim.c — simulation des particules, portage FIDÈLE de com.badlogic.gdx.graphics.g2d.ParticleEmitter
 * (stock libGDX, EN CLAIR dans game.jar ; le natif PerBlue en est le portage C++). Cf. JOURNAL g262-g264.
 *
 * MÉTHODE (§8) : chaque calcul est VÉRIFIÉ (bytecode) ou HYPOTHÈSE explicite, guidée/corrigée par le
 * harnais différentiel `np_certify` (compare la sortie à l'oracle unidbg -- un mapping faux = diff
 * localisé). RNG CONFIRMÉE g264 (seed=1, *16807, float bit-exact).
 *
 * MAPPING (structOff via `map` g262ter, fixe 535/535 ; velocity=0x110=scaledB[4] CONFIRMÉ). Layout des
 * slots parseur (ordre fichier) : scaledA[6]={0x70,0x48,0x10,0x210,0x238,0x260},
 * scaledB[12]={0x290,0x2b8,0x98,0xc0,0x110*,0x138,0x160,0xe8,0x188,0x1b0,0x328,0x2f0}, scaledC[3]=
 * {0x388,0x350,0x3b0}, scaledD=0x1d8. (* = velocity). Les autres = HYPOTHÈSES à valider par np_certify.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "np_parser.h"
#include "np_sim.h"

/* --------------------------------------------------------------------------------- RNG (g264, bit-exacte) */
static uint32_t g_seed = 1;
static void rng_reset(void) { g_seed = 1; }
static float rng_next(void) {
    g_seed = g_seed * 16807u;
    uint32_t bits = (g_seed & 0x7fffffu) | 0x3f800000u;
    float f; memcpy(&f, &bits, 4);
    return f - 1.0f;
}

static float ranged_newLow(const NpRanged* r, float linked) {
    float u = r->lowUsesLinkedRange ? linked : rng_next();
    return r->lowMin + (r->lowMax - r->lowMin) * u;
}
static float scaled_newHigh(const NpScaled* s, float linked) {
    float u = s->highUsesLinkedRange ? linked : rng_next();
    return s->highMin + (s->highMax - s->highMin) * u;
}
static float scaled_getScale(const NpScaled* s, const float* pool, float percent) {
    if (s->timelineN <= 0) return 0.0f;
    const float* timeline = pool + s->timelineOffA;
    const float* scaling  = pool + s->timelineOffB;
    int n = s->timelineN, i = 1;
    while (i < n && timeline[i] <= percent) i++;
    if (i >= n) return scaling[n - 1];
    float t0 = timeline[i-1], t1 = timeline[i], sc0 = scaling[i-1], sc1 = scaling[i];
    return sc0 + (sc1 - sc0) * (percent - t0) / (t1 - t0);
}

/* ------------------------------------------------------------------------------ mapping semantique -> slot
 * HYPOTHÈSES (à confirmer/corriger via np_certify). velocity = scaledB[4] est SÛR. */
#define F_EMISSION   (&e->def.scaledA[0])   /* HYP : emissionValue */
#define F_LIFE       (&e->def.scaledA[1])   /* HYP : lifeValue */
#define F_VELOCITY   (&e->def.scaledB[4])   /* CONFIRMÉ */

/* --------------------------------------------------------------------------------------- start/restart */
static void restart(NpEmitterRuntime* e) {
    float linked = rng_next();
    e->emission = (int)ranged_newLow((const NpRanged*)F_EMISSION, linked);
    e->emissionDiff = (int)scaled_newHigh(F_EMISSION, linked) - e->emission;
    e->life = (int)ranged_newLow((const NpRanged*)F_LIFE, linked);
    e->lifeDiff = (int)scaled_newHigh(F_LIFE, linked) - e->life;
    e->duration = ranged_newLow(&e->def.duration, linked);
    e->durationTimer = 0;
    e->delay = e->def.delay.active ? ranged_newLow(&e->def.delay, linked) : 0;
    e->delayTimer = 0;
    e->emissionDelta = 0;
}

void np_sim_start(NpEmitterRuntime* e, NpEmitter* def) {
    memset(e, 0, sizeof(*e));
    e->def = *def;
    e->maxParticleCount = def->maxParticleCount > 0 ? def->maxParticleCount : 4;
    e->minParticleCount = def->minParticleCount;
    e->particles = calloc(e->maxParticleCount, sizeof(NpParticle));
    e->firstUpdate = 1;
    e->continuous = def->continuous;
    restart(e);
}

/* ---------------------------------------------------------------------------------------- activate one */
static void activateParticle(NpEmitterRuntime* e, int index) {
    NpParticle* p = &e->particles[index];
    memset(p, 0, sizeof(*p));
    p->active = 1;
    float pct = e->durationTimer / e->duration;
    /* life = life + lifeDiff * lifeValue.getScale(pct)  (BYTECODE : activateParticle) */
    int life = e->life + (int)(e->lifeDiff * scaled_getScale(F_LIFE, e->def.timelinePool, pct));
    if (life < 1) life = 1;
    p->life = life; p->currentLife = life;
    float linked = rng_next();   /* activateParticle : 1 random partagé (BYTECODE local 6) */
    p->velocity = ranged_newLow((const NpRanged*)F_VELOCITY, linked);
    p->velocityDiff = scaled_newHigh(F_VELOCITY, linked) - p->velocity;
    p->drawX = e->x; p->drawY = e->y;
}

void np_sim_add(NpEmitterRuntime* e, int count) {
    for (int c = 0; c < count && e->activeCount < e->maxParticleCount; c++) {
        activateParticle(e, e->activeCount);
        e->activeCount++;
    }
}

/* ----------------------------------------------------------------------------------------- update one */
static int updateParticle(NpParticle* p, int dtMs) {
    p->currentLife -= dtMs;
    if (p->currentLife <= 0) { p->active = 0; return 0; }
    return 1;
}

static void doParticleUpdate(NpEmitterRuntime* e, int dtMs) {
    for (int i = 0; i < e->activeCount; ) {
        if (!updateParticle(&e->particles[i], dtMs)) e->particles[i] = e->particles[--e->activeCount];
        else i++;
    }
}

/* ------------------------------------------------ ParticleEmitter.update(float) -- BYTECODE VÉRIFIÉ g262 */
void np_sim_update(NpEmitterRuntime* e, float dtSec) {
    e->accumulator += dtSec * 1000.0f;
    if (e->accumulator < 1.0f) return;
    int deltaMs = (int)e->accumulator;
    e->accumulator -= deltaMs;

    if (e->delayTimer < e->delay) { e->delayTimer += deltaMs; doParticleUpdate(e, deltaMs); return; }

    int done = 0;
    if (e->firstUpdate) { e->firstUpdate = 0; np_sim_add(e, 1); }
    if (e->durationTimer < e->duration) e->durationTimer += deltaMs;
    else { if (!e->continuous) done = 1; else restart(e); }

    if (!done) {
        e->emissionDelta += deltaMs;
        float emissionTime = e->emission + e->emissionDiff *
            scaled_getScale(F_EMISSION, e->def.timelinePool, e->durationTimer / e->duration);
        if (emissionTime > 0) {
            emissionTime = 1000.0f / emissionTime;
            if (e->emissionDelta >= emissionTime) {
                int emitCount = (int)(e->emissionDelta / emissionTime);
                if (emitCount > e->maxParticleCount - e->activeCount) emitCount = e->maxParticleCount - e->activeCount;
                e->emissionDelta -= emitCount * emissionTime;
                e->emissionDelta = fmodf(e->emissionDelta, emissionTime);
                np_sim_add(e, emitCount);
            }
        }
        if (e->activeCount < e->minParticleCount) np_sim_add(e, e->minParticleCount - e->activeCount);
    }
    doParticleUpdate(e, deltaMs);
}

int np_sim_active(const NpEmitterRuntime* e) { return e->activeCount; }
void np_sim_free(NpEmitterRuntime* e) { free(e->particles); e->particles = 0; }

/* --------------------------------------------------------------- interface np_certify : TOUS les émetteurs
 * ÉTAPE 1 (compte) : 4 sommets (drawX,drawY, reste 0) par particule vivante, tous émetteurs cumulés. */
void np_sim_run_frames(const unsigned char* npData, int npLen, int nframes, float dt,
                       int* outVertCounts, float** outVerts) {
    rng_reset();
    NpEffect* eff = np_parse(npData, npLen);
    if (!eff || eff->emitterCount < 1) {
        for (int f=0; f<nframes; f++){ outVertCounts[f]=0; outVerts[f]=0; }
        if (eff) np_free(eff);
        return;
    }
    int ne = eff->emitterCount;
    NpEmitterRuntime* ems = calloc(ne, sizeof(NpEmitterRuntime));
    for (int i = 0; i < ne; i++) np_sim_start(&ems[i], &eff->emitters[i]);  /* start dans l'ordre (RNG) */

    for (int f = 0; f < nframes; f++) {
        for (int i = 0; i < ne; i++) np_sim_update(&ems[i], dt);   /* update dans l'ordre (RNG) */
        int total = 0;
        for (int i = 0; i < ne; i++) total += ems[i].activeCount;
        outVertCounts[f] = total * 4;
        outVerts[f] = malloc(sizeof(float) * (total > 0 ? total : 1) * 4 * 6);
        int vi = 0;
        for (int i = 0; i < ne; i++)
            for (int j = 0; j < ems[i].activeCount; j++)
                for (int v = 0; v < 4; v++) {
                    float* b = outVerts[f] + (vi++) * 6;
                    b[0] = ems[i].particles[j].drawX; b[1] = ems[i].particles[j].drawY;
                    b[2]=b[3]=b[4]=b[5]=0;
                }
    }
    for (int i = 0; i < ne; i++) np_sim_free(&ems[i]);
    free(ems);
    np_free(eff);
}
