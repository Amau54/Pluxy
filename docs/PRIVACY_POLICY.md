# Politique de confidentialité de Pluxy

Dernière mise à jour : 21 juillet 2026

Cette politique couvre les applications Android **Pluxy** (`com.pluxy.tv`) et
**Pluxy Serveur** (`com.pluxy.server`), ainsi que le serveur PC Pluxy distribué
avec le projet.

Le responsable de la publication et de la maintenance est **Amau54**, développeur
du projet Pluxy. Le nom de développeur affiché sur Google Play doit rester cohérent
avec cette politique ; si le compte de publication utilise une autre identité, ce
paragraphe et les coordonnées de contact devront être mis à jour avant publication.

## Principe général

Pluxy est un lecteur et serveur multimédia auto-hébergé destiné à un **réseau
domestique privé de confiance**. Il ne crée aucun compte, n'affiche aucune
publicité et n'intègre aucune télémétrie propre à Pluxy ni aucun suivi publicitaire.
Le SDK Google Cast facultativement utilisé dans Pluxy Serveur traite toutefois les
données techniques décrites dans la section « Services tiers facultatifs ».

Pluxy ne fournit aucun film, photo ni morceau. L'utilisateur choisit lui-même les fichiers auxquels
il souhaite donner accès et reste responsable de disposer des droits nécessaires.

## Données traitées localement

Selon les fonctions utilisées, Pluxy conserve localement :

- l'adresse et le nom du serveur choisi ;
- les préférences de lecture et de présentation ;
- les URI des dossiers explicitement sélectionnés avec le sélecteur Android ;
- l'index du catalogue, les titres déduits des noms de fichiers, les métadonnées
  audio artiste/album et les sous-titres ;
- la progression et l'état « vu » ;
- l'adresse IP locale des appareils pendant les échanges réseau ;
- des profils de compatibilité par télévision ; l'identifiant du récepteur est haché
  avant stockage et les erreurs de format restent uniquement sur le téléphone ;
- les clés TMDB ou OMDb saisies volontairement ;
- un cache d'affiches, de métadonnées et de miniatures photo bornées.

Les sauvegardes Android de l'application sont désactivées. La désinstallation ou
l'effacement des données Android supprime ces informations sur l'appareil. Pour le
serveur PC, les données peuvent être supprimées dans `%LOCALAPPDATA%\Pluxy` sous
Windows ou `~/.local/share/Pluxy` sous Linux/macOS après avoir arrêté le serveur.

## Partage sur le réseau local

Quand le serveur est actif, tous les appareils du LAN peuvent découvrir Pluxy,
consulter le catalogue et lire les médias des dossiers vidéo, photo et audio sélectionnés. Ce fonctionnement
sans compte ni authentification est volontaire. Le trafic multimédia local utilise
HTTP afin de rester compatible avec les télévisions et n'est pas chiffré.

N'utilisez pas Pluxy sur un Wi-Fi public et ne redirigez jamais le port 8420 depuis
Internet. Pluxy refuse les adresses Internet directes, mais un appareil compromis
déjà présent sur le réseau domestique peut accéder aux médias partagés.

## Envoi vers une télévision

Lorsque l'utilisateur choisit explicitement une télévision DLNA ou Google Cast, Pluxy
Serveur lui transmet sur le réseau l'URL locale du fichier choisi, son type, son titre,
sa durée et, si disponible, l'URL locale de sa miniature. La télévision demande ensuite
les octets du film, de la photo ou du morceau au serveur HTTP local. Ces informations
sont nécessaires à la lecture et ne sont pas envoyées au développeur Pluxy.

La découverte DLNA reste locale et n'utilise aucun serveur Pluxy. Elle peut révéler au
téléphone le nom, le modèle, l'adresse locale et les capacités annoncées des récepteurs.
Pluxy ne conserve pas leur adresse ; seul un profil indexé par identifiant haché reste
stocké jusqu'à sa réinitialisation, l'effacement des données ou la désinstallation.

## Services tiers facultatifs

Pluxy Serveur intègre le **SDK Android Sender Google Cast**, fourni avec Google Play
services. Il est sollicité pour découvrir et piloter un appareil Cast. Selon la
[déclaration officielle du SDK](https://developers.google.com/cast/docs/android_sender/data_disclosure),
Google collecte de façon obligatoire et chiffrée des événements génériques de découverte
et de session, des informations techniques sur l'appareil mobile, le SDK et l'application.
Google indique que ces journaux sont anonymes, conservés brièvement, agrégés pour améliorer
Cast, non utilisés pour modéliser un utilisateur et non partagés avec des tiers. Ni Pluxy
ni l'utilisateur ne peuvent désactiver ou supprimer cette collecte au niveau du SDK.
La fonction Cast reste facultative ; DLNA et l'application Pluxy TV n'en dépendent pas.

Si l'utilisateur configure une clé TMDB, Pluxy envoie à l'API de **The Movie
Database (TMDB)** le titre et, lorsqu'elle est disponible, l'année déduits du nom
d'un film ou d'une série. Les photos et morceaux ne sont pas envoyés à TMDB. TMDB
reçoit également les informations réseau normalement associées à
une requête Internet. Pluxy récupère ensuite synopsis, affiches, casting et liens de
bande-annonce. Consultez la [politique de confidentialité de TMDB](https://www.themoviedb.org/privacy-policy).

Le serveur PC peut également interroger OMDb si une clé OMDb est fournie. Ouvrir
une bande-annonce quitte Pluxy et ouvre YouTube ou une application compatible, qui
applique alors sa propre politique de confidentialité.

This product uses the TMDB API but is not endorsed or certified by TMDB.

## Transmission au développeur et conservation

Pluxy ne transmet au développeur ni bibliothèque, ni fichier, ni historique de
lecture, ni clé API. Les rapports de panne et journaux ne sont pas envoyés
automatiquement par Pluxy. La collecte technique propre au SDK Cast est transmise à
Google comme expliqué ci-dessus. Si l'utilisateur choisit de joindre manuellement un journal à un
rapport GitHub, il doit vérifier son contenu avant l'envoi.

Les échanges LAN constituent néanmoins une transmission hors de l'appareil au sens
du formulaire Google Play. Ils sont conservés uniquement sur les appareils Pluxy
concernés : index, configuration et progression restent jusqu'à leur effacement ;
les octets vidéo, photo ou audio sont envoyés à la demande et ne sont pas archivés
par le lecteur.

## Enfants

Pluxy n'est pas spécifiquement destiné aux enfants et ne collecte pas sciemment de
données personnelles d'enfants. Le contenu disponible dépend exclusivement des
fichiers choisis par le responsable du serveur domestique.

## Contact

Pour une question de confidentialité ou une demande concernant Pluxy, ouvrez une
demande sur le [suivi public du projet](https://github.com/Amau54/Pluxy/issues).

Toute modification importante de cette politique sera publiée dans ce fichier avec
une nouvelle date de mise à jour.
