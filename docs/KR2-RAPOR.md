# Kırılım–Retest 2. tur: 3 dakikalık tarama, ayrı koruma, yeniden üretim ve sınırlı araştırma

14 Kasım 2026 · sürüm 4.2.0-paper · yalnız sanal işlem. Ön kayıt: [KR2-ONKAYIT.md](KR2-ONKAYIT.md) (commit `8019487`,
sonuçlardan önce).

## Sonuç

**Avantaj bulunamadı. Strateji başarılı değildir.**

- **Yeniden üretim:** İlk turun sonuçları aynı veri ve kodla birebir yeniden üretildi. Bayt düzeyinde aynı işlem
  CSV'leri; ikinci kez de yeni kodla, varyant anahtarları kapalıyken.
- **Değişiklikler:** Dört gerekçeli, tek değişkenli değişiklikten yalnız V1 (daha geniş stop tamponu) araştırma
  döneminde ön kayıtlı seçim kuralını geçti.
- **Değerlendirme:** V1 değerlendirmede zararda kaldı.

| Değerlendirme | İşlem | Net | Ort. net R | Kâr faktörü (PF) |
| --- | --- | --- | --- | --- |
| MEXC (Ağu–Kas 2026) | 61 | −67,80 USDT | −0,49 | 0,24 |
| Binance (2023-11 → 2025-10) | 417 | −167,23 USDT | — | 0,65 |

- **Sınıf:** "Başarısız".
- **Uygulama:** Ön kayıt §7 gereği temel kurallarla (B0) kaldı.

Bu turdaki asıl iş altyapıdır:

- 3 dakikalık tarama;
- ayrı ve sık koruma;
- fiyat akışı;
- olayların sırayla yeniden işlenmesi;
- mum önbelleği;
- ana ekranda tarama, bağlantı ve koruma durumu.

Bunlar sinyal üretme sıklığını değiştirmez: giriş yine yalnız kapanmış 15M mumla olur. Risk ve veri kontrolleri
gevşetilmedi; günlük kota yok.

## 1. Tarama ve koruma (uygulama)

### Tarama (3 dakikada bir)

- **Zamanlama:** UTC saatine hizalıdır (:00, :03, …, +10 sn). 15M kapanışları tarama anlarına denk gelir; retest
  kapanışından ~10 saniye sonra karar verilir.
- **Çakışma yok:** Önceki tarama sürüyorsa yenisi başlamaz ve "atlanan" olarak sayılır (`KrPaper.scan`, `scanLock`).
- **CMC listesi:** Günde bir yenilenir. Anahtar girilince hemen alınır.
- **Mumlar:**
  - Yalnız **yeni bir 15M mum kapandığında** ve yalnız yeni mumlar indirilir.
  - Mumlar diskte önbelleklenir (`paper-mumlar/`); uygulama yeniden açıldığında yalnız eksik mumlar gelir.
  - Ara taramalarda mum isteği yoktur; yalnız tek bir toplu anlık fiyat isteği yapılır.
- **Sinyal:**
  - Yalnız kapanmış 15M mumdan. 3 dakikalık tarama, kapanmamış mumu teyit saymaz.
  - Her düzenin kimliği (sembol + kırılım mumu + seviye) bir kez işlenir.
  - Aynı kırılım ya da aynı kapanmış mum ikinci kez işlem üretmez.
- **Gösterge:** Son başarılı tarama, süresi, gecikmesi, sonraki tarama, atlanan sayısı ve son hata kayda yazılır; ana
  ekranda görünür.

### Koruma (taramadan ayrı)

- **Sıklık:** Açık pozisyon varken 10 saniyede bir.
- **Fiyat akışı:** MEXC WebSocket bookTicker (protobuf, 100 ms) açık pozisyonlara abone olur. Stop/hedef geçilince koruma
  **anında** çalışır; sert stop 15M kapanışını beklemez.
