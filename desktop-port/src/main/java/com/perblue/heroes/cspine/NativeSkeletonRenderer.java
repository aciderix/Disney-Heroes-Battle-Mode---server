package com.perblue.heroes.cspine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.perblue.heroes.g2d.BoundingRect;
import com.perblue.heroes.g2d.channels.ShaderChannels;
import com.perblue.heroes.g2d.scene.renderable.BorderedSpineRenderable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Shadow de {@code cspine.NativeSkeletonRenderer} : pose le skeleton (via {@link NativeSkeleton})
 * dans un {@link Mesh} au format sommets **2-couleurs** IDENTIQUE à celui du jeu
 * ({@code a_position, a_light, a_dark, a_texCoord0}) et le dessine avec le shader fourni par
 * {@link ShaderChannels}. Première implémentation (le rendu s'affinera : draw-calls multi-pages,
 * canaux d'effets, teinte sombre). Fidélité : PARTIEL → RÉEL en cours.
 */
public class NativeSkeletonRenderer {
    private static final int MAX_VERTS = 2300, MAX_INDICES = 6900;

    public final Mesh mesh;
    public final ShortBuffer drawCalls;
    public int drawCount;
    public final BoundingRect currentBounds = new BoundingRect();

    private final FloatBuffer vbuf;
    private final ShortBuffer ibuf;
    private final float[] vscratch = new float[MAX_VERTS * NativeSkeleton.VLEN];
    private final short[] iscratch = new short[MAX_INDICES];
    private int indexCount;

    public NativeSkeletonRenderer() {
        // Usages bruts (libGDX de PerBlue) : 1=Position, 4=ColorPacked, 16=TextureCoordinates.
        // Format IDENTIQUE au jeu : a_position(2f) + a_light(couleur empaquetée) +
        // a_dark(couleur empaquetée) + a_texCoord0(2f) = 6 floats/sommet.
        mesh = new Mesh(false, MAX_VERTS, MAX_INDICES,
            new VertexAttribute(1, 2, "a_position"),
            new VertexAttribute(4, 4, "a_light"),
            new VertexAttribute(4, 4, "a_dark"),
            new VertexAttribute(16, 2, "a_texCoord0"));
        vbuf = ByteBuffer.allocateDirect(MAX_VERTS * NativeSkeleton.VLEN * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        ibuf = ByteBuffer.allocateDirect(MAX_INDICES * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        drawCalls = ByteBuffer.allocateDirect(64 * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
    }

    public BoundingRect prepareVerticesWithBounds(NativeSkeleton skel, boolean glitched, BorderedSpineRenderable renderable) {
        prepare(skel, currentBounds);
        return currentBounds;
    }

    public void render(NativeSkeleton skel, ShaderChannels channels) {
        prepare(skel, null);
        renderPreparedVertices(skel, channels);
    }

    public void renderPreparedVertices(NativeSkeleton skel, ShaderChannels channels) {
        if (indexCount == 0 || channels == null || channels.shader == null) return;
        Texture tex = firstTexture(skel);
        if (tex != null) tex.bind(0);
        // Constantes GL brutes (le GL20 de PerBlue a les constantes effacées par ProGuard) :
        // GL_BLEND=0x0BE2, GL_SRC_ALPHA=0x0302, GL_ONE_MINUS_SRC_ALPHA=0x0303, GL_TRIANGLES=0x0004.
        GL20 gl = Gdx.gl;
        gl.glEnable(0x0BE2);
        gl.glBlendFunc(0x0302, 0x0303);
        // Le shader est fourni configuré par le framework (projection/uniforms globaux) ;
        // on dessine le maillage avec. mesh.render lie le shader.
        mesh.render(channels.shader, 0x0004, 0, indexCount, true);
    }

    private void prepare(NativeSkeleton skel, BoundingRect bounds) {
        vbuf.clear(); ibuf.clear(); drawCalls.clear();
        drawCount = skel.getVerticesAndBounds(vbuf, ibuf, drawCalls, bounds != null ? bounds : currentBounds, false);
        int vFloats = vbuf.limit();
        indexCount = ibuf.limit();
        vbuf.get(vscratch, 0, vFloats);
        ibuf.get(iscratch, 0, indexCount);
        if (vFloats > 0) mesh.setVertices(vscratch, 0, vFloats);
        if (indexCount > 0) mesh.setIndices(iscratch, 0, indexCount);
    }

    private Texture firstTexture(NativeSkeleton skel) {
        if (skel == null || skel.data == null || skel.data.atlas == null) return null;
        com.badlogic.gdx.utils.Array ts = skel.data.atlas.getTextures();
        return ts.size > 0 ? (Texture) ts.first() : null;
    }
}
