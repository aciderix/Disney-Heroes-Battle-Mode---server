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
/* g287 : mapping CORRIGÉ via l'ordre de tirage natif (g286) + l'ordre des champs game.jar. Les anciennes
 * macros (force/taille) étaient scramblées. velocity/angle/emission/life restent, le reste change. */
#define F_EMISSION     (&e->def.scaledA[0])   /* 0x70  */
#define F_LIFE         (&e->def.scaledA[1])   /* 0x48  */
#define F_TANGENTIAL   (&e->def.scaledA[3])   /* 0x210 = tangentialInfluence */
#define F_CENTRIPETAL  (&e->def.scaledA[4])   /* 0x238 = centripetalInfluence */
#define F_BROWNIAN     (&e->def.scaledA[5])   /* 0x260 = brownian (probable, à confirmer) */
#define F_SIZEX        (&e->def.scaledB[2])   /* 0x98  */
#define F_SIZEY        (&e->def.scaledB[3])   /* 0xc0  */
#define F_VELOCITY     (&e->def.scaledB[4])   /* 0x110 */
#define F_VELOCITYZ    (&e->def.scaledB[5])   /* 0x138 */
#define F_ANGLE        (&e->def.scaledB[6])   /* 0x160 */
#define F_ROTATION     (&e->def.scaledB[7])   /* 0xe8  */
#define F_WIND         (&e->def.scaledB[8])   /* 0x188 */
#define F_GRAVITY      (&e->def.scaledD)      /* 0x1d8 */

/* Helpers de CONSOMMATION RNG (g287) : tirent le bon nb de randoms pour un champ sans stocker la valeur.
 * Scaled -> newLow + newHigh (2 tirages) ; Ranged -> newLow (1). Nécessaire pour aligner le flux RNG sur le
 * natif même pour les champs qu'on n'utilise pas encore (spawnWidth/Height/centripetal/tangential). */
static void consume_scaled(const NpScaled* s) { (void)ranged_newLow((const NpRanged*)s, 0); (void)scaled_newHigh(s, 0); }
static void consume_ranged(const NpRanged* r) { (void)ranged_newLow(r, 0); }

/* --------------------------------------------------------------------------------------- start/restart */
/* g287 : ORDRE DE TIRAGE FIDÈLE à ParticleEmitter::restart() (game.jar, cf. JOURNAL g285/g286). Chaque champ
 * tire dans CET ordre pour consommer la RNG EXACTEMENT comme le natif (sinon désync du flux, cf. g284). */
