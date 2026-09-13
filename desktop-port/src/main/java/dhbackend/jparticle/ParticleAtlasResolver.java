package dhbackend.jparticle;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BaseSprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.AtlasRegion;
import com.badlogic.gdx.graphics.g2d.TwoColorAtlasSprite;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Resolver d'atlas pour {@link JavaParticleEngine} : donne, pour un (atlasHandle, atlasTag), le sprite d'atlas
 * (TwoColorAtlasSprite) et la region (uv) requis par le ParticleEmitter du jeu.
 *
 * Les octets du fichier .atlas (texte libGDX standard) sont captures a la creation de l'atlas
 * (cspine.Native.Atlas_create -> {@link #registerAtlasBytes}). On en PARSE les regions (nom, xy, size, index) et la
 * taille de page pour calculer l'uv. Le PIXEL reel vient de la texture NATIVE (chargee en GL par HostSpine,
 * format ETC2) que le renderer du jeu binde ; le sprite Java ne sert qu'a porter la region/uv + les dimensions.
 *
 * g303 : FLIPBOOKS. Un effet anime (ex spark_impact, icicle_impact, snow_flake_anim) a PLUSIEURS regions du meme
 * nom avec un champ {@code index:} 0,1,2... Avant on ne gardait que la 1re -> particules figees sur une frame
 * (souvent quasi vide) -> INVISIBLE. Desormais on collecte TOUTES les frames (triees par index) et on expose
 * {@link #framesFor} : {@link JavaParticleEngine} construit une {@code Animation} et appelle {@code em.setAnimation},
 * la simulation du jeu (reutilisee) fait alors defiler {@code particle.region}. (SS3/SS4 : code du jeu, glue de format.)
 */
public final class ParticleAtlasResolver implements JavaParticleEngine.AtlasResolver {

    // ---- registre atlasHandle -> atlas parse ----
    private static final Map<Integer, Atlas> ATLASES = new HashMap<>();
    /** A appeler depuis cspine.Native.Atlas_create(bytes, handle) : memorise le .atlas texte pour ce handle. */
    public static void registerAtlasBytes(int handle, byte[] atlasText) {
        try { ATLASES.put(handle, parse(atlasText)); } catch (Throwable t) { /* pas un atlas texte -> ignore */ }
    }
    public static void unregister(int handle) { Atlas a = ATLASES.remove(handle); if (a != null) a.dispose(); }

    // ---- modele ----
    static final class Region { int page, x, y, w, h, index = -1; }
    static final class Page { String image; int w, h; Texture tex; }
    static final class Atlas {
        final List<Page> pages = new ArrayList<>();
        final Map<String, List<Region>> byName = new HashMap<>();   // TOUTES les frames par nom (triees par index)
        void dispose(){ for (Page p: pages) if (p.tex != null) p.tex.dispose(); }
        List<Region> frames(String name){ return byName.get(name); }
        Region first(String name){ List<Region> l = byName.get(name); return (l == null || l.isEmpty()) ? null : l.get(0); }
    }

    // Parse le format .atlas libGDX (page: image / size / ... ; region: nom / rotate / xy / size / index / ...).
    static Atlas parse(byte[] bytes) {
        String txt = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = txt.split("\r?\n");
        Atlas a = new Atlas(); Page page = null; int i = 0;
        while (i < lines.length) {
            String ln = lines[i];
            if (ln.trim().isEmpty()) { page = null; i++; continue; }
            if (page == null) {
                page = new Page(); page.image = ln.trim(); a.pages.add(page);
                i++;
                while (i < lines.length && lines[i].contains(":")) {
                    String[] kv = lines[i].split(":", 2); String k = kv[0].trim();
                    if (k.equals("size")) { int[] xy = ints(kv[1]); page.w = xy[0]; page.h = xy[1]; }
                    i++;
                }
                continue;
            }
            if (!ln.startsWith(" ") && !ln.startsWith("\t")) {
                String curName = ln.trim(); Region cur = new Region(); cur.page = a.pages.size() - 1;
                i++;
                while (i < lines.length && (lines[i].startsWith(" ") || lines[i].startsWith("\t"))) {
                    String[] kv = lines[i].split(":", 2); if (kv.length == 2) {
                        String k = kv[0].trim();
                        if (k.equals("xy")) { int[] v = ints(kv[1]); cur.x = v[0]; cur.y = v[1]; }
                        else if (k.equals("size")) { int[] v = ints(kv[1]); cur.w = v[0]; cur.h = v[1]; }
                        else if (k.equals("index")) { try { cur.index = Integer.parseInt(kv[1].trim()); } catch (Exception e) {} }
                    }
                    i++;
                }
                a.byName.computeIfAbsent(curName, k -> new ArrayList<>()).add(cur);
                continue;
            }
            i++;
        }
        // trier les frames de chaque nom par index (index -1 = region unique, reste seul)
        for (List<Region> l : a.byName.values()) l.sort(Comparator.comparingInt(r -> r.index));
        return a;
    }
    private static int[] ints(String s) { String[] p = s.trim().split("\\s*,\\s*"); return new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) }; }

    // ---- API resolver ----
    @Override public TextureRegion regionFor(int atlasHandle, String atlasTag) { return atlasRegion(atlasHandle, atlasTag, firstFrame(atlasHandle, atlasTag)); }
    /** Index de PAGE (dans l'ordre de {@code atlas.getTextures()}) de la frame 0 du tag. -1 si absent. */
    public int pageFor(int atlasHandle, String atlasTag) {
        Atlas a = ATLASES.get(atlasHandle); if (a == null || atlasTag == null) return -1;
        Region reg = a.first(atlasTag); return reg == null ? -1 : reg.page;
    }
    @Override public BaseSprite spriteFor(int atlasHandle, String atlasTag) {
        AtlasRegion r = atlasRegion(atlasHandle, atlasTag, firstFrame(atlasHandle, atlasTag)); if (r == null) return null;
        try { return new TwoColorAtlasSprite(r); } catch (Throwable t) { return null; }
    }
    /** g303 : TOUTES les frames du tag (triees par index) comme AtlasRegion[] pour construire l'Animation.
     *  1 seul element si region unique (index -1) -> pas d'animation cote {@link JavaParticleEngine}. */
    @Override public TextureRegion[] framesFor(int atlasHandle, String atlasTag) {
        Atlas a = ATLASES.get(atlasHandle); if (a == null || atlasTag == null) return null;
        List<Region> frames = a.frames(atlasTag); if (frames == null || frames.isEmpty()) return null;
        TextureRegion[] out = new TextureRegion[frames.size()];
        for (int k = 0; k < frames.size(); k++) { out[k] = atlasRegion(atlasHandle, atlasTag, frames.get(k)); if (out[k] == null) return null; }
        return out;
    }
    private Region firstFrame(int atlasHandle, String tag) { Atlas a = ATLASES.get(atlasHandle); return a == null ? null : a.first(tag); }
    // Construit une AtlasRegion libGDX (uv correct via une Texture aux dims de page ; pixels neutres).
    private AtlasRegion atlasRegion(int atlasHandle, String tag, Region reg) {
        Atlas a = ATLASES.get(atlasHandle); if (a == null || tag == null || reg == null) return null;
        Page pg = a.pages.get(Math.min(reg.page, a.pages.size() - 1));
        if (pg.tex == null) {
            try { Pixmap pm = new Pixmap(Math.max(1, pg.w), Math.max(1, pg.h), Pixmap.Format.RGBA8888); pg.tex = new Texture(pm); pm.dispose(); }
            catch (Throwable t) { return null; }   // pas de contexte GL (headless) -> null
        }
        AtlasRegion ar = new AtlasRegion(pg.tex, reg.x, reg.y, reg.w, reg.h);
        ar.name = tag; ar.index = reg.index;
        return ar;
    }
}
