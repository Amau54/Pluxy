# Signature du canal APK debug

Le canal de test utilise deux clés privées stables depuis le **4 octobre 2026**, une
pour le lecteur et une pour le serveur Android. Les identifiants Android restent
inchangés. Ces clés permettent les mises à jour du même canal et sont conservées hors
du dépôt, séparément des clés d'envoi Google Play.

## Installer ou changer de canal

Une APK portant un autre certificat ne peut pas mettre à jour directement
l'application installée. Avant de rejoindre le canal actuel :

1. Noter les réglages et sauvegarder les données exportables. Ne pas supposer qu'une
   sauvegarde Android sera restaurable après un changement de certificat.
2. Désinstaller l'ancienne application après avoir accepté la perte de ses données privées.
3. Installer la nouvelle APK, puis reconfigurer connexion, permissions et dossiers.

La désinstallation efface préférences et cache privés. Le serveur Android doit recevoir
à nouveau les autorisations d'accès aux dossiers. Les médias stockés hors de
l'application restent sur leur support ; le serveur PC et ses données ne sont pas
réinstallés. Les scripts d'installation refusent une incompatibilité et ne désinstallent
pas automatiquement l'application.

Cette réinstallation n'est requise que pour changer d'identité. Les mises à jour avec
le même certificat conservent normalement les données.

## Certificats publics du canal actuel

Ces empreintes sont publiques ; elles ne permettent pas de reconstruire les clés privées.

| Application | Package | Certificat SHA-256 |
|---|---|---|
| Lecteur debug | `com.pluxy.tv` | `871e112ad4dff59554897fc54b105622614c08912972357bbcc0798ac9b936e3` |
| Serveur Android debug | `com.pluxy.server` | `961050702a4555191209e980c0a04f752981f02bc0d1cd2a8cafa96f43b3a626` |

Les clés debug ne doivent pas être réutilisées comme clés `UPLOAD` ni comme clés finales
Play App Signing. Une clé anciennement exposée reste compromise, même après suppression
du fichier ou de son historique. La procédure Play est décrite dans
[PLAY_STORE_RELEASE.md](PLAY_STORE_RELEASE.md).

## Build Windows et dossier privé

Sur un PC configuré, la signature debug est conservée hors du dépôt :

```text
%LOCALAPPDATA%\Pluxy\Signing\debug-2026-10\
  pluxy-player-debug.jks
  pluxy-server-debug.jks
  credentials.xml
  recovery.json
```

Les alias sont `pluxy-player-debug` et `pluxy-server-debug`. Les JKS contiennent les
clés privées. Le dossier doit être réservé au compte Windows concerné.

`credentials.xml` contient des mots de passe SecureString protégés par Windows DPAPI.
`Build-APK.bat` charge temporairement les identifiants des modules demandés et retire
les variables créées après le build. Les variables `PLUXY_PLAYER_DEBUG_*` et
`PLUXY_SERVER_DEBUG_*` explicites sont prioritaires ; une configuration partielle est refusée.

Sans ces identifiants, le build utilise la clé debug Android locale. Gradle lancé
directement doit recevoir les variables de signature pour utiliser les clés stables.
Les APK de contrôle GitHub peuvent porter une signature temporaire : téléchargez
`dev-latest` pour obtenir le certificat du canal publié.

## Sauvegarde et restauration

**Sauvegarder ensemble les deux JKS et `recovery.json` sur un support privé chiffré.**
Ce dernier contient les mots de passe portables en clair : les permissions Windows
protègent l'accès local, mais ne chiffrent pas son contenu. Ne jamais copier ces fichiers
dans Git, des pièces jointes publiques, des artefacts CI ou des journaux.

DPAPI lie `credentials.xml` au compte et à l'installation Windows d'origine. Copier
ce fichier après formatage ne suffit pas à déchiffrer les mots de passe. Les JKS seuls
ne permettent pas de reprendre la signature sans leurs identifiants.

Après restauration, placer les JKS dans un dossier privé et recréer `credentials.xml`
avec les chemins actuels et le nouveau compte Windows. Pour un build ponctuel, fournir
au processus les quatre variables de chaque application :

- `PLUXY_PLAYER_DEBUG_STORE_FILE`, `PLUXY_PLAYER_DEBUG_STORE_PASSWORD`,
  `PLUXY_PLAYER_DEBUG_KEY_ALIAS`, `PLUXY_PLAYER_DEBUG_KEY_PASSWORD` ;
- `PLUXY_SERVER_DEBUG_STORE_FILE`, `PLUXY_SERVER_DEBUG_STORE_PASSWORD`,
  `PLUXY_SERVER_DEBUG_KEY_ALIAS`, `PLUXY_SERVER_DEBUG_KEY_PASSWORD`.

Vérifier le certificat réel de l'APK avant distribution. Garder les clés existantes :
en générer d'autres imposerait une nouvelle réinstallation.

## Publication GitHub

Le canal utilise huit secrets Actions : pour chacun des préfixes
`PLUXY_PLAYER_DEBUG_` et `PLUXY_SERVER_DEBUG_`, les suffixes `JKS_BASE64`,
`STORE_PASSWORD`, `KEY_ALIAS` et `KEY_PASSWORD`. Le Base64 d'un JKS est aussi secret
que le fichier lui-même. Une copie du dépôt n'inclut pas les valeurs des secrets ;
elles doivent être configurées dans le dépôt de publication.

La publication attend les contrôles Python, Android et APK du même run. Elle vérifie
packages, versions, certificats et hashes avant d'actualiser `dev-latest`.
`BUILD_INFO.json` et `SHA256SUMS.txt` décrivent les binaires publiés.
Le certificat du canal précédent est contrôlé ; aucune rotation automatique n'est prévue.

Référence : [signature et conservation des clés Android](https://developer.android.com/studio/publish/app-signing).
