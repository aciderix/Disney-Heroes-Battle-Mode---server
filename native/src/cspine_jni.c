/*
 * cspine_jni.c — implémentation DESKTOP de la lib native `spine-native` de PerBlue, au-dessus du
 * runtime spine-c OFFICIEL (Esoteric 3.6). Reproduit l'interface JNI EXACTE de
 * com.perblue.heroes.cspine.Native (relevée par javap → native/jni-decl) pour que le CODE JAVA
 * D'ORIGINE du jeu (cspine.*) tourne INCHANGÉ. Aucune modification du jeu.
 *
 * Convention : les objets natifs (spAtlas/spSkeletonData/spSkeleton/spAnimationState(Data)) sont
 * exposés au Java par des HANDLES entiers (index dans des tables) — exactement le modèle de PerBlue.
 * Les textures GL sont gérées côté Java (l'atlas natif ne parse que les noms de pages).
 */
#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <math.h>

#include <spine/spine.h>

#include "com_perblue_heroes_cspine_Native.h"

/* ------------------------------------------------------------------ erreurs */
static char g_lastError[1024] = {0};
static void setError(const char* msg) { if (msg) { strncpy(g_lastError, msg, sizeof(g_lastError)-1); g_lastError[sizeof(g_lastError)-1]=0; } }

/* ------------------------------------------------------ callbacks spine-c requis */
/* Les textures sont chargées côté Java (le jeu passe par son AssetManager) : ici, no-op. */
void _spAtlasPage_createTexture(spAtlasPage* self, const char* path) { (void)self; (void)path; }
void _spAtlasPage_disposeTexture(spAtlasPage* self) { (void)self; }
char* _spUtil_readFile(const char* path, int* length) { (void)path; if (length) *length = 0; return 0; }

/* ------------------------------------------------------------- table de handles */
typedef struct { void** items; int size, cap; } HandleTable;
static HandleTable t_atlas, t_skelData, t_skel, t_asd, t_animState;

/* Tint black GLOBAL par squelette (Skeleton_setTintBlack) : spine-c 3.6 n'a pas de champ pour ça, on le stocke
 * à côté, indexé par pointeur spSkeleton*. Relevé hex de l'oracle : tous les slots SANS darkColor propre portent
 * la MÊME dark (ex. 0x00,0x11,0x33) → c'est un tint sombre global, pas une couleur par-slot. Le no-op précédent
 * laissait dark=0 (héros légèrement moins teintés). */
typedef struct { spSkeleton* key; float r, g, b; } TintEntry;
static TintEntry* g_tints = 0; static int g_tintCount = 0, g_tintCap = 0;
static TintEntry* tintFor(spSkeleton* s, int create) {
    for (int i = 0; i < g_tintCount; i++) if (g_tints[i].key == s) return &g_tints[i];
    if (!create) return 0;
    if (g_tintCount >= g_tintCap) { g_tintCap = g_tintCap ? g_tintCap*2 : 32; g_tints = (TintEntry*)realloc(g_tints, g_tintCap*sizeof(TintEntry)); }
    g_tints[g_tintCount].key = s; g_tints[g_tintCount].r = g_tints[g_tintCount].g = g_tints[g_tintCount].b = 0;
    return &g_tints[g_tintCount++];
}
static void tintRemove(spSkeleton* s) {
    for (int i = 0; i < g_tintCount; i++) if (g_tints[i].key == s) { g_tints[i] = g_tints[--g_tintCount]; return; } /* évite un tint périmé si malloc réutilise l'adresse */
}

static int ht_add(HandleTable* t, void* p) {
    if (t->size >= t->cap) { t->cap = t->cap ? t->cap*2 : 64; t->items = (void**)realloc(t->items, t->cap*sizeof(void*)); }
    /* réutilise un trou libre si possible */
    for (int i = 0; i < t->size; i++) if (!t->items[i]) { t->items[i] = p; return i+1; }
    t->items[t->size++] = p; return t->size; /* handle = index+1 (0 = invalide) */
}
static void* ht_get(HandleTable* t, int h) { return (h > 0 && h <= t->size) ? t->items[h-1] : 0; }
static void* ht_take(HandleTable* t, int h) { void* p = ht_get(t,h); if (h>0 && h<=t->size) t->items[h-1]=0; return p; }

/* --------------------------------------------------------------- utilitaires JNI */
static jstring cstr(JNIEnv* e, const char* s) { return s ? (*e)->NewStringUTF(e, s) : 0; }

static jobjectArray strArray(JNIEnv* e, const char** names, int n) {
    jclass sc = (*e)->FindClass(e, "java/lang/String");
    jobjectArray a = (*e)->NewObjectArray(e, n, sc, 0);
    for (int i = 0; i < n; i++) { jstring s = cstr(e, names[i]); (*e)->SetObjectArrayElement(e, a, i, s); (*e)->DeleteLocalRef(e, s); }
    return a;
}

