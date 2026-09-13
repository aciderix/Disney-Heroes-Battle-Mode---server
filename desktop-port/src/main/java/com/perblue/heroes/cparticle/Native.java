package com.perblue.heroes.cparticle;

import dhbackend.unidbg.UnidbgVM;
import dhbackend.jparticle.JavaParticleEngine;
import dhbackend.jnispine.AtlasBridge;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * SHADOW de {@code com.perblue.heroes.cparticle.Native}. Deux backends :
 *  - DÉFAUT : moteur d'origine (`libspine-native.so`) via unidbg ({@link dhbackend.unidbg.UnidbgVM}).
 *  - {@code -Ddh.particlebackend=java} + resolver d'atlas enregistré : le VRAI moteur Java du jeu
 *    (`com.badlogic.gdx.graphics.g2d.ParticleEmitter`) via {@link dhbackend.jparticle.JavaParticleEngine}
 *    — pas d'émulation, pas de réimplémentation (docs/PARTICLE_REUSE.md, §3/§4). Câblage, pas récréation.
 *
 * MODE COMPARAISON (diag, {@code -Ddh.jparticle.compare=1}, actif seulement en backend java) : chaque appel
 * est DUPLIQUÉ vers unidbg (create/clone/start/update/position/rotation en lockstep) ; sur getVertices on
 * appelle LES DEUX moteurs et on diffe les sommets (position/light/dark/uv/nb) — l'oracle unidbg étant
 * PROUVÉ correct EN JEU (bisection g300), le composant qui diverge est le bug exact du moteur java. Le rendu
 * reste alimenté par java (on voit toujours le bug), la comparaison n'écrit que dans des buffers scratch.
 */
public class Native {
    static {
        // g296 : backend particules Java -> enregistre le resolver d'atlas (parse le .atlas, uv ; sprite GL lazy).
        if (JavaParticleEngine.flagJava())
            try { JavaParticleEngine.setResolver(new dhbackend.jparticle.ParticleAtlasResolver()); } catch (Throwable ignore) {}
    }
    private static boolean jpe() { return JavaParticleEngine.enabled(); }
    public static void ensureLoaded() { UnidbgVM.get(); }

    // ---- mode comparaison java<->unidbg (diag) ----
    private static final boolean CMP = "1".equals(System.getProperty("dh.jparticle.compare"));
    private static final java.util.Map<Integer,Integer> J2U = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object CMPLOCK = new Object();
    private static FloatBuffer uVerts;   // scratch (alloués à la 1re diff)
    private static ShortBuffer uDraws;
    private static final java.util.Set<String> cmpSeen = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static boolean cmpActive() { return CMP && jpe(); }
    private static Integer uh(int jHandle) { return J2U.get(jHandle); }