static void restart(NpEmitterRuntime* e) {
    /* g290 : la trace newLowValue (g286) montre 2 tirages PAR CHAMP en restart (même les Ranged) → tirer 2×
     * partout pour aligner le flux (mesuré : natif ~236 après start, ~19.7/émetteur). */
    e->delay = e->def.delay.active ? ranged_newLow(&e->def.delay, 0) : 0;  /* delay (gated) -- 0x0 */
    if (e->def.delay.active) (void)rng_next();
    e->duration = ranged_newLow(&e->def.duration, 0); (void)rng_next();     /* duration -- 0x38 (2×) */
    e->emission = (int)ranged_newLow((const NpRanged*)F_EMISSION, 0);       /* emission -- 0x70 */
    e->emissionDiff = (int)scaled_newHigh(F_EMISSION, 0) - e->emission;
    e->life = (int)ranged_newLow((const NpRanged*)F_LIFE, 0);               /* life -- 0x48 */
    e->lifeDiff = (int)scaled_newHigh(F_LIFE, 0) - e->life;
    if (e->def.scaledA[2].low.active) consume_scaled(&e->def.scaledA[2]);   /* lifeOffset -- 0x10 (gated, 2×) */
    e->spawnWidth = ranged_newLow((const NpRanged*)&e->def.scaledB[0], 0);   /* spawnWidth  -- 0x290 (2×) */
    e->spawnWidthDiff = scaled_newHigh(&e->def.scaledB[0], 0) - e->spawnWidth;
    e->spawnHeight = ranged_newLow((const NpRanged*)&e->def.scaledB[1], 0);  /* spawnHeight -- 0x2b8 (2×) */
    e->spawnHeightDiff = scaled_newHigh(&e->def.scaledB[1], 0) - e->spawnHeight;
    consume_scaled(&e->def.scaledB[11]);  /* centripetalRadius -- 0x2f0 (2×) */
    consume_ranged(&e->def.rangedC[0]); (void)rng_next();  /* centripetalForce -- 0x318 (2×) */
    consume_scaled(&e->def.scaledC[1]);   /* tangentialRadius  -- 0x350 (2×) */
    consume_ranged(&e->def.rangedC[1]); (void)rng_next();  /* tangentialForce  -- 0x378 (2×) */
    e->durationTimer = 0;
    e->delayTimer = 0;
    e->emissionDelta = 0;
}
static int g_dbgStart = -1, g_startN = 0;
void np_sim_start(NpEmitterRuntime* e, NpEmitter* def) {
    memset(e, 0, sizeof(*e));
    e->def = *def;
    if (g_dbgStart < 0) g_dbgStart = getenv("NP_DBG_START") ? 1 : 0;
    if (g_dbgStart && g_startN < 12) {
        fprintf(stderr, "[emit %d] spawnShape.code=%d | scaledA hi: ", g_startN, def->spawnShape.code);
        for (int k=0;k<6;k++) fprintf(stderr,"%.0f%c ", def->scaledA[k].highMax, def->scaledA[k].low.active?'*':' ');
        fprintf(stderr, "| scaledB hi: ");
        for (int k=0;k<12;k++) fprintf(stderr,"%.0f%c ", def->scaledB[k].highMax, def->scaledB[k].low.active?'*':' ');
        fprintf(stderr, "| scaledC/D: %.0f %.0f %.0f / %.0f",
            def->scaledC[0].highMax, def->scaledC[1].highMax, def->scaledC[2].highMax, def->scaledD.highMax);
        fprintf(stderr, " | rangedC[0] act=%d lowMin=%.1f lowMax=%.1f | rangedC[1] act=%d lowMin=%.1f lowMax=%.1f\n",
            def->rangedC[0].active, def->rangedC[0].lowMin, def->rangedC[0].lowMax,
            def->rangedC[1].active, def->rangedC[1].lowMin, def->rangedC[1].lowMax);
        fprintf(stderr, "   lowUsesLinked: velB4=%d angB6=%d sizeB8=%d  highUsesLinked: velB4=%d | relative velB4=%d\n",
            def->scaledB[4].low.lowUsesLinkedRange, def->scaledB[6].low.lowUsesLinkedRange,
            def->scaledB[8].low.lowUsesLinkedRange, def->scaledB[4].highUsesLinkedRange, def->scaledB[4].relative);
        g_startN++;
    }
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

    /* g288-corr : game.jar activateParticle TIRE une valeur partagée au début (bytecode 108 : MathUtils.random()
     * -> Java local 6), passée comme `value` liée à newLow/newHigh. RESTAURÉ (g288 l'avait retirée à tort). */
    float value = rng_next();

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
    /* sizeY : UNCONDITIONNEL (game.jar tire toujours newLow+newHigh, g285) */
    p->scaleY = ranged_newLow((const NpRanged*)F_SIZEY, value);
    p->scaleYDiff = scaled_newHigh(F_SIZEY, value);
    if (!F_SIZEY->relative) p->scaleYDiff -= p->scaleY;

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

    p->transparency = 1.0f; p->transparencyDiff = 0.0f;

    /* g288 : POSITION DE SPAWN (game.jar activateParticle, après brownian). Dimensions = spawnWidth/Height
     * (tirés en restart) + diff*getScale(pct). Puis selon spawnShape.code : point(0)=centre ; square(2)=
     * rectangle uniforme (2 randoms) ; line(1)/ellipse(3) = variantes. random() = rng_next() [0,1). */
    float spawnW = e->spawnWidth + e->spawnWidthDiff * scaled_getScale(&e->def.scaledB[0], pool, pct);
    float spawnH = e->spawnHeight + e->spawnHeightDiff * scaled_getScale(&e->def.scaledB[1], pool, pct);
    float px = 0, py = 0;
    switch (e->def.spawnShape.code) {
        case 2:  /* square : rectangle uniforme */
            px = spawnW * (rng_next() - 0.5f);
            py = spawnH * (rng_next() - 0.5f);
            break;
        case 1:  /* line */
            { float t = rng_next(); px = spawnW * t; py = spawnH * t; }
            break;
        case 3:  /* ellipse (simplifié : à raffiner) */
            { float a = rng_next() * 6.2831853f; float r = rng_next();
              px = spawnW * 0.5f * r * cosDeg(a * 57.29578f); py = spawnH * 0.5f * r * sinDeg(a * 57.29578f); }
            break;
        default: /* point (0) : pas de tirage */
            break;
    }
    /* g292 : les émetteurs point reçoivent au spawn un déplacement basé sur velocity·direction avec un facteur
     * par-particule (mesuré g291, table value↔f) issu de la boucle d'accumulation SoA (activateParticles). La
     * formule exacte (getScale(value) ? autre) reste à figer par single-step -> spawn laissé à l'origine. */
    p->drawX = e->x + px; p->drawY = e->y + py;
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
    if (dbg && n < 12) {
        const NpScaled* vf = F_VELOCITY;
        fprintf(stderr, "[phys %d] velHi=%.1f gs=%.3f angle=%.1f vx=%.2f vy=%.2f | tlN=%d tlA=%d tlB=%d tl=[",
            n, p->velocityDiff, scaled_getScale(vf, pool, percent), p->angle, vx, vy, vf->timelineN, vf->timelineOffA, vf->timelineOffB);
        for (int k=0;k<vf->timelineN && k<4;k++) fprintf(stderr,"%.2f ", pool[vf->timelineOffA+k]);
        fprintf(stderr, "] sc=[");
        for (int k=0;k<vf->timelineN && k<4;k++) fprintf(stderr,"%.2f ", pool[vf->timelineOffB+k]);
        fprintf(stderr, "]\n"); n++;
    }
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
    if (getenv("NP_DBG_RNG")) fprintf(stderr, "== apres start (restart x%d) : %d tirages [natif ~236 avant 1er spawn] ==\n", ne, g_dbgCount);
    for (int f = 0; f < nframes; f++) {
        for (int i = 0; i < ne; i++) np_sim_update(&ems[i], dt);
        if (f == 0 && getenv("NP_DBG_RNG")) fprintf(stderr, "== apres frame 0 : %d tirages [natif 342] ==\n", g_dbgCount);
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
