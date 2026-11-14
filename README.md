# Wolof Health

Private Android-App: zeigt die Daten des Fitbit Air (via Google Health → Health Connect)
in eigener Gestaltung. Nur Lesezugriff auf Health Connect; Internet nur für den Update-Check.

## Updates
Die App liest `updates/version.json` aus diesem Repo. Ist `versionCode` höher als die
installierte Version, bietet sie das Update an, prüft die SHA-256 und installiert über den
System-Installer.

Neue Version veröffentlichen:

    ./release.sh "Was neu ist"

## Signaturschlüssel
Alle Versionen müssen mit demselben Schlüssel signiert sein, sonst lässt Android das Update
nicht zu. Der Schlüssel (`wolof-health-release.jks` + `keystore.properties`) liegt **nicht**
in diesem Repo, sondern privat in Google Drive im Ordner «Wolof Health Signierschlüssel».
Vor dem Bauen in einen Ordner legen und `WOLOF_SIGNING_DIR` darauf zeigen lassen.
Geht der Schlüssel verloren, muss die App einmal deinstalliert und neu installiert werden.

Schrift: Archivo (SIL Open Font License, siehe FONT-LICENSE-OFL.txt).
