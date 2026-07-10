package com.perblue.heroes.cparticle;

import com.badlogic.gdx.graphics.g2d.ParticleEffect;
import com.badlogic.gdx.graphics.g2d.ParticleEmitter;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecteur du format binaire {@code .np} des particules de PerBlue.
 *
 * <p>Le {@code .np} est produit au build par {@code ParticleConverter} via
 * {@code com.badlogic.gdx.graphics.g2d.ParticleEffect.saveBinary(ParticleEffectPacker)} (libGDX
 * MODIFIÉ par PerBlue). Il n'existe PAS de lecteur symétrique dans libGDX (le moteur natif C le
 * lisait au runtime) : ce {@code NpUnpacker} est l'inverse EXACT de {@code saveBinary}, dérivé du
 * bytecode du writer. Il reconstruit un {@link ParticleEffect} libGDX RÉEL, qui possède la
 * simulation ({@code update}) ET le rendu 2-couleurs ({@code drawPositiveDepth/NegativeDepth}) —
 * donc aucune réimplémentation de moteur : on réutilise le moteur de particules du jeu lui-même.
 *
 * <p>Format (mirroir de saveBinary) :
 * <pre>
 *   byte 0, byte 3                                  // magie/version
 *   int emitterCount
 *   emitterCount × ParticleEmitter :
 *     int minParticleCount, int maxParticleCount
 *     29 valeurs (Ranged/Scaled/Numeric/SpawnShape/GradientColor) dans l'ordre des champs
 *     float frameDuration ; bool attached,continuous,aligned ; byte flags ; bool behind
 *     writeTimelines() : int poolSize ; int atlasTagLen ; float[poolSize] pool ; byte[atlasTagLen] atlasTag
 * </pre>
 * Les valeurs Scaled/Gradient écrivent en ligne {@code (stride, idxA, idxB)} référençant le pool ;
 * longueurs déduites des offsets consécutifs (idxB-idxA, /stride). atlasTag = nom de région d'atlas.
 *
 * <p><b>⚠️ EN CHANTIER — NE PAS CÂBLER TEL QUEL.</b> Le mécanisme (magie, compteur de pool, refs de
 * timeline, section pool, tail) est validé octet à octet sur les vrais {@code .np}. MAIS l'octet de
 * version vaut <b>3</b> et le jeu de champs des assets v3 <b>diffère</b> du writer {@code saveBinary}
 * courant de game.jar (analyse : pas de {@code zToYMultiplier}, un dégradé (tint) plus tôt qu'attendu,
 * probablement un 2ᵉ dégradé pour le mode 2-couleurs, et des champs de 7 o non encore attribués).
 * L'ordre EXACT des champs v3 reste à établir (cf. JOURNAL #NP-V3). Tant qu'il n'est pas fixé,
 * l'ordre ci-dessous (writer courant) produit un maillage DÉCALÉ → interdit en prod (pas de « oui oui »).
 * Fidélité VISÉE : RÉEL (réutilise la simulation + rendu 2-couleurs du {@link ParticleEffect} du jeu).
 */
public final class NpUnpacker {
    private NpUnpacker() {}

    /** Un emitter reconstruit + le nom de région d'atlas à lui lier (via loadEmitterImages). */
    public static final class Result {
        public final ParticleEffect effect = new ParticleEffect();
        public final List<String> atlasTags = new ArrayList<>(); // parallèle à effect.getEmitters()
    }

    // Références de timeline à résoudre après lecture du pool (le pool suit les valeurs).
    private static final class Pending {
        final Object value; final String firstField, secondField; final int idxA, idxB, stride;
        Pending(Object v, String f, String s, int a, int b, int st) {
            value = v; firstField = f; secondField = s; idxA = a; idxB = b; stride = st;
        }
    }

