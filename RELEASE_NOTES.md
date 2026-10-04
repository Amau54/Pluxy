# Pluxy 1.5.1

Version Android commune aux deux applications : **1.5.1 · versionCode 46**.

- Interface TV modernisée avec surfaces glass, lueur discrète et focus télécommande lisible.
- Bibliothèques personnalisables, recherche unifiée, reprise, épisode suivant, photos et musique.
- Catalogue Android restauré en arrière-plan et conservé si une source devient inaccessible.
- Cycle de démarrage et d'arrêt du serveur Android ordonné, connexions réseau libérées à leur fermeture.
- Construction debug ciblée avec cache, installation ADB vérifiée et détection des APK déjà installées.
- Publication validée par les contrôles Python et Android, avec certificats et sommes SHA-256 vérifiés.

## Signature du canal de test

Deux clés debug stables distinctes sont utilisées depuis le 4 octobre 2026 : une pour
`com.pluxy.tv`, une pour `com.pluxy.server`. Les identifiants Android restent inchangés.

Une ancienne APK portant un autre certificat nécessite une réinstallation unique.
Sauvegardez les réglages disponibles avant de désinstaller : les données privées de
l'application sont effacées. Les médias stockés hors de l'application restent sur leur
support. Les mises à jour utilisant le même certificat conservent normalement les données.

Les APK debug sont destinées aux tests hors Google Play. La publication Play utilise
des clés séparées et exige la validation sur appareils réels.

[Téléchargements](../../releases/tag/dev-latest) ·
[Signature et sauvegarde](docs/DEBUG_SIGNING.md) ·
[Validation des appareils](docs/DEVICE_VALIDATION.md).