/* ================================================================= Spine_init/err */
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Spine_1init(JNIEnv* e, jclass c) {
    (void)e; (void)c; g_lastError[0] = 0;
}
JNIEXPORT jstring JNICALL Java_com_perblue_heroes_cspine_Native_getLastSpineError(JNIEnv* e, jclass c) {
    (void)c; return g_lastError[0] ? (*e)->NewStringUTF(e, g_lastError) : 0;
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_printUsedHandleReport(JNIEnv* e, jclass c) {
    (void)e; (void)c;
    fprintf(stderr, "[spine-native] handles: atlas=%d skelData=%d skel=%d asd=%d animState=%d\n",
            t_atlas.size, t_skelData.size, t_skel.size, t_asd.size, t_animState.size);
}

/* ============================================================================ Atlas */
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_Atlas_1create(JNIEnv* e, jclass c, jbyteArray data, jboolean premul) {
    (void)c; (void)premul;
    jsize len = (*e)->GetArrayLength(e, data);
    jbyte* buf = (*e)->GetByteArrayElements(e, data, 0);
    spAtlas* atlas = spAtlas_create((const char*)buf, (int)len, "", 0);
    (*e)->ReleaseByteArrayElements(e, data, buf, JNI_ABORT);
    if (!atlas) { setError("spAtlas_create a échoué"); return 0; }
    /* Marque chaque page par sa POSITION (0-based) dans rendererObject : ordre identique à
       NativeAtlas.getTextures() côté jeu (textures.get(pageIndex)) → sert au regroupement drawCalls
       dans getVertices (le renderer fait textures.get(texturePageIndex).bind()). */
    int pi = 0;
    for (spAtlasPage* p = atlas->pages; p; p = p->next) p->rendererObject = (void*)(intptr_t)(pi++);
    return ht_add(&t_atlas, atlas);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Atlas_1dispose(JNIEnv* e, jclass c, jint h) {
    (void)e; (void)c; spAtlas* a = (spAtlas*)ht_take(&t_atlas, h); if (a) spAtlas_dispose(a);
}
static spAtlasPage* atlasPageAt(spAtlas* a, int idx) {
    spAtlasPage* p = a ? a->pages : 0; for (int i = 0; p && i < idx; i++) p = p->next; return p;
}
JNIEXPORT jstring JNICALL Java_com_perblue_heroes_cspine_Native_Atlas_1getTexture(JNIEnv* e, jclass c, jint h, jint page) {
    (void)c; spAtlasPage* p = atlasPageAt((spAtlas*)ht_get(&t_atlas, h), page); return p ? cstr(e, p->name) : 0;
}
JNIEXPORT jboolean JNICALL Java_com_perblue_heroes_cspine_Native_Atlas_1getParams(JNIEnv* e, jclass c, jint h, jint page, jintArray out) {
    (void)c; spAtlasPage* p = atlasPageAt((spAtlas*)ht_get(&t_atlas, h), page);
    if (!p) return JNI_FALSE;
    jint vals[7] = { p->width, p->height, (jint)p->format, (jint)p->minFilter, (jint)p->magFilter, (jint)p->uWrap, (jint)p->vWrap };
    jsize n = (*e)->GetArrayLength(e, out); if (n > 7) n = 7;
    (*e)->SetIntArrayRegion(e, out, 0, n, vals);
    return JNI_TRUE;
}

/* ==================================================================== SkeletonData */
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1create(JNIEnv* e, jclass c, jbyteArray data, jint atlasHandle) {
    (void)c;
    spAtlas* atlas = (spAtlas*)ht_get(&t_atlas, atlasHandle);
    if (!atlas) { setError("atlas handle invalide"); return 0; }
    jsize len = (*e)->GetArrayLength(e, data);
    jbyte* buf = (*e)->GetByteArrayElements(e, data, 0);
    spSkeletonBinary* bin = spSkeletonBinary_create(atlas);
    spSkeletonData* sd = spSkeletonBinary_readSkeletonData(bin, (const unsigned char*)buf, (int)len);
    if (!sd && bin->error) setError(bin->error);
    (*e)->ReleaseByteArrayElements(e, data, buf, JNI_ABORT);
    spSkeletonBinary_dispose(bin);
    if (!sd) return 0;
    return ht_add(&t_skelData, sd);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1dispose(JNIEnv* e, jclass c, jint h) {
    (void)e; (void)c; spSkeletonData* d = (spSkeletonData*)ht_take(&t_skelData, h); if (d) spSkeletonData_dispose(d);
}
JNIEXPORT jfloatArray JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1getAnimationDurations(JNIEnv* e, jclass c, jint h) {
    (void)c; spSkeletonData* d = (spSkeletonData*)ht_get(&t_skelData, h); if (!d) return 0;
    jfloatArray a = (*e)->NewFloatArray(e, d->animationsCount);
    for (int i = 0; i < d->animationsCount; i++) { jfloat dur = d->animations[i]->duration; (*e)->SetFloatArrayRegion(e, a, i, 1, &dur); }
    return a;
}
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1getAnimationID(JNIEnv* e, jclass c, jint h, jstring name) {
    /* IDs d'animation 1-based (le jeu fait animNames[id-1], et 0 = « aucune », cf. getPrimaryAnimation). */
    (void)c; spSkeletonData* d = (spSkeletonData*)ht_get(&t_skelData, h); if (!d) return 0;
    const char* n = (*e)->GetStringUTFChars(e, name, 0); int id = 0;
    for (int i = 0; i < d->animationsCount; i++) if (!strcmp(d->animations[i]->name, n)) { id = i+1; break; }
    (*e)->ReleaseStringUTFChars(e, name, n); return id;
}
JNIEXPORT jobjectArray JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1getAnimationNames(JNIEnv* e, jclass c, jint h) {
    (void)c; spSkeletonData* d = (spSkeletonData*)ht_get(&t_skelData, h); if (!d) return 0;
    const char** names = (const char**)malloc(sizeof(char*)*d->animationsCount);
    for (int i = 0; i < d->animationsCount; i++) names[i] = d->animations[i]->name;
    jobjectArray a = strArray(e, names, d->animationsCount); free(names); return a;
}
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1getBoneID(JNIEnv* e, jclass c, jint h, jstring name) {
    (void)c; spSkeletonData* d = (spSkeletonData*)ht_get(&t_skelData, h); if (!d) return -1;
    const char* n = (*e)->GetStringUTFChars(e, name, 0); int id = -1;
    for (int i = 0; i < d->bonesCount; i++) if (!strcmp(d->bones[i]->name, n)) { id = i; break; }
    (*e)->ReleaseStringUTFChars(e, name, n); return id;
}
JNIEXPORT jobjectArray JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1getBoneNames(JNIEnv* e, jclass c, jint h) {
    (void)c; spSkeletonData* d = (spSkeletonData*)ht_get(&t_skelData, h); if (!d) return 0;
    const char** names = (const char**)malloc(sizeof(char*)*d->bonesCount);
    for (int i = 0; i < d->bonesCount; i++) names[i] = d->bones[i]->name;
    jobjectArray a = strArray(e, names, d->bonesCount); free(names); return a;
}
JNIEXPORT jobjectArray JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1getSkinNames(JNIEnv* e, jclass c, jint h) {
    (void)c; spSkeletonData* d = (spSkeletonData*)ht_get(&t_skelData, h); if (!d) return 0;
    const char** names = (const char**)malloc(sizeof(char*)*d->skinsCount);
    for (int i = 0; i < d->skinsCount; i++) names[i] = d->skins[i]->name;
    jobjectArray a = strArray(e, names, d->skinsCount); free(names); return a;
}
JNIEXPORT jobjectArray JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1getSlotNames(JNIEnv* e, jclass c, jint h) {
    (void)c; spSkeletonData* d = (spSkeletonData*)ht_get(&t_skelData, h); if (!d) return 0;
    const char** names = (const char**)malloc(sizeof(char*)*d->slotsCount);
    for (int i = 0; i < d->slotsCount; i++) names[i] = d->slots[i]->name;
    jobjectArray a = strArray(e, names, d->slotsCount); free(names); return a;
}
JNIEXPORT jboolean JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1getStats(JNIEnv* e, jclass c, jint h, jobject out) {
    (void)e; (void)c; (void)out; spSkeletonData* d = (spSkeletonData*)ht_get(&t_skelData, h);
    /* Rapport de diagnostic (facultatif) : renseigné plus tard si le jeu le déréférence. */
    return d ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jobjectArray JNICALL Java_com_perblue_heroes_cspine_Native_SkeletonData_1getVertexWeightReport(JNIEnv* e, jclass c, jint h, jint slot) {
    (void)e; (void)c; (void)h; (void)slot; return 0; /* rapport de diagnostic — non requis au rendu */
}

/* ======================================================================== Skeleton */
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1create(JNIEnv* e, jclass c, jint dataHandle) {
    (void)e; (void)c; spSkeletonData* d = (spSkeletonData*)ht_get(&t_skelData, dataHandle);
    if (!d) { setError("skeletonData handle invalide"); return 0; }
    spSkeleton* s = spSkeleton_create(d); if (!s) return 0;
    spSkeleton_setToSetupPose(s); spSkeleton_updateWorldTransform(s);
    return ht_add(&t_skel, s);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1dispose(JNIEnv* e, jclass c, jint h) {
    (void)e; (void)c; spSkeleton* s = (spSkeleton*)ht_take(&t_skel, h); if (s) { tintRemove(s); spSkeleton_dispose(s); }
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1update(JNIEnv* e, jclass c, jint h, jfloat dt) {
    (void)e; (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h); if (s) spSkeleton_update(s, dt);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1updateWorldTransform(JNIEnv* e, jclass c, jint h) {
    (void)e; (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h); if (s) spSkeleton_updateWorldTransform(s);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1setToSetupPose(JNIEnv* e, jclass c, jint h) {
    (void)e; (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h); if (s) spSkeleton_setToSetupPose(s);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1setColor(JNIEnv* e, jclass c, jint h, jfloat r, jfloat g, jfloat b, jfloat a) {
    (void)e; (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h);
    if (s) { s->color.r = r; s->color.g = g; s->color.b = b; s->color.a = a; }
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1setTintBlack(JNIEnv* e, jclass c, jint h, jfloat r, jfloat g, jfloat b) {
    (void)e; (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h); if (!s) return;
    TintEntry* t = tintFor(s, 1); t->r = r; t->g = g; t->b = b; /* tint sombre GLOBAL, base de dark au getVertices */
}
JNIEXPORT jboolean JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1setSkin(JNIEnv* e, jclass c, jint h, jstring name) {
    (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h); if (!s) return JNI_FALSE;
    const char* n = name ? (*e)->GetStringUTFChars(e, name, 0) : 0;
    int ok = spSkeleton_setSkinByName(s, n);
    if (ok) spSkeleton_setSlotsToSetupPose(s);
    if (n) (*e)->ReleaseStringUTFChars(e, name, n);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1setSlotEyeState(JNIEnv* e, jclass c, jint h, jint slot, jint state) {
    (void)e; (void)c; (void)h; (void)slot; (void)state; return JNI_FALSE; /* extension PerBlue — à confirmer sur la lib ARM */
}
/* Contrat du jeu (NativeSkeleton) : STRIDE 6 floats/os = la MATRICE AFFINE monde de l'os
   [a, b, c, d, worldX, worldY] (2x3 : rotation/échelle + translation), PAS [worldX,worldY,rot,sx,sy].
   getBoneTransforms dimensionne count*6 ; son 3ᵉ arg est le NOMBRE d'os (boucle 0..count), PAS un offset.
   (Corrigé pour matcher EXACTEMENT le binaire PerBlue — relevé par le harnais différentiel : unidbg écrivait
   [a,b,c,d,x,y], notre glue écrivait [x,y,rot,sx,sy,0]. Les 2 bugs (layout + stride 7/boucle) étaient tolérés
   sous unidbg — qui exécute le binaire PerBlue, pas notre glue — donc jamais vus avant.) */
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1getBoneTransform(JNIEnv* e, jclass c, jint h, jint boneId, jfloatArray out, jint off) {
    (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h);
    if (!s || boneId < 0 || boneId >= s->bonesCount) return;
    spBone* b = s->bones[boneId];
    jfloat v[6] = { b->a, b->c, b->b, b->d, b->worldX, b->worldY };
    (*e)->SetFloatArrayRegion(e, out, off, 6, v);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1getBoneTransforms(JNIEnv* e, jclass c, jint h, jintArray ids, jint count, jfloatArray out, jint outOff) {
    (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h); if (!s) return;
    jsize len = (*e)->GetArrayLength(e, ids); jint* idp = (*e)->GetIntArrayElements(e, ids, 0);
    if (count > len) count = len;
    for (jint i = 0; i < count; i++) {
        int bid = idp[i]; if (bid < 0 || bid >= s->bonesCount) continue;
        spBone* b = s->bones[bid];
        jfloat v[6] = { b->a, b->c, b->b, b->d, b->worldX, b->worldY };
        (*e)->SetFloatArrayRegion(e, out, outOff + i*6, 6, v);
    }
    (*e)->ReleaseIntArrayElements(e, ids, idp, JNI_ABORT);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1setBoneTransform(JNIEnv* e, jclass c, jint h, jint boneId, jfloat x, jfloat y, jfloat rot, jfloat sx, jfloat sy, jfloat shx, jfloat shy) {
    (void)e; (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h);
    if (!s || boneId < 0 || boneId >= s->bonesCount) return;
    spBone* b = s->bones[boneId];
    b->x = x; b->y = y; b->rotation = rot; b->scaleX = sx; b->scaleY = sy; b->shearX = shx; b->shearY = shy;
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1getPosedBounds(JNIEnv* e, jclass c, jint h, jfloatArray out) {
    (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h); if (!s) return;
    float minX=1e30f, minY=1e30f, maxX=-1e30f, maxY=-1e30f, wv[8];
    for (int i = 0; i < s->slotsCount; i++) {
        spSlot* slot = s->drawOrder[i]; if (!slot->attachment) continue;
        if (slot->attachment->type == SP_ATTACHMENT_REGION) {
            spRegionAttachment_computeWorldVertices((spRegionAttachment*)slot->attachment, slot->bone, wv, 0, 2);
            for (int k=0;k<4;k++){ float X=wv[k*2],Y=wv[k*2+1]; if(X<minX)minX=X; if(X>maxX)maxX=X; if(Y<minY)minY=Y; if(Y>maxY)maxY=Y; }
        }
    }
    if (minX > maxX) { minX=minY=maxX=maxY=0; }
    jfloat v[4] = { minX, minY, maxX-minX, maxY-minY }; (*e)->SetFloatArrayRegion(e, out, 0, 4, v);
}

/* ------------------------------------------------- extraction du maillage 2-couleurs
 * Format sommet du jeu (cf. NativeSkeletonRenderer) : a_position(2f) + a_light(couleur empaquetée
 * ABGR, 1f) + a_dark(couleur empaquetée, 1f) + a_texCoord0(2f) = 6 floats/sommet. Triangles indexés.
 *
 * drawCalls (contrat relevé du renderer Java EN CLAIR NativeSkeletonRenderer.renderInternal) :
 *   n = getVertices(verts, indices, drawCalls);                 // n = nombre de draw calls
 *   for (i in 0..n) { indexCount = drawCalls.get(); pageIdx = drawCalls.get();
 *                     textures.get(pageIdx).bind();
 *                     mesh.render(shader, GL_TRIANGLES, indexStart, indexCount, false);
 *                     indexStart += indexCount; }
 * ⇒ drawCalls = n paires de shorts (indexCount, texturePageIndex). Les triangles étant émis DANS
 *   l'ordre de dessin (draw order), on ouvre un nouveau draw call à chaque changement de page ; les
 *   attachments consécutifs sur la même page fusionnent (indexCount cumulé). getVertices renvoie n. */
/* ETC1 n'a PAS de canal alpha → PerBlue empile l'alpha SOUS le RGB : la texture physique fait 2× la hauteur
 * déclarée dans l'atlas (relevé : PKM = 2048×1024 alors que l'atlas dit « size: 2048,512 » ; RGB en haut,
 * alpha en bas). Le shader du jeu échantillonne le RGB dans la MOITIÉ HAUTE → la coord V doit être ramenée
 * dans [0, 0.5] : v_out = v_atlas × (512/1024) = v_atlas × 0.5. La lib ARM d'origine (oracle unidbg) applique
 * ce facteur dans getVertices (relevé par le harnais différentiel : notre V valait 2× celui de l'oracle, U
 * identique). page->height reste 512 dans les DEUX moteurs (Atlas_getParams identiques) → le facteur est un
 * ×0.5 explicite à l'émission, PAS une hauteur de page différente. (Extraction fidèle, pas une rustine.) */
#define TEXV_SCALE 0.5f
static float packColor(float r, float g, float b, float a) {
    unsigned int ir=(unsigned int)(r*255), ig=(unsigned int)(g*255), ib=(unsigned int)(b*255), ia=(unsigned int)(a*255);
    if(ir>255)ir=255; if(ig>255)ig=255; if(ib>255)ib=255; if(ia>255)ia=255;
    unsigned int c = (ia<<24)|(ib<<16)|(ig<<8)|ir; /* ABGR empaqueté (octets bruts) */
    /* PAS de masque NaN-avoidance (& 0xfeffffff) : la lib ARM d'origine (oracle unidbg) écrit les 4 OCTETS
       BRUTS réinterprétés en float ; le shader les relit en ubyte normalisé → la « NaN-itude » du float est
       sans effet. blanc opaque → 0xFFFFFFFF (=NaN), exactement comme PerBlue (relevé par le harnais différentiel :
       u=NaN vs notre 0xFEFFFFFF masqué). Reproduire l'oracle à l'octet près, pas l'idiome libGDX Color.toFloatBits. */
    float f; memcpy(&f,&c,4); return f;
}

/* Recale un java.nio.Buffer (position=0, limit=n) après remplissage par pointeur direct : le natif
 * écrit en mémoire brute (GetDirectBufferAddress) sans toucher les champs Java position/limit ; or le
 * renderer (chemin VertexArray) fait buffer.position(offset)/limit(offset+count) → il FAUT que limit
 * couvre tout ce qu'on a écrit. On appelle java.nio.Buffer.position/limit (méthodes de base, retour
 * Ljava/nio/Buffer; → descripteur stable toutes versions du JDK). */
static void bufferSetLimit(JNIEnv* e, jobject buf, int n) {
    if (!buf) return;
    static jclass bufCls = 0; static jmethodID mPos = 0, mLim = 0;
    if (!bufCls) {
        jclass c = (*e)->FindClass(e, "java/nio/Buffer");
        bufCls = (jclass)(*e)->NewGlobalRef(e, c);
        mPos = (*e)->GetMethodID(e, bufCls, "position", "(I)Ljava/nio/Buffer;");
        mLim = (*e)->GetMethodID(e, bufCls, "limit", "(I)Ljava/nio/Buffer;");
    }
    jobject r;
    r = (*e)->CallObjectMethod(e, buf, mPos, 0); if (r) (*e)->DeleteLocalRef(e, r);
    r = (*e)->CallObjectMethod(e, buf, mLim, n); if (r) (*e)->DeleteLocalRef(e, r);
}

/* Page d'atlas (0-based, cf. Atlas_create) d'un attachment région/mesh, via spAtlasRegion->page. */
static int attachmentPage(spAttachment* att) {
    spAtlasRegion* reg = 0;
    if (att->type == SP_ATTACHMENT_REGION) reg = (spAtlasRegion*)((spRegionAttachment*)att)->rendererObject;
    else if (att->type == SP_ATTACHMENT_MESH) reg = (spAtlasRegion*)((spMeshAttachment*)att)->rendererObject;
    return (reg && reg->page) ? (int)(intptr_t)reg->page->rendererObject : 0;
}

static int buildVertices(JNIEnv* e, spSkeleton* s, jobject vertsBuf, jobject indicesBuf, jobject drawCallsBuf, float* boundsOut) {
    float* verts = (float*)(*e)->GetDirectBufferAddress(e, vertsBuf);
    short* indices = (short*)(*e)->GetDirectBufferAddress(e, indicesBuf);
    short* draws = drawCallsBuf ? (short*)(*e)->GetDirectBufferAddress(e, drawCallsBuf) : 0;
    if (!verts || !indices) return 0;
    int vi = 0, ii = 0, vertexCount = 0;
    int drawCount = 0, curPage = -1, curDrawIdx = -1;   /* regroupement par page (draw order) */
    float minX=1e30f, minY=1e30f, maxX=-1e30f, maxY=-1e30f, wv[8];
    static const int QUAD[6] = {0,1,2,2,3,0};
    TintEntry* tb = tintFor(s, 0);                                     /* tint black global (setTintBlack) ; base de dark */
    float gtr = tb?tb->r:0, gtg = tb?tb->g:0, gtb = tb?tb->b:0;
    for (int d = 0; d < s->slotsCount; d++) {
        spSlot* slot = s->drawOrder[d]; spAttachment* att = slot->attachment; if (!att) continue;
        if (att->type != SP_ATTACHMENT_REGION && att->type != SP_ATTACHMENT_MESH) continue;
        float lr = s->color.r*slot->color.r, lg = s->color.g*slot->color.g, lb = s->color.b*slot->color.b, la = s->color.a*slot->color.a;
        /* dark = darkColor propre du slot (slot 2-couleurs) si présent, SINON le tint black global (relevé oracle). */
        float dr = slot->darkColor?slot->darkColor->r:gtr, dg = slot->darkColor?slot->darkColor->g:gtg, db = slot->darkColor?slot->darkColor->b:gtb;
        int page = attachmentPage(att);
        int idxAdded = 0;
        if (att->type == SP_ATTACHMENT_REGION) {
            spRegionAttachment* r = (spRegionAttachment*)att;
            spRegionAttachment_computeWorldVertices(r, slot->bone, wv, 0, 2);
            float A = la*r->color.a;                                                    /* alpha totale (squelette×slot×attachment) */
            float pl = packColor(lr*r->color.r, lg*r->color.g, lb*r->color.b, A);        /* light DROITE, rgb NON prémultipliée (relevé hex oracle : 0x00FFFFFF à A=0 → rgb blanc conservé) */
            float pd = packColor(dr, dg, db, 1);                                         /* dark DROITE, alpha=flag PMA=1 (0xFF) */
            for (int k=0;k<4;k++){ float X=wv[k*2],Y=wv[k*2+1];
                verts[vi++]=X; verts[vi++]=Y; verts[vi++]=pl; verts[vi++]=pd; verts[vi++]=r->uvs[k*2]; verts[vi++]=r->uvs[k*2+1]*TEXV_SCALE;
                if(X<minX)minX=X; if(X>maxX)maxX=X; if(Y<minY)minY=Y; if(Y>maxY)maxY=Y; }
            for (int k=0;k<6;k++) indices[ii++] = (short)(vertexCount + QUAD[k]);
            vertexCount += 4; idxAdded = 6;
        } else { /* SP_ATTACHMENT_MESH */
            spMeshAttachment* m = (spMeshAttachment*)att; int n = m->super.worldVerticesLength;
            float* mv = (float*)malloc(sizeof(float)*n);
            spVertexAttachment_computeWorldVertices(&m->super, slot, 0, n, mv, 0, 2);
            float A = la*m->color.a; int nv = n/2;                                       /* alpha totale */
            float pl = packColor(lr*m->color.r, lg*m->color.g, lb*m->color.b, A);        /* light DROITE, rgb NON prémultipliée */
            float pd = packColor(dr, dg, db, 1);                                         /* dark DROITE, alpha=flag PMA=1 (0xFF) */
            for (int k=0;k<nv;k++){ float X=mv[k*2],Y=mv[k*2+1];
                verts[vi++]=X; verts[vi++]=Y; verts[vi++]=pl; verts[vi++]=pd; verts[vi++]=m->uvs[k*2]; verts[vi++]=m->uvs[k*2+1]*TEXV_SCALE;
                if(X<minX)minX=X; if(X>maxX)maxX=X; if(Y<minY)minY=Y; if(Y>maxY)maxY=Y; }
            for (int k=0;k<m->trianglesCount;k++) indices[ii++] = (short)(vertexCount + m->triangles[k]);
            vertexCount += nv; idxAdded = m->trianglesCount; free(mv);
        }
        /* Ouvre un nouveau draw call au changement de page ; sinon cumule dans le courant. */
        if (page != curPage) {
            curPage = page; curDrawIdx = drawCount*2;
            if (draws) { draws[curDrawIdx] = 0; draws[curDrawIdx+1] = (short)page; }
            drawCount++;
        }
        if (draws) draws[curDrawIdx] += (short)idxAdded;
    }
    if (boundsOut) { if (minX>maxX){minX=minY=maxX=maxY=0;} boundsOut[0]=minX; boundsOut[1]=minY; boundsOut[2]=maxX; boundsOut[3]=maxY; }
    /* DIAG (DH_SPINEDBG) : trace tailles/capacités pour diagnostiquer un dépassement de buffer côté renderer. */
    static int dbg = -1; static int dbgN = 0;
    if (dbg < 0) dbg = getenv("DH_SPINEDBG") ? 1 : 0;
    if (dbg && dbgN < 40) {
        long vcap = (*e)->GetDirectBufferCapacity(e, vertsBuf);
        long icap = (*e)->GetDirectBufferCapacity(e, indicesBuf);
        long dcap = drawCallsBuf ? (*e)->GetDirectBufferCapacity(e, drawCallsBuf) : -1;
        fprintf(stderr, "[spinedbg] slots=%d vi=%d(cap%ld float) ii=%d(cap%ld short) drawCount=%d(cap%ld short) sumCounts=",
                s->slotsCount, vi, vcap, ii, icap, drawCount, dcap);
        int sum=0; for (int g=0; g<drawCount && draws; g++) { sum += (unsigned short)draws[g*2]; fprintf(stderr,"%d,", (unsigned short)draws[g*2]); }
        fprintf(stderr, " sum=%d\n", sum);
        dbgN++;
    }
    /* CONTRAT EXACT extrait de NativeSkeleton.getVertices (le jeu, §4) — À REPRODUIRE FIDÈLEMENT (pas de devinette) :
     *   • la valeur de RETOUR native = le nombre d'INDICES (ii), PAS le nombre de draw calls ;
     *   • 2 shorts de MÉTADONNÉES sont écrits APRÈS les indices : indices[ii] = nb de VERTICES (le jeu fait ×6 → limite
     *     du buffer verts), indices[ii+1] = nb de DRAW CALLS (le jeu fait ×2 → limite drawCalls, et renvoie /2) ;
     *   • le JEU recale lui-même les limites (verts/indices/drawCalls) à partir de la valeur de retour + ces métadonnées
     *     → getVertices ne DOIT PAS fixer les limites (comme la lib ARM d'origine, qui laisse les buffers tels quels).
     * (Avant : on renvoyait drawCount et on posait les limites → dépassement de buffer côté renderer sur squelette
     *  multi-pages. Corrigé par extraction du contrat réel.) */
    {
        long icap = (*e)->GetDirectBufferCapacity(e, indicesBuf);
        if (icap >= ii + 2) {                 /* place pour les 2 shorts de métadonnées */
            indices[ii]   = (short)vertexCount;
            indices[ii+1] = (short)drawCount;
        }
    }
    return ii;
}

JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1getVertices(JNIEnv* e, jclass c, jint h, jobject verts, jobject indices, jobject drawCalls) {
    (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h); if (!s) return 0;
    return buildVertices(e, s, verts, indices, drawCalls, 0);
}
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1getVerticesAndBounds(JNIEnv* e, jclass c, jint h, jobject verts, jobject indices, jobject drawCalls, jfloatArray bounds) {
    (void)c; spSkeleton* s = (spSkeleton*)ht_get(&t_skel, h); if (!s) return 0;
    float b[4]; int r = buildVertices(e, s, verts, indices, drawCalls, b);
    if (bounds) (*e)->SetFloatArrayRegion(e, bounds, 0, 4, b);
    return r;
}
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_Skeleton_1getVerticesAndBoundsGlitched(JNIEnv* e, jclass c, jint h, jobject verts, jobject indices, jobject drawCalls, jfloatArray bounds) {
    return Java_com_perblue_heroes_cspine_Native_Skeleton_1getVerticesAndBounds(e, c, h, verts, indices, drawCalls, bounds);
}

/* =============================================================== AnimationStateData */
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_AnimationStateData_1create(JNIEnv* e, jclass c, jint dataHandle, jfloat defaultMix) {
    (void)e; (void)c; spSkeletonData* d = (spSkeletonData*)ht_get(&t_skelData, dataHandle); if (!d) return 0;
    spAnimationStateData* asd = spAnimationStateData_create(d); if (!asd) return 0;
    asd->defaultMix = defaultMix; return ht_add(&t_asd, asd);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_AnimationStateData_1dispose(JNIEnv* e, jclass c, jint h) {
    (void)e; (void)c; spAnimationStateData* a = (spAnimationStateData*)ht_take(&t_asd, h); if (a) spAnimationStateData_dispose(a);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_AnimationStateData_1setMix(JNIEnv* e, jclass c, jint h, jint fromA, jint toA, jfloat dur) {
    (void)e; (void)c; spAnimationStateData* asd = (spAnimationStateData*)ht_get(&t_asd, h); if (!asd) return;
    spSkeletonData* d = asd->skeletonData; /* IDs 1-based */
    if (fromA>=1 && fromA<=d->animationsCount && toA>=1 && toA<=d->animationsCount)
        spAnimationStateData_setMix(asd, d->animations[fromA-1], d->animations[toA-1], dur);
}

/* ---- file d'événements par spAnimationState (contrat NativeAnimationState.eventPump : nextEvent(out) →
   out[0] = type PerBlue (1=start, 2=interrupt, 3=end, 4=complete, 5=dispose, 6=event = spEventType+1),
   out[1] = trackIndex ; renvoie true tant qu'un événement est dépilé). Le runtime spine-c appelle notre
   listener PENDANT apply/update, dans son ordre de drain interne (_spEventQueue) → on empile dans cet
   ordre exact, et le jeu dépile ensuite via eventPump. Sans ça, aucun callback complete/end/… → les
   machines d'état d'animation du jeu ne tournent pas (backend JNI autonome). */
typedef struct { int type, id; } EvItem;
typedef struct { EvItem* buf; int head, tail, cap; int seq; } EvQueue;  /* seq = compteur de trackEntry par animState */
static void evq_push(EvQueue* q, int type, int id) {
    int count = q->cap ? (q->tail - q->head + q->cap) % q->cap : 0;
    if (count + 1 >= q->cap) {                       /* grossit (gère aussi cap==0) ; garde 1 slot libre → full≠empty */
        int nc = q->cap ? q->cap * 2 : 32;
        EvItem* nb = (EvItem*)malloc(nc * sizeof(EvItem));
        int k = 0; if (q->cap) for (int i = q->head; i != q->tail; i = (i+1)%q->cap) nb[k++] = q->buf[i];
        free(q->buf); q->buf = nb; q->head = 0; q->tail = k; q->cap = nc;
    }
    q->buf[q->tail].type = type; q->buf[q->tail].id = id; q->tail = (q->tail + 1) % q->cap;
}
static int evq_pop(EvQueue* q, int* type, int* id) {
    if (!q || q->head == q->tail) return 0;
    *type = q->buf[q->head].type; *id = q->buf[q->head].id; q->head = (q->head + 1) % q->cap; return 1;
}
static void _dhAnimListener(spAnimationState* state, spEventType type, spTrackEntry* entry, spEvent* event) {
    (void)event; EvQueue* q = (EvQueue*)state->rendererObject; if (!q) return;
    evq_push(q, (int)type + 1, entry ? entry->trackIndex : 0);
}

/* =================================================================== AnimationState */
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_AnimationState_1create(JNIEnv* e, jclass c, jint asdHandle) {
    (void)e; (void)c; spAnimationStateData* asd = (spAnimationStateData*)ht_get(&t_asd, asdHandle); if (!asd) return 0;
    spAnimationState* st = spAnimationState_create(asd); if (!st) return 0;
    EvQueue* q = (EvQueue*)calloc(1, sizeof(EvQueue)); st->rendererObject = q; st->listener = _dhAnimListener;
    return ht_add(&t_animState, st);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_AnimationState_1dispose(JNIEnv* e, jclass c, jint h) {
    (void)e; (void)c; spAnimationState* st = (spAnimationState*)ht_take(&t_animState, h);
    /* dispose D'ABORD (spine émet un DISPOSE via le listener → la file doit être encore valide), PUIS libère. */
    if (st) { EvQueue* q = (EvQueue*)st->rendererObject; spAnimationState_dispose(st); if (q) { free(q->buf); free(q); } }
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_AnimationState_1update(JNIEnv* e, jclass c, jint h, jfloat dt) {
    (void)e; (void)c; spAnimationState* st = (spAnimationState*)ht_get(&t_animState, h); if (st) spAnimationState_update(st, dt);
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_AnimationState_1apply(JNIEnv* e, jclass c, jint h, jint skelHandle) {
    (void)e; (void)c; spAnimationState* st = (spAnimationState*)ht_get(&t_animState, h); spSkeleton* s = (spSkeleton*)ht_get(&t_skel, skelHandle);
    if (st && s) spAnimationState_apply(st, s);
}
/* id 1-based (convention PerBlue : 0 = aucune) -> animation spine-c. */
static spAnimation* animOf(spAnimationState* st, int id) {
    spSkeletonData* d = st->data->skeletonData; return (id>=1 && id<=d->animationsCount) ? d->animations[id-1] : 0;
}
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_AnimationState_1setAnimation(JNIEnv* e, jclass c, jint h, jint track, jint animId, jboolean loop) {
    /* Renvoie un COMPTEUR de trackEntry par animState (1-based, incrémenté à chaque set/addAnimation) : c'est
       l'identifiant d'instance d'animation que le jeu combine à eventIDOffset (relevé contre l'oracle unidbg :
       u=1,2,3… par animState, indépendant de l'animId et du track). */
    (void)e; (void)c; spAnimationState* st = (spAnimationState*)ht_get(&t_animState, h); if (!st) return -1;
    spAnimation* a = animOf(st, animId); if (!a) return -1; spAnimationState_setAnimation(st, track, a, loop);
    EvQueue* q = (EvQueue*)st->rendererObject; return q ? ++q->seq : 0;
}
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_AnimationState_1addAnimation(JNIEnv* e, jclass c, jint h, jint track, jint animId, jboolean loop, jfloat delay) {
    (void)e; (void)c; spAnimationState* st = (spAnimationState*)ht_get(&t_animState, h); if (!st) return -1;
    spAnimation* a = animOf(st, animId); if (!a) return -1; spAnimationState_addAnimation(st, track, a, loop, delay);
    EvQueue* q = (EvQueue*)st->rendererObject; return q ? ++q->seq : 0;
}
JNIEXPORT void JNICALL Java_com_perblue_heroes_cspine_Native_AnimationState_1clearTracks(JNIEnv* e, jclass c, jint h) {
    (void)e; (void)c; spAnimationState* st = (spAnimationState*)ht_get(&t_animState, h); if (st) spAnimationState_clearTracks(st);
}
JNIEXPORT jint JNICALL Java_com_perblue_heroes_cspine_Native_AnimationState_1getCurrentAnimationID(JNIEnv* e, jclass c, jint h, jint track) {
    /* 1-based ; 0 = aucune animation courante (le jeu teste `if (id != 0)`, cf. getPrimaryAnimation). */
    (void)e; (void)c; spAnimationState* st = (spAnimationState*)ht_get(&t_animState, h); if (!st) return 0;
    spTrackEntry* t = spAnimationState_getCurrent(st, track); if (!t || !t->animation) return 0;
    spSkeletonData* d = st->data->skeletonData;
    for (int i=0;i<d->animationsCount;i++) if (d->animations[i]==t->animation) return i+1; return 0;
}
JNIEXPORT jfloat JNICALL Java_com_perblue_heroes_cspine_Native_AnimationState_1getCurrentAnimationTime(JNIEnv* e, jclass c, jint h, jint track) {
    (void)e; (void)c; spAnimationState* st = (spAnimationState*)ht_get(&t_animState, h); if (!st) return 0;
    spTrackEntry* t = spAnimationState_getCurrent(st, track); return t ? t->trackTime : 0;
}
JNIEXPORT jboolean JNICALL Java_com_perblue_heroes_cspine_Native_AnimationState_1nextEvent(JNIEnv* e, jclass c, jint h, jintArray out) {
    (void)c; spAnimationState* st = (spAnimationState*)ht_get(&t_animState, h); if (!st) return JNI_FALSE;
    EvQueue* q = (EvQueue*)st->rendererObject; int type, id;
    if (!evq_pop(q, &type, &id)) return JNI_FALSE;
    jint v[2] = { type, id }; (*e)->SetIntArrayRegion(e, out, 0, 2, v); return JNI_TRUE;
}
