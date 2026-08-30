import com.perblue.heroes.network.messages.CampaignType;
import com.perblue.heroes.network.messages.Rarity;
import com.perblue.heroes.network.messages.ResourceType;
import com.perblue.heroes.network.messages.UnitType;
import com.perblue.heroes.game.data.campaign.CampaignStats;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;
import dhserver.auth.MnemonicIdentity;

/**
 * DEV (vérif EN JEU strict, chantier C2a-2 « play ») — GARNIT le compte de jeu dont le {@code userID} DÉRIVE d'une
 * phrase mnémonique, pour que le hub soit PEUPLÉ (barre de ressources + nav + héros) une fois le joueur authentifié
 * (sinon un compte fraîchement semé reste en état TUTORIEL = hub quasi vide).
 *
 * <p>C'est une mise en état du COMPTE (comme {@code CodebaseUnlock}/{@code SetTeamLevel}), pas un comportement serveur :
 * TL monté + roster de héros JAUNE + campagne chapitre 41 + {@code completeAllTutorials} (un vrai joueur monté a déjà
 * fait les tutos) + ressources visibles. À lancer SERVEUR ARRÊTÉ (écrit la ligne {@code users}).
 *
 * <p>Args : {@code <db> <mot1 mot2 …>}. Imprime le {@code userID} sur STDOUT.
 */
public final class StrictAuthAccount {
    public static void main(String[] a) throws Exception {
        if (a.length < 2) { System.err.println("usage: StrictAuthAccount <db> <phrase...>"); System.exit(2); }
        ServerContext.init();
        String db = a[0];
        String phrase = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
        long uid = MnemonicIdentity.fromPhrase(phrase).userID;

        try (UserStore s = new UserStore(db)) {
            ServerUser su = s.loadOrCreate(uid, 1);
            su.bootData().userInfo.basicInfo.teamLevel = 200;

            // Campagne : chapitre 41 terminé (débloque tout jusqu'aux modes tardifs) — mêmes bornes que CodebaseUnlock.
            int chap = 41;
            int maxIdx = CampaignStats.getMaxLevelIndex(CampaignType.NORMAL, chap);
            try { su.grantCampaignLevel(CampaignType.NORMAL, chap, maxIdx, 3); } catch (Throwable ignore) {}

            // Roster VISIBLE : 5 héros JAUNE haut niveau (équipe pleine → le hub affiche des persos).
            UnitType[] team = { UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE, UnitType.MR_INCREDIBLE, UnitType.YAX };
            int granted = 0;
            for (UnitType t : team) { try { su.grantHero(t, Rarity.YELLOW, 200, 6); granted++; } catch (Throwable ignore) {} }

            // Tutoriels TERMINÉS (cohérent avec un compte monté : sinon le hub reste en état tuto = chrome masqué).
            int tut = su.completeAllTutorials();

            // Ressources VISIBLES dans la barre du haut.
            try { su.giveResource(ResourceType.GOLD, 5_000_000L); } catch (Throwable ignore) {}
            try { su.giveResource(ResourceType.DIAMONDS, 5_000L); } catch (Throwable ignore) {}

            s.save(su);
            ServerUser rl = s.loadOrCreate(uid, 1);
            System.err.println("[strict-acct] userID=" + uid + " TL=" + rl.bootData().userInfo.basicInfo.teamLevel
                + " héros=" + granted + " tutosTerminés=" + tut + " [persisté] — phrase = « " + phrase + " »");
        }
        System.out.println(uid);
    }
}
