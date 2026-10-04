# Publication officielle Google Play

Pluxy comporte deux applications indépendantes :

| Application | Package | Artefact Play |
|---|---|---|
| Pluxy lecteur TV/mobile | `com.pluxy.tv` | `Pluxy-<version>.aab` |
| Pluxy Serveur Android | `com.pluxy.server` | `Pluxy-Server-Android-<version>.aab` |

## 1. Signature de production

Activez **Play App Signing** pour chacun des deux packages et utilisez deux clés
d'envoi distinctes, conservées hors du dépôt. Ne réutilisez pas les clés debug du
canal de test. Une clé privée anciennement exposée reste compromise, même après
nettoyage de Git ; elle ne doit jamais servir à une nouvelle publication Play.
L'exposition du certificat public, à elle seule, ne compromet pas sa clé privée.

Exemple à lancer dans un dossier privé sauvegardé, en laissant `keytool` demander les
mots de passe plutôt que de les écrire dans la commande :

```text
keytool -genkeypair -v -storetype JKS -keystore pluxy-player-upload.jks -alias pluxy-player-upload -keyalg RSA -keysize 4096 -validity 10000
keytool -genkeypair -v -storetype JKS -keystore pluxy-server-upload.jks -alias pluxy-server-upload -keyalg RSA -keysize 4096 -validity 10000
```

Les anciennes installations APK signées avec une autre identité ne peuvent pas recevoir
directement une mise à jour signée avec la nouvelle clé sur tous les appareils.
Prévoir une désinstallation/réinstallation unique. Une désinstallation efface les
données privées de l'application ; sauvegarder les réglages disponibles avant cette
opération. Les médias conservés hors de l'application ne sont pas supprimés.

Le canal debug actuel utilise deux clés stables depuis le **4 octobre 2026**, une
pour chaque package. Leurs certificats et leur sauvegarde sont décrits dans
[DEBUG_SIGNING.md](DEBUG_SIGNING.md). Ces APK servent aux tests hors Play ; elles
ne sont pas des releases officielles de production.

## 2. Secrets GitHub

Créer un environnement GitHub nommé `play-release`, le limiter à la branche `main`
et aux tags protégés `v*`, puis exiger idéalement une approbation manuelle. Configurer
les secrets suivants dans cet environnement protégé :

- `PLUXY_PLAYER_UPLOAD_JKS_BASE64`
- `PLUXY_PLAYER_UPLOAD_STORE_PASSWORD`
- `PLUXY_PLAYER_UPLOAD_KEY_ALIAS`
- `PLUXY_PLAYER_UPLOAD_KEY_PASSWORD`
- `PLUXY_SERVER_UPLOAD_JKS_BASE64`
- `PLUXY_SERVER_UPLOAD_STORE_PASSWORD`
- `PLUXY_SERVER_UPLOAD_KEY_ALIAS`
- `PLUXY_SERVER_UPLOAD_KEY_PASSWORD`

Encoder chaque fichier JKS en Base64 sur la machine de confiance et copier uniquement
la valeur dans le secret. Ne jamais ajouter le JKS, son Base64 ou les mots de passe à
Git, à un artefact CI ou à un journal.

## 3. Contrôles automatiques

Chaque pull request exécute :

- tests Python et audit des dépendances ;
- tests unitaires des deux applications Android ;
- Android Lint sur les variantes release ;
- compilation des APK debug et AAB release non signés, avec R8 et réduction des
  ressources réellement exercés sur les variantes release ;
- contrôle du checksum de la distribution Gradle et des artefacts de dépendances Gradle
  via `client/gradle/verification-metadata.xml`.

Une release signée n'est produite que par un tag `v<version>` correspondant exactement
au `versionName`, ou par le déclenchement manuel avec `publish_release=true`. Le job
échoue si un secret manque, si la révision n'est pas la tête actuelle de `main`, si le
tag n'est pas un SemVer strict ou si la release existe déjà. Les APK/AAB sont vérifiés,
accompagnés d'une SBOM CycloneDX et de sommes SHA-256.

Le job conserve séparément pendant 90 jours les deux fichiers `mapping.txt` R8. Les
télécharger depuis le run protégé et les joindre à la version correspondante dans Play
Console pour désobfusquer les rapports de crash. Ils ne sont pas ajoutés à la release
GitHub publique.

Les contrôles d'administration GitHub obligatoires sont détaillés dans
[`RELEASE_GATES.md`](RELEASE_GATES.md). Ils doivent être réellement activés : ce fichier
ne remplace pas un ruleset, une approbation d'environnement ou un test physique.

La version 1.5.1 cible le SDK 36 : avec cette cible, Android accorde l'accès LAN via la
permission `INTERNET`. **Avant tout passage à targetSdk 37 (Android 17)**, ajouter le
parcours runtime `ACCESS_LOCAL_NETWORK` ou un sélecteur système adapté ; ne pas simplement
augmenter la cible, car serveur, découverte Pluxy et DLNA seraient alors bloqués sans
autorisation. Référence : <https://developer.android.com/privacy-and-security/local-network-permission>.

## 4. Fiches Play Console