    public static String getLastParticleError() { return UnidbgVM.get().getLastParticleError(); }
    static int Effect_create(byte[] npBytes, int atlasHandle) {
        if (jpe()) {
            int jh = JavaParticleEngine.get().create(npBytes, atlasHandle);
            if (cmpActive()) try { int u = UnidbgVM.get().effectCreate(npBytes, AtlasBridge.toUnidbg(atlasHandle)); if (u != 0) J2U.put(jh, u); } catch (Throwable t) {}
            return jh;
        }
        return UnidbgVM.get().effectCreate(npBytes, AtlasBridge.toUnidbg(atlasHandle));
    }
    static int Effect_clone(int handle) {
        if (jpe()) {
            int jc = JavaParticleEngine.get().clone(handle);
            if (cmpActive()) { Integer u = uh(handle); if (u != null) try { int uc = UnidbgVM.get().effectClone(u); if (uc != 0) J2U.put(jc, uc); } catch (Throwable t) {} }
            return jc;
        }
        return UnidbgVM.get().effectClone(handle);
    }
    static void Effect_dispose(int handle) {
        if (jpe()) { JavaParticleEngine.get().dispose(handle); if (cmpActive()) { Integer u = uh(handle); if (u != null) try { UnidbgVM.get().effectDispose(u); } catch (Throwable t) {} } return; }
        UnidbgVM.get().effectDispose(handle);
    }
    private static final boolean RENDER_UNIDBG = "1".equals(System.getProperty("dh.jparticle.renderunidbg"));
    private static long ruUsed=0, ruFallback=0;
    static int Effect_getVertices(int handle, FloatBuffer verts, ShortBuffer drawCalls) {
        if (jpe()) {
            callGV++;
            // TEST data-vs-pipeline : rendre les sommets UNIDBG (corrects) à travers la session java-active.
            if (RENDER_UNIDBG && cmpActive()) { Integer u = uh(handle);
                if (u != null) { try { int un = UnidbgVM.get().effectGetVertices(u, verts, drawCalls); ruUsed++; if (ruUsed%200==1) System.err.println("[RU] rendu UNIDBG used="+ruUsed+" fallback="+ruFallback+" (dernier n="+un+")"); return un; } catch (Throwable t) { ruFallback++; } }
                else { ruFallback++; if (ruFallback%200==1) System.err.println("[RU] FALLBACK java (handle unidbg absent) used="+ruUsed+" fallback="+ruFallback); } }
            int jn = JavaParticleEngine.get().getVertices(handle, verts, drawCalls); if (cmpActive()) diff(handle, verts, drawCalls, jn, 0); return jn;
        }
        return UnidbgVM.get().effectGetVertices(handle, verts, drawCalls);
    }
    static long callGV=0, callAbove=0, callBelow=0;   // diag : quelle méthode le combat utilise
    static int Effect_getVerticesAboveZ(int handle, float z, FloatBuffer verts, ShortBuffer drawCalls) {
        if (jpe()) {
            callAbove++; if (callAbove%300==1) System.err.println("[PATH] getVertices="+callGV+" AboveZ="+callAbove+" BelowZ="+callBelow);
            if (RENDER_UNIDBG && cmpActive()) { Integer u = uh(handle); if (u != null) { try { return UnidbgVM.get().effectGetVerticesAboveZ(u, z, verts, drawCalls); } catch (Throwable t) {} } }
            int jn = JavaParticleEngine.get().getVertices(handle, verts, drawCalls); if (cmpActive()) diff(handle, verts, drawCalls, jn, 1); return jn;
        }
        return UnidbgVM.get().effectGetVerticesAboveZ(handle, z, verts, drawCalls);
    }
    static int Effect_getVerticesBelowZ(int handle, float z, FloatBuffer verts, ShortBuffer drawCalls) {
        if (jpe()) {
            callBelow++;
            if (RENDER_UNIDBG && cmpActive()) { Integer u = uh(handle); if (u != null) { try { return UnidbgVM.get().effectGetVerticesBelowZ(u, z, verts, drawCalls); } catch (Throwable t) {} } }
            return 0;
        }
        return UnidbgVM.get().effectGetVerticesBelowZ(handle, z, verts, drawCalls);
    }
    static void Effect_start(int handle) { if (jpe()) { JavaParticleEngine.get().start(handle); if (cmpActive()) { Integer u = uh(handle); if (u != null) try { UnidbgVM.get().effectStart(u); } catch (Throwable t) {} } return; } UnidbgVM.get().effectStart(handle); }
    static void Effect_reset(int handle) { if (jpe()) { JavaParticleEngine.get().start(handle); if (cmpActive()) { Integer u = uh(handle); if (u != null) try { UnidbgVM.get().effectReset(u); } catch (Throwable t) {} } return; } UnidbgVM.get().effectReset(handle); }
    static void Effect_kill(int handle) { if (jpe()) { JavaParticleEngine.get().dispose(handle); if (cmpActive()) { Integer u = uh(handle); if (u != null) try { UnidbgVM.get().effectKill(u); } catch (Throwable t) {} } return; } UnidbgVM.get().effectKill(handle); }
    static void Effect_stopEmitting(int handle) { if (jpe()) { if (cmpActive()) { Integer u = uh(handle); if (u != null) try { UnidbgVM.get().effectStopEmitting(u); } catch (Throwable t) {} } return; } UnidbgVM.get().effectStopEmitting(handle); }
    static void Effect_setPositionXY(int handle, float x, float y) { if (jpe()) { JavaParticleEngine.get().setPosition(handle, x, y); if (cmpActive()) { Integer u = uh(handle); if (u != null) try { UnidbgVM.get().effectSetPositionXY(u, x, y); } catch (Throwable t) {} } return; } UnidbgVM.get().effectSetPositionXY(handle, x, y); }
    static void Effect_setPositionXYZ(int handle, float x, float y, float z) { if (jpe()) { JavaParticleEngine.get().setPosition(handle, x, y); if (cmpActive()) { Integer u = uh(handle); if (u != null) try { UnidbgVM.get().effectSetPositionXYZ(u, x, y, z); } catch (Throwable t) {} } return; } UnidbgVM.get().effectSetPositionXYZ(handle, x, y, z); }
    static void Effect_setRotation(int handle, float rot) { if (jpe()) { JavaParticleEngine.get().setRotation(handle, rot); if (cmpActive()) { Integer u = uh(handle); if (u != null) try { UnidbgVM.get().effectSetRotation(u, rot); } catch (Throwable t) {} } return; } UnidbgVM.get().effectSetRotation(handle, rot); }
    static void Effect_setScale(int handle, float scale) { if (jpe()) { if (cmpActive()) { Integer u = uh(handle); if (u != null) try { UnidbgVM.get().effectSetScale(u, scale); } catch (Throwable t) {} } return; } UnidbgVM.get().effectSetScale(handle, scale); }
    static boolean Effect_update(int handle, float dt) {
        if (jpe()) { boolean r = JavaParticleEngine.get().update(handle, dt); if (cmpActive()) { Integer u = uh(handle); if (u != null) try { UnidbgVM.get().effectUpdate(u, dt); } catch (Throwable t) {} } return r; }
        return UnidbgVM.get().effectUpdate(handle, dt);
    }
    static boolean Effect_usesMultiply(int handle) { if (jpe()) return false; return UnidbgVM.get().effectUsesMultiply(handle); }
    static boolean Effect_usesZOffsets(int handle) { if (jpe()) return false; return UnidbgVM.get().effectUsesZOffsets(handle); }

