<div align="center">

<img src="assets/pluxy-logo.png" width="160" alt="Pluxy" />

# Pluxy

**Vos médias sur votre TV, depuis un téléphone Android ou un PC.**

Version actuelle : **1.5.1 · versionCode 46**.

[Télécharger le lecteur TV / mobile](../../releases/download/dev-latest/Pluxy-debug.apk)
· [Télécharger le serveur Android](../../releases/download/dev-latest/Pluxy-Server-Android-debug.apk)
· [Télécharger le serveur PC](../../archive/refs/heads/main.zip)
· [Détails du build](../../releases/tag/dev-latest)

</div>

Pluxy lit vos films, séries, photos et musique sur un réseau domestique, sans compte
Pluxy ni catalogue fourni. Le lecteur fonctionne sur **Android TV et Android 6.0+**.
Le serveur Android partage les dossiers que vous choisissez ; le serveur PC peut
aussi adapter les vidéos avec FFmpeg et un GPU NVIDIA compatible.

> Les APK `dev-latest` sont des **builds debug de test, hors Google Play**.
> Depuis le changement de signature du 4 octobre 2026, une ancienne installation
> portant une autre signature nécessite une désinstallation puis une réinstallation.
> Cette opération efface les données privées de l'application. Sauvegardez les réglages
> disponibles auparavant ; vos médias stockés hors de l'application restent sur leur
> support. Les mises à jour avec le même certificat conservent normalement les données.
> [Signature et migration](docs/DEBUG_SIGNING.md).

## Démarrer

### Avec un téléphone ou une tablette Android

1. Installez **Pluxy Serveur Android** sur l'appareil qui contient les médias.
2. Ouvrez **Pluxy Serveur**, puis choisissez un ou plusieurs dossiers de vidéos,
   photos ou musique. Seuls ces dossiers sont partagés.
3. Démarrez le serveur. L'option **Rester disponible écran éteint** maintient le service
   pendant la lecture ; une notification permet de l'arrêter.
4. Installez le lecteur **Pluxy** sur la TV, ouvrez-le et sélectionnez le serveur détecté.
   Les deux appareils doivent être sur le même réseau domestique.

Le serveur Android sert les fichiers originaux, sans transcodage. La TV ou le lecteur
externe choisi doit savoir les décoder. Pour réduire le débit ou convertir un format,
utilisez le serveur PC.

### Avec un PC Windows

1. Téléchargez et extrayez le ZIP du projet.
2. Lancez [Pluxy-Server.bat](Pluxy-Server.bat) sur **Windows 10 ou 11**.
   Le lanceur réutilise Python 3.11 à 3.14 s'il est disponible ; sinon, il télécharge
   et installe Python automatiquement pour votre compte, sans droits administrateur.
   Une connexion Internet est nécessaire à la première installation. Les composants
   Pluxy sont préparés, puis la configuration s'ouvre dès que le serveur est prêt.
3. Choisissez vos dossiers. Le lecteur Android découvre le serveur automatiquement ;
   vous pouvez aussi saisir son adresse manuellement.

Administration du PC : `http://localhost:8420/`. Le catalogue et les flux sont
accessibles aux appareils du LAN sur `http://<IP_PC>:8420/`.

Pour le remux ou le transcodage, configurez FFmpeg et ffprobe dans le `PATH` ou dans
la configuration locale. Le transcodage vidéo utilise NVENC et nécessite un GPU NVIDIA
et les pilotes adaptés ; la lecture directe reste disponible sans FFmpeg.

### Installer sur une TV avec ADB

Activez les options développeur et le débogage USB ou réseau de la TV, puis utilisez
[Install-APK-on-TV.bat](Install-APK-on-TV.bat) depuis le PC. En Wi-Fi, utilisez le port
de connexion affiché par Android. Certains appareils demandent un appairage préalable.

```powershell
.\Install-APK-on-TV.bat -Apk "C:\Downloads\Pluxy-debug.apk" -Device SERIAL_USB -Launch
.\Install-APK-on-TV.bat -Apk "C:\Downloads\Pluxy-debug.apk" -Address 192.168.1.50:5555 -Launch
# Android avec appairage sans fil : ports d'appairage et de connexion distincts.
.\Install-APK-on-TV.bat -PairAddress IP:PORT_APPAIRAGE -Address IP:PORT_CONNEXION
```

Pour le serveur Android, utilisez [Install-Server-on-Android.bat](Install-Server-on-Android.bat).
Les scripts vérifient package, version et certificat, refusent une signature incompatible
ou une rétrogradation, et ne désinstallent pas automatiquement une application.

## Fonctions

