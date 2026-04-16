# FocusTimer — Guide de lancement

Appli Android anti-distraction. Bloque partiellement Instagram (whitelist : DMs + Profil uniquement), et totalement TikTok + YouTube pendant 100 secondes.

---

## Prérequis

- Android Studio (Giraffe ou plus récent)
- Android SDK + platform-tools installés (inclus avec Android Studio)
- Un Xiaomi (ou autre device Android) avec **Android 7+ (API 24)**, OU l'émulateur Android Studio
- Câble USB (pour un device physique)

**Chemin adb par défaut sur cette machine :**
```
C:\Users\mrbln\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

---

## Première configuration du téléphone (une seule fois)

### 1. Activer le mode développeur

Paramètres → À propos du téléphone → tape 7 fois sur **Version MIUI** (ou "Numéro de build").

### 2. Activer le débogage USB

Paramètres → Paramètres supplémentaires → Options pour les développeurs → active :
- **Débogage USB**
- **Installer via USB**
- **Débogage USB (Paramètres de sécurité)** (Xiaomi uniquement)

### 3. Brancher et autoriser

Branche le câble USB. Sur le tel, une pop-up "Autoriser le débogage USB depuis cet ordinateur" → coche "Toujours autoriser" → OK.

Change le mode USB sur **"Transfert de fichiers"** (pas "Charge uniquement", sinon adb ne voit rien).

### 4. Vérifier

Dans Android Studio, en haut, le device doit apparaître dans la liste déroulante à côté du bouton Run.

---

## Lancer l'appli

### Depuis Android Studio

1. Sélectionner le device (Xiaomi ou émulateur) dans la barre du haut
2. Cliquer **Run** (▶)
3. Attendre l'installation automatique

### Permissions à accorder la première fois

Deux permissions sont nécessaires, demandées automatiquement au premier lancement :

**A. Permission d'overlay**
- L'app ouvre directement le bon écran de réglages
- Active le toggle "Afficher par-dessus les autres apps"
- Retour

**B. Service d'accessibilité (obligatoire)**
- Paramètres → Accessibilité → Applications téléchargées → **FocusTimer**
- Active le toggle
- Sur Xiaomi : **attendre le compte à rebours de 10s** avant de pouvoir confirmer (sécurité MIUI imposée par l'OS, pas contournable via l'UI — cf. astuce adb plus bas)
- Confirmer

Une fois ces deux permissions données, l'app tourne en arrière-plan (notification permanente "FocusTimer est actif").

---

## Astuces adb (gros gain de temps en dev)

### Activer le service d'accessibilité sans passer par l'UI

Bypass le compte à rebours de 10 secondes :

```
adb shell settings put secure enabled_accessibility_services com.example.focustimer/com.example.focustimer.AppCheckService
adb shell settings put secure accessibility_enabled 1
```

Si `adb` n'est pas reconnu dans PowerShell, utiliser le chemin complet :
```
C:\Users\mrbln\AppData\Local\Android\Sdk\platform-tools\adb.exe shell ...
```

### Désactiver le service

```
adb shell settings put secure enabled_accessibility_services ""
```

### Réactiver MainActivity si elle a été désactivée par "Cacher l'app"

```
adb shell pm enable com.example.focustimer/com.example.focustimer.MainActivity
```

### Ajouter adb au PATH Windows (une seule fois)

Menu Démarrer → "variables d'environnement" → Variables d'environnement → Path (utilisateur) → Modifier → Nouveau → coller :
```
C:\Users\mrbln\AppData\Local\Android\Sdk\platform-tools
```
OK partout, **fermer et rouvrir PowerShell**. Ensuite `adb version` doit répondre.

### Raccourcis PowerShell (optionnel)

Ouvrir le profil PowerShell :
```
notepad $PROFILE
```

Coller :
```powershell
function accessi-on {
    adb shell settings put secure enabled_accessibility_services com.example.focustimer/com.example.focustimer.AppCheckService
    adb shell settings put secure accessibility_enabled 1
    Write-Host "FocusTimer accessibility activé" -ForegroundColor Green
}

function accessi-off {
    adb shell settings put secure enabled_accessibility_services ""
    Write-Host "FocusTimer accessibility désactivé" -ForegroundColor Yellow
}

