import dhbackend.jparticle.JavaParticleEngine;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BaseSprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Validation headless de l'ADAPTATEUR .np v3 -> ParticleEmitter du jeu ({@link JavaParticleEngine}), sur
 * l'ENSEMBLE des fichiers .np du jeu (pas juste le sous-ensemble de certif). Sans resolver d'atlas (donc sans
 * GL) : create() saute le sprite, la SIMULATION (update) + la géométrie (draw*) du jeu tournent quand même.
 *
 * Pour CHAQUE .np v3 : create -> start -> update 60 frames @ dt=1/60 -> getVertices chaque frame -> vérifie que
 * tous les floats de sommet sont FINIS (pas de NaN/Inf) et bornés. Un mapping v3->Java faux se manifeste par une
 * exception (setPriv échoue / mauvais champ) ou des sommets NaN/Inf/hors-borne -> reporté avec le fichier.
 *
 * Usage : java ... NpAdapterValidate <dir1> [dir2 ...]   (parcourt récursivement les *.np)
 * Sortie : compteurs (total / v3 / chargés / simulés OK / échecs) + liste des échecs (fichier + cause).
 * Code de sortie 0 si 0 échec sur les fichiers v3, sinon 1.
 */
public final class NpAdapterValidate {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { System.out.println("usage: NpAdapterValidate <dir> [dir ...]"); System.exit(2); }
        List<Path> files = new ArrayList<>();
        for (String a : args) {
            Path root = Paths.get(a);
            if (!Files.exists(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.toString().toLowerCase().endsWith(".np")).forEach(files::add);
            }
        }
        // dédoublonne par nom de fichier (les mêmes .np existent en ETC/ETC2/... ; l'adaptateur ne dépend que des octets)
        files.sort(Comparator.comparing(Path::toString));
        System.out.println("fichiers .np trouvés : " + files.size());

        int total=0, notV3=0, loaded=0, simOK=0, fail=0;
        FloatBuffer verts = ByteBuffer.allocateDirect(4*6*4*20000).order(ByteOrder.nativeOrder()).asFloatBuffer();
        ShortBuffer draws = ByteBuffer.allocateDirect(2*8).order(ByteOrder.nativeOrder()).asShortBuffer();
        List<String> fails = new ArrayList<>();
        int maxParticlesSeen = 0;

        // Resolver FACTICE (headless, sans GL) : la simulation ne lit du sprite que ses dimensions (getWidth/
        // getHeight/originX/Y). On fournit un BaseSprite à dims fixes (texture null) → update() tourne sans GL.
        final BaseSprite dummy = new DummySprite();
        JavaParticleEngine.setResolver(new JavaParticleEngine.AtlasResolver() {
            public BaseSprite spriteFor(int h, String tag) { return dummy; }
            public TextureRegion regionFor(int h, String tag) { return null; }   // uv par défaut 0..1
        });

        JavaParticleEngine eng = JavaParticleEngine.get();
        for (Path p : files) {
            total++;
            byte[] b;
            try { b = Files.readAllBytes(p); } catch (Exception e) { continue; }
            if (b.length < 6 || b[0] != 0 || b[1] != 3) { notV3++; continue; }  // pas v3 -> hors périmètre adaptateur
            int id = 0;
            try {
                id = eng.create(b, 0);   // pas de resolver -> pas de sprite/GL, la sim tourne
                loaded++;
                eng.start(id);
                boolean bad = false; String why = null;
                for (int f = 0; f < 60 && !bad; f++) {
                    eng.update(id, 1f/60f);
                    int n = eng.getVertices(id, verts, draws);
                    int limit = verts.limit();
                    // 6 floats/sommet : 0=x,1=y,2=light(couleur PACKÉE),3=dark(couleur PACKÉE),4=u,5=v.
                    // Les couleurs sont des floats bit-packés (procédé libGDX) → magnitude quelconque légitime :
                    // on vérifie seulement qu'elles sont FINIES. x,y,u,v : finies ET bornées (vraies coordonnées).
                    for (int i = 0; i < limit && !bad; i++) {
                        float v = verts.get(i);
                        if (Float.isNaN(v) || Float.isInfinite(v)) { bad = true; why = "sommet NaN/Inf frame " + f + " (comp " + (i%6) + ")"; break; }
                        int comp = i % 6;
                        if (comp != 2 && comp != 3 && Math.abs(v) > 1e7f) { bad = true; why = "coord hors-borne (" + v + ") frame " + f + " (comp " + comp + ")"; break; }
                    }
                    maxParticlesSeen = Math.max(maxParticlesSeen, eng.activeCount(id));
                }
                if (bad) { fail++; fails.add(rel(p) + " : " + why); }
                else simOK++;
            } catch (Throwable t) {
                fail++; fails.add(rel(p) + " : " + t.getClass().getSimpleName() + " " + String.valueOf(t.getMessage()));
            } finally {
                try { if (id != 0) eng.dispose(id); } catch (Throwable ignore) {}
            }
        }
        System.out.println("---- RÉSULTAT ----");
        System.out.println("total          : " + total);
        System.out.println("non-v3 (skip)  : " + notV3);
        System.out.println("v3 chargés     : " + loaded);
        System.out.println("v3 simulés OK  : " + simOK);
        System.out.println("v3 ÉCHECS      : " + fail);
        System.out.println("max particules simultanées vues : " + maxParticlesSeen);
        if (!fails.isEmpty()) {
            System.out.println("---- ÉCHECS (max 60) ----");
            fails.stream().limit(60).forEach(s -> System.out.println("  " + s));
        }
        System.exit(fail == 0 ? 0 : 1);
    }
    private static String rel(Path p) { String s = p.toString(); int i = s.indexOf("Assets"); return i>=0 ? s.substring(i) : p.getFileName().toString(); }

    /** Sprite factice headless : le vrai sprite du jeu EST une TextureRegion (le moteur le caste) → on étend
     *  TextureRegion (uv à 0 par défaut, aucune texture) et on implémente BaseSprite avec des dims fixes. Aucune
     *  ressource GL → suffit à la SIMULATION (update ne lit du sprite que dims/uv/couleur, pas la texture). */
    static final class DummySprite extends TextureRegion implements BaseSprite {
        private final Color c = new Color(1f, 1f, 1f, 1f);
        public float getWidth() { return 64f; }
        public float getHeight() { return 64f; }
        public float getOriginX() { return 32f; }
        public float getOriginY() { return 32f; }
        public Color getColor() { return c; }
        public Texture getTexture() { return null; }
    }
}
