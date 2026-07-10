package com.perblue.heroes.cspine;

/** Statistiques d'un skeleton (rapport de diagnostic du jeu). Shadow de la classe cspine.
 *  Rempli au mieux depuis les données Spine Java (valeurs non critiques au fonctionnement). */
public class NativeSkeletonDataStats {
    public int numBones;
    public int numVerts;
    public int numBoneWeights;
    public int numIK;
    public int numAnimatedChannels;
    public int maxAnimatedChannels;
    public int maxBonesPerVert;

    public NativeSkeletonDataStats() {}
}
