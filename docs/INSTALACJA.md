# JARVIS: instalacja i używanie

JARVIS to asystent głosowy na Meta Quest 3. Mówisz „JARVIS, uruchom Spotify”, a gogle otwierają aplikację, a JARVIS odpowiada głosem. Działa w tle, także kiedy panel jest zamknięty.

## Czego potrzebujesz

- **Gogle w trybie dewelopera.** To samo, co przy Stopklatce (`docs/USTAWIENIA.md` w repo Stopklatki, kroki 1–2).
- **Kabel USB-C do przesyłu danych** i komputer z Windows.
- **Opcjonalnie klucz API Claude**, jeśli JARVIS ma rozmawiać, a nie tylko wykonywać komendy:
  1. Wejdź na <https://console.anthropic.com> i załóż konto.
  2. W **Billing** dodaj środki, na przykład 5 USD. To osobna opłata, niezależna od subskrypcji Claude.
  3. W **API Keys** kliknij **Create Key** i skopiuj klucz (zaczyna się od `sk-ant-`).
  4. Klucz wpiszesz tylko w skrypcie instalacyjnym albo w panelu JARVISA. Nikomu go nie wysyłaj.

## Instalacja

1. Pobierz repozytorium: `git clone https://github.com/Pl0T3R/jarvis` albo na GitHubie **Code → Download ZIP** i rozpakuj.
2. Podłącz gogle kablem i załóż je na chwilę. Przy pytaniu „Allow USB debugging” wybierz **Always allow from this computer**.
3. W folderze `jarvis` otwórz PowerShell i wpisz:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\install.ps1
   ```
   Skrypt robi kolejno:
   - instaluje `dist\jarvis.apk`,
   - daje JARVISOWI uprawnienia: mikrofon, powiadomienia, uruchamianie aplikacji z tła i działanie bez usypiania,
   - prosi o klucz API (Enter pomija ten krok).

   Jeśli na komputerze nie ma `adb`, skrypt sam pobierze je od Google.
4. Załóż gogle. JARVIS otwiera się sam i pobiera modele mowy, około 600 MB przez Wi-Fi, raz.
5. Gdy usłyszysz „JARVIS gotowy, sir.”, możesz mówić.

Później JARVIS znajdziesz w **Biblioteka → Nieznane źródła** (Library → Unknown Sources).

## Jak mówić

1. Powiedz „JARVIS” i od razu, bez pauzy, polecenie: „JARVIS, uruchom Spotify”.
2. Możesz też zrobić pauzę po „JARVIS”. Wtedy odpowie „Tak, sir?” i poczeka na polecenie.
3. Krótki dźwięk oznacza, że JARVIS usłyszał swoje imię.

| Mówisz | Co się dzieje |
| --- | --- |
| „uruchom / otwórz / odpal / włącz [aplikacja]” | Otwiera aplikację, np. Spotify, YouTube, Beat Saber, Stopklatkę |
| „włącz muzykę” | Otwiera aplikację do muzyki (np. Spotify) |
| „głośniej” / „ciszej” / „głośność na 50 procent” / „wycisz” | Głośność gogli |
| „która godzina?” / „jaki dziś dzień?” | Godzina i data |
| „ile mam baterii?” | Bateria gogli |
| „dziękuję” / „stop” | Koniec |
| „zapomnij rozmowę” | JARVIS zaczyna rozmowę od nowa |
| Dowolne pytanie, np. „jaka jutro pogoda w Warszawie?” | Odpowiada AI (tylko z kluczem API) |

Wszystkie komendy z tabeli działają offline i za darmo. Do internetu idą tylko pytania do AI.

## Panel JARVISA

| Ustawienie | Co zmienia |
| --- | --- |
| **Włącz / Wyłącz nasłuch** | Wyłączony JARVIS nie słucha, także po restarcie gogli |
| **Model** | Opus 5.5 jest najmądrzejszy. Haiku 5.5 jest najszybszy i kilkadziesiąt razy tańszy |
| **Wyszukiwanie w internecie** | Pogoda, wiadomości, wyniki |
| **Wykrywanie „JARVIS”: Dokładne** | Każde zdanie sprawdza mały Whisper. Najlepiej słyszy, ale gdy ktoś dużo mówi w pobliżu, gogle więcej liczą |
| **Wykrywanie „JARVIS”: Oszczędne** | Prawie nie obciąża gogli, ale czasem nie usłyszy imienia |
| **Rozpoznawanie poleceń** | „Dokładniejsze” lepiej zna polski. „Szybsze” odpowiada wcześniej |
| **Czułość na „JARVIS”** | Wyżej: łatwiej go zawołać, ale częściej zareaguje bez powodu |
| **Efekt głosu** | Ile „cyfrowego” brzmienia ma głos |

## Koszty i prywatność

- Dźwięk z mikrofonu nie opuszcza gogli. Rozpoznawanie mowy i głos JARVISA działają w goglach.
- Do Claude trafia tylko tekst pytań, których nie obsłużyła komenda lokalna.
- Orientacyjny koszt jednego pytania:

  | Model / rodzaj pytania | Koszt |
  | --- | --- |
  | Opus 5.5 | około 1–2 centów |
  | Pytanie z wyszukiwaniem w internecie | kilka centów |
  | Haiku 5.5 | ułamek centa |

  Wydatki widzisz w console.anthropic.com.
- Klucz API jest zapisany tylko w goglach. W panelu jest przycisk „Usuń klucz”.

## Gdy coś nie działa

| Objaw | Co zrobić |
| --- | --- |
| „Mikrofon zajęty” w trakcie gry | Gra używa mikrofonu (np. czatu głosowego). JARVIS wróci, gdy gra go zwolni |
| JARVIS nie słucha po restarcie gogli | Otwórz go raz z Biblioteki. Jeśli tak jest za każdym razem, daj znać Claude Code na komputerze |
| Nie otwiera aplikacji, mówi, że się nie udało | Uruchom `install.ps1` jeszcze raz, bo daje zgodę na uruchamianie z tła |
| Myli nazwę aplikacji | Powiedz ją wyraźnie po angielsku albo z kluczem API powiedz to po swojemu, AI dobierze aplikację |
| Reaguje bez wołania | Zmniejsz czułość w panelu |
| Nie reaguje na „JARVIS” | Zwiększ czułość albo wybierz „Dokładne” |
