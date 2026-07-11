/*
 * cparticle_jni.c — partie particules de la lib native `spine-native` de PerBlue.
 *
 * ⚠️ ÉCHAFAUDAGE TEMPORAIRE (link-scaffold), PAS le moteur final. But immédiat : que la lib EXPORTE
 * les 18 symboles natifs de com.perblue.heroes.cparticle.Native pour que le jeu CHARGE (sinon
 * UnsatisfiedLinkError au 1er `.np`) et qu'on puisse VALIDER le rendu natif de cspine (squelettes/
 * héros via le code d'origine). Ici les effets ne SIMULENT pas encore.
 *
 * À FAIRE (câblage, PAS récréation) : rebâtir fidèlement le moteur de particules en étudiant la lib
 * ARM d'origine (format `.np` v3 + simulation). Le `.np` est un ParticleEmitter libGDX (cf.
 * desktop-port/NP_FORMAT.md) ; le natif de PerBlue en est le portage C. Tant que la lib ARM n'est
 * pas étudiée, on n'INVENTE pas le format → simulation neutre (aucune donnée falsifiée).
 */
#include <jni.h>
#include "com_perblue_heroes_cparticle_Native.h"

static char g_lastParticleError[256] = {0};

JNIEXPORT jstring JNICALL Java_com_perblue_heroes_cparticle_Native_getLastParticleError(JNIEnv* e, jclass c) {
    (void)c; return g_lastParticleError[0] ? (*e)->NewStringUTF(e, g_lastParticleError) : 0;
}

/* handle non nul : cparticle.NativeParticleEffect.load() considère l'effet valide (hasValidHandle). */
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1create(JNIEnv* e, jclass c, jbyteArray np, jint atlas) {
    (void)e; (void)c; (void)np; (void)atlas; return 1;
}
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1clone(JNIEnv* e, jclass c, jint h) { (void)e;(void)c;(void)h; return 1; }
JNIEXPORT void JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1dispose(JNIEnv* e, jclass c, jint h) { (void)e;(void)c;(void)h; }

/* 0 sommet → rien dessiné (simulation différée, cf. entête). */
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1getVertices(JNIEnv* e, jclass c, jint h, jobject v, jobject d) { (void)e;(void)c;(void)h;(void)v;(void)d; return 0; }
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1getVerticesAboveZ(JNIEnv* e, jclass c, jint h, jfloat z, jobject v, jobject d) { (void)e;(void)c;(void)h;(void)z;(void)v;(void)d; return 0; }
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1getVerticesBelowZ(JNIEnv* e, jclass c, jint h, jfloat z, jobject v, jobject d) { (void)e;(void)c;(void)h;(void)z;(void)v;(void)d; return 0; }

JNIEXPORT void JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1start(JNIEnv* e, jclass c, jint h) { (void)e;(void)c;(void)h; }
JNIEXPORT void JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1reset(JNIEnv* e, jclass c, jint h) { (void)e;(void)c;(void)h; }
JNIEXPORT void JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1kill(JNIEnv* e, jclass c, jint h) { (void)e;(void)c;(void)h; }
JNIEXPORT void JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1stopEmitting(JNIEnv* e, jclass c, jint h) { (void)e;(void)c;(void)h; }
JNIEXPORT void JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1setPositionXY(JNIEnv* e, jclass c, jint h, jfloat x, jfloat y) { (void)e;(void)c;(void)h;(void)x;(void)y; }
JNIEXPORT void JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1setPositionXYZ(JNIEnv* e, jclass c, jint h, jfloat x, jfloat y, jfloat z) { (void)e;(void)c;(void)h;(void)x;(void)y;(void)z; }
JNIEXPORT void JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1setRotation(JNIEnv* e, jclass c, jint h, jfloat r) { (void)e;(void)c;(void)h;(void)r; }
JNIEXPORT void JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1setScale(JNIEnv* e, jclass c, jint h, jfloat s) { (void)e;(void)c;(void)h;(void)s; }
/* complete=true : effets one-shot rendus au pool proprement (pas de fuite de handles). */
JNIEXPORT jboolean JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1update(JNIEnv* e, jclass c, jint h, jfloat dt) { (void)e;(void)c;(void)h;(void)dt; return JNI_TRUE; }
JNIEXPORT jboolean JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1usesMultiply(JNIEnv* e, jclass c, jint h) { (void)e;(void)c;(void)h; return JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_perblue_heroes_cparticle_Native_Effect_1usesZOffsets(JNIEnv* e, jclass c, jint h) { (void)e;(void)c;(void)h; return JNI_FALSE; }
