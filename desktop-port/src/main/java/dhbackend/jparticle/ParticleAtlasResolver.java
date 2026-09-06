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
 * (cspine.Native.Atlas_create -> {@link #registerAtlasBytes}). On en PARSE les regions (nom, xy, size) et la
 * taille de page pour calculer l'uv. Le PIXEL reel vient de la texture NATIVE (chargee en GL par HostSpine,
 * format ETC2) que le renderer du jeu binde ; le sprite Java ne sert qu'a porter la region/uv + les dimensions.
 * On cree donc une Texture libGDX aux dimensions de la page (contenu neutre) uniquement pour l'uv/geometrie.
 * -> aucun moteur reecrit ; glue de format (SS1/SS4). GL requis (contexte du client) pour la Texture/sprite.
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
    static final class Region { int page, x, y, w, h; }
    static final class Page { String image; int w, h; Texture tex; }
    static final class Atlas {
        final List<Page> pages = new ArrayList<>();
        final Map<String, Region> regions = new HashMap<>();   // 1er index par nom (les particules referencent par nom+index, ici on prend le 1er)
        void dispose(){ for (Page p: pages) if (p.tex != null) p.tex.dispose(); }
    }

    // Parse le format .atlas libGDX (page: image / size / ... ; region: nom / rotate / xy / size / ...).
    static Atlas parse(byte[] bytes) {
        String txt = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = txt.split("\r?\n");
        Atlas a = new Atlas(); Page page = null; Region cur = null; String curName = null; int i = 0;
        while (i < lines.length) {
            String ln = lines[i];
            if (ln.trim().isEmpty()) { page = null; i++; continue; }
            if (page == null) {
                // debut d'une page : ligne image, puis size/format/filter/repeat (avec ':')
                page = new Page(); page.image = ln.trim(); a.pages.add(page);
                i++;
                while (i < lines.length && lines[i].contains(":")) {
                    String[] kv = lines[i].split(":", 2); String k = kv[0].trim();
                    if (k.equals("size")) { int[] xy = ints(kv[1]); page.w = xy[0]; page.h = xy[1]; }
                    i++;
                }
                continue;
            }
            // region : nom (pas d'indentation) suivi de lignes indentees k: v
            if (!ln.startsWith(" ") && !ln.startsWith("\t")) {
                curName = ln.trim(); cur = new Region(); cur.page = a.pages.size() - 1;
                i++;
                boolean first = true;
                while (i < lines.length && (lines[i].startsWith(" ") || lines[i].startsWith("\t"))) {
                    String[] kv = lines[i].split(":", 2); if (kv.length == 2) {
                        String k = kv[0].trim();
                        if (k.equals("xy")) { int[] v = ints(kv[1]); cur.x = v[0]; cur.y = v[1]; }
                        else if (k.equals("size")) { int[] v = ints(kv[1]); cur.w = v[0]; cur.h = v[1]; }
                    }
                    i++;
                }
                // on ne garde que la 1re region par nom (suffisant pour l'uv de reference)
                if (!a.regions.containsKey(curName)) a.regions.put(curName, cur);
                continue;
            }
            i++;
        }
        return a;
    }
    private static int[] ints(String s) { String[] p = s.trim().split("\\s*,\\s*"); return new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) }; }

    // ---- API resolver ----
    @Override public TextureRegion regionFor(int atlasHandle, String atlasTag) {
        AtlasRegion r = atlasRegion(atlasHandle, atlasTag); return r;
    }
    @Override public BaseSprite spriteFor(int atlasHandle, String atlasTag) {
        AtlasRegion r = atlasRegion(atlasHandle, atlasTag); if (r == null) return null;
        try { return new TwoColorAtlasSprite(r); } catch (Throwable t) { return null; }
    }
    // Construit une AtlasRegion libGDX (uv correct via une Texture aux dims de page ; pixels neutres).
    private AtlasRegion atlasRegion(int atlasHandle, String tag) {
        Atlas a = ATLASES.get(atlasHandle); if (a == null || tag == null) return null;
        Region reg = a.regions.get(tag); if (reg == null) return null;
        Page pg = a.pages.get(Math.min(reg.page, a.pages.size() - 1));
        if (pg.tex == null) {
            try { Pixmap pm = new Pixmap(Math.max(1, pg.w), Math.max(1, pg.h), Pixmap.Format.RGBA8888); pg.tex = new Texture(pm); pm.dispose(); }
            catch (Throwable t) { return null; }   // pas de contexte GL (headless) -> null
        }
        AtlasRegion ar = new AtlasRegion(pg.tex, reg.x, reg.y, reg.w, reg.h);
        ar.name = tag; ar.index = -1;
        return ar;
    }
}
