import xml.etree.ElementTree as ET
import requests
import time
import os

# Configuration des clés
ACCESS_KEY = os.getenv("IA_ACCESS_KEY")
SECRET_KEY = os.getenv("IA_SECRET_KEY")

SITEMAPS = ["posts.xml", "pages.xml", "categories.xml", "tags.xml", "users.xml"]
NAMESPACE = {'ns': 'http://www.sitemaps.org/schemas/sitemap/0.9'}

def send_to_wayback(url):
    """Envoie l'URL et attend une confirmation réelle d'Archive.org"""
    # L'API SPN2 préfère les requêtes POST sur cet endpoint
    api_url = "https://web.archive.org/save/"
    
    headers = {
        "Accept": "application/json",
        "Authorization": f"LOW {ACCESS_KEY}:{SECRET_KEY}"
    }
    
    # Paramètres envoyés en tant que données de formulaire (POST)
    data = {
        "url": url,
        "capture_outlinks": "1",
        "capture_screenshot": "1",
        "force_get": "1",
        "skip_first_archive": "1"
    }

    try:
        # On ne passe plus l'URL dans le lien mais dans les données POST
        response = requests.post(api_url, headers=headers, data=data, timeout=30)
        
        if response.status_code == 200:
            res_json = response.json()
            if "job_id" in res_json:
                print(f" -> SUCCÈS ! (Job ID: {res_json['job_id']})")
                return True
            else:
                print(f" -> ACCEPTÉ mais pas de Job ID (Déjà archivé récemment ?)")
                return True
        elif response.status_code == 429:
            print(" -> ERREUR 429 : Trop de requêtes. On dort 60 secondes...")
            time.sleep(60)
            return False
        elif response.status_code == 401:
            print(" -> ERREUR 401 : Tes clés IA sont peut-être invalides.")
            return False
        else:
            print(f" -> ÉCHEC (Code: {response.status_code}): {response.text[:100]}")
            return False

    except Exception as e:
        print(f" -> ERREUR RÉSEAU : {e}")
        time.sleep(10)
        return False

def main():
    if not ACCESS_KEY or not SECRET_KEY:
        print("Erreur : IA_ACCESS_KEY ou IA_SECRET_KEY manquant.")
        return

    all_urls = []
    for filename in SITEMAPS:
        if not os.path.exists(filename): continue
        tree = ET.parse(filename)
        root = tree.getroot()
        for loc in root.findall(".//ns:loc", NAMESPACE):
            all_urls.append(loc.text)

    all_urls = sorted(list(set(all_urls)))
    total = len(all_urls)
    print(f"Total d'URLs à traiter : {total}")

    # On réduit la cadence pour être sûr que ça passe
    # Archive.org limite souvent à 15-20 captures par minute même avec clé
    for index, url in enumerate(all_urls, 1):
        print(f"[{index}/{total}] {url}", end="")
        send_to_wayback(url)
        time.sleep(8) # 8 secondes de pause = ~7 captures par minute. Très stable.

if __name__ == "__main__":
    main()
