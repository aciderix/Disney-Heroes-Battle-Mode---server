package com.perblue.heroes.cspine;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/** Utilitaire cspine (ordre de libération des dépendances). Shadow : version Java. */
public class NativeSpineHelper {
    public static boolean IGNORE_DISPOSAL_ORDER;

    public NativeSpineHelper() {}

    public static void disposeDependents(String name, Array<?> deps) {
        if (deps == null) return;
        for (Object o : deps) {
            if (o instanceof Disposable) {
                try { ((Disposable) o).dispose(); } catch (Throwable ignored) {}
            }
        }
    }
}
