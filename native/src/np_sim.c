/*
 * np_sim.c — simulation des particules, portage FIDÈLE de com.badlogic.gdx.graphics.g2d.ParticleEmitter
 * (stock libGDX, EN CLAIR dans game.jar ; le natif PerBlue en est le portage C++). Cf. JOURNAL g262-g264.
 *
 * MÉTHODE (§8) : VÉRIFIÉ (bytecode/np_certify) ou HYPOTHÈSE explicite guidée par np_certify (diff sommet
 * localisé vs oracle unidbg). RNG CONFIRMÉE g264 (seed=1, *16807, float bit-exact).
 *
 * MAPPING CHAMPS (offsets struct via `map` ; ORDRE JAVA via `activorder` -> 6 champs consécutifs à +0x28
 * matchant l'ordre Java : velocity=0x110, velocityZ=0x138, angle=0x160, sizeX=0x188, sizeY=0x1b0,
 * rotation=0x1d8, puis wind=0x210, gravity=0x238, tangential=0x260, centripetal=0x290, brownian=0x2b8,
 * transparency=0x2f0). Traduit en slots parseur (ordre FICHIER) ci-dessous. velocity CONFIRMÉ ; le reste
 * = hypothèse forte (structurelle) à valider par np_certify.
 *   scaledA[6]={0x70,0x48,0x10,0x210,0x238,0x260} ; scaledB[12]={0x290,0x2b8,0x98,0xc0,0x110,0x138,0x160,
 *   0xe8,0x188,0x1b0,0x328,0x2f0} ; scaledC[3]={0x388,0x350,0x3b0} ; scaledD=0x1d8.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdio.h>
#include "np_parser.h"
#include "np_sim.h"

#define DEG2RAD (3.14159265358979323846f / 180.0f)
static float cosDeg(float d) { return cosf(d * DEG2RAD); }
static float sinDeg(float d) { return sinf(d * DEG2RAD); }

/* --------------------------------------------------------------------------------- RNG (g264, bit-exacte) */
static uint32_t g_seed = 1;
static int g_dbgRng = -1, g_dbgCount = 0;
static void rng_reset(void) { g_seed = 1; g_dbgCount = 0; }
static float rng_next(void) {
    g_seed = g_seed * 16807u;
    uint32_t bits = (g_seed & 0x7fffffu) | 0x3f800000u;
    float f; memcpy(&f, &bits, 4); f -= 1.0f;
    if (g_dbgRng < 0) g_dbgRng = getenv("NP_DBG_RNG") ? 1 : 0;
    if (g_dbgRng && g_dbgCount < 30) { fprintf(stderr, "[rng %2d] %.6f\n", g_dbgCount, f); }
    g_dbgCount++;
    return f;
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
    const float* tl = pool + s->timelineOffA;
    const float* sc = pool + s->timelineOffB;
    int n = s->timelineN, i = 1;
    while (i < n && tl[i] <= percent) i++;
    if (i >= n) return sc[n - 1];
    return sc[i-1] + (sc[i] - sc[i-1]) * (percent - tl[i-1]) / (tl[i] - tl[i-1]);
}

/* ------------------------------------------------------------------------------ mapping semantique -> slot */
#define F_EMISSION     (&e->def.scaledA[0])
#define F_LIFE         (&e->def.scaledA[1])
#define F_WIND         (&e->def.scaledA[3])
#define F_GRAVITY      (&e->def.scaledA[4])
#define F_TANGENTIAL   (&e->def.scaledA[5])
#define F_CENTRIPETAL  (&e->def.scaledB[0])
#define F_BROWNIAN     (&e->def.scaledB[1])
#define F_VELOCITY     (&e->def.scaledB[4])
#define F_VELOCITYZ    (&e->def.scaledB[5])
#define F_ANGLE        (&e->def.scaledB[6])
#define F_SIZEX        (&e->def.scaledB[8])
#define F_SIZEY        (&e->def.scaledB[9])
#define F_TRANSPARENCY (&e->def.scaledB[11])
#define F_ROTATION     (&e->def.scaledD)

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