    // Diff sommets java (déjà écrits dans verts/drawCalls, flippés) vs unidbg (scratch). Log 1×/tag combat.
    private static void diff(int jHandle, FloatBuffer jVerts, ShortBuffer jDraws, int jn, int variant) {
        Integer u = uh(jHandle);
        if (u == null) return;
        String tag = JavaParticleEngine.get().tagOf(jHandle);
        if (tag == null || !tag.matches("(?i).*(snow|flake|punch|impact|splash|ice|energy|frost|snowball|blast|spark|mist).*")) return;
        String key = tag + "|" + variant;
        synchronized (CMPLOCK) {
            if (cmpSeen.contains(key)) return;
            try {
                if (uVerts == null) { uVerts = ByteBuffer.allocateDirect(4000 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); uDraws = ByteBuffer.allocateDirect(6001 * 2).order(ByteOrder.nativeOrder()).asShortBuffer(); }
                uVerts.clear(); uDraws.clear();
                int un = (variant == 1) ? UnidbgVM.get().effectGetVerticesAboveZ(u, 1e9f, uVerts, uDraws)
                                        : UnidbgVM.get().effectGetVertices(u, uVerts, uDraws);
                // nb sommets total : drawCalls.get(n*3) (flippé, lecture absolue)
                int jvc = (jn > 0 && jDraws != null && jDraws.limit() > jn * 3) ? (jDraws.get(jn * 3) & 0xffff) : 0;
                int uvc = (un > 0 && uDraws.limit() > un * 3) ? (uDraws.get(un * 3) & 0xffff) : 0;
                // on n'imprime que quand AU MOINS un des deux a des sommets (effet actif) — sinon on retente + tard
                if (jvc == 0 && uvc == 0) return;
                cmpSeen.add(key);
                System.err.println("[CMP] ===== tag='" + tag + "' variant=" + (variant == 1 ? "AboveZ" : "all")
                    + " | JAVA drawCalls=" + jn + " verts=" + jvc + "  ||  UNIDBG drawCalls=" + un + " verts=" + uvc + " =====");
                System.err.println("[CMP]   JAVA   " + vtx(jVerts, jvc, jDraws, jn));
                System.err.println("[CMP]   UNIDBG " + vtx(uVerts, uvc, uDraws, un));
            } catch (Throwable t) { System.err.println("[CMP] échec tag='" + tag + "' : " + t); }
        }
    }
    // Décrit le 1er sommet + bbox + 1er draw-call d'un buffer flippé (lecture absolue, ne bouge pas la position).
    private static String vtx(FloatBuffer v, int vc, ShortBuffer d, int n) {
        if (vc <= 0) return "(aucun sommet)";
        int lb = Float.floatToRawIntBits(v.get(2)), db = Float.floatToRawIntBits(v.get(3));
        float minx = 1e9f, maxx = -1e9f, miny = 1e9f, maxy = -1e9f, minu = 1e9f, maxu = -1e9f, minw = 1e9f, maxw = -1e9f;
        for (int i = 0; i < vc; i++) { int o = i * 6;
            float x = v.get(o), y = v.get(o + 1), uu = v.get(o + 4), ww = v.get(o + 5);
            if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y;
            if (uu < minu) minu = uu; if (uu > maxu) maxu = uu; if (ww < minw) minw = ww; if (ww > maxw) maxw = ww; }
        String dc = "";
        if (d != null && n > 0 && d.limit() >= 3) dc = " draw0=[idx=" + (d.get(0) & 0xffff) + " blend=" + (d.get(1) & 0xffff) + " page=" + (d.get(2) & 0xffff) + "]";
        return "v0=(" + v.get(0) + "," + v.get(1) + ") light=RGBA(" + (lb & 0xff) + "," + ((lb >>> 8) & 0xff) + "," + ((lb >>> 16) & 0xff) + ",a=" + ((lb >>> 24) & 0xff) + ")"
             + " dark=RGBA(" + (db & 0xff) + "," + ((db >>> 8) & 0xff) + "," + ((db >>> 16) & 0xff) + ",a=" + ((db >>> 24) & 0xff) + ")"
             + " uv0=(" + v.get(4) + "," + v.get(5) + ")"
             + " bbox=[" + (int) minx + "," + (int) miny + ".." + (int) maxx + "," + (int) maxy + "]"
             + " u=[" + String.format("%.3f", minu) + ".." + String.format("%.3f", maxu) + "] v=[" + String.format("%.3f", minw) + ".." + String.format("%.3f", maxw) + "]" + dc;
    }
}
