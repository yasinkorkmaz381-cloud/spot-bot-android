# Momentum RS-1 V6: teşhis, düzeltmeler, araştırma ve bağımsız test (12 Kasım 2026)

**Belgeler ve ham çıktılar:**

- Protokol: [RS1-V6-ONKAYIT.md](RS1-V6-ONKAYIT.md). Varyantlardan önce commit edildi (`b59902f`).
- Dondurma commit'i: `15271c7`. Bağımsız testten önce yapıldı.
- [research/rs1-v6-arastirma.txt](research/rs1-v6-arastirma.txt): bütün denemeler.
- [research/rs1-v6-islemler-arastirma.csv](research/rs1-v6-islemler-arastirma.csv): işlem dökümü.
- [research/rs1-v6-bagimsiz-1-veri-hatali.txt](research/rs1-v6-bagimsiz-1-veri-hatali.txt)
- [research/rs1-v6-bagimsiz-2-veri-duzeltilmis.txt](research/rs1-v6-bagimsiz-2-veri-duzeltilmis.txt)

## Sonuç

**Kanıtlanmış bir avantaj bulunamadı.**

| Dönem | Sınıf |
| --- | --- |
| Bağımsız test (2018–2019) | **Yetersiz kanıt.** Yalnız 5 işlem; sonucun neredeyse tamamı aynı gün açılan iki işlemden geliyor. |
| Daha önce görülmüş 2025–2026 dönemi | Nihai kurallar 19 işlemde **−0,44R, PF 0,30, −%4,1** |
| Araştırma ve doğrulama dönemleri | Sonuçlar iyi görünüyor (+%25,7 ve +%14,6). Ancak işlem sayısı 30'un altında (16 ve 26). Araştırma kârı 2020'deki 2 işleme dayanıyor. |

Uygulama, dondurulmuş kurallarla paper trading için hazır. Arayüz "Kanıtlanmış avantaj yok" der; "kârlı" ya da "onaylı"
etiketi kullanılmaz.

## 1. Mevcut sistemin teşhisi (V5 kaynak koduna göre)

**Kullanıcı mesajındaki sayılar RS-1'e ait değil:**

- **1.000 → 970,82 USDT, 45 işlem:** Eski A/B/C (15M) sisteminin V2.3 backtest'i. 45. işlem C stratejisinden geliyordu.
- **−%20,99:** V2'deki A+B'nin bir yıllık, düz spread'li testi.
- **RS-1 (V5) son testi:** Ayrı bir test; 45 işlem, 1.000 → 967,39. İşlem sayısının 45 olması tesadüf.

Bunlar birbirinden ayrı testlerdir.

### V5'te gerçekte çalışan kurallar

Kaynak: `Rs1Config`, `Rs1Rules`, `Rs1Engine`.

| Konu | V5 kodu |
| --- | --- |
| BTC filtresi | Kapanış > EMA100(1D) ve 30 günlük getiri > 0; veri eksik ya da bayatsa giriş yok |
| Uygunluk | EMA100 üstü, 7 ve 30 günlük getiri > 0, BTC'ye göre ≥ 3 puan (BTC muaf), 30 gün medyan hacim ≥ 5M USDT, son gün aralığı ≤ 2,5 ATR, ≥ 150 gün geçmiş. Canlıda ek olarak spread ≤ %0,15 ve ±%1 derinlik ≥ 3 × en büyük pozisyon. |
| Sıralama ve giriş | 30 günlük getiriye göre; pazartesi 00:00 UTC kapanışında en güçlü 2; pazartesi açılışından dolum |
| Çıkış | Gün içi koruyucu stop (2,5 ATR); iz süren stop (en yüksek kapanış − 3 ATR); BTC filtresi kapanırsa; iki gün EMA50 altı; pazartesi uygunluk kaybı |
| Risk | %0,5 risk, en fazla 2 pozisyon, coin başına %25, toplam %50, günlük %1,5 kayıp sınırı |
| Kullanıcının değiştirebildiği | Yok. Parametreler salt okunur gösteriliyor; yalnız botu aç/kapa ve sanal hesabı sıfırlama var. |

### Zamanlama

