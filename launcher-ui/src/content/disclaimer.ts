// Avertissement du 1er lancement (docs/LAUNCHER_UI.md §2). Versionné : incrémenter DISCLAIMER_VERSION si le texte
// change → l'utilisateur doit ré-accepter. Auteur : Aciderix. Ce n'est pas un avis juridique.

export const DISCLAIMER_VERSION = 1;
export const DISCLAIMER_AUTHOR = "Aciderix";
export const DISCLAIMER_CONTACT = "fromthenext77@gmail.com";

/** Paragraphes de l'avertissement (rendus dans l'ordre). Le titre est géré par la vue. */
export const DISCLAIMER_PARAGRAPHS: string[] = [
  "Ce logiciel (« le Launcher ») est un projet amateur, indépendant et à but non lucratif. Il n'a AUCUN lien avec Disney, PerBlue, ni aucun de leurs partenaires, et n'est ni approuvé, ni sponsorisé, ni affilié à eux d'aucune manière.",
  "Le jeu « Disney Heroes: Battle Mode », son code, ses images, ses personnages, ses sons, ses musiques et tout son contenu demeurent la propriété exclusive de Disney, de PerBlue et de leurs ayants droit respectifs. Toutes les marques et personnages cités appartiennent à leurs propriétaires respectifs.",
  "Le Launcher ne distribue PAS le jeu ni aucun de ses contenus protégés : il ne contient aucun fichier du jeu. Pour l'utiliser, vous devez fournir vous-même votre propre copie de l'application (APK), que vous devez posséder légalement. Vous êtes seul responsable de la légalité de son obtention et de son usage dans votre pays.",
  "Le Launcher est fourni entièrement gratuitement. Je n'autorise ni ne cautionne aucune utilisation à des fins commerciales ou financières (vente, revente, dons contre accès, publicité, monétisation de serveurs, etc.). Toute personne qui hébergerait un serveur le fait à titre privé et gratuit, sous sa seule responsabilité.",
  "Le Launcher est fourni « EN L'ÉTAT », SANS AUCUNE GARANTIE, sans support, et sans aucune responsabilité de l'auteur quant à d'éventuels dommages, pertes de données, ou conséquences de son utilisation. Vous l'utilisez à vos propres risques.",
  "Ce projet existe à des fins d'étude, d'archivage et d'usage personnel entre particuliers. Si un ayant droit le demande, l'auteur cessera la distribution.",
  `Auteur : ${DISCLAIMER_AUTHOR} — contact / retrait sur demande : ${DISCLAIMER_CONTACT}.`,
  "En cochant la case ci-dessous, vous déclarez avoir lu et compris cet avertissement et l'accepter intégralement.",
];
