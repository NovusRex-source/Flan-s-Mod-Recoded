# Projekt: Flan's mod: Recoded

## 1. Projektübersicht
* **Name:** Flan's mod: Recoded
* **Plattform:** Minecraft 26.3 (Java 25, Fabric Loader 0.19.5, Loom 1.18)
* **Mappings:** Mojang-Namen (Minecraft ist seit 26.x unobfuskiert, Yarn existiert nicht mehr). Also `Player`, `Gui`, `GameRenderer` statt `PlayerEntity`, `InGameHud`.
* **Mod-Loader:** Fabric
* **Sprache:** Kotlin (via `fabric-language-kotlin`)
* **Architektur:** Ein datengetriebenes Framework. Die Mod fügt keine eigenen Waffen/Fahrzeuge hinzu, sondern lädt diese aus modularen, nutzergenerierten "Content Packs" (JSON, .bbmodel, .ogg).

## 2. Entwicklungs-Philosophie (CRITICAL)
* **Kein Rad neu erfinden:** Für jede Funktionalität MUSS geprüft werden, ob eine etablierte Fabric-Bibliothek existiert. Eigener Code wird nur für die spezifische Gunplay-Logik geschrieben.
* **Abhängigkeiten statt Custom-Code:** Externe Bibliotheken werden über `build.gradle.kts` importiert.
* **Fehlende Versionen:** Gibt es eine Bibliothek nicht für die aktuelle Minecraft-Version, wird eine kompatible Alternative gesucht. Nur wenn keine existiert, wird eine minimale eigene Lösung geschrieben. Kein Downgrade von Minecraft.

## 3. Vorgegebene Bibliotheken & Ressourcen
Wenn Code generiert wird, nutze zwingend diese Frameworks für die jeweiligen Aufgaben:

* **3D-Modelle & Animationen:** Nutze **GeckoLib** (5.5.x, eingebunden über Modrinth-Maven). Schreibe KEINE eigenen Entity- oder Item-Renderer für Blockbench-Modelle. GeckoLib übernimmt das Parsing der `.geo.json` / `.bbmodel` Dateien und das Abspielen der Nachlade-Animationen.
* **JSON-Parsing & Datenmodelle:** Nutze **kotlinx.serialization**. Keine manuellen JSON-Parser oder verschachtelten Gson-Konstrukte. Waffen-Konfigurationen werden als unkomplizierte `@Serializable` Data Classes abgebildet.
* **Content Pack Loading:** ARRP unterstützt 26.3 nicht. Stattdessen ist jeder Ordner/jedes Zip in `contentpacks/` ein normales Vanilla-Pack (`pack.mcmeta` + `assets/` + `data/`). `ContentPackSource` + `PackRepositoryMixin` hängen den Ordner als Pflicht-Pack in das Client-Resource- und das Welt-Datapack-Repository ein; Dateizugriff macht Vanilla (`FolderRepositorySource.discoverPacks`). Schreibe keinen eigenen File-I/O-Code. Waffen-Definitionen liegen unter `data/<namespace>/...` und werden über Reload-Listener der **Fabric Resource Loader API** gelesen.
* **UI & Config-Menüs:** Nutze **Cloth Config API** (+ **Mod Menu** für den Menüeintrag) für Einstellungsfenster. Keybinds über die Fabric Key Mapping API. (owo-lib unterstützt 26.3 noch nicht.) Keine Custom-Screen-Klassen für simple Buttons oder Slider.
* **Networking:** Nutze die native **Fabric Networking API** (ClientPlayNetworking / ServerPlayNetworking). Keine eigenen Packet-Buffer-Wrapper.

## 4. Kernmechaniken (Gunplay & Ballistik)
* **Mixin-Nutzung:** Verwende Mixins (SpongePowered) in Vanilla-Klassen (`Player`, `GameRenderer`, `Gui`), um Kamera-Rückstoß (prozedural), Aim-Down-Sights (ADS) und FOV-Änderungen umzusetzen.
* **Serverseitige Autorität:** Hit-Registration und Projektil-Flugkurven (Bullet Drop) werden immer serverseitig über Raytracing (`ProjectileUtil.getHitResultOnMoveVector` / `getEntityHitResult`) berechnet. Der Client rendert nur visuelle Interpolation und Partikel.