- **Akış yoksa:** Akış eskiyse (15 sn) REST'e düşülür.
- **Sıralı yeniden oynatma** (`KrExits.replay`):
  - Son denetimden sonra kapanan 1 dakikalık mumlar zaman sırasıyla işlenir.
  - Her dakikada sıra: bekleyen kapanış çıkışı → sert stop → hedef.
  - Çeyrek saat kapanışlarında tez iptali, ilerleme yok ve en uzun süre kuralları işlenir.
  - Ardından anlık fiyata bakılır.
  - Bağlantı koptuysa olaylar gerçekleştikleri sırayla işlenir. Örnek: 15. dakikadaki tez iptali, 30. dakikadaki stoptan
    önce gelir (test: `KrLoopsTest`).
- **İzleme boşluğu:**
  - Açık pozisyon varken 90 saniyeden uzun denetimsiz aralık "izleme boşluğu" olarak kaydedilir.
  - O aralıktaki çıkış "canlı izlenmedi" diye işaretlenir.
  - Boşluk, son denetim ile giriş zamanından hangisi daha geçse oradan başlar. Bu turda bulunan hata: bot giriş yaptığı
    anda durursa boşluk kaydedilmiyordu.

### Android arka plan

- **Ön plan servisi:** WorkManager'ın en kısa periyodik aralığı 15 dakikadır; 3 dakikalık tarama ve sürekli stop takibi
  için uygun değildir.
- **Kalp atışı:** Servis kalp atışı ve sonraki tarama zamanı süreç içinde tutulur.
- **Servis durursa** (uygulama kapatıldı, sistem durdurdu), ana ekran şunu gösterir:
  - "Servis çalışmıyor: tarama yapılmıyor";
  - açık pozisyon varsa kırmızı "Koruma izlenemiyor" uyarısı.

  Tarama yapılmış gibi gösterilmez.

### Ana ekran

Ana ekranda sırayla:

- son tarama (saat, kaç sn önce, süre, gecikme, atlanan);
- sonraki tarama;
- MEXC bağlantısı (son başarılı veri);
- fiyat akışı (bağlı / yeniden bağlanıyor / kapalı);
- açık pozisyon varsa koruma (son denetim, kaynak).

Ekran görüntüleri: `screenshots/kr/01-ana-ekran.png`, `01b-ana-ekran-koruma-izlenemiyor.png`.

### Canlı deneme (bu ortamda, uygulama koduyla, gerçek MEXC + CMC)

İki çalıştırma, toplam 12 tarama. Hepsi 3 dakikada bir yapıldı; gecikme 0–8 ms, hata 0, çakışma 0. Evren: ilk 300 → MEXC'de
215 → taranan 211.

| Tarama | Süre |
| --- | --- |
| Ara tarama (yeni 15M mum yok, yalnız anlık fiyat) | 0,5–0,8 sn |
| 15M kapanışından sonra (212 coinin yeni mumu), sıralı indirme | 45,6 sn |
| Aynı durum, eşzamanlı indirme (6 istek) | 12,5 sn (karar kapanıştan ~22 sn sonra) |
| Yeniden başlatma, ilk tarama: önbellek boş | 101 sn |
| Yeniden başlatma, ilk tarama: dolu disk önbelleği | 14,6 sn |

Bu sürede kabul edilen kırılım olmadı (190 coinin 4H yönü yükselişte değildi), dolayısıyla açık pozisyon da olmadı.
Koruma davranışı testlerle doğrulandı. Ayrıntı: `research/kr2/kr2-paper-canli.txt`.

## 2. Yeniden üretim

`cryptoscan kr --phase final` iki kez çalıştırıldı:

- eski kodla;
- varyant anahtarları eklendikten sonra, anahtarlar kapalıyken.

Üç dönemin işlem CSV'leri ve özet rapor ilk turla **bayt düzeyinde aynı** çıktı.

## 3. Araştırma (MEXC 15M, 2025-11-28 → 2026-07-31, her gün o günün CMC ilk 300'ü)

