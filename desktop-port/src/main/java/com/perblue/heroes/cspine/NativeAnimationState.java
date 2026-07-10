package com.perblue.heroes.cspine;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.esotericsoftware.spine.Animation;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationState.TrackEntry;

/**
 * Shadow de {@code cspine.NativeAnimationState} : porte un {@link AnimationState} Spine Java.
 * Les « ID » d'animation = index dans les données ; les événements Spine sont relayés aux
 * listeners du jeu (par index de piste). Fidélité : RÉEL (première implémentation).
 */
public class NativeAnimationState implements AutoCloseable, Disposable {
    public static NativeAnimationState NULL = new NativeAnimationState();

    AnimationState state; // package
    NativeAnimationStateData stateData;
    private final Array<AnimationStateListener> listeners = new Array<>();
    private int eventIDOffset = 0;

    public NativeAnimationState() {}

    public boolean load(NativeAnimationStateData d) {
        this.stateData = d;
        this.state = new AnimationState(d.data);
        this.state.addListener(new AnimationState.AnimationStateAdapter() {
            @Override public void start(TrackEntry e) { fire(0, e.getTrackIndex()); }
            @Override public void interrupt(TrackEntry e) { fire(1, e.getTrackIndex()); }
            @Override public void end(TrackEntry e) { fire(2, e.getTrackIndex()); }
            @Override public void dispose(TrackEntry e) { fire(3, e.getTrackIndex()); }
            @Override public void complete(TrackEntry e) { fire(4, e.getTrackIndex()); }
            @Override public void event(TrackEntry e, com.esotericsoftware.spine.Event ev) { fire(5, e.getTrackIndex() + eventIDOffset); }
        });
        return true;
    }

    private void fire(int kind, int arg) {
        for (int i = 0; i < listeners.size; i++) {
            AnimationStateListener l = (AnimationStateListener) listeners.get(i);
            switch (kind) {
                case 0: l.start(arg); break;
                case 1: l.interrupt(arg); break;
                case 2: l.end(arg); break;
                case 3: l.dispose(arg); break;
                case 4: l.complete(arg); break;
                case 5: l.event(arg); break;
            }
        }
    }

    private Animation anim(int id) {
        if (stateData == null || stateData.skel == null || stateData.skel.data == null) return null;
        if (id < 0 || id >= stateData.skel.data.getAnimations().size) return null;
        return (Animation) stateData.skel.data.getAnimations().get(id);
    }

    public int setAnimation(int track, int animID, boolean loop) {
        Animation a = anim(animID); if (state == null || a == null) return -1;
        state.setAnimation(track, a, loop); return animID;
    }
    public int addAnimation(int track, int animID, boolean loop, float delay) {
        Animation a = anim(animID); if (state == null || a == null) return -1;
        state.addAnimation(track, a, loop, delay); return animID;
    }
    public void clearTracks() { if (state != null) state.clearTracks(); }
    public void update(float dt) { if (state != null) state.update(dt); }
    public void apply(NativeSkeleton skel) { if (state != null && skel != null && skel.skeleton != null) state.apply(skel.skeleton); }

    public int getCurrentAnimID(int track) {
        if (state == null) return -1;
        TrackEntry e = state.getCurrent(track);
        if (e == null || stateData == null || stateData.skel == null || stateData.skel.data == null) return -1;
        return stateData.skel.data.getAnimations().indexOf(e.getAnimation(), true);
    }
    public float getCurrentAnimTime(int track) {
        if (state == null) return 0f;
        TrackEntry e = state.getCurrent(track);
        return e == null ? 0f : e.getAnimationTime();
    }
    public NativeSkeletonData getSkelData() { return stateData != null ? stateData.skel : null; }

    public void addListener(AnimationStateListener l) { if (l != null) listeners.add(l); }
    public void removeListener(AnimationStateListener l) { listeners.removeValue(l, true); }
    public void moveListeners(NativeAnimationState other) {
        if (other == null) return;
        other.listeners.addAll(this.listeners); this.listeners.clear();
    }
    public void setEventIDOffset(int off) { this.eventIDOffset = off; }

    @Override public void close() { dispose(); }
    @Override public void dispose() { state = null; listeners.clear(); }

    public interface AnimationStateListener {
        void start(int i);
        void interrupt(int i);
        void end(int i);
        void dispose(int i);
        void complete(int i);
        void event(int i);
    }

    public abstract static class AnimationStateAdapter implements AnimationStateListener {
        public void start(int i) {}
        public void interrupt(int i) {}
        public void end(int i) {}
        public void dispose(int i) {}
        public void complete(int i) {}
        public void event(int i) {}
    }
}
