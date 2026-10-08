# Publier sur Google Play

Pluxy réunit le lecteur et le serveur Android dans une seule application :

| Application | Package | Bundle à envoyer |
|---|---|---|
| Pluxy, lecteur et serveur Android | `com.pluxy.tv` | `Pluxy-<version>.aab` |

Les APK de test sont disponibles sur GitHub. La publication Play reste à préparer :
clés d'envoi, coordonnées du développeur, politique publique et essais sur appareils.
Les clés du canal APK sont décrites dans [DEBUG_SIGNING.md](DEBUG_SIGNING.md).

## Signature et secrets

Activer Play App Signing pour Pluxy. Conserver sa clé d’envoi hors de Git et en faire une sauvegarde privée. Ne pas reprendre une clé
debug ni une clé privée qui a déjà été exposée. Un certificat public peut être partagé.

Depuis un dossier privé, lancer cette commande. `keytool` demande les mots de passe :

```text
keytool -genkeypair -v -storetype JKS -keystore pluxy-player-upload.jks -alias pluxy-player-upload -keyalg RSA -keysize 4096 -validity 10000
```

Dans l'environnement GitHub `play-release`, renseigner les quatre secrets :

| Secret |
|---|
| `PLUXY_PLAYER_UPLOAD_JKS_BASE64` |
| `PLUXY_PLAYER_UPLOAD_STORE_PASSWORD` |
| `PLUXY_PLAYER_UPLOAD_KEY_ALIAS` |
| `PLUXY_PLAYER_UPLOAD_KEY_PASSWORD` |

Encoder les JKS en Base64 sur la machine qui les conserve. Copier la valeur dans le
secret, jamais dans un fichier suivi, un journal ou un artefact de compilation.

Vérifier les réglages GitHub avant la première publication : accès aux secrets limité,
environnement réservé à `main` et aux tags `v*`, protection des tags de version et
interdiction des force-pushes et suppressions de `main`. Activer la protection contre
les secrets si elle est disponible. Une approbation de `play-release` peut protéger
le déclenchement de production. Ces réglages se font dans GitHub ; le workflow ne les
active pas à lui seul.

## Construire la version

1. Mettre à jour `versionName` et `versionCode` dans `client/app/build.gradle.kts`.
   Augmenter `versionCode` par rapport à la dernière version distribuée.
2. Pousser le commit sur `main` et vérifier les contrôles concernés dans Actions.
3. Créer le tag `v<version>` sur la tête de `main`, ou lancer le workflow Android sur
   `main` avec `publish_release=true`.
4. Télécharger l’AAB Pluxy, `BUILD_INFO.json`, `SHA256SUMS.txt` et la SBOM de la release.
5. Télécharger aussi l’artefact `pluxy-r8-mapping-<version>` du run Actions.
   Le fichier `Pluxy-<version>-mapping.txt` est conservé 90 jours pour les rapports de crash.

Le workflow vérifie les tests Python, les dépendances, les tests Android et Android
Lint. Les variantes release passent par R8 et la réduction des ressources. Gradle
vérifie sa distribution et les sommes de dépendances enregistrées dans
`client/gradle/verification-metadata.xml`. Relire ces sommes lorsqu'une dépendance change.

Le job signé refuse les secrets manquants, un commit différent de la tête de `main`,
un tag qui ne correspond pas à `vMAJEUR.MINEUR.CORRECTIF`, une version différente du
tag et une release déjà publiée. Il contrôle les signatures et les sommes SHA-256.
Les tables R8 restent dans l'artefact Actions séparé.

Les APK debug et les applications installées depuis Play peuvent porter des
certificats différents. Pour changer de canal, sauvegarder les réglages disponibles
avant une désinstallation : elle efface les données privées de l'application.