| Ne | Ne zaman |
| --- | --- |
| Tarama | Bot çalışırken 30 dakikada bir |
| Paper trading güncellemesi | 5 dakikada bir |
| Canlı ölçüm (spread, derinlik) | Pazartesi 00:15–04:00 UTC |
| Uygulama o pencerede kapalıysa | O hafta giriş yok; "erişim nedeni" olarak kaydediliyor |

### Backtest ile paper trading arasındaki farklar ve bulunan hatalar

| # | Bulgu | Etkisi | Durum |
| --- | --- | --- | --- |
| 1 | Her pariteye %0,05 ücret. MEXC API'si: BTC/ETH/SOL ve 172 parite %0,05, 789 parite %0,08, 241 parite %0,2. | Maliyet düşük hesaplanıyordu | **Düzeltildi**: parite bazında ücret |
| 2 | Stop günlük mumla, stop fiyatından dolduruluyordu; gün içi boşluk görülmüyordu | İyimser | **Düzeltildi**: 4H (backtest) / 1H (paper); boşlukta mum açılışından |
| 3 | EMA100 ısınması: 150 günde başlangıç ortalamasının ağırlığı hâlâ yaklaşık %37 | Gösterge yanlı | **Düzeltildi**: asgari geçmiş 250 gün |
| 4 | Paper girişi, değerlendirme 04:00'e kadar gecikse bile 00:00 açılış fiyatından | Eski fiyattan dolum (geriye bakarak) | **Düzeltildi**: değerlendirme anındaki emir defteri VWAP'ı |
| 5 | Paper'da giriş günü stopu, girişten önceki saatlerin düşüğüyle tetiklenebiliyordu | Yanlış çıkış | **Düzeltildi**: yalnız girişten sonraki 1H mumlar |
| 6 | MEXC miktar adımı ve asgari emir tutarı uygulanmıyordu | Gerçekçi değil | **Düzeltildi** (paper) |
| 7 | Test sonunda açık pozisyonlar son kapanıştan zorla kapatılıyordu | Gerçekleşmiş ve gerçekleşmemiş sonuç karışıyordu | **Düzeltildi**: ayrı raporlanıyor |
| 8 | Takip edilmeyen zaman aralıkları kaydedilmiyordu; o sırada oluşan işlemler canlı izlenmiş gibi görünüyordu | Yanıltıcı | **Düzeltildi**: aralıklar ve "canlı izlenmedi" etiketi |
| 9 | Stop değişiklikleri kaydedilmiyordu | Açıklanabilirlik | **Düzeltildi**: stop geçmişi |
| 10 | Cihaz saati yanlışsa pazartesi penceresi kayabiliyordu | Zamanlama | **Düzeltildi**: MEXC sunucu saatine göre düzeltme |
| 11 | MEXC'nin 2018-08-23 öncesi günlük mumları UTC+8 gün sınırında; motor bunları sessizce atıyordu | Eksik veri raporlanmıyordu | **Düzeltildi**: sayılıyor, eksik UTC günleri 4H'den kuruluyor |
| 12 | BTC al-tut, BTC verisi dönem başından sonra başlıyorsa %0 gösteriyordu | Raporlama | **Düzeltildi** |
| — | Hacim | USDT cinsinden doğru alan (kline alan 7) kullanılıyordu | Doğru |
| — | Yeniden başlatma ve çift işlem | Paper trading her güncellemede aynı veriden yeniden hesaplanıyor; çift işlem üretilemiyor (testli) | Doğru |
| — | Hayatta kalma yanlılığı | Borsadan kaldırılmış pariteler MEXC API'sinden indirilemiyor | Düzeltilemez, raporlanır |

**Doğrulama:** B0, V5'i birebir yeniden üretti. Son test 45 işlem / 967,39 USDT; tüm dönem 147 işlem / 1.106,37 USDT.
Yeni kod eski davranışı bozmadı.

### İşlem açılmama nedenleri

Uygulama ve araştırma, nedenleri ayrı sınıflarda kaydeder:

