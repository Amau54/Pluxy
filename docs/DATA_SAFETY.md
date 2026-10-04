# Préparation du formulaire Google Play « Sécurité des données »

Ce document décrit le comportement de Pluxy 1.5.1. Il sert de fiche de saisie et ne
remplace pas la relecture de la taxonomie affichée par la Play Console au moment de
chaque publication.

Dans la terminologie Google Play, une donnée est « collectée » dès qu'elle est
transmise hors de l'appareil, même si elle reste sur le réseau local et n'est jamais
reçue par le développeur. Les deux applications doivent donc répondre **Oui** à la
question générale sur la collecte. Elles doivent aussi répondre **Non** à « toutes
les données collectées sont-elles chiffrées en transit ? », car le protocole LAN est
HTTP. Ne pas présenter Pluxy comme une application qui ne collecte aucune donnée.

## Commun aux deux applications

- aucun compte, publicité, télémétrie Pluxy ou suivi publicitaire ;
- aucune donnée envoyée au développeur Pluxy ;
- communication avec le serveur multimédia choisi par l'utilisateur sur son LAN ;
- sauvegarde Android désactivée ;
- suppression locale par « Effacer les données » ou désinstallation ;
- trafic LAN HTTP non chiffré, clairement annoncé dans l'application.

Les données LAN sont utilisées uniquement pour la fonctionnalité principale de
l'application. Elles ne sont ni vendues ni utilisées pour la publicité. Dans la
question distincte « partage », la transmission entre les deux applications est le
transfert demandé par l'utilisateur ; vérifier l'exception correspondante dans le
formulaire courant. La transmission facultative à TMDB est indiquée séparément
ci-dessous afin de retenir la déclaration la plus prudente.

## Pluxy lecteur (`com.pluxy.tv`)

Le lecteur reçoit du serveur choisi le catalogue, les métadonnées, vidéos, photos,
miniatures et fichiers audio, puis
envoie les identifiants de média sélectionnés, décisions de lecture et positions de
reprise. Ces échanges restent sur le LAN. Il télécharge éventuellement des images
depuis `image.tmdb.org` et ouvre YouTube uniquement après une action de l'utilisateur.

Déclaration prudente à préparer :

| Type Play à vérifier | Collectée | Obligatoire | Finalité | Conservation |
|---|---:|---:|---|---|
| Activité dans l'application : progression, état vu, média choisi | Oui | Oui | Fonctionnalité de l'application | Sur le serveur choisi, jusqu'à suppression |
| Photos et vidéos / fichiers audio / fichiers et documents : média demandé et ses métadonnées | Oui | Oui | Fonctionnalité de l'application | Échange LAN ; pas de copie par le développeur |
| Identifiants de l'appareil ou autres : adresse IP réseau | Oui | Oui | Communication et sécurité réseau | Traitement éphémère côté lecteur ; état de décision borné côté serveur PC |

Le média reçu par le lecteur est un téléchargement entrant et n'est pas renvoyé par
celui-ci. Les requêtes HTTPS vers le CDN TMDB transmettent cependant l'adresse IP et
les données techniques normalement associées à une connexion Internet ; vérifier si
la version courante du formulaire les classe dans « Identifiants de l'appareil ou
autres identifiants ».

## Pluxy Serveur (`com.pluxy.server`)

L'utilisateur sélectionne jusqu'à trois dossiers par le Storage Access Framework :
vidéos, photos et musique. Pluxy indexe les noms, tailles et caractéristiques utiles,
génère des miniatures photo bornées et rend les médias accessibles sur le LAN.
La progression envoyée par les lecteurs est également conservée. La clé et l'URI du
dossier restent dans le stockage privé de l'application et ne sont pas incluses dans
les sauvegardes Android.

Déclaration prudente à préparer :

| Type Play à vérifier | Collectée | Obligatoire | Finalité | Conservation |
|---|---:|---:|---|---|
| Photos et vidéos : vidéos, photos, miniatures et métadonnées | Oui | Oui | Fonctionnalité de l'application | Média transmis à la demande ; index et cache de miniatures locaux jusqu'à suppression |
| Fichiers audio : morceaux et métadonnées artiste/album | Oui | Oui | Fonctionnalité de l'application | Audio transmis à la demande ; index local jusqu'à suppression |
| Activité dans l'application : progression et état vu | Oui | Oui | Fonctionnalité de l'application | Stockage local jusqu'à suppression |
| Identifiants de l'appareil ou autres : adresse IP LAN du lecteur | Oui | Oui | Communication réseau | Traitement éphémère, non conservé par le serveur Android |

Quand l'utilisateur choisit un récepteur DLNA ou Cast, son nom, modèle, adresse locale
et capacités sont traités pour la découverte. L'URL LAN, le titre, le type, la durée et
la miniature du média sélectionné sont transmis au récepteur pour exécuter la lecture.
Le profil de compatibilité est stocké localement sous une clé dérivée d'un hachage de
l'identifiant du récepteur ; l'adresse IP du récepteur n'est pas persistée.

### SDK Google Cast

Le SDK Android Sender Google Cast est intégré à Pluxy Serveur. Sa
[fiche Data Safety officielle](https://developers.google.com/cast/docs/android_sender/data_disclosure)
indique une collecte automatique de données **Activité dans l'application** relatives
aux interactions de découverte et de session, ainsi que d'informations techniques sur
l'appareil mobile, le SDK et l'application. Google précise que :

- la collecte est obligatoire lorsque le SDK/fonction concerné est utilisé et ne peut
  pas être désactivée ou supprimée par le développeur ou l'utilisateur ;
- les journaux sont anonymes, chiffrés en transit, conservés brièvement puis agrégés ;
- ils servent à mesurer l'adoption, les performances et les défauts de Cast ;
- le SDK ne les partage pas avec des tiers ni avec d'autres applications.

Dans la Play Console, ajouter au minimum la catégorie actuelle correspondant à
**Activité dans l'application / interactions avec l'application**, collectée pour
« Analyse » ou la finalité équivalente proposée par le formulaire. Revalider aussi si
les « informations sur l'appareil » exigent la catégorie Identifiants de l'appareil dans
la taxonomie du jour. Cette transmission Google est chiffrée, mais la réponse globale
reste **Non** au chiffrement intégral puisque les médias LAN utilisent HTTP.

Si une clé TMDB est fournie, le titre et l'année déduits du fichier sont transmis à
TMDB en HTTPS. Déclarer prudemment ce flux facultatif comme « Fichiers et documents »,
collecté et partagé avec TMDB pour les fonctionnalités/personnalisation de
l'application. Il est facultatif : le serveur fonctionne sans clé TMDB.

## Points à déclarer avec prudence

Avant chaque envoi en production :

- comparer cette fiche au trafic réel de la version publiée ;
- contrôler la qualification « collectée », « partagée », obligatoire/facultative et
  la durée de conservation pour chaque type ;
- conserver la réponse **Non** au chiffrement intégral tant que le LAN utilise HTTP ;
- relire la fiche Data Safety Google Cast à chaque mise à jour de Media3/Play services ;
- mettre à jour simultanément la politique de confidentialité et ce document si un
  autre SDK, service tiers ou mécanisme de télémétrie est ajouté.

Les appels directs à TMDB, au CDN TMDB et à YouTube sont HTTPS, mais ils ne rendent
pas chiffré le trafic vidéo local.