- Films, Séries, Photos et Musique, avec recherche transversale et mode avancé facultatif.
- Accueil personnalisable, reprise de lecture, épisode suivant et bibliothèques par dossier.
- Interface TV à surfaces glass, focus télécommande explicite et commandes protégées de l'overscan.
- Lecteur vidéo avec audio, sous-titres, vitesse, format d'image et seek précis au D-pad.
- Galerie photo avec diaporama et lecteur musical avec file d'album.
- Miniatures et pochettes locales, catalogue persistant et scan en arrière-plan.
- Métadonnées, affiches et synopsis TMDB avec une clé API personnelle facultative.
- Direct Play HTTP Range, reprise et sous-titres externes.
- Serveur PC : remux et transcodage HLS, adaptation du débit et tone mapping selon FFmpeg.
- Serveur Android : dossiers SAF, fonctionnement écran éteint et découverte LAN.
- Envoi depuis le téléphone vers DLNA ou Google Cast, selon les capacités du récepteur.

| Mode | Usage |
|---|---|
| Direct Play | Le lecteur décode le fichier original. |
| Direct Stream | Le serveur PC adapte le conteneur ou l'audio sans réencoder la vidéo. |
| Transcodage | Le serveur PC adapte le codec, le débit ou le HDR si nécessaire. |

Pour TMDB, créez votre propre clé sur
[themoviedb.org](https://www.themoviedb.org/settings/api) et renseignez-la dans la
configuration locale. Sans clé, Pluxy utilise les noms de fichiers et miniatures locales.
La compatibilité Cast/DLNA, les codecs et la veille doivent être vérifiés sur vos appareils :
[guide des récepteurs](docs/CAST_DLNA_PLAN.md) et [matrice de validation](docs/DEVICE_VALIDATION.md).

## Construire et vérifier

Prérequis Android : **JDK 17**, SDK Android **36** et build-tools **36**.
Le projet utilise Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21 et Media3 1.10.1.

```powershell
.\Build-APK.bat -Application Player
.\Build-APK.bat -Application Server
# Contrôles complets : tests, lint et bundles release.
.\Build-APK.bat -Verify
```

Le build debug produit les APK demandées dans `dist/` et réutilise le cache. Le manifeste
local identifie les fichiers construits. Sur un PC configuré, le script charge les clés
debug stables depuis `%LOCALAPPDATA%\Pluxy\Signing\debug-2026-10` ; des variables
de signature explicites restent prioritaires. Sans ces identifiants, une clé debug locale
est utilisée et ne permet pas de mettre à jour le canal publié.

Les deux clés debug privées, une par application, restent hors du dépôt. Les APK de
contrôle GitHub peuvent avoir une autre signature que `dev-latest`. `BUILD_INFO.json`
et `SHA256SUMS.txt` identifient la version, le commit, les certificats et les fichiers
publiés. La publication attend les contrôles du même run et les secrets requis.

Les clés d'envoi et la signature finale Google Play sont séparées du canal debug.
`-Configuration Release` exige la configuration de signature complète.
[Guide de signature](docs/DEBUG_SIGNING.md) · [Publication Play](docs/PLAY_STORE_RELEASE.md).

## Architecture

| Dossier | Responsabilité |
|---|---|
| `client/app` | Lecteur Android TV/mobile, Kotlin et Media3. |
| `client/serverapp` | Serveur Android, scan SAF et streaming HTTP Range. |
| `client/receiver-core`, `receiver-dlna`, `receiver-cast` | Profils et lecture distante. |
| `server/pluxy` | Serveur PC FastAPI, bibliothèque et moteur FFmpeg. |
| `tools` | Construction, installation, publication et validation. |
| `assets` | Identité visuelle et générateur d'icônes. |

Le schéma de configuration est décrit dans
[server/config.default.json](server/config.default.json) et
[server/pluxy/config.py](server/pluxy/config.py).

## Données et sécurité

Les réglages du serveur PC, la clé TMDB, l'index, les caches et les positions de reprise
sont stockés hors du projet : `%LOCALAPPDATA%\Pluxy` sous Windows ou
`~/.local/share/Pluxy` sous Linux/macOS. `PLUXY_DATA_DIR` permet de choisir un autre
emplacement. Remplacer le dossier du projet ne supprime pas ces données.

**Pluxy utilise un LAN domestique de confiance, sans authentification ni TLS.** Tous
les appareils de ce réseau peuvent accéder aux médias partagés. Ne redirigez pas les
ports TCP 8420 et UDP 8421 sur Internet ; n'utilisez pas un Wi-Fi public ou un tunnel public.
L'administration sensible du serveur PC reste limitée à `localhost`.

Les clés privées, mots de passe et réglages personnels doivent rester hors de Git.
Sauvegardez-les séparément sur un support privé chiffré.

- [Confidentialité](docs/PRIVACY_POLICY.md)
- [Sécurité](docs/SECURITY.md)
- [Restauration après changement de PC](docs/RESTORE.md)
- [Notes de version 1.5.1](RELEASE_NOTES.md)
- [Mentions des composants tiers](docs/THIRD_PARTY_NOTICES.md)
