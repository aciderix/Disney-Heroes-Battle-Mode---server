package com.perblue.heroes.cspine;

import com.badlogic.gdx.utils.Disposable;
import com.esotericsoftware.spine.Animation;
import com.esotericsoftware.spine.AnimationStateData;

/** Shadow de {@code cspine.NativeAnimationStateData} : porte un {@link AnimationStateData} Spine
 *  Java (durées de mix entre animations). Fidélité : RÉEL. */
public class NativeAnimationStateData implements AutoCloseable, Disposable {
    public static final NativeAnimationStateData NULL = new NativeAnimationStateData();

    AnimationStateData data; // package
    NativeSkeletonData skel;

    public NativeAnimationStateData() {}

    public boolean load(NativeSkeletonData d, float defaultMix) {
        this.skel = d;
        this.data = new AnimationStateData(d.data);
        this.data.setDefaultMix(defaultMix);
        return true;
    }

    public NativeSkeletonData getSkelData() { return skel; }

    public void setMix(int fromID, int toID, float duration) {
        if (data == null || skel == null) return;
        Animation from = anim(fromID), to = anim(toID);
        if (from != null && to != null) data.setMix(from, to, duration);
    }
    public void setMix(String from, String to, float duration) {
        if (data != null) try { data.setMix(from, to, duration); } catch (Exception ignored) {}
    }

    private Animation anim(int id) {
        if (skel == null || skel.data == null || id < 0 || id >= skel.data.getAnimations().size) return null;
        return (Animation) skel.data.getAnimations().get(id);
    }

    @Override public void close() { dispose(); }
    @Override public void dispose() { data = null; }
}
