# Récepteurs DLNA et Google Cast

Pluxy 1.5.1 prend en charge DLNA et Google Cast. La validation physique multi-marques
reste obligatoire avant d'annoncer une compatibilité large ou une diffusion Play en production.

## Objectif et périmètre

Le téléphone serveur peut envoyer un film, une photo ou un morceau personnel vers un
récepteur du LAN, tout en conservant le parcours autonome de l'application Pluxy TV :

- Direct Play et qualité originale en premier choix ;
- accès sans compte sur un réseau domestique privé de confiance ;
- lecture, pause, seek et arrêt depuis le téléphone et sa notification ;
- aucun transcodage simulé sur Android : une incompatibilité est expliquée avant l'envoi ;
- le serveur PC conserve ses profils adaptatifs lorsque l'application Pluxy TV le lit.

## Architecture livrée

| Module | Responsabilité | Dépendance du lecteur TV |
|---|---|---:|
| `receiver-core` | contrats, plan Direct Play, état et profils appris par récepteur | aucune |
| `receiver-dlna` | SSDP, description UPnP, `GetProtocolInfo`, DIDL-Lite et AVTransport | aucune |
| `receiver-cast` | session officielle Media3 Cast, sélecteur système et commandes | aucune |
| `serverapp` | onglets Vidéos/Photos/Audio, service au premier plan et notification | utilise les trois modules |

Une panne SSDP ou l'absence des services Google ne bloque donc ni le serveur HTTP ni
l'application Pluxy TV. Le contrôleur de lecture distante appartient au processus du
service et non à l'activité : rotation, retour à l'accueil ou verrouillage de l'écran
ne détruisent pas volontairement la session.

## DLNA / UPnP AV

- découverte SSDP seulement lorsque l'écran Récepteurs est visible ;
- verrou multicast libéré après chaque fenêtre, 32 appareils maximum et timeouts courts ;
- URL de description et endpoints SOAP limités à des adresses IP privées ou link-local,
  sans redirection ni nom DNS afin de réduire le risque SSRF/rebinding ;
- XML limité à 512 Kio, DTD et entités externes interdits ;
- capacités lues dans `ConnectionManager.GetProtocolInfo`, avec profil prudent si le
  téléviseur n'annonce rien d'exploitable ;
- `SetAVTransportURI`, `Play`, `Pause`, `Seek`, `Stop` et position interrogée toutes les
  deux secondes ; métadonnées DIDL-Lite adaptées à la vidéo, la photo ou l'audio ;
- flux Pluxy HTTP Range avec en-têtes de transfert DLNA.

Limites honnêtes : les sous-titres externes, le volume `RenderingControl` et la reprise
après extinction complète du récepteur ne sont pas promis dans cette version. Les
variantes DLNA des fabricants doivent être confirmées sur le matériel listé dans
[DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

## Google Cast

- initialisation et bouton de route officiels Media3 ;
- `RemoteCastPlayer` avec titre, pochette, état, position et commandes ;
- profil volontairement prudent, puis apprentissage local par appareil ;
- absence d'une session Cast ou des services Google affichée comme indisponibilité
  locale, sans casser le reste de Pluxy ;
- erreur de conteneur, vidéo ou audio mémorisée séparément : un échec H.265 ne condamne
  ni MP4, ni AAC, ni les autres téléviseurs.

Le SDK Cast et Google Play services constituent un service tiers. Pluxy n'ajoute aucun
cloud ni compte propre ; les règles de confidentialité Google peuvent toutefois
s'appliquer à la découverte et à la session Cast.

## Décision avant lecture

1. Le média et le profil du récepteur sont comparés localement.
2. Compatible : envoi direct de l'URL HTTP Range originale.
3. Compatibilité incertaine sur Android : raisons affichées et bouton explicite
   « Essayer en Direct Play ».
4. Incompatible sur le serveur PC avec le client Pluxy TV : remux ou transcodage adapté
   selon le diagnostic existant, avec Direct Play conservé dès que possible.
5. Après une erreur déterministe, seul le composant refusé réduit le profil de cette TV ;
   une lecture stable enrichit le profil sans inventer de plafond 4K.

## Veille, réseau et sécurité

- le service `connectedDevice` conserve les verrous processeur/Wi-Fi pendant une session
  active et les libère à son arrêt ;
- découverte multicast arrêtée lorsque l'activité n'est plus visible ;
- notification persistante avec lecture/pause, arrêt TV et arrêt serveur ;
- identifiants de récepteurs hachés avant stockage des profils ;
- aucune URI SAF, chemin local ou clé TMDB transmise au récepteur ;
- tout appareil du LAN de confiance peut néanmoins atteindre les médias : Wi-Fi public,
  VPN exposé et redirection du port 8420 restent hors périmètre.

La version 1.5.1 cible le SDK 36. Android 17
rend cette permission runtime obligatoire pour une application ciblant le SDK 37 : sa
demande contextualisée devient un prérequis de la prochaine montée de cible.

## Porte de sortie production

L'implémentation logicielle est complète, mais la fonction ne doit être annoncée comme
compatible multi-marques qu'après :

- 100 cycles découverte/connexion/arrêt sans fuite de socket ou verrou ;
- deux heures écran éteint sur Pixel/AOSP, Samsung et Xiaomi/Redmi ;
- Samsung, LG et Philips en DLNA, puis Chromecast et Google TV ;
- seek répété, coupure/reprise Wi-Fi et arrêt du récepteur ;
- H.264/AAC, HEVC, HDR, MKV, photo et audio avec résultat consigné ;
- Play Pre-launch Report sans alerte bloquante.

Le protocole et l'outillage sont prêts ; ces résultats physiques ne peuvent pas être
déduits d'un test unitaire ni déclarés réussis sans les appareils.
