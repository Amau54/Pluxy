# Confidentialité

Mise à jour : 4 octobre 2026

Cette politique concerne Pluxy (`com.pluxy.tv`), Pluxy Serveur (`com.pluxy.server`)
et le serveur PC. Le projet est maintenu par **Amau54**.

## Fonctionnement

Pluxy lit et partage les fichiers que vous choisissez sur votre réseau domestique.
Il fonctionne sans compte et sans publicité. Il n'intègre aucune télémétrie propre
à Pluxy ni aucun suivi publicitaire. Le SDK Google Cast traite ses propres données
techniques, décrites plus bas.

Pluxy ne fournit aucun film, photo ou morceau. Vous choisissez les dossiers partagés
et devez disposer des droits nécessaires sur leur contenu.

## Données sur vos appareils

Selon les fonctions utilisées, Pluxy conserve :

- l'adresse du serveur et vos réglages de lecture ;
- les accès aux dossiers sélectionnés avec le sélecteur Android ;
- le catalogue, les titres, métadonnées et informations de sous-titres ;
- les positions de reprise et l'état « vu » ;
- les affiches et miniatures mises en cache ;
- les clés TMDB ou OMDb que vous renseignez ;
- les profils de compatibilité des télévisions, sous un identifiant haché.

Les adresses IP locales servent aux échanges réseau. L'adresse d'un récepteur n'est
pas enregistrée dans son profil de compatibilité.

Les sauvegardes Android sont désactivées. « Effacer les données » ou désinstaller
l'application supprime ses réglages et caches sur cet appareil. La progression
enregistrée sur un autre serveur reste sur ce serveur. Vos fichiers dans les dossiers
partagés restent sur leur support.

Sur PC, arrêtez le serveur avant de supprimer ses données dans `%LOCALAPPDATA%\Pluxy`
sous Windows ou `~/.local/share/Pluxy` sous Linux/macOS. Si `PLUXY_DATA_DIR` est défini,
les données se trouvent dans son sous-dossier `Pluxy`.

## Réseau local

Lorsque le serveur est actif, les appareils de votre réseau local peuvent découvrir
Pluxy, consulter son catalogue et lire les médias des dossiers choisis. Il n'y a pas
de compte ni d'authentification pour ces accès. Les échanges locaux utilisent HTTP,
sans chiffrement, pour rester compatibles avec les télévisions.

Utilisez un réseau domestique de confiance. Ne partagez pas sur un Wi-Fi public et
ne redirigez pas le port du serveur vers Internet. Un appareil déjà présent sur
votre réseau peut accéder aux médias partagés.

Quand vous choisissez une télévision DLNA ou Google Cast, Pluxy lui envoie l'URL
locale du média, son type, son titre, sa durée et sa miniature si elle est disponible.
La télévision récupère ensuite le fichier auprès du serveur local. La découverte
DLNA traite le nom, le modèle, l'adresse et les capacités annoncées par le récepteur.
Le profil conservé peut être réinitialisé dans l'application.

## Services externes

**Google Cast.** Pluxy Serveur utilise le SDK Android Sender pour découvrir et piloter
les appareils Cast. Selon sa [déclaration officielle](https://developers.google.com/cast/docs/android_sender/data_disclosure),
Google reçoit des événements de découverte et de session ainsi que des informations
techniques sur le téléphone, le SDK et l'application. Les journaux sont anonymes,
chiffrés en transit, conservés brièvement puis agrégés pour améliorer Cast. Google
indique ne pas les partager avec des tiers. Le SDK ne permet pas à Pluxy ni à
l'utilisateur de désactiver cette collecte ou de supprimer ces données. L'utilisation
de Cast reste facultative ; DLNA et le lecteur Pluxy n'en dépendent pas.

**TMDB.** Si vous fournissez une clé, le serveur envoie en HTTPS à The Movie Database
le titre et l'année disponible d'un film ou d'une série pour récupérer ses informations
et affiches. Les photos et morceaux ne sont pas envoyés à TMDB. Le téléchargement
d'une affiche depuis son CDN transmet aussi les informations habituelles d'une
connexion Internet, dont l'adresse IP. Voir la
[politique de TMDB](https://www.themoviedb.org/privacy-policy).

This product uses the TMDB API but is not endorsed or certified by TMDB.

**OMDb et YouTube.** Le serveur PC peut consulter OMDb si vous renseignez une clé.
Ouvrir une bande-annonce lance YouTube ou une application compatible, avec sa propre
politique de confidentialité.

**Installation PC.** Au premier lancement sous Windows, le lanceur peut télécharger
Python depuis `python.org` et les dépendances depuis l'index Python configuré pour
`pip` (PyPI par défaut). Ces services reçoivent les informations nécessaires au
téléchargement ; le lanceur ne leur envoie pas votre bibliothèque.

## Conservation et transmission

Pluxy n'envoie au développeur ni fichiers, ni bibliothèque, ni historique de lecture,
ni clés API. Il ne transmet pas automatiquement les journaux ou rapports de panne.
Si vous joignez un journal à un signalement, vérifiez son contenu avant de l'envoyer.

Le catalogue, les réglages et la progression restent sur les appareils concernés
jusqu'à leur effacement. Les médias sont transmis à la demande. Des affiches,
miniatures et photos peuvent être mises en cache sur le lecteur ; il ne crée pas de
bibliothèque hors ligne des vidéos et morceaux. Les échanges locaux constituent
néanmoins une transmission hors de l'appareil dans le formulaire de sécurité des
données de Google Play.

Pluxy n'est pas spécifiquement destiné aux enfants. Le contenu disponible dépend des
fichiers choisis par la personne qui gère le serveur domestique.

## Contact

Pour une question, utilisez les [Issues du projet](https://github.com/Amau54/Pluxy/issues)
si vous avez accès au dépôt. Celui-ci est actuellement privé. Un contact public et
une copie de cette politique accessible sans connexion doivent être ajoutés avant
une distribution publique sur Google Play.

La date en haut de ce fichier sera mise à jour si cette politique change.