La version 1.7.0 cible le SDK 36. Avant de cibler le SDK 37, prévoir l'autorisation
runtime `ACCESS_LOCAL_NETWORK` ou les sélecteurs système adaptés, avec gestion du
refus. Serveur, découverte et DLNA utilisent le réseau local. Voir la
[documentation Android](https://developer.android.com/privacy-and-security/local-network-permission).

## Remplir la fiche

Créer Pluxy dans Play Console, activer Play App Signing puis
renseigner la catégorie « Lecteurs et éditeurs vidéo », l'absence de publicité et
l'accès sans compte. Choisir les pays, le prix, le public cible et répondre au
questionnaire de classification selon le contenu réellement montré.

Le dépôt est actuellement privé. Le lien de confidentialité utilisé par les apps est :

```text
https://github.com/Amau54/Pluxy/blob/main/docs/PRIVACY_POLICY.md
```

Avant publication, rendre cette politique accessible sans connexion et ajouter un
contact public durable. Si elle est hébergée ailleurs, mettre aussi à jour le lien
dans les applications. Le nom du responsable doit correspondre au compte développeur.
Il reste également à choisir la licence de distribution du projet.

Pour le mode Serveur, déclarer le service premier plan `connectedDevice`. Joindre une
vidéo montrant les dossiers choisis, le démarrage du serveur, la notification avec
son bouton Arrêter et une lecture pendant que l'écran du téléphone est éteint.

Préparer l'icône 512 × 512, le visuel 1 024 × 500 et les captures demandées pour les
types d'appareils distribués. Montrer la navigation TV et, pour le serveur, le choix
des dossiers, l'envoi vers une TV et la notification. Utiliser ses propres médias ou
des médias autorisés ; masquer les clés, noms privés et adresses réseau. Les captures
doivent montrer la version réellement distribuée.

Conserver le logo et l'attribution TMDB dans « À propos / crédits ». Les sources et
conditions sont dans [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

### Texte pour Pluxy

**Description courte :**

> Films, séries, photos et musique de votre serveur local, sans compte.

**Description complète :**

> Pluxy lit les médias de votre serveur Pluxy sur Android TV ou sur un appareil
> Android. Connectez les appareils au même réseau, choisissez le serveur et ouvrez
> votre bibliothèque de films, séries, photos et musique.
>
> Vous pouvez rechercher un titre, reprendre une vidéo, choisir ses pistes audio et
> sous-titres, enchaîner les épisodes, afficher un diaporama ou écouter un album.
> Sur la télévision, les commandes sont accessibles à la télécommande.
>
> La même application peut partager les dossiers de cet appareil en mode Serveur,
> ou lire ceux d’un PC Windows ou d’un autre appareil Android en mode Lecteur. Le serveur PC peut
> adapter le flux ; le serveur Android partage le fichier original. La lecture
> dépend des formats pris en charge par l'appareil et de la qualité du réseau.
>
> Pluxy ne fournit aucun contenu. Il fonctionne sans compte et sans publicité sur
> un réseau domestique de confiance. Les échanges locaux utilisent HTTP sans
> chiffrement et les médias partagés sont accessibles aux appareils du réseau.

## Formulaire « Sécurité des données »

Google compte comme collecte les données envoyées hors de l'appareil, y compris sur
le LAN. Pour Pluxy, préparer **Oui** à la collecte et **Non** au chiffrement
de toutes les données en transit : les médias locaux passent en HTTP. L'absence de
télémétrie Pluxy ne dispense pas de déclarer ces échanges. Voir la
[définition Google Play](https://support.google.com/googleplay/android-developer/answer/10787469).

| Flux | Données | Usage et conservation |
|---|---|---|
| Lecteur → serveur choisi | Identifiant du média, commandes, progression et état vu | Lecture et reprise ; progression conservée sur le serveur jusqu'à effacement |
| Serveur → lecteur, TV DLNA ou Cast | Vidéos, photos, audio, titres, métadonnées et miniatures | Partage demandé ; fichiers transmis à la demande, index et caches sur les appareils |
| Découverte et connexion LAN | Adresses IP, nom, modèle et capacités des appareils | Connexion ; profils de compatibilité conservés sous un identifiant haché, sans adresse de récepteur persistée |
| SDK Cast → Google, en mode Serveur | Événements de découverte et de session, informations techniques sur l'appareil, le SDK et l'app | Mesure du fonctionnement de Cast ; collecte chiffrée propre au SDK |
| Serveur → TMDB, si une clé est fournie | Titre, année disponible, clé API et informations de connexion | Recherche des métadonnées en HTTPS ; facultatif |
| Lecteur → CDN TMDB | Requête d'image et informations de connexion | Affichage des affiches en HTTPS |

Déclarer les flux des deux modes dans la fiche de Pluxy : lecture, partage des fichiers,
découverte réseau et services facultatifs. Vérifier les catégories du formulaire selon
le comportement de la version publiée.

La finalité des échanges LAN est la fonctionnalité de l'application. Le transfert
vers un appareil choisi peut relever de l'exception de partage demandé par
l'utilisateur ; vérifier cette réponse dans le formulaire. Traiter séparément le
flux facultatif vers TMDB et la question du partage avec ce service.

Pour Cast, inclure au minimum les interactions de l'application à finalité d'analyse.
Google décrit des journaux anonymes, gardés brièvement puis agrégés, sans partage à
des tiers. Le SDK ne permet pas de désactiver cette collecte ni d'en demander la
suppression. Relire sa [fiche officielle](https://developers.google.com/cast/docs/android_sender/data_disclosure)
à chaque mise à jour et vérifier le comportement de la version intégrée.

Les sauvegardes Android sont désactivées. L'index, les réglages, les profils et la
progression restent jusqu'à effacement des données ou désinstallation. Pluxy ne vend
pas ces données et ne les transmet pas au développeur. La
[politique de confidentialité](PRIVACY_POLICY.md) décrit aussi TMDB, OMDb et YouTube.

## Essayer avant d'ouvrir la production

Envoyer d'abord les AAB sur la piste interne, puis sur une piste fermée. Compléter
[DEVICE_VALIDATION.md](DEVICE_VALIDATION.md) avec les modèles, versions Android,
résultats et problèmes rencontrés. Conserver le SHA du commit, le lien du run Actions,
les rapports `device_soak.py`, le rapport Play Pre-launch et les anomalies restantes.

Une simulation HTTP ne vérifie ni l'image et le son sur une télévision, ni le Wi-Fi
écran éteint, ni la navigation à la télécommande. Terminer ces essais et traiter les
alertes bloquantes de Play Console avant la production.
