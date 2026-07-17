import xml.etree.ElementTree as ET
import requests
import time
import os

# CONFIGURATION
ACCESS_KEY = os.getenv("IA_ACCESS_KEY")
SECRET_KEY = os.getenv("IA_SECRET_KEY")
SITEMAPS = ["posts.xml", "pages.xml", "categories.xml", "tags.xml", "users.xml"]
NAMESPACE = {'ns': 'http://www.sitemaps.org/schemas/sitemap/0.9'}
PROGRESS_FILE = "processed_urls.txt"

def load_processed_urls():
    """Charge la liste des URLs déjà sauvegardées pour ne pas les refaire"""
    if os.path.exists(PROGRESS_FILE):
        with open(PROGRESS_FILE, "r") as f:
            return set(line.strip() for line in f if line.strip())
    return set()

def save_processed_url(url):
    """Enregistre une URL réussie dans le fichier de progression"""
    with open(PROGRESS_FILE, "a") as f:
        f.write(url + "\n")

def send_to_wayback(url):
    """Envoie l'URL à la Wayback Machine avec gestion des erreurs"""
    api_url = "https://web.archive.org/save/"
    headers = {
        "Accept": "application/json",
        "Authorization": f"LOW {ACCESS_KEY}:{SECRET_KEY}"
    }
    data = {
        "url": url,
        "capture_outlinks": "1",
        "capture_screenshot": "1",
        "force_get": "1",
        "skip_first_archive": "1"
    }

    try:
        # On tente l'envoi
        response = requests.post(api_url, headers=headers, data=data, timeout=60)
        
        if response.status_code == 200:
            print(" -> SUCCÈS")
            save_processed_url(url)
            return True
        elif response.status_code == 429:
            print(" -> QUOTA ATTEINT (429). Pause de 5 minutes...")
            time.sleep(300)
            return False
        else:
            print(f" -> ÉCHEC (Code {response.status_code})")
            return False
            
    except (requests.exceptions.ConnectionError, requests.exceptions.Timeout):
        print(" -> ERREUR CONNEXION (Archive.org saturé). Pause 60s...")
        time.sleep(60)
        return False
    except Exception as e:
        print(f" -> ERREUR INATTENDUE : {e}")
        return False

def main():
    if not ACCESS_KEY or not SECRET_KEY:
        print("Erreur : Clés IA_ACCESS_KEY ou IA_SECRET_KEY manquantes dans les Secrets GitHub.")
        return

    processed_urls = load_processed_urls()
    all_urls = []

    # 1. Lecture des sitemaps
    for filename in SITEMAPS:
        if not os.path.exists(filename):
            continue
        try:
            tree = ET.parse(filename)
            root = tree.getroot()
            for loc in root.findall(".//ns:loc", NAMESPACE):
                if loc.text:
                    all_urls.append(loc.text.strip())
        except Exception as e:
            print(f"Erreur lors de la lecture de {filename}: {e}")

    # 2. Nettoyage et tri
    all_urls = sorted(list(set(all_urls)))
    
    # 3. Filtrage (On ne garde que ce qui n'a pas été fait)
    urls_to_do = [url for url in all_urls if url not in processed_urls]
    
    print(f"Total d'URLs uniques : {len(all_urls)}")
    print(f"Déjà traitées         : {len(processed_urls)}")
    print(f"Restantes à faire    : {len(urls_to_do)}")

    if not urls_to_do:
        print("Toutes les pages sont déjà archivées !")
        return

    # 4. Boucle de traitement
    for index, url in enumerate(urls_to_do, 1):
        print(f"[{index}/{len(urls_to_do)}] {url}", end="", flush=True)
        send_to_wayback(url)
        # Pause de sécurité pour la stabilité d'Archive.org
        time.sleep(10)

if __name__ == "__main__":
    main()
