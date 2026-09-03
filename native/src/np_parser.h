/* np_parser.h — struct + API du lecteur `.np` v3 (cf. np_parser.c pour la provenance/certification). */
#ifndef DH_NP_PARSER_H
#define DH_NP_PARSER_H

typedef struct { unsigned char active, lowUsesLinkedRange; float lowMin, lowMax; } NpRanged;
typedef struct {
    NpRanged low;
    float highMin, highMax;
    unsigned char highUsesLinkedRange, relative;
    int timelineN, timelineOffA, timelineOffB;
} NpScaled;
typedef struct { unsigned char active; float value; } NpNumeric;
typedef struct { unsigned char active; int timelineN, timelineOffColors, timelineOffTimeline; } NpGradient;
typedef struct { unsigned char active, code, edges, side; } NpSpawnShape;   /* code==3 (ELLIPSE) -> edges/side valides */

/* Un emitter. TODO (non bloquant, cf. np_parser.c en-tête) : affiner les noms sémantiques exacts de
 * scaledA/scaledB/scaledC/rangedC une fois la simulation portée (oracle sur updateParticles/getTCVertices,
 * même méthode que le format lui-même) — l'ORDRE ci-dessous est CERTIFIÉ 535/535, pas deviné. */
typedef struct {
    int minParticleCount, maxParticleCount;
    NpRanged delay, duration;
    NpScaled scaledA[6];        /* bloc avant numeric0/spawnShape */
    NpNumeric numeric0;
    NpSpawnShape spawnShape;
    NpScaled scaledB[12];       /* bloc après spawnShape */
    NpRanged rangedC[2];        /* motif Ranged,Scaled,Scaled,Ranged,Scaled */
    NpScaled scaledC[3];
    NpGradient tint;
    NpScaled scaledD;           /* dernier scaled avant frameDuration */
    float frameDuration;
    unsigned char attached, continuous, aligned, behind;
    unsigned char additive, premultipliedAlpha, multiply;   /* décompactés du byte flags (cf. saveBinary) */
    int timelinePoolSize;       /* pool de floats partagé par toutes les timelines de cet emitter */
    float* timelinePool;
    int atlasTagLen;
    char* atlasTag;             /* nom de région d'atlas (UTF-8, NUL-terminé), ex. "fireworks_b" */
} NpEmitter;

typedef struct {
    int emitterCount;
    NpEmitter* emitters;
} NpEffect;

/* Parse un `.np` v3 réel. Retourne 0 si le fichier est invalide/tronqué (magie, EOF prématuré, taille
 * de pool/tag aberrante) -- ne DEVINE jamais une valeur pour continuer. À libérer avec np_free(). */
NpEffect* np_parse(const unsigned char* data, int len);
void np_free(NpEffect* eff);

#endif
