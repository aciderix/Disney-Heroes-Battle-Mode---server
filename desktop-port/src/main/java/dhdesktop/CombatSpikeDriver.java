package dhdesktop;

import com.badlogic.gdx.utils.Array;
import com.perblue.heroes.DH;
import com.perblue.heroes.GameMain;
import com.perblue.heroes.game.logic.CampaignLootHelper;
import com.perblue.heroes.game.objects.Scene;
import com.perblue.heroes.game.objects.Unit;
import com.perblue.heroes.game.objects.UnitData;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.network.messages.CampaignType;
import com.perblue.heroes.network.messages.GameMode;
import com.perblue.heroes.network.messages.HeroLineupType;
import com.perblue.heroes.simulation.headless.HeadlessCombat;
import com.perblue.heroes.ui.screens.CampaignAttackScreen;
import com.perblue.heroes.ui.screens.CoreAttackScreen;

import java.util.Random;

/**
 * Spike Opt.2 (#27, SERVER_PLAN §D) — DEV UNIQUEMENT ({@code -Ddh.combatspike=1}), off par défaut, aucun effet
 * en prod, aucune modif du jeu/serveur. But : exécuter le <b>vrai {@code HeadlessCombat} du jeu</b> DANS le
 * client headless déjà booté (contexte GL Xvfb/llvmpipe + {@code GameMain} réel → asset manager + renderContext
 * peuplés + unidbg cspine/cparticle + assets), pour :
 * <ol>
 *   <li><b>Mesurer la faisabilité/lourdeur</b> (ms constructeur + ms boucle {@code work()} jusqu'à {@code DONE},
 *       part unidbg) — décide si l'Opt.2 est viable en prod ;</li>
 *   <li>en faire l'<b>ORACLE</b> de certification de l'Opt.3 (voie spine-Java plus légère) : {@code seed}
 *       COMBAT → issue reproductible.</li>
 * </ol>
 * Une seule exécution (au premier moment où {@code DH.app.getYourUser()} a des héros, après login/boot).
 * Réglages : {@code -Ddh.combatspike.ch/lv/seed}.
 */
public final class CombatSpikeDriver {

    private static boolean done = false;

    private CombatSpikeDriver() {}

    /** @return true quand le spike a été exécuté (ou l'est déjà) ; false tant que le user n'a pas de héros. */
    public static boolean tryRunOnce(GameMain game) {
        if (done) return true;
        User u = DH.app.getYourUser();
        if (u == null) return false;

        Array<CoreAttackScreen.CombatUnitData> attackers =
                new Array<CoreAttackScreen.CombatUnitData>(CoreAttackScreen.CombatUnitData.class);
        for (Object o : u.getHeroes()) {
            attackers.add(new CoreAttackScreen.CombatUnitData((UnitData) o));
            if (attackers.size >= 5) break;
        }
        if (attackers.size == 0) return false;   // pas encore prêt (login/coffre pas finis)
        done = true;

        int ch = Integer.getInteger("dh.combatspike.ch", 1);
        int lv = Integer.getInteger("dh.combatspike.lv", 1);
        long seed = Long.getLong("dh.combatspike.seed", 123456789L);
        CampaignType type = CampaignType.NORMAL;
        GameMode mode = GameMode.CAMPAIGN;

        System.out.println("[combatspike] OPT.2 — héros=" + attackers.size + " → NORMAL " + ch + "-" + lv
                + " seed=" + seed + " (vrai HeadlessCombat dans le client headless unidbg)");
        try {
            Random rng = new Random(seed);
            CampaignLootHelper.CampaignLoot loot =
                    CampaignLootHelper.getLoot(u, type, ch, lv, 0, SpecialEventSnapshot.NONE, u, false);
            Array defenders = CampaignAttackScreen.createStageDefenders(
                    type, ch, lv, rng, false, SpecialEventSnapshot.NONE, u, loot);
            System.out.println("[combatspike] vagues défenseurs=" + defenders.size);

            HeadlessCombat.IHeadlessEvents ev = new HeadlessCombat.IHeadlessEvents() {
                @Override public void onDefenderUnitDeath(Unit x) {}
            };
            long t0 = System.nanoTime();
            HeadlessCombat hc = new HeadlessCombat(HeroLineupType.DEFAULT, rng, attackers, defenders, mode, ev);
            long tCtor = System.nanoTime();

            int ticks = 0, cap = 200000;
            while (!"DONE".equals(hc.getState().name()) && ticks < cap) {
                hc.work();
                ticks++;
            }
            long t1 = System.nanoTime();

            Scene scene = hc.getScene();
            boolean win = scene != null && scene.isAttackersLeft() && !scene.isDefendersLeft();
            System.out.printf("[combatspike] FIN state=%s ticks=%d | ctor=%.1f ms  work=%.1f ms  total=%.1f ms%n",
                    hc.getState(), ticks, (tCtor - t0) / 1e6, (t1 - tCtor) / 1e6, (t1 - t0) / 1e6);
            System.out.println("[combatspike] ISSUE : attackersLeft=" + (scene != null && scene.isAttackersLeft())
                    + " defendersLeft=" + (scene != null && scene.isDefendersLeft()) + " → "
                    + (win ? "WIN" : "LOSS/indéterminé"));
        } catch (Throwable t) {
            System.out.println("[combatspike] EXCEPTION: " + t);
            t.printStackTrace();
        }
        return true;
    }
}