## 5. Arbeitsanweisung für die KI
* Bevor du komplexe Helfer-Klassen schreibst, frage oder prüfe, ob eine der oben genannten Libraries diese Funktion bereits anbietet.
* Halte den Code kurz und nutze Kotlin-spezifische Features (Extension Functions, Scope Functions wie `let` und `apply`).
* Kommentiere Mixins ausführlich, um Seiteneffekte mit anderen Mods (wie Sodium) zu vermeiden.
* Mixins werden in Java geschrieben (`src/*/java/.../mixin`), die eigentliche Logik in Kotlin.
* Build: `./gradlew build`, Test: `./gradlew runClient` / `runServer`. Der Gradle-Daemon nutzt Java 25 (`gradle/gradle-daemon-jvm.properties`).

## 6. Architektur (Stand)
* **Waffen-Item:** Es gibt genau ein Item `flansmod:gun`. Welche Waffe ein Stack ist, steht in der Data Component `flansmod:gun` (Id der Definition), geladene Munition in `flansmod:ammo`. Dadurch brauchen Content Packs keine Item-Registrierung und Waffen funktionieren nach `/reload`.
* **Definitionen:** `data/<ns>/flansmod/guns/<name>.json` → `GunDefinition` (kotlinx.serialization), geladen von `Guns` (Fabric Resource Loader v1), an Clients per `GunSyncPayload` synchronisiert.
* **Modelle:** GeckoLib-Dateien pro Waffe, Default-Pfade aus der Id abgeleitet: `assets/<ns>/geckolib/models/gun/<name>.geo.json`, `geckolib/animations/gun/<name>.animation.json`, `textures/gun/<name>.png`. Trigger-Animationen: `shoot`, `reload`.
* **Darstellung:** Das Basis-Itemmodell `flansmod:item/gun_base` hat keine Display-Transforms; `GunRenderer` wendet stattdessen `display` aus der Waffendefinition an (Blockbench/Vanilla-Format pro Kontext, plus `ads` für die Zielpose, überblendet mit dem Zielfortschritt). Fehlende Kontexte nutzen `Transform.DEFAULTS` (Lauf zeigt in Blockbench nach Norden/-Z).
* **Ballistik:** `Ballistics` simuliert Kugeln serverseitig als Punkte (kein Entity) mit Gravitation/Drag und Raycasts pro Tick. `GunHandler` erzwingt Feuerrate, Munition und Nachladen.
* **Effekte:** Der Server sendet pro Schuss `ShotPayload` an Schützen + Tracker; `ShotEffects` simuliert die Flugbahn clientseitig nur visuell (Tracer, Mündungsfeuer). Treffer entscheidet ausschließlich der Server.
* **Definitionen allgemein:** `DefinitionRegistry<T>` (in `gun/Guns.kt`) lädt `data/<ns>/flansmod/<ordner>/*.json`; `Guns` und `Attachments` sind Instanzen. Neue Inhaltstypen (z.B. Fahrzeuge) als weitere Instanz + Feld in `ContentSyncPayload`.
* **Aufsätze:** Item `flansmod:attachment` + Component `flansmod:attachment`; installierte Aufsätze in Component `flansmod:attachments` (Slot → Id) auf der Waffe. `ItemStack.definition` liefert immer die effektiven Werte inkl. Aufsätzen (`baseDefinition` ohne). Modell-Bones `attachment_<name>` / `default_<slot>` werden ein-/ausgeblendet.
* **Tastenbelegung:** R = Nachladen, J = Aufsatz anbringen (Schleichen = alle abnehmen). Vanilla 26.3 belegt V bereits (Debug) – freie Tasten vorher in `options.txt` prüfen.
* **Tests:** `./gradlew runGameTest` (Server-GameTests) und `./gradlew runClientGameTest` (echter Client, Screenshots unter `build/run/clientGameTest/screenshots`). Neue Gameplay-Features bekommen einen GameTest in `src/gametest`.
