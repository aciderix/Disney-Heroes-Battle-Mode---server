import xml.etree.ElementTree as ET
import requests
import time
import os

ACCESS_KEY = os.getenv("IA_ACCESS_KEY")
SECRET_KEY = os.getenv("IA_SECRET_KEY")
SITEMAPS = ["posts.xml", "pages.xml", "categories.xml", "tags.xml", "users.xml"]
NAMESPACE = {'ns': 'http://www.sitemaps.org/schemas/sitemap/0.9'}
PROGRESS_FILE = "processed_urls.txt"

def load_processed_urls():
    if os.path.exists(PROGRESS_FILE):
        with open(PROGRESS_FILE, "r") as f:
            return set(line.strip() for l in f)
    return set()

def save_processed_url(url):
    with open(PROGRESS_FILE, "a") as f:
        f.write(url + "\n")

def send_to_wayback(url):
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
        response = requests.post(api_url, headers=headers, data=data, timeout=60)
        
        if response.status_code == 200:
            print(" -> SUCCÈS")
            save_processed_url(url)
            return True
        elif response.status_code == 429:
            print(" -> ERREUR 429 (Quota). Pause de 5 minutes...")
            time.sleep(300) # Grosse pause si quota atteint
            return False
        else:
            print(f" -> ÉCHEC ({response.status_code})")
            return False
    except Exception as e:
        print(f" -> ERREUR RÉSEAU (Attente 60s...)")
        time.sleep(60)
        return False

def main():
    processed_urls = load_processed_urls()
    all_urls = []
    for filename in SITEMAPS:
        if not os.path.exists(filename): continue
        tree = ET.parse(filename)
        root = tree.getroot()
        for loc in root.findall(".//ns:loc", NAMESPACE):
            all_urls.append(loc.text)

    all_urls = sorted(list(set(all_urls)))
    urls_to_do = [u for l in all_urls if l not in processed_urls]
    
    print(f"Total : {len(all_urls)} | Déjà faits : {len(processed_urls)} | Restant : {len(urls_to_do)}")

    for index, url in enumerate(urls_to_do, 1):
        print(f"[{index}/{len(urls_to_do)}] {url}", end="")
        send_to_wayback(url)
        time.sleep(10) # 10s entre les requêtes pour la stabilité

if __name__ == "__main__":
    main()
