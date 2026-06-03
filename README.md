<div align="center">

<img src="assets/pluxy-logo.png" width="160" alt="Pluxy" />

# Pluxy

**Vos films. Sans limites.**

Serveur multimédia auto-hébergé optimisé pour le streaming **4K HDR (HEVC/HDR10)**
depuis un PC Windows (Ryzen 7 9800X3D + RTX 5080) vers un **Android TV (Philips 803)**
en Wi-Fi 5 GHz double saut.

</div>

---

## ⬇️ Télécharger / Lancer

| Plateforme | Accès |
|---|---|
| 📱📺 **Android (mobile **et** TV)** | **[Télécharger la dernière version](../../releases/latest)** · ou [`dist/`](dist/) |
| 💻 **Serveur PC (Windows)** | Double-clic sur **[`Pluxy-Server.bat`](Pluxy-Server.bat)** |

> L'APK fonctionne sur **téléphone/tablette Android (6.0+) ET Android TV** :
> elle apparaît dans le lanceur des deux (icône standard + bannière TV).

### 🔄 Mises à jour sans désinstaller
L'app est signée avec une **clé stable** : il suffit d'installer la nouvelle APK
par-dessus (`adb install -r`, ou ouvrir l'APK depuis l'appareil) — **les données
et la config serveur sont conservées**, aucune désinstallation nécessaire, tant
que le `versionCode` augmente.
> *Migration unique* : si une version **antérieure à la 1.0.1** (signature debug
> aléatoire) est déjà installée, désinstallez-la **une seule fois** avant de passer
> à la 1.0.1. Ensuite, toutes les mises à jour se feront par-dessus.

> **PC en un clic** : `Pluxy-Server.bat` crée l'environnement au 1er lancement,
> démarre le serveur et ouvre l'UI de configuration dans le navigateur.
> *(Python 3.11+ requis uniquement à la première exécution.)*

### Installer l'app sur la TV
1. TV → **Paramètres ▸ Système ▸ À propos** : cliquer **7×** sur *Build* (mode développeur),
   puis **Préférences développeur** : activer *Débogage réseau*.
2. PC → double-clic sur **[`Install-APK-on-TV.bat`](Install-APK-on-TV.bat)**, saisir l'IP de la TV.
3. Au 1er lancement, l'app **détecte automatiquement le serveur** sur le réseau
   (broadcast UDP). Si plusieurs serveurs sont trouvés, elle propose un choix ;
   sinon l'IP peut être saisie manuellement. **Aucune IP à configurer en dur.**

---

## ✨ Fonctionnalités

- **Découverte serveur automatique** : le client trouve le PC serveur sur le LAN
  (broadcast UDP), choix proposé si plusieurs, repli manuel — zéro IP à configurer.
- **Métadonnées façon Plex** (TMDB) : identification du film, synopsis, casting,
  genres, note, affiches/backdrops et **bande-annonce** YouTube.
- **Reconnaissance de noms robuste** : parser multi-passes (titres numériques
  *2012/1917/300*, sagas, scene-names, VOSTFR/MULTI, éditions…) validé sur 16 cas.
