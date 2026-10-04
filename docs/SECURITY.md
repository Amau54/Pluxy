# Sécurité de Pluxy

## Modèle de confiance

Pluxy est volontairement ouvert à tous les appareils d'un LAN domestique privé :
aucun compte, mot de passe ou appairage n'est demandé pour découvrir le serveur,
lister le catalogue ou lire un média.

Les protections ne transforment pas un réseau non fiable en réseau sûr. Elles
empêchent principalement une exposition accidentelle directe à Internet :

- seules les adresses loopback, RFC1918, link-local et IPv6 ULA sont acceptées ;
- aucune redirection UPnP n'est créée ;
- l'administration sensible du serveur PC est limitée à `localhost` ;
- les clés API, chemins média et exécutables ne sont jamais renvoyés à la TV ;
- les URL de flux, photos et miniatures locales restent sur le serveur sélectionné ;
- les affiches externes sont limitées au CDN TMDB et les bandes-annonces à YouTube ;
- les miniatures photo sont décodées avec une taille et une concurrence bornées pour
  ne pas affamer un flux vidéo en cours ;
- connexions, scans, éléments indexés, enrichissements et transcodages sont bornés.

## Déploiement sûr

1. Utiliser uniquement un Wi-Fi/Ethernet domestique protégé.
2. Ne jamais rediriger les ports TCP 8420 ou UDP 8421 sur la box.
3. Ne pas placer le serveur derrière un proxy Internet ou un tunnel public.
4. Garder Windows, Android, Python, FFmpeg et les dépendances à jour.
5. Choisir uniquement les dossiers vidéo, photo et audio à partager. Pluxy ne demande
   pas d'accès global au stockage.
6. Arrêter le serveur avant de rejoindre un réseau public.

## Signature et provenance

Les clés privées, mots de passe et réglages personnels doivent rester hors de Git.
Un certificat public peut être partagé ; il ne permet pas de reconstruire sa clé privée.
Toute clé anciennement exposée reste compromise, même après nettoyage du dépôt, et
ne doit pas servir à une nouvelle publication. Une release officielle doit utiliser
Play App Signing, deux clés d'envoi distinctes, l'environnement GitHub protégé
`play-release`, puis les contrôles
de signature, SBOM et sommes SHA-256 décrits dans
[`PLAY_STORE_RELEASE.md`](PLAY_STORE_RELEASE.md).

Les deux clés privées du nouveau canal debug, créées le 4 octobre 2026, sont
conservées hors du dépôt et distinctes des clés d'envoi Google Play. Leur migration
et leur sauvegarde sont décrites dans [DEBUG_SIGNING.md](DEBUG_SIGNING.md).

## Signalement

Ne publiez pas immédiatement une preuve de concept contenant des données privées.
Ouvrez d'abord un ticket minimal sur
[GitHub Issues](https://github.com/Amau54/Pluxy/issues) pour convenir d'un canal de
transmission approprié.
