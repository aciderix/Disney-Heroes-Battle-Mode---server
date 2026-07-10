package com.perblue.heroes.cspine;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;
import com.esotericsoftware.spine.Animation;
import com.esotericsoftware.spine.BoneData;
import com.esotericsoftware.spine.SkeletonBinary;
import com.esotericsoftware.spine.SkeletonData;
import com.esotericsoftware.spine.Skin;
import com.esotericsoftware.spine.SlotData;
import com.esotericsoftware.spine.Skin;
import com.esotericsoftware.spine.attachments.AtlasAttachmentLoader;

/**
 * Shadow de {@code cspine.NativeSkeletonData} : porte un {@link SkeletonData} Spine Java (lu du
 * {@code .skel} 3.6 par {@link SkeletonBinary}). Les « ID » entiers du jeu = index dans les
 * tableaux Spine (animations/os), convention stable. Fidélité : RÉEL.
 */
public class NativeSkeletonData implements AutoCloseable, Disposable {
    public static final NativeSkeletonData NULL = new NativeSkeletonData();

    SkeletonData data;      // package
    NativeAtlas atlas;
    String name;

    public NativeSkeletonData() {}

    void set(SkeletonData d, NativeAtlas a, String n) { this.data = d; this.atlas = a; this.name = n; }

    static NativeSkeletonData fromFile(FileHandle skel, NativeAtlas atlas, String name) {
        SkeletonBinary bin = new SkeletonBinary(new AtlasAttachmentLoader(atlas.gdx()));
        SkeletonData d = bin.readSkeletonData(skel);
        if (d.getName() == null) d.setName(name);
        NativeSkeletonData nsd = new NativeSkeletonData();
        nsd.set(d, atlas, name);
        return nsd;
    }

    public boolean load(byte[] bytes, NativeAtlas atlas, String name) {
        // Chemin direct par octets non utilisé : le loader charge depuis le FileHandle
        // (SkeletonBinary lit un FileHandle). Voir NativeSkeletonData.fromFile / le loader.
        return data != null;
    }

    public boolean hasValidHandle() { return data != null; }
    public NativeAtlas getAtlas() { return atlas; }
    public String getName() { return name != null ? name : (data != null ? data.getName() : null); }

    private Animation animAt(int i) { return (Animation) data.getAnimations().get(i); }
    private BoneData boneAt(int i) { return (BoneData) data.getBones().get(i); }
    private Skin skinAt(int i) { return (Skin) data.getSkins().get(i); }
    private SlotData slotAt(int i) { return (SlotData) data.getSlots().get(i); }

    public int getAnimationID(String n) {
        if (data == null) return -1;
        for (int i = 0; i < data.getAnimations().size; i++) if (animAt(i).getName().equals(n)) return i;
        return -1;
    }
    public String getAnimationName(int id) {
        if (data == null || id < 0 || id >= data.getAnimations().size) return null;
        return animAt(id).getName();
    }
    public String[] getAnimationNames() {
        if (data == null) return new String[0];
        String[] out = new String[data.getAnimations().size];
        for (int i = 0; i < out.length; i++) out[i] = animAt(i).getName();
        return out;
    }
    public float getAnimationDuration(int id) {
        if (data == null || id < 0 || id >= data.getAnimations().size) return 0f;
        return animAt(id).getDuration();
    }
    public float[] getAnimationDurations() {
        if (data == null) return new float[0];
        float[] out = new float[data.getAnimations().size];
        for (int i = 0; i < out.length; i++) out[i] = animAt(i).getDuration();
        return out;
    }
    public int getBoneID(String n) {
        if (data == null) return -1;
        for (int i = 0; i < data.getBones().size; i++) if (boneAt(i).getName().equals(n)) return i;
        return -1;
    }
    public String[] getSkinNames() {
        if (data == null) return new String[0];
        String[] out = new String[data.getSkins().size];
        for (int i = 0; i < out.length; i++) out[i] = skinAt(i).getName();
        return out;
    }
    public String[] getSlotNames() {
        if (data == null) return new String[0];
        String[] out = new String[data.getSlots().size];
        for (int i = 0; i < out.length; i++) out[i] = slotAt(i).getName();
        return out;
    }
    public NativeSkeletonDataStats getStatReport() {
        NativeSkeletonDataStats s = new NativeSkeletonDataStats();
        if (data != null) { s.numBones = data.getBones().size; }
        return s;
    }
    public VertexWeightReportEntry[] getWeightReport(int i) { return new VertexWeightReportEntry[0]; }

    @Override public void close() { dispose(); }
    @Override public void dispose() { data = null; }
}
