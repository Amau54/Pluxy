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
| 📺 **Android TV (APK)** | **[Télécharger la dernière version](../../releases/latest)** · ou [`dist/Pluxy-TV-1.0.0.apk`](dist/Pluxy-TV-1.0.0.apk) |
| 💻 **Serveur PC (Windows)** | Double-clic sur **[`Pluxy-Server.bat`](Pluxy-Server.bat)** |

> **PC en un clic** : `Pluxy-Server.bat` crée l'environnement au 1er lancement,
> démarre le serveur et ouvre l'UI de configuration dans le navigateur.
> *(Python 3.11+ requis uniquement à la première exécution.)*

### Installer l'app sur la TV
1. TV → **Paramètres ▸ Système ▸ À propos** : cliquer **7×** sur *Build* (mode développeur),
   puis **Préférences développeur** : activer *Débogage réseau*.
2. PC → double-clic sur **[`Install-APK-on-TV.bat`](Install-APK-on-TV.bat)**, saisir l'IP de la TV.
3. Au 1er lancement de l'app, régler l'IP du serveur (PC).

---

## ✨ Fonctionnalités

- **3 modes de lecture automatiques** : Direct Play / Direct Stream / Transcode.
- **Transcodage 100 % NVIDIA** : NVDEC → CUDA → `hevc_nvenc` (RTX 5080).
- **Tone mapping HDR10 → SDR** via filtres CUDA (`zscale`/`tonemap`).
- **Downmix audio** TrueHD/DTS-HD → AC3/EAC3 5.1 (ARC) **sans toucher au flux 4K**.
- **Pré-buffer agressif** ExoPlayer (256 Mo réglables) anti micro-coupures Wi-Fi.
- **Sous-titres .srt** injectés dans ExoPlayer (pas de burn-in).
- **UI de configuration Web** : toggle NVENC, bitrate cap, taille du buffer, tone mapping…

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
Build-APK.bat        # -> dist\Pluxy-TV-1.0.0.apk
```

Stack : Gradle 8.9 · AGP 8.7 · Kotlin 2.1 · **Media3 1.4.1** · `compileSdk 34` · `minSdk 23`.

---

## 🗂️ Politique de versions

`Build-APK.bat` ne conserve que les **3 APK les plus récentes** dans `dist/` ;
les versions antérieures sont supprimées automatiquement à chaque build.

## 📦 Schéma de configuration

Toutes les options (transcodage, audio, réseau, buffer, sous-titres) sont décrites
dans [`server/config.default.json`](server/config.default.json) et typées dans
[`server/pluxy/config.py`](server/pluxy/config.py).
