import xml.etree.ElementTree as ET
import requests
import time
import os
from requests.adapters import HTTPAdapter
from requests.packages.urllib3.util.retry import Retry

# Configuration des clés
ACCESS_KEY = os.getenv("IA_ACCESS_KEY")
SECRET_KEY = os.getenv("IA_SECRET_KEY")

SITEMAPS = ["posts.xml", "pages.xml", "categories.xml", "tags.xml", "users.xml"]
NAMESPACE = {'ns': 'http://www.sitemaps.org/schemas/sitemap/0.9'}

def get_session():
    """Crée une session avec stratégie de retry automatique pour les erreurs HTTP"""
    session = requests.Session()
    retry_strategy = Retry(
        total=5,  # Nombre de tentatives
        backoff_factor=2,  # Attend 2s, 4s, 8s... entre les tentatives
        status_forcelist=[429, 500, 502, 503, 504],
    )
    adapter = HTTPAdapter(max_retries=retry_strategy)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session

def send_to_wayback(session, url):
    """Envoie l'URL à la Wayback Machine avec gestion robuste des erreurs"""
    api_url = f"https://web.archive.org/save/{url}"
    headers = {
        "Accept": "application/json",
        "Authorization": f"LOW {ACCESS_KEY}:{SECRET_KEY}"
    }
    params = {
        "capture_outlinks": "1",
        "capture_screenshot": "1",
        "force_get": "1"
    }

    try:
        # On utilise la session avec retries
        response = session.post(api_url, headers=headers, data=params, timeout=60)
        
        if response.status_code == 200:
            print(f" -> Succès")
            return True
        else:
            print(f" -> Erreur HTTP {response.status_code}")
            return False

    except requests.exceptions.ConnectionError:
        print(" -> Connexion refusée par Archive.org (Surcharge). Pause de 60s...")
        time.sleep(60) # Grosse pause si le serveur nous rejette
        return False
    except Exception as e:
        print(f" -> Erreur inattendue : {e}")
        return False

def main():
    if not ACCESS_KEY or not SECRET_KEY:
        print("Erreur : Clés IA manquantes.")
        return

    session = get_session()
    all_urls = []

    for filename in SITEMAPS:
        if not os.path.exists(filename): continue
        tree = ET.parse(filename)
        root = tree.getroot()
        for loc in root.findall(".//ns:loc", NAMESPACE):
            all_urls.append(loc.text)

    all_urls = sorted(list(set(all_urls))) # Trié pour avoir un ordre cohérent
    total = len(all_urls)
    print(f"Total d'URLs uniques : {total}")

    for index, url in enumerate(all_urls, 1):
        print(f"[{index}/{total}] {url}", end="")
        success = send_to_wayback(session, url)
        
        # Pause de sécurité : 5 secondes entre chaque page pour éviter le ban
        # Archive.org est très strict en ce moment.
        time.sleep(5)

if __name__ == "__main__":
    main()