| Yapılandırma | İşlem | Net USDT | Beklenti USDT | Ort. net R | PF | Maks. düşüş | Günde işlem | Ort. süre | Toplam maliyet | Maliyet / brüt kâr | Kazanan | Ort. kazanç R | Ort. kayıp R |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B0 temel | 198 | −157,47 | −0,80 | −0,45 | 0,41 | %16,1 | 0,80 | 45 dk | 108,38 | %93 | %18,7 | 1,73 | −0,95 |
| B0 ×2 | 102 | −119,03 | −1,17 | −0,58 | 0,29 | %12,0 | 0,41 | 49 dk | 91,03 | %170 | %13,7 | 1,61 | −0,93 |
| V1 stop tamponu 1,0 ATR | 131 | −101,45 | −0,77 | −0,39 | 0,39 | %10,6 | 0,53 | 67 dk | 55,27 | %80 | %16,8 | 1,30 | −0,73 |
| V1 ×2 | 43 | −28,91 | −0,67 | −0,30 | 0,50 | %3,6 | 0,17 | 85 dk | 28,05 | %88 | %20,9 | 1,33 | −0,73 |
| V2 BTC 4H yön şartı | 131 | −97,44 | −0,74 | −0,43 | 0,42 | %10,3 | 0,53 | 42 dk | 73,10 | %97 | %19,1 | 1,77 | −0,94 |
| V2 ×2 | 61 | −84,89 | −1,39 | −0,70 | 0,17 | %8,6 | 0,25 | 46 dk | 57,96 | %292 | %9,8 | 1,48 | −0,94 |
| V3 retest kalitesi | 129 | −110,90 | −0,86 | −0,42 | 0,39 | %11,9 | 0,52 | 54 dk | 68,51 | %89 | %20,9 | 1,47 | −0,92 |
| V3 ×2 | 59 | −83,24 | −1,41 | −0,64 | 0,19 | %8,5 | 0,24 | 65 dk | 52,79 | %238 | %11,9 | 1,26 | −0,89 |
| V4 maliyet payı %25 | 198 | −157,47 | −0,80 | −0,45 | 0,41 | %16,1 | 0,80 | 45 dk | 108,38 | %93 | %18,7 | 1,73 | −0,95 |
| V4 ×2 | 97 | −112,25 | −1,16 | −0,56 | 0,30 | %11,3 | 0,39 | 51 dk | 86,20 | %161 | %14,4 | 1,61 | −0,93 |

**Seçim kuralı (ön kayıt §4):** Ortalama R'de en az +0,05, en az 60 işlem ve ×2 maliyette kötüleşmeme.

| Varyant | Ortalama R farkı | Kural |
| --- | --- | --- |
| V1 | +0,063 | geçti |
| V2 | +0,025 | geçmedi |
| V3 | +0,029 | geçmedi |
| V4 | 0 | geçmedi |

V4'ün normal maliyette hiç etkisi olmadı. Net R:R ≥ 1,2 şartını geçen işlemlerde maliyet payı zaten %25'in altındaydı;
×2 maliyette biraz eleme yaptı. Kuralı yalnız V1 geçtiği için birleşik yapılandırma VK = V1 oldu.

**"Büyük stop / küçük hedef" denetimi:** V1'de kazanma oranı **yükselmedi** (%18,7 → %16,8). Ortalama kazanç 1,73R'den
1,30R'ye düştü. Net R:R ≥ 1,2 şartı aynen uygulandı: geniş stoplu işlemlerde hedef aynı formülle kalır, ödülü yetmeyen
işlem elenir. Kazanma oranını yapay olarak şişiren bir yapı kurulmadı.

## 4. Değerlendirme (VK = V1, bir kez)

