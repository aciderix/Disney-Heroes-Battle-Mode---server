/* np_sim.h — API + structs de la simulation de particules (cf. np_sim.c pour la provenance). */
#ifndef DH_NP_SIM_H
#define DH_NP_SIM_H
#include "np_parser.h"

typedef struct {
    int active;
    int life, currentLife;
    float drawX, drawY, z;
    float velocity, velocityDiff;
    float velocityZ, velocityZDiff;
    float angle, angleDiff, angleCos, angleSin;
    float scaleX, scaleXDiff, scaleY, scaleYDiff;     /* échelle relative (÷ largeur sprite annulé -> = taille px) */
    float rotation, rotationDiff, drawRotation;
    float wind, windDiff, gravity, gravityDiff;
    float transparency, transparencyDiff;
    float tint[3];
} NpParticle;

typedef struct {
    NpEmitter def;
    NpParticle* particles;
    int maxParticleCount, minParticleCount;
    int activeCount;
    float accumulator, emissionDelta;
    int emission, emissionDiff;
    int life, lifeDiff;
    float duration, durationTimer;
    float delay, delayTimer;
    int firstUpdate, continuous;
    float x, y, rotationEmitter;
    float spawnWidth, spawnWidthDiff, spawnHeight, spawnHeightDiff;   /* g288 : tirés en restart, spawn position */
} NpEmitterRuntime;

void np_sim_start(NpEmitterRuntime* em, NpEmitter* def);
void np_sim_update(NpEmitterRuntime* em, float dtSec);
void np_sim_add(NpEmitterRuntime* em, int count);
int  np_sim_active(const NpEmitterRuntime* em);
void np_sim_free(NpEmitterRuntime* em);

void np_sim_run_frames(const unsigned char* npData, int npLen, int nframes, float dt,
                       int* outVertCounts, float** outVerts);

#endif
