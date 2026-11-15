# Wolof Health – Hinweise für Claude

- Für jede Arbeit an Gestaltung, Layout, Texten in der App oder Animationen immer zuerst den
  Skill **frontend-design-studio** laden und befolgen.
- Gestaltung: kühles Blaugrau (Nacht-Indigo auf «Schlaf»), Schrift Archivo (schmal/fett für Zahlen),
  Farben kodieren Datentypen (Blau Bewegung, Rot Puls, Violett Schlaf, Bernstein Wachphasen/kcal).
  Keine KI-Klischees: kein Creme/Terrakotta, keine ALL-CAPS-Labels, keine Mono-Labels, keine Mittelpunkt-Trenner.
- App-Texte kurz halten: ein Element, eine Aufgabe.
- Nur echte Daten aus Health Connect; Standardquelle ist das Band (Google Health/Fitbit).
- Neue Version veröffentlichen: `./release.sh "Was neu ist"` (Schlüssel siehe README, liegt in Google Drive).
- Vor dem Veröffentlichen Screenshots mit `gradle testDebugUnitTest -Proborazzi.test.record=true` ansehen.
- Animationen beim Betreten einer Seite: so kurz wie möglich (Reveal 1100 ms bei jedem Besuch, startet sofort mit der Ziel-Seite).
- Seitenaufbau: jede Seite passt genau über die Taskleiste. Ein Hauptelement pro Seite skaliert (Ringe, Schrittwoche, Hypnogramm), alles andere natürliche Grösse; Restplatz = gleichmässige Abstände zwischen Gruppen. Nie Diagramme verzerren.
- Architektur (ab 2.0): `data/HealthRepo` liest Health Connect und baut `DaySummary` pro Tag (Cache `DayCache` für Tage vor gestern),
  `data/Analytics` enthält alle eigenen Werte (Zonen, TRIMP, Schlafwert, Erholung, Normalbereich, Belastung 7/28, Schlafbedarf, Trends),
  `data/Insights` die Nachtwerte mit Bereich. Detailansichten in `ui/Sheets.kt` (dürfen scrollen, die vier Seiten nicht).
  Widget `widget/RingsWidget`, Hintergrund und Hinweise in `work/Background.kt`.
- Google Health schreibt keine aktiven kcal und kein SpO2 nach Health Connect: aktive kcal = Gesamt − Grundumsatz (Profil), als Schätzung kennzeichnen.
- Eigene Werte sind Näherungen und tragen eigene Namen (Erholung, Schlafwert, Belastung); Bestandteile immer in der Detailansicht zeigen.
- Wochenansichten haben feste Plätze (7 Tage, 14 Nächte), damit sie mit wenig Daten nicht zerfallen.
