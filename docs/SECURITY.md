# Sécurité

Pluxy est prévu pour un réseau domestique de confiance. Tous les appareils de ce
réseau peuvent voir le serveur et lire les fichiers des dossiers partagés.
Il n'y a ni authentification ni chiffrement des flux locaux.

## Utilisation

- Choisissez uniquement les dossiers que vous voulez partager.
- N'ouvrez pas les ports TCP 8420 et UDP 8421 sur la box.
- N'exposez pas le serveur par un proxy ou un tunnel public.
- Arrêtez-le avant de rejoindre un Wi-Fi public.
- Gardez le système, Python, FFmpeg et les applications à jour.

Les connexions sont limitées aux adresses locales. Pluxy ne crée pas de redirection
UPnP. La configuration sensible du serveur PC reste accessible depuis `localhost`.
Les clés API et les chemins privés ne sont pas renvoyés aux lecteurs.

## Clés et publications

Gardez les clés privées, mots de passe et réglages personnels hors de Git.
Un certificat public peut être partagé. Une clé privée déjà exposée doit être
remplacée, même après nettoyage de l'historique.

Les APK de test utilisent les [clés debug stables](DEBUG_SIGNING.md).
La [publication Google Play](PLAY_STORE_RELEASE.md) utilise d'autres clés et
nécessite sa propre configuration. Les secrets manquants ou les tests sur appareils
non réalisés doivent être réglés avant de publier.

## Signaler un problème

Utilisez un rapport privé dans l'onglet Security du dépôt si cette option est disponible.
Sinon, ouvrez un [ticket](https://github.com/Amau54/Pluxy/issues) bref pour convenir
d'un échange privé. N'y joignez pas de clé, journal personnel ou preuve de concept
contenant des données privées. L'accès au suivi dépend de vos droits sur le dépôt.