- **Lecteur complet (parité Plex)** : reprise, **roue crantée unique** (audio,
  sous-titres, vitesse 0.5×–2×, format d'image, **aller à un instant**, **infos & logs**),
  saut ±10 s, reconnexion auto, style des sous-titres, réglages de lecture **en amont**.
- **Audio passthrough** : DTS-HD / TrueHD / **Dolby Atmos** transmis tel quel (bitstream)
  vers l'ampli quand l'appareil/HDMI le permet ; sinon downmix AC3/EAC3.
- **Tone mapping HDR→SDR haute qualité** (libplacebo bt.2390 + détection du pic),
  repli zscale.
- **Barre de lecture complète + seek partout (même en transcodage)** : HLS VOD en
  **session de transcodage continue** (façon Plex/Jellyfin) — segments parfaitement
  alignés (keyframes forcées, `-forced-idr`), aucune coupure son/vidéo aux frontières ;
  le seek redémarre proprement la session à l'instant choisi et garde les segments
  déjà produits en cache. Direct Play seeke nativement via HTTP Range.
- **Bibliothèque persistante** : l'index survit aux redémarrages (liste instantanée),
  scan en arrière-plan, bouton **Actualiser** + pull-to-refresh côté app, et
  **regroupement des titres similaires** (sagas côte à côte).
- **Robuste sans FFmpeg** : repli automatique en Direct Play (plus d'erreur de flux).
- **3 modes de lecture automatiques** : Direct Play / Direct Stream / Transcode.
- **Transcodage 100 % NVIDIA** : NVDEC → CUDA → `hevc_nvenc` (RTX 5080).
- **Tone mapping HDR10 → SDR** via filtres CUDA (`zscale`/`tonemap`).
- **Downmix audio** TrueHD/DTS-HD → AC3/EAC3 5.1 (ARC) **sans toucher au flux 4K**.
- **Pré-buffer agressif** ExoPlayer (256 Mo réglables) anti micro-coupures Wi-Fi.
- **Sous-titres .srt** injectés dans ExoPlayer (pas de burn-in).
- **UI de configuration Web** : NVENC, bitrate cap, buffer, tone mapping, clé TMDB…

> **Métadonnées** : créez une clé API gratuite sur
> [themoviedb.org](https://www.themoviedb.org/settings/api) et collez-la dans
> l'UI serveur (carte *Métadonnées*). Sans clé, le titre et l'année sont déduits
> du nom de fichier.

| Mode | Condition | Charge serveur |
|------|-----------|----------------|
| **Direct Play** | Codec + conteneur + bitrate + audio OK | Aucune (HTTP brut, Range) |
| **Direct Stream** | Vidéo OK, seul le conteneur/audio bloque | Faible (remux + audio) |
| **Transcode** | Bitrate trop élevé, codec KO, ou HDR→SDR | GPU NVENC (CUDA) |

---

## 🏗️ Architecture

```
Pluxy/
├── Pluxy-Server.bat            # Lanceur serveur PC (un clic)
├── Build-APK.bat               # Recompile l'APK (garde 3 versions max)
├── Install-APK-on-TV.bat       # Push ADB vers la TV
├── assets/                     # Logo + générateur d'icônes (gen_icons.py)
├── dist/                       # APK prêtes à installer
├── server/                     # Serveur FastAPI + moteur FFmpeg
│   ├── pluxy/
│   │   ├── main.py             # App FastAPI + UI
│   │   ├── config.py           # Modèle de données + persistance JSON
│   │   ├── models.py           # Schémas (média, décision, capacités client)
│   │   ├── probe.py            # Wrapper ffprobe
│   │   ├── library.py          # Scanner de bibliothèque
│   │   ├── decision.py         # Moteur Direct Play / Stream / Transcode
│   │   ├── transcoder.py       # Constructeur + sessions FFmpeg (NVENC)
│   │   ├── api/                # Routes settings / library / stream
│   │   └── web/index.html      # UI de configuration
│   ├── config.default.json     # Schéma JSON des options
│   ├── requirements.txt
│   └── run.py
└── client/                     # Client Android TV (Kotlin / Media3)
    └── app/src/main/java/com/pluxy/tv/
        ├── api/                # Client réseau + détection capacités
        ├── player/             # ExoPlayer (buffer XXL + HDR10)
        └── ui/                 # Grille de navigation D-pad
```

---

## ⚙️ Pré-requis serveur

- **FFmpeg** compilé avec `--enable-nvenc --enable-cuda` + filtres `zscale`/`tonemap`
  (build **`full`** de [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) recommandé).
  Placer `ffmpeg.exe` / `ffprobe.exe` dans le `PATH` ou renseigner le chemin dans la config.
- Pilote **NVIDIA** récent (RTX 5080).
- **Python 3.11+** (testé jusqu'à 3.14).

## 🔧 Démarrage manuel (équivalent au .bat)

```powershell
cd server
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python run.py
```

UI : `http://<IP_PC>:8420/` · API/docs : `http://<IP_PC>:8420/docs`

## 📱 Rebuild du client

```powershell
# Pré-requis : JDK 17+, Android SDK (platform 34 + build-tools 34).
Build-APK.bat        # -> dist\Pluxy-<version>.apk  (signée clé stable)
```

Stack : Gradle 8.9 · AGP 8.7 · Kotlin 2.1 · **Media3 1.4.1** · `compileSdk 34` · `minSdk 23`.

**Signature** : l'APK est signée par `client/keystore/pluxy.jks` (clé fixe, versionnée
dans le repo) afin que toutes les builds soient interchangeables et permettent la
mise à jour sans désinstallation. Pour publier une nouvelle version, incrémentez
`versionCode` (et `versionName`) dans `client/app/build.gradle.kts`.
> ⚠️ Le keystore est volontairement commité (app perso auto-hébergée). Pour un
> usage public, sortez-le du repo et gardez le mot de passe secret.

---

## 💾 Données utilisateur (persistance)

La configuration (**clé TMDB**, dossiers), l'index de bibliothèque, le cache de
métadonnées et les positions de reprise sont stockés dans un emplacement **stable,
hors du dossier du projet** :

```
Windows : %LOCALAPPDATA%\Pluxy\
Linux/Mac : ~/.local/share/Pluxy/
```

→ Mettre à jour ou remplacer le dossier Pluxy **ne perd plus** ces données.
Une ancienne config présente dans `server/config.json` est **migrée automatiquement**
au premier démarrage. *(Personnalisable via la variable d'environnement `PLUXY_DATA_DIR`.)*

## 🔒 Sécurité & robustesse

Audité (multi-agents) et durci pour un usage **LAN de confiance** :
- Anti **path-traversal** sur les segments HLS, **Range** hors-limites (416),
  validation Pydantic des corps de requête, garde des fichiers manquants (404).
- Les **chemins d'exécutables** (ffmpeg/ffprobe) et le dossier de cache ne sont
  **pas** modifiables via l'API (anti-RCE) ; le chemin disque des médias n'est plus
  exposé au client.
- Écritures **atomiques** (config/index/reprise), scans non concurrents, sessions
  FFmpeg sans deadlock (stderr non bloquant) ni fuite (reaper robuste).

> ⚠️ **À garder en tête** : Pluxy est conçu pour un réseau privé. Il n'y a pas encore
> d'authentification ni de TLS — n'exposez pas le port `8420` directement sur Internet.

## 🗂️ Politique de versions

`Build-APK.bat` ne conserve que les **3 APK les plus récentes** dans `dist/` ;
les versions antérieures sont supprimées automatiquement à chaque build.

## 📦 Schéma de configuration

Toutes les options (transcodage, audio, réseau, buffer, sous-titres) sont décrites
dans [`server/config.default.json`](server/config.default.json) et typées dans
[`server/pluxy/config.py`](server/pluxy/config.py).
