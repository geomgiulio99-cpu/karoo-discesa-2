# Discese KOM — estensione Karoo 3

Estensione per Hammerhead Karoo 3 che porta i **live segment in discesa**, che
Strava non sincronizza sui ciclocomputer (filtra i segmenti con pendenza media
inferiore a -0,25%). L'estensione ricostruisce la funzione per conto proprio.

Repository: `karoo-discesa-2` (utente `geomgiulio99-cpu`), nato dal template
`hammerheadnav/karoo-ext-template`.

## Come si compila

Non c'è ambiente di sviluppo locale: la build gira su **GitHub Actions**
(`.github/workflows/build.yml`, `./gradlew assembleDebug`). L'APK esce come
artifact `karoo-app-debug` e si installa sul Karoo tramite l'app
**Hammerhead Companion** (condividi l'APK → Companion → Installa).

Dopo ogni push: attendere la build verde, scaricare l'artifact, installare.

## Struttura

    app/src/main/kotlin/io/hammerhead/karooexttemplate/
      MainActivity.kt                    app di servizio (sync, simulazione, armamento)
      extension/SegmentSync.kt           tutta la logica Strava
      extension/TemplateExtension.kt     estensione, tracker, campi dati, mappa
    app/src/main/res/layout/field_delta.xml
    app/src/main/res/xml/extension_info.xml
    app/src/main/res/values/strings.xml
    gradle/libs.versions.toml            karoo-ext DEVE essere 1.1.9 (la 1.1.3 del
                                         template non ha OnLocationChanged)

## Funzioni

- Sincronizza da Strava **tutti** i segmenti preferiti e li tiene in cache.
  Ognuno porta il flag `desc` (`average_grade < 0`): solo le discese vengono
  tracciate e armate, le altre stanno sulla mappa e basta. Il profilo
  altimetrico viene scaricato solo per le discese — una chiamata invece di due
  per tutti gli altri, e la loro mancanza di profilo non conta come
  sincronizzazione incompleta.
- Disegna le discese sulla mappa in arancione con bandierine INIZIO/FINE, e
  ogni altro preferito con una bandierina singola. Tutte riportano la
  velocità media del KOM davanti al nome.
- Campo dati **Discesa vicina**: metri dalla partenza della discesa più vicina.
- Campo dati **Distacco KOM** (grafico): dentro il segmento mostra a sinistra la
  media del KOM e la propria, a destra il distacco in grande (verde in vantaggio,
  rosso in ritardo) con sotto i km che mancano. Fuori dal segmento mostra la
  media oraria del giro in corso.
- Beep di avvicinamento, di ingresso e di uscita; giro automatico (`MarkLap`)
  **solo all'ingresso**; risultato finale fermo 15 secondi.
- Sincronizzazione automatica all'avvio dell'estensione, con notifica di sistema.
- Simulazione di una discesa dal pulsante nell'app, per collaudare senza uscire.
- Armamento manuale di un segmento: dalla mappa con "Vai a" su una bandierina,
  oppure dall'elenco nell'app. Ha la precedenza sulla scelta automatica e si
  consuma appena il segmento viene imboccato.
- Interruttore **"Salite e pianeggianti"** nell'app (spento di default, chiave
  `trackAll`). Acceso, l'estensione traccia anche i segmenti non in discesa —
  quelli che il Karoo lascia fuori dal suo tetto di 200 — cedendo il passo al
  live nativo dove c'è (vedi sotto). Spento, la lista tracciata torna alle sole
  discese e tutto il codice della cessione è inerte.

## Vincoli dell'API Strava (già verificati, non riesplorare)

- Le **classifiche** non sono più accessibili. Il KOM però arriva dal campo non
  documentato `xoms.kom` di `GET /segments/{id}`.
- Le tracce degli **altri atleti** non sono accessibili: il ritmo reale del
  recordman non è recuperabile. Per questo il confronto usa un modello di ritmo
  ricavato dal profilo altimetrico (`GET /segments/{id}/streams`), che
  distribuisce il tempo del KOM secondo le pendenze invece che a media costante.
- Il limite di richieste è la vera strettoia: **due chiamate per ogni segmento
  nuovo**. La sincronizzazione deve sempre salvare tutte le discese e arricchirle
  progressivamente — mai troncare la lista quando il limite viene raggiunto.

## Vincoli di karoo-ext (già verificati)

- Non esiste alcun evento di **tocco sulla mappa**. L'unico appiglio è
  `OnNavigationState.NavigatingToDestination`, che restituisce il POI scelto
  quando il rider preme "Vai a".
- Non è possibile inserirsi nella schermata nativa dei live segment. Esistono
  però i tipi di dato nativi `SEGMENT_KOM`, `SEGMENT_TIME`, `SEGMENT_PR` e
  simili: durante un segmento ufficiale si possono leggere tempo del KOM e
  lunghezza, e ricavarne la media, ma **non il nome del segmento**.
- `MarkLap` è un `data object` senza parametri: il menu lap che il Karoo mostra
  quando si segna un giro **non è sopprimibile**. O si segna il giro accettando
  il menu, o non lo si segna. Scelta presa: si segna solo all'ingresso.