| Sınıf | Örnek kayıt |
| --- | --- |
| Strateji | "Uygunluk şartlarını geçen coin yok", BTC filtresi kapalı |
| Veri | "BTC verisi eksik ya da güncel değil", "Veri alınamayan pariteler" |
| Zamanlama | "Canlı değerlendirme yapılamadı… sinyalin süresi doldu" |
| Likidite | Spread ve derinlik başarısızlığı, hacim aşaması |
| Bakiye | "Bakiye yetersiz", "Pozisyon sınırı dolu", günlük kayıp sınırı |
| Yazılım | Panel ve Tarama'daki "Yazılım / veri sorunu" |

Her giriş değerlendirmesinde, veri şartlarında elenen paritelerin ilk başarısız şarta göre sayısı da kaydedilir.

## 2. Araştırma: ne denendi, ne seçildi

Ön kayıtlı kurallar mekanik olarak uygulandı. Toplam 51 dönem × yapılandırma çalıştırması yapıldı; hepsi ham çıktıda.

**Ortak koşullar:**

- Orta maliyet, parite bazında ücret, 4H gün içi stop.
- Araştırma: 2020-08 → 2022.
- Doğrulama: 2023–2024.

### Adım sonuçları

| Adım | Varyant | Araştırma | Doğrulama | Karar |
| --- | --- | --- | --- | --- |
| Taban B1 | V5 kuralları + düzeltmeler | 33 işlem, +0,450R, PF 2,00 | 59 işlem, +0,201R, PF 1,64 | — |
| 1 | B: günlük giriş | 55 işlem, +0,427R, PF 2,34 | 83 işlem, +0,227R, PF 1,92 | **A kaldı** (araştırmada ort. R düşük; işlem sayısı ölçüt değil) |
| 2 | İz süren stop yok | +0,550R, PF 2,24 | +0,307R, PF 1,99 | kaldırıldı |
| 2 | EMA50 çıkışı yok | B1 ile aynı | B1 ile aynı | kaldırıldı (hiç katkısı yok) |
| 2 | Uygunluk kaybı çıkışı yok | 24 işlem, +0,782R, PF 3,02 | 37 işlem, +0,433R, PF 2,00 | kaldırıldı |
| 2 | BTC rejimi çıkışı yok | +0,350R, PF 1,71 | +0,190R, PF 1,57 | **korundu** |
| 3 | BTC giriş filtresi yok | 50 işlem, +1,107R | 56 işlem, +0,485R | korundu (adım 2'ye göre kötü) |
| 3 | EMA100 şartı yok | adım 2 ile aynı | adım 2 ile aynı | kaldırıldı (tekrarlı) |
| 3 | 30 günlük getiri şartı yok | adım 2 ile aynı | adım 2 ile aynı | kaldırıldı (tekrarlı: BTC 30g > 0 ve +3 puan şartı zaten 30g > 0 demek) |
| 3 | 7g getiri, RS, aralık | — | — | korundu |
| 4 | Duyarlılık (6 varyant) | — | — | Kırılgan: 0/6. Ancak örneklem çok küçük. |

**Gereksiz satış kontrolü:** B1'deki 44 uygunluk kaybı çıkışından sonraki 30 günde coinlerin getirisi:

- Ortalama +%8,5, ama medyan −%4,7.
- Coinlerin %41'i yükseldi.

Ortalamayı birkaç aşırı yükseliş çekiyor. Çıkışların bir kısmı trendi erken kesti, bir kısmı zararı önledi.

**Seçim için uyarı:** Ön kayıttaki karar kuralı işlem sayısı şartı içermiyordu. Bu yüzden nihai yapılandırma 16 ve 26
işlemlik dönemlerde seçildi. Kanıt bu kadar zayıf olunca seçimin kendisi de güvenilir değildir.

## 3. Nihai kurallar (dondurulmuş, `Rs1Config` varsayılanları)

**Evren ve veri:**

- MEXC USDT spot pariteleri.
- Ayıklananlar: stablecoin, tokenleştirilmiş hisse, emtia, kaldıraçlı token, işlemi durmuş ya da MEXC risk etiketli
  pariteler.
- 30 gün medyan USDT hacmi ≥ 5M.
- ≥ 250 tamamlanmış gün ve son 60 günde eksik gün yok.

**BTC filtresi:** Kapanış > EMA100(1D) ve 30 günlük getiri > 0. Veri eksikse yeni giriş yok.

**Giriş şartları:**

1. 7 günlük getiri > 0.
2. 30 günlük getiri − BTC'nin 30 günlük getirisi ≥ 3 puan (BTC muaf).
3. Son gün aralığı ≤ 2,5 × ATR14.
4. Canlı: spread ≤ %0,15.
5. Canlı: ±%1 derinlik her iki tarafta ≥ 3 × en büyük pozisyon.

**Zamanlama:**

- Pazartesi 00:15 UTC (03:15 TSİ) değerlendirmesi.
- Uygunlar 30 günlük getiriye göre sıralanır; boş yer kadar en güçlüler alınır (en fazla 2).
- Sinyal yalnız tamamlanmış günlük mumdan üretilir.
- Paper dolumu: değerlendirme anındaki emir defterinin pozisyon büyüklüğüne göre VWAP'ı + kayma.
- Backtest dolumu: pazartesi açılışı + maliyet senaryosu.
- Değerlendirme 04:00 UTC'ye kadar yapılamazsa sinyalin süresi dolar; eski fiyattan dolum yapılmaz.

**Çıkış:**

- **Koruyucu stop:** Giriş referans fiyatı − 2,5 × ATR14. Hiç gevşemez. Gün içinde izlenir; boşlukta açılıştan doldurulur.
- **BTC rejimi bozulursa:** Günlük kapanışta kapanış ≤ EMA100 ya da 30g ≤ 0 olursa yeni günün açılışında çıkılır.
- İz süren stop, EMA50 çıkışı, uygunluk kaybı çıkışı ve sabit kâr hedefi **yok**.

**Risk:**

- Sermaye 1.000 USDT, işlem başına özsermayenin %0,5'i.
- En fazla 2 pozisyon, coin başına %25, toplam %50.
- Günlük %1,5 kayıp sınırı.
- Miktar: stop mesafesi, iki yön ücret ve kayma, nakit, toplam sınır, günlük hacmin %10'u ve MEXC lot kuralları ile
  sınırlanır.
- Risk bütçesi stopta hedeflenen kayıptır; boşlukta gerçek kayıp daha büyük olabilir.
- Kaldıraç, açığa satış, zararda ek alım ve martingale yok.

## 4. Karşılaştırma (aynı koşullar)

Tablolardaki ölçüler: işlem, ortalama R, PF, net, en büyük düşüş, ücret + spread/kayma (USDT), ortalama süre, piyasada
kalma.

### Araştırma 2020-08 → 2022

| Yapılandırma | İşlem | Ort. R | PF | Net | Düşüş | Maliyet | Süre | Piyasada |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| V5 (B0) | 42 | +0,372 | 1,93 | +%7,85 | %3,6 | 1,1 + 2,7 | 7,5 g | %25 |
| B1 | 33 | +0,450 | 2,00 | +%7,36 | %3,6 | 0,8 + 2,2 | 8,0 g | %22 |
| Nihai | 16 | +3,122 | 7,97 | +%25,69 | %5,3 | 0,5 + 1,6 | 26,6 g | %27 |

### Doğrulama 2023–2024

| Yapılandırma | İşlem | Ort. R | PF | Net | Düşüş | Maliyet | Süre | Piyasada |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| V5 (B0) | 60 | +0,204 | 1,66 | +%6,04 | %3,6 | 2,1 + 5,4 | 9,2 g | %45 |
| B1 | 59 | +0,201 | 1,64 | +%5,83 | %3,6 | 2,1 + 5,3 | 9,2 g | %44 |
| Nihai | 26 | +1,081 | 3,99 | +%14,59 | %4,2 | 1,1 + 2,9 | 24,9 g | %49 |

### Görülmüş dönem 2025 → 2026-11 (bağımsız değil)

| Yapılandırma | İşlem | Ort. R | PF | Net | Düşüş |
| --- | ---: | ---: | ---: | ---: | ---: |
| V5 (B0) | 45 | −0,145 | 0,56 | −%3,26 | %5,4 |
| B1 | 45 | −0,237 | 0,34 | −%5,23 | %6,0 |
| Nihai | 19 | −0,439 | 0,30 | −%4,13 | %4,5 |

### Kıyaslama ölçütleri

| Dönem | BTC al-tut | USDT'de bekleme |
| --- | ---: | ---: |
| Araştırma | +%45,4 | %0 |
| Doğrulama | +%463,7 | %0 |
| Görülmüş | −%19,8 | %0 |
| Bağımsız (BTC verisinin başladığı 2018-06-02'den) | −%4,6 | %0 |

**Gözlem:** Nihai kurallar araştırma ve doğrulamada daha iyi görünürken görülmüş dönemde V5'ten de kötü. Bu tablo
geçmişe uyma (aşırı uydurma) riskine işaret ediyor.

**Kâr yoğunlaşması (nihai kurallar; araştırma + doğrulama + görülmüş):**

- Kârın büyük kısmı BTC'den geliyor: 10 işlem, +207,5 USDT.
- 2020'de 2 işlem: +208,6 USDT, ortalama +20,9R.
- Yıllara göre: 2021 +59 · 2022 −11 · 2023 +90 · 2024 +56 · 2025 −21 · 2026 −20 USDT.

**Çıkış nedenleri:** BTC rejimi 44 işlem (+1,97R), koruyucu stop 17 işlem (−1,00R).

### Bağımsız test 2018–2019 (dondurulmuş kurallar; bir kez, veri hatası nedeniyle iki çalıştırma)

**1. çalıştırma (veri hatalı):** 2018-08-23 öncesi UTC+8 hizalı mumlar sessizce atıldı.

- 5 işlem, −%0,53, PF 0,63.
- BTC filtresi 72 hafta "veri eksik" durumunda kaldı.

**2. çalıştırma (veri düzeltilmiş):** Kurallar aynı; atılan 1.364 mum yerine 500 UTC günü 4H'den kuruldu.

- 49 paritenin verisi vardı.
- BTC filtresi 2019 başına kadar yine veri yetersizliğinden kapalı.

| Ölçü | Değer |
| --- | --- |
| İşlem | 5 (2 kazanç) |
| Net | +%3,11 |
| PF | 4,14 |
| Ortalama R | +1,26 |
| %90 güven aralığı | [−0,56, +3,17] |
| En büyük düşüş | %7,7 |
| İki kat maliyetle | +%2,94 |
| V5 kuralları, aynı veri | 16 işlem, +%4,83 |
| BTC al-tut | −%4,6 |

**Sınıf: Yetersiz kanıt.**

- Başarısız şartlar:
  - 5 < 30 işlem.
  - En iyi 3 işlem çıkarılınca net −8,0 USDT.
  - Güven aralığının alt sınırı negatif.
- Kâr, aynı gün (2019-04-01) açılan LTC (+25,7) ve ETC (+15,3) işlemlerinden geliyor.

## 5. İşlem dökümü, maliyet ve dağılım

- **Dökümler:**
  - [research/rs1-v6-islemler-arastirma.csv](research/rs1-v6-islemler-arastirma.csv): nihai kurallar, araştırma + doğrulama + görülmüş dönem.
    Her satır: coin, giriş/çıkış tarihi ve fiyatı, miktar, ilk stop, net, R, ücret, spread+kayma, çıkış nedeni, stop
    değişim sayısı, rejim.
  - Bağımsız testin 5 işlemi [research/rs1-v6-bagimsiz-2-veri-duzeltilmis.txt](research/rs1-v6-bagimsiz-2-veri-duzeltilmis.txt) içinde.
- **Yıl ve coin dağılımı:** Yukarıda. Ham çıktıda en iyi 8 ve en kötü 8 coin var.

## 6. Çalıştırılan testler

**Çekirdek: 52 test** (1'i yalnız `-Plive` ile çalışan canlı MEXC testi). Başarısız yok.

- `Rs1EngineTest`, 17 test:
  - Giriş günü, pozisyon, %25 ve %50 sınırları.
  - BTC filtresi.
  - Stop, günlük ve 4H boşluk çözümü.
  - İz süren stop yalnız yukarı; stop geçmişi.
  - EMA50 ve haftalık çıkış (V5 kural setiyle).
  - Günlük kayıp, ileriye bakmama, 150/250 gün geçmiş kuralı, filtreler.
  - Günlük giriş modunda çift pozisyon olmaması.
  - Gerçekleşmiş ve gerçekleşmemiş sonuç.
  - Parite ücreti ve ×2 maliyet.
  - Dondurulmuş varsayılanların yalnız stop ve BTC çıkışı yapması.
- `Rs1PaperTest`, 8 test:
  - Paper sonucu, kaydedilmiş girdilerle motorun yeniden çalıştırılmasına birebir eşit; backtest ile aynı kararlar.
  - Gün içi stop.
  - Kaçırılan değerlendirmede giriş yok.
  - Yeniden başlatmada kayıp yok.
  - Canlı spread ve derinlik.
  - Ölçüm penceresi.
  - VWAP dolumu, lot adımı ve parite ücreti.
  - Takip edilmeyen aralıklar.
- `Rs1ScannerTest` (7 test) ve `SecurityTest` (4 test): eski stratejiler yok, imzalı uç nokta yok, parametreler
  dondurulmuş değerlerle aynı.
- `MexcRestClientTest` (6 test) ve `IndicatorsTest` (9 test).

**Uygulama: 28 test.** Göç ve arşiv, etkileşim, biçimlendirme, 8 ekran × 2 ekran boyutu. Lint temiz.

**Derleme:** APK **3.1.0-paper** (versionCode 12).

**Gerçek MEXC kontrolü (12 Kasım 2026):**

- 1.396 parite tarandı.
- 9'u likidite şartını geçti.
- BTC filtresi kapalı; aday yok.
- Paper trading hatasız.

## 7. Kalan sınırlamalar

- **Hayatta kalma yanlılığı:** Kaldırılmış coinler yok; özellikle 2018–2019 evreninde (49 parite) güçlü.
- **Ücret geçmişi:** Bugünkü API ücretlerinin geçmişte de geçerli olduğu bir varsayım. 2018–2019 ücretleri muhtemelen
  daha yüksekti; iki kat maliyet senaryosu bunu kısmen kapsar.
- **Geçmiş spread ve emir defteri yok:** Canlı spread ve derinlik şartları backtest'te uygulanamaz.
- **Cihazdaki backtest:** Gün içi mum indirmez; stoplar günlük mumla çözülür. Sonuç CLI'daki 4H çözümlü sonuçtan biraz
  iyimser olabilir; ekranda belirtiliyor.
- **Android:** Kesintisiz çalışma garanti edilemez.
  - Uygulama pazartesi 00:15–04:00 UTC'de çalışmazsa o hafta giriş yapılmaz.
  - Çıkışlar veriden sonradan hesaplanır ve "canlı izlenmedi" olarak işaretlenir.
- **İstatistiksel güç:** Bütün dönemlerde işlem sayısı düşük; nihai kurallarla yılda 10–15 işlem. 100 işleme ulaşmak
  yıllar sürebilir.
- **Varyant anahtarları:** Kaldırılan çıkış ve filtreler kodda araştırma anahtarı olarak duruyor (yeniden üretilebilirlik
  için). Uygulamada açılamaz.

## 8. Paper trading takip planı

- **Kurallar:** Dondurulmuş `Rs1Config` olduğu gibi kalır. Yeni filtre ya da parametre değişikliği yapılmaz.
- **Kayıt:** Her pazartesi, Tarama ve İşlemler → Haftalık ekranında karar ve nedenler kaydedilir.
- **Ara kontrol:** Her ay şunlara bakılır:
  - Kapanmış işlem sayısı.
  - Ortalama R.
  - PF.
  - En büyük düşüş.
  - Takip edilmeyen aralık sayısı.
  - Kaçırılan değerlendirmeler.
- **Erken durdurma:**
  - Düşüş %10'u aşarsa.
  - 50 işlemden sonra beklenen değer ≤ 0 ise.
- **Değerlendirme:** 100 kapanmış işlemde (en az 12 ay), bu belgedeki kabul ölçütleriyle. Bağımsız test bu ileri
  testtir.
- **Kurallar değiştirilirse:** Ancak yeni bir ön kayıt ve yeni bir ileri test dönemiyle. Eski sonuç raporda kalır.
- **Gerçek parayla kullanım:** "Kanıt var" sınıfı olmadan gündeme gelmez. Gerçek emir gönderimi kodda yoktur.
