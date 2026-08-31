// i18n minimal (dictionnaire clé→texte). Ajouter des langues = ajouter une entrée. Aucune logique métier.
export type Lang = "fr" | "en";

const fr = {
  "app.title": "Launcher Disney Heroes (port privé)",
  "nav.play": "Jouer", "nav.servers": "Serveurs", "nav.host": "Héberger",
  "nav.generate": "Générer", "nav.admin": "Admin", "nav.settings": "Réglages",
  "nav.account": "Compte",
  "daemon.waiting": "Connexion au launcher local…",
  "daemon.down": "Launcher local injoignable. Relance l'application.",
  "gate.title": "Avertissement — à lire intégralement",
  "gate.accept": "J'ai lu et j'accepte",
  "gate.decline": "Refuser et quitter",
  "gate.checkbox": "J'ai lu et j'accepte l'intégralité de cet avertissement.",
  "gate.scrollHint": "Faites défiler jusqu'en bas pour activer l'acceptation.",
  "common.soon": "à venir",
  "common.todo": "à construire",
};

const en: Record<keyof typeof fr, string> = {
  "app.title": "Disney Heroes Launcher (private port)",
  "nav.play": "Play", "nav.servers": "Servers", "nav.host": "Host",
  "nav.generate": "Generate", "nav.admin": "Admin", "nav.settings": "Settings",
  "nav.account": "Account",
  "daemon.waiting": "Connecting to local launcher…",
  "daemon.down": "Local launcher unreachable. Restart the app.",
  "gate.title": "Disclaimer — read in full",
  "gate.accept": "I have read and accept",
  "gate.decline": "Decline and quit",
  "gate.checkbox": "I have read and accept this disclaimer in full.",
  "gate.scrollHint": "Scroll to the bottom to enable acceptance.",
  "common.soon": "coming soon",
  "common.todo": "to build",
};

const dict: Record<Lang, Record<string, string>> = { fr, en };
export type MsgKey = keyof typeof fr;
export function t(lang: Lang, key: MsgKey): string { return dict[lang][key] ?? key; }
