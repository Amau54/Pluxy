# Restaurer Pluxy après un changement de PC

## Réinstallation

1. Installer Git et Python 3.11+ sur le nouveau PC.
2. Cloner `https://github.com/Amau54/Pluxy.git` ; se connecter à GitHub si l'accès
   au dépôt le demande.
3. Pour le serveur PC, installer Python 3.11+ avec PATH, puis lancer
   `Pluxy-Server.bat`. L'environnement Python est recréé automatiquement.
4. Pour Android, réinstaller le JDK et le SDK indiqués par le workflow
   `.github/workflows/build-apk.yml`, puis utiliser les scripts de construction.
   Ne pas restaurer les caches Gradle ni les chemins SDK de l'ancien PC.
5. Restaurer séparément les données privées du serveur dans
   `%LOCALAPPDATA%\Pluxy`, serveur arrêté. Adapter les chemins des médias aux
   nouveaux disques. Conserver aussi l'ancien `server/config.json` si utilisé.
6. Restaurer les clés de signature et leurs identifiants depuis une sauvegarde
   privée avant de produire un APK destiné à mettre à jour une installation.
   Les APK ne permettent pas de reconstruire la clé privée. Les clés ne doivent
   jamais être ajoutées au dépôt. Les variables de signature sont documentées
   dans le [guide de signature debug](DEBUG_SIGNING.md) et les fichiers Gradle.
   Restaurer les deux JKS et les identifiants portables de `recovery.json` depuis
   le support privé chiffré. Recréer `credentials.xml` avec le nouveau compte
   Windows : sa protection DPAPI n'est pas portable après formatage.

GitHub sauvegarde les fichiers versionnés, pas les médias personnels, les
réglages du serveur, les clés privées ou les modifications non commitées. Ils
ont été volontairement exclus du dépôt. Conserver les deux clés debug stables
créées le 4 octobre 2026 pour les prochaines mises à jour : une nouvelle clé
imposerait encore une réinstallation et la perte des données privées Android.

La release `dev-latest` peut être plus ancienne que `main`. Vérifier sa version,
son certificat et son `BUILD_INFO.json` avant installation.
