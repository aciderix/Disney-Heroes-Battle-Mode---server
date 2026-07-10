import com.perblue.heroes.cparticle.NpUnpacker;
import com.badlogic.gdx.graphics.g2d.ParticleEmitter;
import java.nio.file.*;
public class NpTest {
  public static void main(String[] a) throws Exception {
    for (String path : a) {
      byte[] b = Files.readAllBytes(Paths.get(path));
      NpUnpacker.Result r = NpUnpacker.parse(b);
      System.out.println("=== " + path + " (" + b.length + " o) : " + r.effect.getEmitters().size + " emitters ===");
      int i = 0;
      for (Object o : r.effect.getEmitters()) {
        ParticleEmitter em = (ParticleEmitter) o;
        System.out.printf("  emitter[%d] tag=%s min=%d max=%d continuous=%b additive=%b life=[%.1f..%.1f] emission=[%.1f..%.1f] frameDur=%.3f%n",
          i, r.atlasTags.get(i), em.getMinParticleCount(), em.getMaxParticleCount(),
          em.isContinuous(), em.isAdditive(),
          em.getLife().getLowMin(), em.getLife().getHighMax(),
          em.getEmission().getLowMin(), em.getEmission().getHighMax(),
          em.getFrameDuration());
        i++;
      }
    }
  }
}
