import com.badlogic.gdx.files.FileHandle;
import com.esotericsoftware.spine.*;
import com.esotericsoftware.spine.attachments.*;
import java.io.File;

/**
 * Test de dé-risque #SPINE : prouve que le runtime Java standard {@code spine-libgdx 3.6.53.1}
 * lit les fichiers d'animation {@code .skel} DU JEU (Spine 3.6.41). Valide l'option A (remplacer
 * le natif cspine par le runtime Spine Java). Voir desktop-port/SPINE_FEASIBILITY.md.
 *
 * Usage : java -cp spine-libgdx.jar:gdx.jar:. SpineParseTest <chemin.skel>
 * (AttachmentLoader minimal : on valide le PARSE de la structure, sans charger de textures.)
 */
public class SpineParseTest {
  public static void main(String[] a) throws Exception {
    File f = new File(a[0]);
    AttachmentLoader loader = new AttachmentLoader() {
      public RegionAttachment newRegionAttachment(Skin s, String n, String p) { return new RegionAttachment(n); }
      public MeshAttachment newMeshAttachment(Skin s, String n, String p) { return new MeshAttachment(n); }
      public BoundingBoxAttachment newBoundingBoxAttachment(Skin s, String n) { return new BoundingBoxAttachment(n); }
      public ClippingAttachment newClippingAttachment(Skin s, String n) { return new ClippingAttachment(n); }
      public PathAttachment newPathAttachment(Skin s, String n) { return new PathAttachment(n); }
      public PointAttachment newPointAttachment(Skin s, String n) { return new PointAttachment(n); }
    };
    SkeletonData data = new SkeletonBinary(loader).readSkeletonData(new FileHandle(f));
    System.out.println("VERSION .skel = " + data.getVersion());
    System.out.println("bones=" + data.getBones().size + " slots=" + data.getSlots().size
        + " skins=" + data.getSkins().size + " animations=" + data.getAnimations().size);
    StringBuilder anims = new StringBuilder();
    for (int i = 0; i < data.getAnimations().size; i++) anims.append(data.getAnimations().get(i).getName()).append(' ');
    System.out.println("anims: " + anims);
    System.out.println("SPINE PARSE OK (spine-libgdx 3.6 lit le .skel du jeu)");
  }
}