function focustimer-enable {
    adb shell pm enable com.example.focustimer/com.example.focustimer.MainActivity
    Write-Host "MainActivity réactivée" -ForegroundColor Green
}
```

Sauver, fermer, rouvrir PowerShell. Utilisable ensuite via `accessi-on`, `accessi-off`, `focustimer-enable`.

---

## Workflow de développement

### Ne pas désinstaller entre deux runs

La permission d'accessibility et overlay **persistent tant que l'app n'est pas désinstallée**. Android Studio fait de l'incremental install par défaut (Run classique), ce qui garde les permissions.

À éviter :
- "Uninstall app" dans le menu Run
- "Clean and Rerun"
- Désinstallation manuelle depuis le tel

En pratique : Stop + Run = tout reste intact.

### Le bouton "Cacher l'appli" est désactivé en debug

En build debug (ce que fait Android Studio), le bouton "Cacher" affiche juste un toast d'avertissement et ne fait rien — pour éviter de perdre l'icône pendant le dev.

En build release (signée), le comportement normal revient : le bouton désactive MainActivity et l'icône disparaît du launcher.

### Tester la fonctionnalité whitelist Instagram

1. Installer Instagram sur le tel si pas déjà fait
2. Lancer Instagram
3. Sur Feed / Reels / Explore : overlay gris avec image + message, **barre du bas accessible**
4. Taper sur l'onglet Profil (en bas à droite) : overlay disparaît
5. Taper sur l'icône des DMs (en haut à droite dans Insta) : overlay disparaît
6. Revenir sur Feed : overlay réapparaît

Si la détection Profil ne marche pas bien (langue système particulière, update Instagram), ajuster les mots-clés dans [`InstagramScreenDetector.kt`](app/src/main/java/com/example/focustimer/InstagramScreenDetector.kt) (constantes `ALLOWED_CLASS_KEYWORDS` et `ALLOWED_SELECTED_TAB_KEYWORDS`).

---

## Dépannage

### "Activity class {...MainActivity} does not exist"

Le bouton "Cacher" a été cliqué en release build, MainActivity est désactivée. Solutions :

1. **Réactiver via adb** (plus rapide) :
   ```
   adb shell pm enable com.example.focustimer/com.example.focustimer.MainActivity
   ```

2. **Désinstaller + réinstaller** (perd les permissions) :
   - Désinstaller FocusTimer du tel
   - Run dans Android Studio
   - Réaccorder overlay + accessibility

3. **Code secret** (ne marche pas toujours sur Xiaomi, le dialer intercepte) :
   - Ouvrir l'app Téléphone, composer `*#*#36287#*#*`

### L'app ne détecte pas que je suis sur Instagram / le service ne semble pas tourner

- Vérifier que le service accessibility est bien **activé** : Paramètres → Accessibilité → FocusTimer → toggle ON
- Vérifier que la notification permanente "FocusTimer est actif" est visible
- Sur Xiaomi, vérifier que l'appli a le droit d'autostart : Sécurité → Autorisations → Démarrage automatique → FocusTimer ON
- Sur Xiaomi, dans les réglages de l'appli : "Économie de batterie" → **Pas de restrictions**

### L'overlay Instagram couvre la barre du bas

La hauteur réservée est `bottomReserveDp = 90` dans [`AppCheckService.kt`](app/src/main/java/com/example/focustimer/AppCheckService.kt) (fonction `showInstagramBlockingOverlay`). Si la bottom nav est plus haute sur ton tel, augmenter à 100 ou 110.

### Android Studio ne voit pas le téléphone

- Le câble est-il en mode "Transfert de fichiers" et pas "Charge uniquement" ?
- La pop-up "Autoriser le débogage USB" a-t-elle été acceptée ?
- Essayer : `adb kill-server` puis `adb start-server`
- Sur Xiaomi, vérifier que "Installer via USB" est activé dans les options développeur

---

## Architecture rapide

```
app/src/main/
├── AndroidManifest.xml                             permissions + service accessibility
├── java/com/example/focustimer/
│   ├── MainActivity.kt                             écran principal + bouton Cacher
│   ├── AppCheckService.kt                          AccessibilityService principal
│   ├── InstagramScreenDetector.kt                  logique whitelist DM/Profil
│   └── SecretReceiver.kt                           réactivation via code *#*#36287#*#*
└── res/
    ├── layout/
    │   ├── activity_main.xml                       UI MainActivity
    │   ├── overlay_layout.xml                      overlay plein écran + timer (TikTok, YouTube)
    │   └── instagram_overlay_layout.xml            overlay partiel Instagram
    └── xml/
        └── accessibility_service_config.xml        config du service
```

Applis ciblées actuellement :
- `com.instagram.android` — whitelist (DM + Profil OK, reste bloqué par overlay partiel)
- `com.zhiliaoapp.musically` (TikTok) — overlay plein écran + timer 100s
- `com.google.android.youtube` — overlay plein écran + timer 100s

Pour ajouter une nouvelle appli bloquée avec timer : ajouter son package dans la liste `timerTargetApps` dans `AppCheckService.kt`.
