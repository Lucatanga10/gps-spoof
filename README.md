# GPS Spoof

App Android che imposta una posizione GPS finta (mock location) usando la funzione
ufficiale di Android per gli sviluppatori. Due modalità:

- **Posizione fissa**: metti latitudine/longitudine e tutte le app che usano il GPS
  vedono te in quel punto (es. Formigine → Modena, Italia → Austria).
- **Percorso**: metti una lista di punti (lat,lng) e l'app "cammina" da uno all'altro
  alla velocità scelta, come se ti muovessi davvero.

---

## 1. Compilare l'APK su GitHub (fai questo)

Non serve installare niente sul PC. GitHub compila da solo.

1. Vai su https://github.com/new e crea un repository nuovo (es. `gps-spoof`).
   Puoi lasciarlo **privato**.
2. Carica **tutti** i file di questa cartella nel repository:
   - Sul sito: bottone **Add file → Upload files**, trascina dentro TUTTO il
     contenuto della cartella (comprese le cartelle `app` e `.github`), poi
     **Commit changes**.
   - Oppure da terminale, dentro questa cartella:
     ```
     git init
     git add .
     git commit -m "gps spoof"
     git branch -M main
     git remote add origin https://github.com/TUO-UTENTE/gps-spoof.git
     git push -u origin main
     ```
3. Sul repository apri la scheda **Actions** in alto. Parte da sola la build
   "Build APK" (1-3 minuti). Aspetta il pallino verde.
4. Clicca sulla build finita → in fondo, sezione **Artifacts** → scarica
   **gps-spoof-apk**. Dentro c'è `app-debug.apk`.

> Se la scheda Actions chiede di abilitare i workflow, premi il bottone verde
> "I understand my workflows, enable them".

---

## 2. Installare l'APK sul telefono

1. Copia `app-debug.apk` sul telefono (o scaricalo direttamente dal telefono).
2. Aprilo. Android chiederà di consentire "installa app da questa sorgente" →
   accetta.
3. Installa e apri **GPS Spoof**. Dai il permesso di **posizione** e **notifiche**.

---

## 3. Attivare la mock location (OBBLIGATORIO)

Senza questo passo NON funziona.

1. Impostazioni → **Info sul telefono** → tocca **Numero build** 7 volte per
   sbloccare le **Opzioni sviluppatore**.
2. Impostazioni → Sistema → **Opzioni sviluppatore** (il bottone nell'app ti ci
   porta).
3. Cerca **"Seleziona app posizioni fittizie"** (*Select mock location app*) e
   scegli **GPS Spoof**.

---

## 4. Usare l'app

**Trovare le coordinate**: su Google Maps tieni premuto un punto → in alto/in
basso compaiono i numeri tipo `44.6469, 10.9252`. Primo = latitudine, secondo =
longitudine.

- **Posizione fissa**: incolla lat e lng, premi **Imposta posizione fissa**.
- **Percorso**: nella casella metti un punto per riga, es.

  ```
  44.6400,10.9200
  44.6450,10.9250
  44.6469,10.9252
  ```

  Imposta la velocità (5 km/h = camminata), premi **Avvia percorso**. Metti più
  punti intermedi per seguire le vie reali (l'app va in linea retta tra un punto
  e il successivo).
- **STOP** ferma tutto e ripristina il GPS vero.

---

## Limiti importanti (leggi)

- Le app con GPS normale (Maps, meteo, social) vedranno la posizione finta.
- App con **anti-spoof** (banche, alcuni giochi come Pokémon GO, alcune app di
  consegne/dating) **rilevano** la mock location e la bloccano o ti segnalano.
  Senza root non si aggira al 100%.
- Il percorso va in **linea retta** tra i punti che dai. Per seguire le curve
  delle strade, aggiungi più punti intermedi.
