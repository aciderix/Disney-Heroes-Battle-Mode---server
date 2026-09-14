-- ANNUAIRE — purge automatique des serveurs MORTS (appliquée sur le projet Supabase « Dhbm »).
--
-- POURQUOI : un serveur vivant se réinscrit toutes les 10 min (LoginServer, thread `dh-directory-publish`) et
-- l'Edge Function `register-server` met `updated_at` à jour. Rien ne supprimait les fiches d'un serveur ÉTEINT :
-- vérifié en base, il n'existait AUCUN job planifié (pg_cron absent) ni trigger sur `public.servers`, et une fiche
-- de démonstration vieille de 12 jours était toujours listée par le picker de l'APK (qui liste sans re-vérifier la
-- vivacité). L'annuaire se serait rempli indéfiniment de serveurs injoignables.
--
-- SEUIL : 30 min = 3 rafraîchissements manqués. Un serveur temporairement coupé disparaît puis REVIENT tout seul au
-- redémarrage (il se réinscrit) — la purge est donc auto-cicatrisante, pas destructrice.
--
-- COMPLÉMENT (côté Edge Function, pas ici) : `register-server` REFUSE désormais d'écrire une fiche dont le /info
-- n'est pas joignable depuis Internet (HTTP 422). Les deux mécanismes sont complémentaires : la fonction empêche
-- d'ENTRER dans l'annuaire sans être joignable, ce job en SORT les serveurs qui s'éteignent.
create extension if not exists pg_cron;

create or replace function public.purge_stale_servers()
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare n integer;
begin
  delete from public.servers where updated_at < now() - interval '30 minutes';
  get diagnostics n = row_count;
  return n;
end;
$$;

-- La fonction tourne en SECURITY DEFINER (elle doit passer outre RLS pour supprimer) : on retire donc tout droit
-- d'exécution aux rôles publics/anon — seul le job pg_cron (postgres) l'appelle.
revoke all on function public.purge_stale_servers() from public, anon, authenticated;

select cron.schedule('purge-stale-servers', '*/10 * * * *', $$select public.purge_stale_servers()$$);
