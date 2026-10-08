# Clés des APK de test

Depuis Pluxy 1.7.0, le canal GitHub `dev-latest` distribue une seule APK,
`com.pluxy.tv`, qui conserve la clé stable de l’ancien lecteur. Le serveur est
intégré à cette application. La clé historique `com.pluxy.server` reste à
conserver pour les anciennes versions. Les clés Google Play sont séparées.

## Changer de signature

Android refuse de mettre à jour une application avec une APK signée par une autre clé.
Dans ce cas :

1. Notez vos réglages et sauvegardez ce qui peut être exporté.
2. Désinstallez l'ancienne application.
3. Installez la nouvelle APK et refaites les réglages et autorisations de dossiers.

La désinstallation efface les préférences et le cache privés. Les fichiers médias
stockés hors de l'application restent sur leur support. Les scripts ADB refusent
ce changement et ne désinstallent rien automatiquement.

Avec la même clé, les mises à jour conservent normalement les données.

## Certificats actuel et historique

| Application | Certificat SHA-256 |
|---|---|
| `com.pluxy.tv` | `871e112ad4dff59554897fc54b105622614c08912972357bbcc0798ac9b936e3` |
| `com.pluxy.server` (ancien serveur séparé) | `961050702a4555191209e980c0a04f752981f02bc0d1cd2a8cafa96f43b3a626` |

Ces empreintes sont publiques. Les clés privées et leurs mots de passe restent hors de Git.
Les clés debug ne doivent pas servir à la [publication Google Play](PLAY_STORE_RELEASE.md).
Une clé privée déjà exposée doit être remplacée, même si le dépôt a été nettoyé.

## Construire avec les clés stables

Sur le PC de développement, les fichiers sont dans :

```text
%LOCALAPPDATA%\Pluxy\Signing\debug-2026-10\
  pluxy-player-debug.jks
  pluxy-server-debug.jks
  credentials.xml
  recovery.json
```

Les alias sont `pluxy-player-debug` et `pluxy-server-debug`.
`Build-APK.bat` charge `credentials.xml` pour les applications demandées,
puis retire les variables qu'il a créées. Les variables déjà définies restent prioritaires.
Une configuration partielle est refusée.

Pour une configuration manuelle, définissez les quatre variables du lecteur :

| Application unifiée |
|---|
| `PLUXY_PLAYER_DEBUG_STORE_FILE` |
| `PLUXY_PLAYER_DEBUG_STORE_PASSWORD` |
| `PLUXY_PLAYER_DEBUG_KEY_ALIAS` |
| `PLUXY_PLAYER_DEBUG_KEY_PASSWORD` |

Sans ces identifiants, un build local utilise la clé debug Android de ce PC.
Gradle lancé directement a besoin des variables pour utiliser les clés stables.
Les APK de contrôle de la CI peuvent aussi avoir une autre signature.
Utilisez `dev-latest` pour installer celles du canal publié.

## Sauvegarder les clés

Conservez ensemble les deux JKS et `recovery.json` sur un support privé chiffré.
`recovery.json` contient les mots de passe en clair. Les permissions Windows
limitent l'accès au fichier, mais ne chiffrent pas son contenu.

`credentials.xml` utilise Windows DPAPI : il est lié au compte et à l'installation
Windows d'origine. Après un formatage ou un changement de PC, recréez-le à partir
des identifiants sauvegardés et des nouveaux chemins. Copier le fichier seul ne suffit pas.

Ne mettez aucun de ces fichiers dans Git, un journal ou un artefact public.
Gardez les clés actuelles pour les prochaines mises à jour.

## Publication GitHub

Pour le préfixe `PLUXY_PLAYER_DEBUG_`, les secrets Actions utilisent les suffixes `JKS_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS` et
`KEY_PASSWORD`, soit quatre secrets. Le Base64 du JKS est lui aussi secret.
Leurs valeurs doivent être configurées dans le dépôt qui publie les APK.

La publication attend les contrôles Python, Android et APK du même run, vérifie
les fichiers et les certificats, puis actualise `dev-latest`. Si les secrets
manquent, le canal publié reste inchangé. Il n'y a pas de rotation automatique des clés.
Les fichiers `BUILD_INFO.json` et `SHA256SUMS.txt` accompagnent les APK.