    public static Result parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        in.readByte(); in.readByte(); // magie [0,3]
        int emitterCount = in.readInt();
        Result res = new Result();
        for (int e = 0; e < emitterCount; e++) {
            ParticleEmitter em = new ParticleEmitter();
            String tag = readEmitter(in, em);
            res.effect.getEmitters().add(em);
            res.atlasTags.add(tag);
        }
        return res;
    }

    private static String readEmitter(DataInputStream in, ParticleEmitter em) throws IOException {
        List<Pending> pending = new ArrayList<>();
        em.setMinParticleCount(in.readInt());
        em.setMaxParticleCount(in.readInt());

        readRanged(in, field(em, "delayValue"));
        readRanged(in, field(em, "durationValue"));
        readScaled(in, field(em, "emissionValue"), pending);
        readScaled(in, field(em, "lifeValue"), pending);
        readScaled(in, field(em, "lifeOffsetValue"), pending);
        readRanged(in, field(em, "xOffsetValue"));
        readRanged(in, field(em, "yOffsetValue"));
        readRanged(in, field(em, "zOffsetValue"));
        readNumeric(in, field(em, "zToYMultiplierValue"));
        readSpawnShape(in, field(em, "spawnShapeValue"));
        readScaled(in, field(em, "spawnWidthValue"), pending);
        readScaled(in, field(em, "spawnHeightValue"), pending);
        readScaled(in, field(em, "sizeXValue"), pending);
        readScaled(in, field(em, "sizeYValue"), pending);
        readScaled(in, field(em, "velocityValue"), pending);
        readScaled(in, field(em, "velocityZValue"), pending);
        readScaled(in, field(em, "angleValue"), pending);
        readScaled(in, field(em, "rotationValue"), pending);
        readScaled(in, field(em, "windValue"), pending);
        readScaled(in, field(em, "gravityValue"), pending);
        readScaled(in, field(em, "tangentialInfluenceValue"), pending);
        readScaled(in, field(em, "tangentialForceValue"), pending);
        readRanged(in, field(em, "tangentialRadiusValue"));
        readScaled(in, field(em, "centripetalInfluenceValue"), pending);
        readScaled(in, field(em, "centripetalForceValue"), pending);
        readRanged(in, field(em, "centripetalRadiusValue"));
        readScaled(in, field(em, "brownianValue"), pending);
        readGradient(in, field(em, "tintValue"), pending);
        readScaled(in, field(em, "transparencyValue"), pending);

        float frameDuration = in.readFloat();
        boolean attached = in.readBoolean();
        boolean continuous = in.readBoolean();
        boolean aligned = in.readBoolean();
        int flags = in.readByte() & 0xFF;
        boolean behind = in.readBoolean();

        // Section pool de timelines : poolSize, atlasTagLen, pool floats, atlasTag bytes.
        int poolSize = in.readInt();
        int atlasTagLen = in.readInt();
        float[] pool = new float[poolSize];
        for (int i = 0; i < poolSize; i++) pool[i] = in.readFloat();
        byte[] tagBytes = new byte[atlasTagLen];
        in.readFully(tagBytes);
        String atlasTag = new String(tagBytes, StandardCharsets.UTF_8);

        // Résolution des timelines (scaling/colors + timeline) contre le pool.
        for (Pending p : pending) {
            int lenA = p.idxB - p.idxA;
            int lenB = p.stride == 0 ? 0 : lenA / p.stride;
            float[] a = slice(pool, p.idxA, lenA);
            float[] b = slice(pool, p.idxB, lenB);
            setField(p.value, p.firstField, a);
            setField(p.value, p.secondField, b);
        }

        setField(em, "frameDuration", frameDuration);
        setField(em, "attached", attached);
        setField(em, "continuous", continuous);
        setField(em, "aligned", aligned);
        setField(em, "additive", (flags & 1) != 0);
        setField(em, "premultipliedAlpha", (flags & 2) != 0);
        setField(em, "multiply", (flags & 4) != 0);
        setField(em, "behind", behind);
        em.setImagePath(atlasTag);
        return atlasTag;
    }

    // --- lecteurs de valeurs (mirroir des *Value.saveBinary) ---

    private static void readParticleValue(DataInputStream in, Object v) throws IOException {
        setField(v, "active", in.readBoolean());
    }

    private static void readRanged(DataInputStream in, Object v) throws IOException {
        readParticleValue(in, v);
        setField(v, "lowMin", in.readFloat());
        setField(v, "lowMax", in.readFloat());
        setField(v, "lowUsesLinkedRange", in.readBoolean());
    }

    private static void readScaled(DataInputStream in, Object v, List<Pending> pending) throws IOException {
        readRanged(in, v);
        setField(v, "highMin", in.readFloat());
        setField(v, "highMax", in.readFloat());
        setField(v, "highUsesLinkedRange", in.readBoolean());
        setField(v, "relative", in.readBoolean());
        int stride = in.readInt();
        int idxA = in.readInt();
        int idxB = in.readInt();
        pending.add(new Pending(v, "scaling", "timeline", idxA, idxB, stride));
    }

    private static void readNumeric(DataInputStream in, Object v) throws IOException {
        readParticleValue(in, v);
        setField(v, "value", in.readFloat());
    }

    private static void readSpawnShape(DataInputStream in, Object v) throws IOException {
        readParticleValue(in, v);
        int code = in.readByte() & 0xFF;
        // saveBinary : code 3 = ellipse (seul cas avec side/edges). Autres codes = point/line/square
        // (mapping du switch d'origine). On consomme les octets exacts ; forme non-ellipse = point
        // par défaut (cas ultra-majoritaire des particules UI/décor). #NP-SPAWNSHAPE (à affiner).
        String shape = "point";
        if (code == 3) shape = "ellipse";
        else if (code == 1) shape = "square";
        else if (code == 0) shape = "line";
        setEnumField(v, "shape", "com.badlogic.gdx.graphics.g2d.ParticleEmitter$SpawnShape", shape);
        if (code == 3) {
            setField(v, "edges", in.readBoolean());
            int sideCode = in.readByte() & 0xFF;
            String side = sideCode == 1 ? "top" : (sideCode == 2 ? "bottom" : "both");
            setEnumField(v, "side", "com.badlogic.gdx.graphics.g2d.ParticleEmitter$SpawnEllipseSide", side);
        }
    }

    private static void readGradient(DataInputStream in, Object v, List<Pending> pending) throws IOException {
        readParticleValue(in, v);
        int stride = in.readInt();
        int idxA = in.readInt();
        int idxB = in.readInt();
        pending.add(new Pending(v, "colors", "timeline", idxA, idxB, stride));
    }

    // --- utilitaires ---

    private static float[] slice(float[] pool, int off, int len) {
        float[] out = new float[Math.max(0, len)];
        if (len > 0 && off >= 0 && off + len <= pool.length) System.arraycopy(pool, off, out, 0, len);
        return out;
    }

    private static Object field(Object owner, String name) {
        try {
            Field f = findField(owner.getClass(), name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (Exception ex) {
            throw new RuntimeException("champ valeur introuvable: " + name + " sur " + owner.getClass(), ex);
        }
    }

    private static void setField(Object owner, String name, Object value) {
        try {
            Field f = findField(owner.getClass(), name);
            f.setAccessible(true);
            f.set(owner, value);
        } catch (Exception ex) {
            throw new RuntimeException("set champ échoué: " + name + " sur " + owner.getClass(), ex);
        }
    }

    private static void setEnumField(Object owner, String name, String enumClassName, String constant) {
        try {
            Class<?> ec = Class.forName(enumClassName);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object val = Enum.valueOf((Class<Enum>) ec.asSubclass(Enum.class), constant);
            setField(owner, name, val);
        } catch (Exception ex) {
            throw new RuntimeException("set enum échoué: " + name + "=" + constant, ex);
        }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignore) {}
        }
        throw new NoSuchFieldException(name);
    }
}
