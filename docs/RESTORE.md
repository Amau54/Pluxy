# Sauvegarder et changer de PC

## Ce qu'il faut garder

- Vos médias, sur leur disque ou une sauvegarde séparée.
- Le dossier `%LOCALAPPDATA%\Pluxy` : réglages, clés API, catalogue et progression du serveur PC.
- L'ancien `server/config.json`, si vous l'utilisez encore.
- Les clés de signature Android et leurs identifiants, si vous construisez les APK.

Si `PLUXY_DATA_DIR` est défini, sauvegardez son sous-dossier `Pluxy` à la place du chemin par défaut.
Sous Linux/macOS, le dossier par défaut est `~/.local/share/Pluxy`.
Arrêtez le serveur avant de copier ses données. Les clés et mots de passe doivent
rester sur un support privé chiffré.

GitHub ne sauvegarde que les fichiers suivis par Git. Les réglages, médias et
clés privées sont exclus du dépôt.

## Sur le nouveau PC

1. Téléchargez et extrayez le ZIP de Pluxy, ou clonez `https://github.com/Amau54/Pluxy.git`.
   Connectez-vous à GitHub si l'accès au dépôt le demande.
2. Restaurez les données du serveur dans leur dossier, serveur arrêté.
3. Sous Windows 10 ou 11, lancez `Pluxy-Server.bat`. Python et les composants
   nécessaires sont installés automatiquement. Internet est nécessaire la première fois.
4. Vérifiez les chemins des dossiers médias : les lettres de lecteur peuvent avoir changé.

Pour construire Android, réinstallez JDK 17, SDK 36, platform-tools et build-tools 36,
puis utilisez `Build-APK.bat`. Les caches Gradle et les anciens chemins SDK
n'ont pas besoin d'être restaurés.

## Retrouver la signature des APK

Restaurez les deux JKS et les mots de passe sauvegardés dans `recovery.json`.
Recréez `credentials.xml` avec le nouveau compte Windows : sa protection DPAPI
n'est pas portable après un formatage. La procédure et les variables sont dans
[DEBUG_SIGNING.md](DEBUG_SIGNING.md).

Une APK ne permet pas de retrouver sa clé privée. Changer de clé impose une
réinstallation sur Android et efface les données privées de l'application.

`dev-latest` contient le dernier build publié, qui peut être plus ancien que `main`.
Sa version et ses certificats sont indiqués dans `BUILD_INFO.json`.