| Dönem | Maliyet | İşlem | Net USDT | Beklenti USDT | Ort. net R | PF | Maks. düşüş | Günde işlem | Ort. süre | Toplam maliyet | Kazanan |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MEXC 2026-08-01 → 11-12 | Normal | 61 | −67,80 | −1,11 | −0,49 | 0,24 | %7,3 | 0,59 | 82 dk | 25,35 | %16,4 |
| | ×2 | 24 | −28,00 | −1,17 | −0,49 | 0,23 | %3,2 | 0,23 | 86 dk | 16,01 | %20,8 |
| (karşılaştırma B0) | Normal | 92 | −83,83 | −0,91 | −0,52 | 0,39 | %9,3 | 0,88 | 59 dk | 51,29 | %16,3 |
| Binance 2023-11 → 2025-10 (yardımcı, yanlı) | Normal | 417 | −167,23 | −0,40 | −0,18 | 0,65 | %16,7 | 0,58 | 72 dk | 183,08 | %27,3 |
| | ×2 | 124 | −54,70 | −0,44 | −0,18 | 0,66 | %6,3 | 0,17 | 73 dk | 75,33 | %28,2 |

**Sınıf (ön kayıt §5):**

| Ölçüt | Sonuç |
| --- | --- |
| En az 60 işlem | ✓ (61) |
| Net beklenti > 0 | ✗ |
| PF ≥ 1,2 | ✗ |
| En büyük düşüş ≤ %6 | ✗ |
| ×2 maliyette net > 0 | ✗ |
| Güven aralığı alt sınırı > 0 | ✗ (−0,62) |
| En iyi coin çıkarılınca net > 0 | ✗ |
| Binance net > 0 | ✗ |

**SONUÇ: Başarısız.** Maliyetsiz sonuç da negatif: MEXC değerlendirmesinde net + maliyet = −42,45 USDT.

**Dürüstlük notu:** Değerlendirme dönemleri ilk turda temel kurallarla bir kez görülmüştü ve teşhisler o sonuçlardan
çıkarıldı. Bu yüzden "bağımsız" değiller (ön kayıt §0). Gerçekten bağımsız test yalnız bundan sonraki canlı paper
işlemleridir.

## 5. Testler

Ayrıntı: [research/kr2/kr2-test-ciktilari.txt](research/kr2/kr2-test-ciktilari.txt).

**Yeni `KrLoopsTest`:**

- 3 dakikalık taramalar kapanmamış mumu teyit saymaz ve aynı kurulumu tekrarlamaz; ara taramada mum isteği yok;
- önceki tarama sürerken yenisi başlamaz ve sayılır;
- fiyat akışı stopun altına inince 15M beklenmeden kapanır ("Fiyat akışı" kaynağı);
- bağlantı kopmasından sonra olaylar gerçekleştikleri sırayla işlenir;
- mumlar diskte önbelleklenir; yeniden başlatmada yalnız yeni mum istenir.

**Ana ekran testleri:**

- son/sonraki tarama, bağlantı ve fiyat akışı satırları;
- servis durunca "tarama yapılmıyor" ve "Koruma izlenemiyor";
- atlanan tarama sayısı.

**Fiyat akışı:** MEXC WebSocket bu ortamdan canlı bağlandı (10 sn'de 170 fiyat güncellemesi; `cryptoscan akis-testi`).

**Yapılamayanlar:** Bu ortamda Android emülatörü yok (KVM yok). APK gerçek bir telefonda çalıştırılmadı; Android'in pil
yönetiminin servisi ne sıklıkla durdurduğu ölçülmedi.

## 6. Dosyalar

| Dosya | İçerik |
| --- | --- |
| `research/kr2/kr2-sonuc.txt` | Bütün tablolar, seçim ve sınıf |
| `research/kr2/kr2-arastirma-{B0,V1,V2,V3,V4}-islemler.csv` | Araştırma dönemi işlemleri |
| `research/kr2/kr2-degerlendirme1-VK-islemler(-x2).csv`, `kr2-degerlendirme1-VK-gunluk.csv` | MEXC değerlendirmesi |
| `research/kr2/kr2-degerlendirme2-binance-VK-islemler.csv` | Binance yardımcı testi |
| `research/kr2/kr2-paper-canli.txt` | Uygulama döngülerinin canlı denemesi |
| `research/kr/kr-*-islemler.csv` | İlk turun (temel kurallar) işlemleri; bu turda birebir yeniden üretildi |