- **La lista dei segmenti sincronizzati da Hammerhead non è esposta.** Non
  esiste modo di sapere in anticipo quali segmenti il nativo gestisca. Si scopre
  però in tempo reale: il tipo di dato `SEGMENT_TIME` emette valori solo mentre
  si è dentro un live segment nativo, quindi un consumer su quello fa da
  rilevatore. **Da verificare sul campo:** esiste un campo
  `SEGMENT_OFF_TIME_REMAINING` che fa sospettare che lo stream continui anche
  dopo l'uscita dal segmento; se fosse così la cessione sarebbe troppo generosa
  e `NATIVE_IDLE` andrebbe stretto.

## Il tetto dei 200 e la cessione al nativo

Il Karoo sincronizza **al massimo 200 segmenti Strava** (documentazione
Hammerhead ufficiale), e non sincronizza mai le discese. Oltre quella soglia il
live nativo semplicemente non esiste. La nostra estensione non ha alcun tetto:
scarica tutti i preferiti, quindi può coprire gli esclusi.

Il meccanismo di cessione riguarda **solo i segmenti non in discesa**, perché le
discese sono sempre nostre:

1. All'aggancio si parte **provvisori** (`provisional`): il cronometro corre, ma
   niente beep, niente giro, campo dati spento.
2. Se entro `NATIVE_WAIT` il cronometro nativo si accende, ritirata muta
   (`abortQuiet`) e il segmento finisce in `nativeHandled`, per non riprovarci.
3. Se non si fa vivo, si subentra: beep, giro e display, **tenendo il tempo già
   contato dall'aggancio** — i primi secondi non si perdono.
4. Se il nativo parte in ritardo, a subentro avvenuto, ci si ritira comunque.

L'avviso di avvicinamento e il campo "Discesa vicina" restano sulle sole
discese: sui non-discesa il preavviso lo dà già il nativo.
- I valori dei tipi di dato di sistema vanno letti con `dataPoint.singleValue`:
  il nome del campo cambia da tipo a tipo (per la media giro è `AVERAGE_SPEED`,
  non `SINGLE`).

## Lezioni imparate a caro prezzo (non ripetere questi errori)

- **Lo stato non va tenuto nei campi dati.** Il Karoo li ferma e riavvia quando
  vuole, azzerandolo: il tracciamento spariva a metà segmento. Tutto lo stato sta
  in `DescentTracker`, che vive nell'estensione.
- **Distanza dalla linea, non dai punti.** Le polilinee Strava sono semplificate
  e sui segmenti lunghi i punti distano centinaia di metri: misurando dal vertice
  più vicino si risultava "fuori traccia" pur essendo in strada.
- **Mai scartare un segmento senza KOM.** Alcune discese vengono ignorate da
  Strava: vanno tracciate comunque, mostrando il tempo che scorre.
- **L'aggancio non può basarsi su un cerchio attorno alla partenza:** ad alta
  velocità si scavalca tra due rilevamenti GPS. Si aggancia sul tracciato nel
  primo quarto, compensando il punto d'ingresso.
- **L'aggancio va però considerato provvisorio finché non si avanza davvero.**
  `onTrack` prende il punto più vicino in tutto il primo quarto: sui tornanti, o
  mentre si sta ancora salendo su una strada che passa entro `JOIN_OFF` dalla
  discesa, quel punto può essere centinaia di metri più avanti. Il cronometro
  partiva li' e la progressione restava ferma (è monotona, la posizione vera è
  più indietro): il distacco live saliva di un secondo al secondo, fino a
  decine di secondi, **senza che il risultato finale ne risentisse** — a fine
  segmento la distribuzione si annulla. Per questo il difetto si vedeva solo
  dal vivo. Ora la base scorre ogni 4 s finché non si vedono 15 m di
  avanzamento reale.
- **Un difetto che non tocca il numero finale può comunque rovinare quello
  live.** Verificare i due separatamente: il totale corretto non dimostra
  niente sulla correttezza istante per istante.
- **Progressione monotona e salti limitati** (80 m per secondo trascorso dall'ultimo
  rilevamento), altrimenti il rumore GPS fa credere di aver finito il segmento in
  anticipo.
- **Il cronometro parte dal punto di aggancio, non dalla linea di partenza,**
  e il KOM viene scontato della stessa frazione (`joinFrac`): le due misure
  devono sempre coprire lo stesso tratto di strada.
- **Alla chiusura non si regala il tratto non misurato.** Il tracciamento
  finisce quasi sempre con qualche metro non contato (polilinea semplificata,
  rilevamenti a ~1 Hz, progressione monotona): scontare comunque il KOM fino in
  fondo faceva uscire un risultato troppo generoso di diversi secondi — a fine
  discesa si leggeva 0 con 5-8 s di ritardo reale. Il tratto mancante va
  percorso al proprio ritmo medio.
- **Il limite di salto va commisurato al tempo trascorso,** non fisso: dopo una
  pausa dello stream di posizione un tetto fisso blocca la progressione per
  sempre, perché ogni rilevamento successivo è ancora più lontano.
- Il distacco è filtrato con una media esponenziale (alpha 0,15), altrimenti
  oscilla di decine di secondi sui segmenti lunghi.

## Collaudo

Verificare sul dispositivo, non solo compilare. La prova che smaschera i difetti
di stato: **entrare in un segmento, cambiare pagina e tornare indietro** — il
conteggio deve proseguire.

Per collaudare in casa: avviare un'attività, aprire l'app, premere
**SIMULA DISCESA**, tornare alla pagina di corsa (attenzione: genera giri veri).

Se si modifica la struttura dei dati salvati, cancellare i dati dell'app e
risincronizzare, altrimenti resta in uso la cache vecchia.