/* ---------------------------------------------------------------------- activate one (RNG-ordre fidèle) */
static void activateParticle(NpEmitterRuntime* e, int index) {
    NpParticle* p = &e->particles[index];
    memset(p, 0, sizeof(*p));
    p->active = 1;
    float pct = e->durationTimer / e->duration;
    const float* pool = e->def.timelinePool;

    int life = e->life + (int)(e->lifeDiff * scaled_getScale(F_LIFE, pool, pct));
    if (life < 1) life = 1;
    p->life = life; p->currentLife = life;

    float value = rng_next();   /* random partagé "linked" (Java local 6) */

    /* velocity (active-gated) */
    if (F_VELOCITY->low.active) {
        p->velocity = ranged_newLow((const NpRanged*)F_VELOCITY, value);
        p->velocityDiff = scaled_newHigh(F_VELOCITY, value);
        if (!F_VELOCITY->relative) p->velocityDiff -= p->velocity;
    }
    /* velocityZ (active-gated) */
    if (F_VELOCITYZ->low.active) {
        p->velocityZ = ranged_newLow((const NpRanged*)F_VELOCITYZ, value);
        p->velocityZDiff = scaled_newHigh(F_VELOCITYZ, value);
        if (!F_VELOCITYZ->relative) p->velocityZDiff -= p->velocityZ;
    }

    /* angle : UNCONDITIONNEL (Java) */
    p->angle = ranged_newLow((const NpRanged*)F_ANGLE, value);
    p->angleDiff = scaled_newHigh(F_ANGLE, value);
    if (!F_ANGLE->relative) p->angleDiff -= p->angle;
    p->angle += e->rotationEmitter;
    /* angle pas dans updateFlags (HYPOTHÈSE : figé au spawn) -> angleCos/Sin fixes */
    float a = p->angle + p->angleDiff * scaled_getScale(F_ANGLE, pool, 0);
    p->angle = a; p->angleCos = cosDeg(a); p->angleSin = sinDeg(a);

    /* sizeX : UNCONDITIONNEL. drawnWidth = sizeXValue (la largeur sprite s'annule). */
    p->scaleX = ranged_newLow((const NpRanged*)F_SIZEX, value);
    p->scaleXDiff = scaled_newHigh(F_SIZEX, value);
    if (!F_SIZEX->relative) p->scaleXDiff -= p->scaleX;
    /* sizeY (si !uniformScale ; HYPOTHÈSE uniform = !sizeY.active) */
    if (F_SIZEY->low.active) {
        p->scaleY = ranged_newLow((const NpRanged*)F_SIZEY, value);
        p->scaleYDiff = scaled_newHigh(F_SIZEY, value);
        if (!F_SIZEY->relative) p->scaleYDiff -= p->scaleY;
    } else { p->scaleY = p->scaleX; p->scaleYDiff = p->scaleXDiff; }

    /* rotation (active-gated) */
    if (F_ROTATION->low.active) {
        p->rotation = ranged_newLow((const NpRanged*)F_ROTATION, value);
        p->rotationDiff = scaled_newHigh(F_ROTATION, value);
        if (!F_ROTATION->relative) p->rotationDiff -= p->rotation;
        p->rotation += e->rotationEmitter;
        p->drawRotation = p->rotation + p->rotationDiff * scaled_getScale(F_ROTATION, pool, 0);
    }
    /* wind/gravity/tangential/centripetal/brownian (active-gated) */
    #define ACT(F) if ((F)->low.active) { ranged_newLow((const NpRanged*)(F), value); scaled_newHigh((F), value); }
    ACT(F_WIND) ACT(F_GRAVITY) ACT(F_TANGENTIAL) ACT(F_CENTRIPETAL) ACT(F_BROWNIAN)
    #undef ACT

    /* transparency : UNCONDITIONNEL */
    p->transparency = ranged_newLow((const NpRanged*)F_TRANSPARENCY, value);
    p->transparencyDiff = scaled_newHigh(F_TRANSPARENCY, value) - p->transparency;

    /* spawn : point (spawnShape.code 0) -> position = émetteur (offsets xy absents en v3 -> 0) */
    p->drawX = e->x; p->drawY = e->y;
}

void np_sim_add(NpEmitterRuntime* e, int count) {
    for (int c = 0; c < count && e->activeCount < e->maxParticleCount; c++)
        activateParticle(e, e->activeCount++);
}

