# JARVIS na Meta Quest 3

Asystent głosowy działający w goglach, w tle. Mówisz „JARVIS, uruchom Spotify”, a gogle otwierają aplikację. JARVIS odpowiada po polsku spokojnym męskim głosem z lekkim „cyfrowym” efektem i zwraca się do Ciebie „sir”.

- **Komendy offline, za darmo:**
  - uruchamianie aplikacji,
  - głośność,
  - godzina i data,
  - bateria.
- **Rozmowa z AI:** odpowiedzi na dowolne pytania przez Claude API, z wyszukiwaniem w internecie. Potrzebny jest własny klucz API.
- **Mowa w goglach:** rozpoznawanie mowy (Whisper) i głos (Piper) działają w goglach. Dźwięk z mikrofonu nigdzie nie wychodzi.
- **Zawsze włączony:** usługa w tle z powiadomieniem i autostartem po restarcie gogli.

Głos nie jest kopią głosu aktora z filmów. To syntezator mowy z efektem w podobnym stylu.

## Jak zacząć

Instrukcja: **[docs/INSTALACJA.md](docs/INSTALACJA.md)**. W skrócie: tryb dewelopera, kabel, `install.ps1`.

Próbki głosu: [docs/probki/](docs/probki/).

## Stan

- **Sprawdzone w chmurze:**
  - kompilacja,
  - testy jednostkowe: komendy, dopasowanie nazw aplikacji, zapytania do Claude API na udawanym serwerze,
  - test całej ścieżki mowy na syntetycznym głosie (`tools/roundtrip.py`).
- **Niesprawdzone na goglach:** JARVIS nie był jeszcze uruchamiany na prawdziwym Queście.

Najważniejsze do sprawdzenia, czy Horizon OS pozwala:
- słuchać w tle podczas gier,
- uruchamiać aplikacje z tła.

Lista kontrolna jest w [CLAUDE.md](CLAUDE.md).

| Folder | Zawartość |
| --- | --- |
| `app/src/main/java/com/pl0t3r/jarvis/` | Aplikacja: usługa w tle, panel, pętla nasłuchu |
| `…/brain/` | Rozumienie poleceń: imię „JARVIS”, komendy po polsku, nazwy aplikacji, Claude |
| `…/speech/`, `…/audio/` | Słowo-klucz, wykrywanie mowy, Whisper, Piper, mikrofon, głośnik, efekt głosu |
| `tools/roundtrip.py` | Test mowy bez gogli |
| `dist/jarvis.apk` | Gotowa aplikacja |