Pour chaque package :

1. créer l'application et activer Play App Signing ;
2. utiliser la politique publique :
   `https://github.com/Amau54/Pluxy/blob/main/docs/PRIVACY_POLICY.md` ;
3. remplir le formulaire à partir de `docs/DATA_SAFETY.md` ;
   déclarer la collecte liée aux transmissions LAN et répondre que toutes les
   données ne sont pas chiffrées en transit tant que le flux local utilise HTTP ;
4. déclarer l'absence de publicité et renseigner la classification du contenu ;
5. préciser que Pluxy ne fournit aucun média et lit uniquement les fichiers choisis
   par l'utilisateur ;
6. pour le serveur Android, déclarer le service premier plan `connectedDevice` :
   transfert continu d'un fichier choisi vers une télévision du LAN, avec notification
   visible et bouton Arrêter ;
7. joindre à cette déclaration une vidéo montrant les sources choisies, le serveur actif,
   la notification et une lecture écran éteint ;
8. ajouter l'attribution TMDB visible dans l'application et les captures de fiche.
9. reprendre les textes et la liste d'éléments de `docs/PLAY_STORE_LISTING.md` ;
10. choisir explicitement une licence de distribution pour le projet et ajouter le
    contact officiel du titulaire du compte avant l'ouverture de la production.

Le logo TMDB officiel et la mention exigée sont déjà présents dans les sections
« À propos / crédits » des deux applications. Leur provenance est documentée dans
`docs/THIRD_PARTY_NOTICES.md` ; ne pas déformer ni recolorer arbitrairement le logo.

## 5. Validation sans régression

Publier d'abord sur la piste interne, puis fermée. Tester au minimum :

- Android 6, 8, 11, 14, 15 et 16 ;
- Android TV avec télécommande uniquement ;
- téléphone serveur écran allumé puis éteint pendant au moins deux heures ;
- changement Wi-Fi, perte/reprise réseau, redémarrage et mise à jour de l'application ;
- Direct Play avec seek HTTP Range et sous-titres ;
- seek : appui bref ±10 s, maintien gauche/droite jusqu'au palier ×8, cible monotone,
  relâche précise et aucun changement involontaire de l'état lecture/pause ;
- modes Automatique, Direct Play uniquement et Compatibilité, y compris changement à
  chaud avec conservation de la position ;
- simulation de cinq rebuffers sur deux minutes et de 30 secondes de stalls cumulés :
  un seul palier descendu, sans faux positif après pause ou seek ;
- remontée d'un seul palier après dix minutes stables et nouvelle descente sans boucle ;
- source 10/20/100 Mbps : débits adaptatifs strictement inférieurs ou égaux à la source ;
- navigation D-pad complète du lecteur sur écrans 720p/1080p/4K avec zone d'overscan,
  aucun texte/bouton coupé et aucun focus invisible ;
- onglets Films/Séries/Photos/Musique en modes simple et avancé ;
- recherche transversale par titre/série/artiste/album/dossier, accents ignorés,
  résultat vide et effacement depuis la télécommande ;
- épisode suivant dans la même série, passage S01→S02, choix manuel, compte à rebours
  automatique et interruption propre si l'application passe en arrière-plan ;
- photos JPEG/PNG/WebP/HEIF selon version Android, rotation, diaporama et galerie
  de plusieurs milliers d'éléments ;
- albums photo issus de deux chemins homonymes, regroupement mensuel et date absente ;
- audio MP3/AAC/FLAC/Ogg/WAV, pochette intégrée/absente/corrompue, artiste/album,
  précédent/suivant dans le même album et formats refusés ;
- serveur PC Direct Play, remux, HLS principal et HLS compatibilité ;
- bibliothèque vide, 10 000 médias, une des trois sources SAF/USB retirée et clé TMDB invalide ;
- films et épisodes sans affiche TMDB : miniature locale PC et Android, cache réutilisé,
  concurrence bornée et placeholder propre si le codec ne peut pas être extrait ;
- rafale de miniatures audio/photo : 503 temporaire, retries bornés côté client et
  lecture vidéo simultanée non affamée ;
- deux lecteurs simultanés et saturation contrôlée des limites ;
- écran d'envoi mobile : onglets Vidéos/Photos/Audio, recherche, rotation et restauration
  de la sélection, Cast indisponible sans services Google sans impact sur DLNA/Pluxy TV ;
- DLNA Samsung/LG/Philips : découverte, profils annoncés, play/pause/seek/stop, récepteur
  éteint, noms identiques, XML malveillant et endpoint hors LAN refusés ;
- Chromecast et Google TV : sélection système, vidéo/photo/audio, métadonnées, contrôle
  depuis la notification, erreur de codec ciblée et profil réinitialisable ;
- 100 cycles de récepteur et rapport deux heures via `tools/device_soak.py`, sans présenter
  les contrôles HTTP automatisés comme une preuve de rendu audio/vidéo physique ;
- vérification Play Pre-launch Report, accessibilité et navigation D-pad.

La production ne doit être ouverte qu'après validation de cette matrice et traitement
de toute alerte bloquante de la Play Console.
