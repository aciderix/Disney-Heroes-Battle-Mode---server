package com.perblue.heroes.cspine;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import com.esotericsoftware.spine.Bone;
import com.esotericsoftware.spine.Skeleton;
import com.esotericsoftware.spine.Slot;
import com.esotericsoftware.spine.attachments.Attachment;
import com.esotericsoftware.spine.attachments.MeshAttachment;
import com.esotericsoftware.spine.attachments.RegionAttachment;
import com.perblue.heroes.g2d.BoundingRect;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Shadow de {@code cspine.NativeSkeleton} : porte un {@link Skeleton} Spine Java. Pose/anim
 * via spine-libgdx ; {@link #getVertices} extrait le maillage posé au format sommets 2-couleurs
 * du jeu (position, light RGBA, dark RGBA, uv = 12 floats/sommet), consommé par
 * {@link NativeSkeletonRenderer}. Format d'échange interne à dhcspine (on contrôle les 2 côtés).
 * Fidélité : RÉEL (première implémentation ; le rendu s'affinera).
 */
public class NativeSkeleton implements AutoCloseable, Disposable {
    static final int VLEN = 6; // x, y, packedLight, packedDark, u, v  (couleurs empaquetées, format du jeu)

    Skeleton skeleton; // package
    NativeSkeletonData data;
    private final Color tmpLight = new Color();
    private final float[] regionVerts = new float[8];
    private float[] meshVerts = new float[256];
    private final float dr = 0f, dg = 0f, db = 0f; // dark par défaut

    public NativeSkeleton() {}

    public boolean load(NativeSkeletonData d) {
        this.data = d;
        this.skeleton = new Skeleton(d.data);
        this.skeleton.setToSetupPose();
        this.skeleton.updateWorldTransform();
        return true;
    }

    public boolean isValid() { return skeleton != null; }
    public NativeSkeletonData getSkelData() { return data; }

    public void update(float dt) { if (skeleton != null) skeleton.update(dt); }
    public void updateWorldTransform() { if (skeleton != null) skeleton.updateWorldTransform(); }
    public void setToSetupPose() { if (skeleton != null) skeleton.setToSetupPose(); }

    public void setColor(float r, float g, float b, float a) { if (skeleton != null) skeleton.getColor().set(r, g, b, a); }
    public void setColor(Color c) { if (skeleton != null) skeleton.getColor().set(c); }
    public void setTintBlackDefault(float r, float g, float b) { /* dark tint par défaut : à affiner */ }
    public void setTintBlackDefault(Color c) { }

    public boolean setSkin(String name) {
        if (skeleton == null) return false;
        try { skeleton.setSkin(name); skeleton.setSlotsToSetupPose(); return true; }
        catch (Exception e) { return false; }
    }
    public int getActiveSkinID() { return 0; }
    public String getActiveSkinName() {
        return skeleton != null && skeleton.getSkin() != null ? skeleton.getSkin().getName() : null;
    }
    public boolean setSlotEyeState(int slot, int state) { return false; }

    // --- transforms d'os ---
    public float[] getBoneTransform(int id) { float[] o = new float[7]; getBoneTransform(id, o, 0); return o; }
    public void getBoneTransform(int id, float[] out, int off) {
        if (skeleton == null || id < 0 || id >= skeleton.getBones().size) return;
        Bone b = (Bone) skeleton.getBones().get(id);
        out[off] = b.getWorldX(); out[off+1] = b.getWorldY();
        out[off+2] = b.getWorldRotationX(); out[off+3] = b.getWorldScaleX(); out[off+4] = b.getWorldScaleY();
        out[off+5] = 0f; out[off+6] = 0f;
    }
    public void getBoneTransforms(int[] ids, int idOff, float[] out, int outOff) {
        for (int i = 0; i < ids.length - idOff; i++) getBoneTransform(ids[idOff+i], out, outOff + i*7);
    }
    public void getBoneTransforms(IntArray ids, FloatArray out) {
        out.clear();
        for (int i = 0; i < ids.size; i++) { float[] t = getBoneTransform(ids.get(i)); for (float v : t) out.add(v); }
    }
    public void setBoneTransform(int id, float x, float y, float rot, float sx, float sy, float shx, float shy) {
        if (skeleton == null || id < 0 || id >= skeleton.getBones().size) return;
        Bone b = (Bone) skeleton.getBones().get(id);
        b.setX(x); b.setY(y); b.setRotation(rot); b.setScaleX(sx); b.setScaleY(sy);
    }

    public void getPosedBounds(Rectangle r) {
        FloatBuffer none = null;
        BoundingRect br = new BoundingRect();
        computeBounds(br);
        r.set(br.minX, br.minY, br.maxX - br.minX, br.maxY - br.minY);
    }

    // --- extraction du maillage posé (format 2-couleurs) ---
    public int getVertices(FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls) {
        return build(verts, indices, drawCalls, null);
    }
    public int getVerticesAndBounds(FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, BoundingRect bounds, boolean glitched) {
        return build(verts, indices, drawCalls, bounds);
    }

    private int build(FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, BoundingRect bounds) {
        if (skeleton == null) return 0;
        verts.clear(); indices.clear(); if (drawCalls != null) drawCalls.clear();
        if (bounds != null) bounds.clr();
        Array order = skeleton.getDrawOrder();
        int vertexCount = 0;   // sommets écrits
        int drawCount = 0;
        Color skelColor = skeleton.getColor();
        for (int s = 0; s < order.size; s++) {
            Slot slot = (Slot) order.get(s);
            Attachment att = slot.getAttachment();
            float[] uvs; short[] tris; int numVerts;
            if (att instanceof RegionAttachment) {
                RegionAttachment ra = (RegionAttachment) att;
                ra.computeWorldVertices(slot.getBone(), regionVerts, 0, 2);
                uvs = ra.getUVs(); tris = QUAD; numVerts = 4;
                emit(verts, indices, regionVerts, uvs, tris, numVerts, vertexCount, slot, ra.getColor(), skelColor, bounds);
            } else if (att instanceof MeshAttachment) {
                MeshAttachment ma = (MeshAttachment) att;
                int n = ma.getWorldVerticesLength();
                if (meshVerts.length < n) meshVerts = new float[n];
                ma.computeWorldVertices(slot, 0, n, meshVerts, 0, 2);
                uvs = ma.getUVs(); tris = ma.getTriangles(); numVerts = n / 2;
                emit(verts, indices, meshVerts, uvs, tris, numVerts, vertexCount, slot, ma.getColor(), skelColor, bounds);
            } else { continue; }
            vertexCount += numVerts;
            drawCount++;
        }
        verts.flip(); indices.flip(); if (drawCalls != null) { drawCalls.put((short) indices.limit()); drawCalls.flip(); }
        return drawCount;
    }

    private void emit(FloatBuffer verts, ShortBuffer indices, float[] xy, float[] uvs, short[] tris, int numVerts,
                      int baseVertex, Slot slot, Color attColor, Color skelColor, BoundingRect bounds) {
        Color sc = slot.getColor();
        float lr = skelColor.r * sc.r * attColor.r;
        float lg = skelColor.g * sc.g * attColor.g;
        float lb = skelColor.b * sc.b * attColor.b;
        float la = skelColor.a * sc.a * attColor.a;
        float packedLight = Color.toFloatBits(lr, lg, lb, la);
        float packedDark = Color.toFloatBits(dr, dg, db, 0f);
        for (int i = 0; i < numVerts; i++) {
            float x = xy[i*2], y = xy[i*2+1];
            verts.put(x); verts.put(y);
            verts.put(packedLight); verts.put(packedDark);
            verts.put(uvs[i*2]); verts.put(uvs[i*2+1]);
            if (bounds != null) bounds.ext(x, y);
        }
        for (int t = 0; t < tris.length; t++) indices.put((short) (baseVertex + tris[t]));
    }

    private void computeBounds(BoundingRect br) {
        // pose bounds via une passe sans écrire (réutilise build avec buffers jetables)
        if (skeleton == null) return;
        Array order = skeleton.getDrawOrder();
        for (int s = 0; s < order.size; s++) {
            Slot slot = (Slot) order.get(s);
            Attachment att = slot.getAttachment();
            if (att instanceof RegionAttachment) {
                ((RegionAttachment) att).computeWorldVertices(slot.getBone(), regionVerts, 0, 2);
                for (int i = 0; i < 4; i++) br.ext(regionVerts[i*2], regionVerts[i*2+1]);
            } else if (att instanceof MeshAttachment) {
                MeshAttachment ma = (MeshAttachment) att;
                int n = ma.getWorldVerticesLength();
                if (meshVerts.length < n) meshVerts = new float[n];
                ma.computeWorldVertices(slot, 0, n, meshVerts, 0, 2);
                for (int i = 0; i < n/2; i++) br.ext(meshVerts[i*2], meshVerts[i*2+1]);
            }
        }
    }

    private static final short[] QUAD = {0, 1, 2, 2, 3, 0};

    @Override public void close() { dispose(); }
    @Override public void dispose() { skeleton = null; }
}
