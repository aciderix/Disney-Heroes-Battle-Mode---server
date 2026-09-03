/* np_sim.h — API + structs de la simulation de particules (cf. np_sim.c pour la provenance). */
#ifndef DH_NP_SIM_H
#define DH_NP_SIM_H
#include "np_parser.h"

typedef struct {
    int active;
    int life, currentLife;
    float drawX, drawY, z;
    float velocity, velocityDiff;
    float vx, vy;
} NpParticle;

typedef struct {
    NpEmitter def;
    NpParticle* particles;
    int maxParticleCount, minParticleCount;
    int activeCount;
    float accumulator;      /* accumulateur d'émission global (ms) */
    float emissionDelta;    /* accumulateur du taux d'émission (ms) */
    int emission, emissionDiff;   /* figés à restart() depuis emissionValue (int) */
    int life, lifeDiff;           /* figés à restart() depuis lifeValue (int) */
    float duration, durationTimer;
    float delay, delayTimer;
    int firstUpdate, continuous;
    float x, y;             /* position de l'émetteur */
} NpEmitterRuntime;

void np_sim_start(NpEmitterRuntime* em, NpEmitter* def);
void np_sim_update(NpEmitterRuntime* em, float dtSec);
void np_sim_add(NpEmitterRuntime* em, int count);
int  np_sim_active(const NpEmitterRuntime* em);
void np_sim_free(NpEmitterRuntime* em);

void np_sim_run_frames(const unsigned char* npData, int npLen, int nframes, float dt,
                       int* outVertCounts, float** outVerts);

#endif