/* ----------------------------------------------------------------------------------------- update one */
static int updateParticle(NpEmitterRuntime* e, NpParticle* p, int dtMs) {
    p->currentLife -= dtMs;
    if (p->currentLife <= 0) { p->active = 0; return 0; }
    float dt = dtMs / 1000.0f;
    const float* pool = e->def.timelinePool;
    float percent = 1.0f - p->currentLife / (float)p->life;
    /* velocity le long de l'angle figé (angleCos/Sin) */
    float vx = 0, vy = 0;
    if (F_VELOCITY->low.active) {
        float v = (p->velocity + p->velocityDiff * scaled_getScale(F_VELOCITY, pool, percent)) * dt;
        vx = p->angleCos * v; vy = p->angleSin * v;
    }
    if (F_WIND->low.active)    vx += (p->wind + p->windDiff * scaled_getScale(F_WIND, pool, percent)) * dt;
    if (F_GRAVITY->low.active) vy += (p->gravity + p->gravityDiff * scaled_getScale(F_GRAVITY, pool, percent)) * dt;
    if (F_VELOCITYZ->low.active) {
        float vz = (p->velocityZ + p->velocityZDiff * scaled_getScale(F_VELOCITYZ, pool, percent)) * dt;
        p->z += vz;
        float mult = e->def.numeric0.active ? e->def.numeric0.value : 0;
        vy += vz * mult;   /* velocityZ couplé à drawY via zToYMultiplier (BYTECODE updateParticle) */
    }
    p->drawX += vx; p->drawY += vy;
    static int dbg = -1, n = 0;
    if (dbg < 0) dbg = getenv("NP_DBG_PHYS") ? 1 : 0;
    if (dbg && n < 6) { fprintf(stderr, "[phys %d] vel=%.1f velDiff=%.1f gs=%.3f angle=%.1f vZ=%.1f vx=%.2f vy=%.2f life=%d pct=%.3f\n",
        n, p->velocity, p->velocityDiff, scaled_getScale(F_VELOCITY, pool, percent), p->angle, p->velocityZ, vx, vy, p->life, percent); n++; }
    return 1;
}
static void doParticleUpdate(NpEmitterRuntime* e, int dtMs) {
    for (int i = 0; i < e->activeCount; ) {
        if (!updateParticle(e, &e->particles[i], dtMs)) e->particles[i] = e->particles[--e->activeCount];
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
        float et = e->emission + e->emissionDiff * scaled_getScale(F_EMISSION, e->def.timelinePool, e->durationTimer / e->duration);
        if (et > 0) {
            et = 1000.0f / et;
            if (e->emissionDelta >= et) {
                int c = (int)(e->emissionDelta / et);
                if (c > e->maxParticleCount - e->activeCount) c = e->maxParticleCount - e->activeCount;
                e->emissionDelta -= c * et;
                e->emissionDelta = fmodf(e->emissionDelta, et);
                np_sim_add(e, c);
            }
        }
        if (e->activeCount < e->minParticleCount) np_sim_add(e, e->minParticleCount - e->activeCount);
    }
    doParticleUpdate(e, deltaMs);
}

int np_sim_active(const NpEmitterRuntime* e) { return e->activeCount; }
void np_sim_free(NpEmitterRuntime* e) { free(e->particles); e->particles = 0; }

/* ------------------------------------------------------------- rendu quad 2-couleurs (position x/y focus)
 * 4 sommets par particule : coins tournés du quad centré (drawX,drawY), demi-tailles drawSizeX/Y/2,
 * angle drawRotation. Ordre coins = BL,TL,TR,BR (cf. golden). light/dark/uv = 0 pour l'instant (étape
 * position ; couleurs+uv nécessitent tint/transparency packés + atlas -> étapes suivantes np_certify). */
static void emitQuad(const NpParticle* p, float* out) {
    float hw = p->scaleX * 0.5f, hh = p->scaleY * 0.5f;
    float c = cosDeg(p->drawRotation), s = sinDeg(p->drawRotation);
    float lx[4] = { -hw, -hw,  hw,  hw };
    float ly[4] = { -hh,  hh,  hh, -hh };
    for (int v = 0; v < 4; v++) {
        float* b = out + v * 6;
        b[0] = p->drawX + (lx[v] * c - ly[v] * s);
        b[1] = p->drawY + (lx[v] * s + ly[v] * c);
        b[2] = b[3] = b[4] = b[5] = 0;
    }
}

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
    for (int i = 0; i < ne; i++) np_sim_start(&ems[i], &eff->emitters[i]);
    for (int f = 0; f < nframes; f++) {
        for (int i = 0; i < ne; i++) np_sim_update(&ems[i], dt);
        int total = 0;
        for (int i = 0; i < ne; i++) total += ems[i].activeCount;
        outVertCounts[f] = total * 4;
        outVerts[f] = malloc(sizeof(float) * (total > 0 ? total : 1) * 4 * 6);
        int vi = 0;
        for (int i = 0; i < ne; i++)
            for (int j = 0; j < ems[i].activeCount; j++, vi++)
                emitQuad(&ems[i].particles[j], outVerts[f] + vi * 4 * 6);
    }
    for (int i = 0; i < ne; i++) np_sim_free(&ems[i]);
    free(ems);
    np_free(eff);
}
