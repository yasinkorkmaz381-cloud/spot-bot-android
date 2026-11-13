# CryptoScan Pro — MEXC Spot İşlem Botu

MEXC spot piyasasında (USDT pariteleri) çalışan, **kontrollü risk** ve **sistematik karar** odaklı otomatik
işlem botu. Android uygulaması (Türkçe kumanda paneli) + platformdan bağımsız Kotlin çekirdeği + komut satırı.

> **Durum: V4.0, tek strateji: Kırılım–Retest (KR), yalnız sanal işlem (paper trading).** Gerçek emir gönderilmez.
> Ön kayıtlı backtest sonucu **Başarısız**: üç dönemde de net zarar, maliyetsiz de avantaj yok. Gerçek parayla
> kullanım için dayanak yoktur. Rapor: [`docs/KR-RAPOR.md`](docs/KR-RAPOR.md).

## V4.0: Kırılım–Retest, CMC ilk 300 evreni, sade arayüz (13 Kasım 2026)

- **Ürün:** CoinMarketCap piyasa değeri ilk 300 → MEXC USDT spot eşleşmesi (günlük). Gün boyu 15M kapanışlarda tarama.
  Tek düzen: 4H yön → önceden sabit 1H direnç → 15M kapanışla kırılım → retest → yakın hedef.
- **Belgeler:**
  - Ön kayıt: [`docs/KR-ONKAYIT.md`](docs/KR-ONKAYIT.md).
  - 60 kural uyarlaması: [`docs/KR-KURALLAR.md`](docs/KR-KURALLAR.md).
  - Rapor, işlem CSV'leri ve test çıktıları: [`docs/KR-RAPOR.md`](docs/KR-RAPOR.md), `docs/research/kr/`.
- **Arayüz:** Ana ekran, Fırsatlar ve İşlemler. Ayrıntılar, Ayarlar ve Arşiv üst çubuktan açılır.
- **Bot kontrolü:** "Yeni girişleri durdur" korumayı sürdürür. "Tamamen kapat" açık pozisyon varken onay ister.
- **CMC anahtarı:** Ayarlar'dan girilir, Android Keystore ile şifreli saklanır.
- **Eski kayıtlar:** Momentum RS-1 ve A/B/C kayıtları `archive-rs1/` ve `archive-v4/` altında, silinmeden duruyor.
  Yeni sonuçlara karışmaz.
- **Komut satırı:**
  - `cryptoscan universe`: canlı CMC→MEXC eşleşmesi (`CMC_API_KEY` ortam değişkeni).
  - `cryptoscan kr --phase research|final`: ön kayıtlı backtest.
  - `cryptoscan kr-aux`: Binance yardımcı testi.

Aşağıdaki V3.x bölümleri eski Momentum RS-1 dönemine aittir; kod aktif sistemden kaldırıldı (git geçmişinde duruyor).

## V3.2: V7 karşılaştırması ve gerçekleşme modeli eşitliği (12 Kasım 2026)

- Ön kayıt: [`docs/RS1-V7-ONKAYIT.md`](docs/RS1-V7-ONKAYIT.md). Rapor: [`docs/RS1-V7.md`](docs/RS1-V7.md).
- **V6 yeniden üretimi:** Byte düzeyinde aynı. Önce bulunan yeniden üretim hatası düzeltildi; kural setleri artık
  `Rs1Versions` içinde açık sabitler.
- **Karşılaştırma:** Haftalık/günlük giriş × V6 çıkışı / + iz süren stop. Dört yapılandırma, 9 genişleyen pencere, 8
  ölçülü Pareto seçimi.
- **Sonuç:** Kazanan yok; dört yapılandırmanın da pencere dışı birleşik sonucu yetersiz kanıt.
- **Android backtest:** Araştırma motoruyla aynı modeli kullanıyor (4H gün içi stop). Yalnız günlük mumla çalışan hızlı
  mod "yaklaşık" olarak ayrı etiketleniyor.

## V3.1: teşhis, gerçekçilik düzeltmeleri ve bağımsız test (12 Kasım 2026)

- Ön kayıt: [`docs/RS1-V6-ONKAYIT.md`](docs/RS1-V6-ONKAYIT.md).
- Rapor: [`docs/RS1-V6.md`](docs/RS1-V6.md). Teşhis tablosu, nihai kurallar, karşılaştırma, işlem dökümü ve paper
  trading takip planı burada.

**Düzeltilen hatalar:**

- Parite bazında MEXC ücreti. V5 her pariteye %0,05 uyguluyordu; API'ye göre çoğu paritede %0,08–0,2.
- Gün içi stop boşlukları 4H/1H mumlarıyla çözülüyor.
- EMA100 ısınması için asgari geçmiş 250 gün.
- Paper girişi eski açılış fiyatından değil, değerlendirme anındaki emir defterinden.
- MEXC lot kuralları.
- Takip edilmeyen aralıklar ve stop geçmişi kaydediliyor.
- Sunucu saati düzeltmesi.
- 2018 verisindeki UTC+8 hizalı mumlar artık sessizce atılmıyor.

**Araştırma:**

- Ön kayıtlı kurallarla haftalık giriş korundu. Günlük giriş araştırmada ortalama R'yi düşürdü.
- Çıkışlar koruyucu stop ve BTC rejimine indirildi.
- Tekrarlı EMA100 ve 30 günlük getiri şartları kaldırıldı.
- Seçim 16 ve 26 işlemlik dönemlere dayanıyor.

**Bağımsız test (2018–2019, hiç görülmemiş veri):** 5 işlem → **yetersiz kanıt**.

**Daha önce görülmüş 2025–2026 dönemi:** −0,44R, PF 0,30.

**Arayüz:**

- Türkiye saati (TSİ) ve UTC birlikte gösteriliyor.
- Son veri, son başarılı değerlendirme ve sıradaki değerlendirme zamanı görünüyor.
- Kanıt durumu yalnız ön kayıtlı ölçütlerden geliyor.
- Stop geçmişi ve "canlı izlenmedi" işaretleri gösteriliyor.

## V3.0: tek strateji, Momentum RS-1 (11 Kasım 2026)

- Kurallar ve test protokolü: [`docs/RS1-ONKAYIT.md`](docs/RS1-ONKAYIT.md). Kod ve sonuçlardan önce commit edildi.
- Sonuç raporu: [`docs/RS1-V5.md`](docs/RS1-V5.md).

**Değişenler:**

- **Eski stratejiler kaldırıldı:** A/B/C, eski 15M motor, eski backtest ve V3/V4 araştırma kodu. Yedek: `e0f2032`
  commit'i. Bu stratejilerin artık çalışmadığı otomatik testle doğrulanıyor.
- **Eski kayıtlar korundu:** Cihazdaki eski kayıtlar silinmeden `archive-v4/` klasörüne taşınır ve İşlemler → Arşiv'de
  salt okunur görünür.
- **Tek karar zinciri:** MEXC verisi → veri doğrulama → BTC filtresi (EMA100 ve 30 günlük getiri) → bütün MEXC USDT
  pariteleri → Momentum RS-1 → risk → paper emir.
- **Aynı motor:** Backtest ve paper trading aynı `Rs1Engine` kodunu çalıştırır; aynı veriyle aynı kararlar çıkar
  (testli).

**Bağımsız son test** (2025-01 → 2026-11, orta maliyet):

| Ölçü | Değer |
| --- | ---: |
| İşlem | 45 |
| Ortalama R | −0,145 |
| PF | 0,56 |
| Net | −%3,3 |
| BTC al-tut | −%19,8 |

Ön kayıtlı ölçüte göre **başarısız.** Eğitim (+%7,9) ve doğrulama (+%6,0) pozitif ama istatistiksel olarak zayıf.

**Bugün işlem yapılabilir evren:** 1.413 paritenin 6'sı (5M USDT medyan hacim şartı). BTC filtresi kapalı.

**Komut satırı:**

```
cryptoscan scan
cryptoscan paper
cryptoscan download --out veri
cryptoscan research --data veri
cryptoscan backtest --data veri --from 2025-01-01 --cost orta
```

Aşağıdaki V2.x bölümleri tarihsel kayıttır. Söz konusu stratejiler ve modüller artık uygulamada yoktur.

## V2.5: geniş coin evreni ve iki aşamalı tarama (10 Kasım 2026)

Ön kayıt: [`docs/EVREN-V4-ONKAYIT.md`](docs/EVREN-V4-ONKAYIT.md). Sonuçlar: [`docs/EVREN-V4.md`](docs/EVREN-V4.md).

- **Dinamik coin keşfi:** Tarama artık sabit listeye bağlı değil. MEXC'deki bütün USDT pariteleri (bugün 1.414) iki
  aşamada değerlendiriliyor:
  - Ürün ayıklama: stablecoin, tokenleştirilmiş hisse, emtia, kaldıraçlı token.
  - Likidite, veri ve trend kontrolü.
  - En güçlü 20 için emir defteri, ATR, volatilite ve korelasyon.
- **Bugün işlem yapılabilir evren küçük:** Likidite kuralını geçen yalnız 31 parite var.
- **Tarihsel test:** 471 paritenin geçmiş verisiyle, listeleme tarihi ve zamana bağlı likidite dikkate alınarak
  yapıldı. Aynı S1 kurallarıyla dar 28 parite, ilk 20/50/100 ve tüm evren karşılaştırıldı.
- **Sonuç: daha fazla coin daha fazla kâr getirmedi.**
  - Son testte tüm evren +%11,4, dar liste +%15,7; ilk 20 ve ilk 50 zararda.
  - Hiçbir evren ön kayıtlı kuralı geçmedi; son test kârı her evrende ZEC'e bağlı.
  - Geniş evren korelasyonu ve düşüşü azaltıyor.
- **İleri test:** S1 ileri testi dar listeyle devam ediyor. Ayarlardan geniş evrene geçilebilir.
- **Tarama ekranı:** Huni, en güçlü 20 ve elenme nedenleri gösteriliyor.

## V2.4: strateji araştırması V3 (10 Kasım 2026)

Ön kayıt: [`docs/STRATEJI-V3-ONKAYIT.md`](docs/STRATEJI-V3-ONKAYIT.md) (testlerden önce commit edildi). Sonuçlar:
[`docs/STRATEJI-V3.md`](docs/STRATEJI-V3.md). Ham çıktılar: [`docs/research/`](docs/research/).

- Dört bağımsız strateji, 2020-08 → 2026-11 arasında 4H veriyle test edildi:
  - Eğitim, doğrulama ve dokunulmamış son test dönemleri.
  - Üç maliyet senaryosu.
  - İleri yürüyen test.
- **S1 (BTC'ye göre göreceli güç)** ön kayıtlı ölçütleri geçti. Doğrulama +0,26R, son test +0,21R.
- **S1'in kanıtı zayıf:**
  - t istatistiği düşük.
  - Son testteki kârın tamamı tek pariteden (ZEC) geliyor.
  - Evrende hayatta kalma yanlılığı var.
  - Tüm dönemde BTC al-tut'un çok gerisinde.
- **S2, S3 ve S4 başarısız.**
- **S1 yalnız ayrı bir sanal portföyde ileri teste alındı:** İşlemler → Gölge test.
- **Strateji A** aktif kâğıt işlemden çıkarıldı ve gölge teste alındı. B aktif profil olarak kaldı. Risk limitleri ve
  işlem kayıtları değişmedi.
- Uygulama artık "kurallar işlem açmadı" ile "yazılım/veri sorunu" durumlarını ayrı gösteriyor.
- Backtest varsayılan olarak "Orta" maliyet senaryosunu kullanıyor. Önceki −%13,92 sonucunun bir maliyet avantajı değil,
  evren farkı olduğu ayrıştırıldı.

## V2.3: kritik sorunlar incelemesi (10 Kasım 2026)

Rapor: [`docs/KRITIK-SORUNLAR.md`](docs/KRITIK-SORUNLAR.md). Yazılım doğru çalışıyor (geçmiş bir sinyal canlı motora
beslenince kâğıt işlem açılıyor); bot işlem açmıyor çünkü BTC düşüş trendinde ve 22 coinin 13'ü MEXC'deki geniş
spread/sığ defter nedeniyle eleniyor. Backtest spread'i gerçekçi hale getirildi; Tarama'ya teşhis paneli eklendi.
Sekiz filtre eğitim/test ve ileri yürüyen testle denendi, hiçbiri bağımsız dönemde başarılı olmadı ve etkinleştirilmedi.

## V2.2.1: BTC / piyasa filtresi açıklaması (10 Kasım 2026)

"Yeni işlem şu an engelli: BTC / piyasa filtresi alıma izin vermiyor" uyarısı incelendi:
[`docs/PIYASA-FILTRESI.md`](docs/PIYASA-FILTRESI.md). Engel gerçek piyasa durumundan (BTC 4H düşüş trendi) kaynaklanıyor;
filtre doğru hesaplıyor (bağımsız hesapla birebir). Panel artık engelin türünü (piyasa riski / veri sorunu) ve düz
açıklamasını gösteriyor; eski BTC verisiyle karar verilmemesi için güncellik şartı eklendi (yalnız sıkılaştırır).

## V2.1: profesyonel backtest denetimi (9 Kasım 2026)

Tam rapor: [`docs/BACKTEST-DENETIMI.md`](docs/BACKTEST-DENETIMI.md) · kural envanteri: [`docs/KURALLAR.md`](docs/KURALLAR.md).

- **Her işlem bağımsız olarak yeniden hesaplandı** (MEXC ham mumlarından, ayrı betikle). Bir kritik hata
  bulundu: kısmi çıkıştan kalan kayan nokta "tozu" satılamıyor, pozisyon aylarca açık kalıp işlem yeri
  işgal ediyordu (canlı kâğıt işlemde de olabilirdi). Düzeltildi; 90 günlük sonuç bu yüzden daha **kötü**
  çıktı (38 işlem −%0,39 → 44 işlem −%2,70).
- Aynı mumda stop + hedef artık 5M mumlarla doğrulanıyor ya da "belirsiz" işaretlenip ihtiyatlı çözülüyor
  (90 gün ve 1 yılda 0 durum). Hedefin üzerinde açılan mum, gerçek spread/kayma maliyeti, canlıdaki %0,3 giriş
  kayması sınırı backtest'e eklendi; ısınma süresi doğrulandı (45 ve 120 gün aynı işlemler).
- **A ve B aynı koşullarda ayrı ayrı (1 yıl):** A 75 işlem PF 0,51 −0,36R; B 144 işlem PF 0,67 −0,24R; ikisi
  de dönemin iki yarısında negatif.
- **5 sınıflı piyasa rejimi** (BTC 4H) eklendi; filtre açık/kapalı ölçüldü, beklenen değeri iyileştirmedi →
  **varsayılan kapalı** (kural Z19 yalnız bilgi verir).
- Kayıp analizi: zararlıların %38'i lehte 0,3R bile gitmeden kapanıyor; dar stop (< 0,75 ATR), düşük ADX,
  1H RSI negatif uyumsuzluk ve 4H/1H uyumsuzluğu iki yarıda da daha kötü. Önceden tanımlı hiçbir filtre
  sonucu pozitife çevirmedi; varsayılanlar değişmedi.
- Backtest ekranı: özet → A/B tablosu → aylık değişim → süzülebilir işlem geçmişi; işleme dokununca mum
  grafiği, giriş anındaki seviyeler ve gerekçe.

| Özet | A/B ve işlem geçmişi | İşlem ayrıntısı | A ve B ayrı |
| --- | --- | --- | --- |
| ![Özet](docs/screenshots/6-backtest.png) | ![A/B](docs/screenshots/6b-backtest-a-b-ve-gecmis.png) | ![Ayrıntı](docs/screenshots/10-islem-detayi.png) | ![A ve B ayrı](docs/screenshots/11-a-b-ayri.png) |

## V2'de neler değişti

Ayrıntılı denetim ve öncesi/sonrası: [`docs/DENETIM.md`](docs/DENETIM.md).

- **Kritik altyapı:** eski veriyle karar verilmez (kural Z17); bekleyen limit emirler kalıcı ve nakit ayrılır;
  hesap ödeyemeyeceği dolumu reddeder; çevrimdışı süre ve o sırada kaçırılan stop/hedefler kaydedilir.
- **Karar günlüğü:** her 15M taramasında her coin için sonuç, gerekçe, kullanılan verinin zamanı; ayrıntılı
  incelenen adaylarda her stratejinin sonucu ve her kuralın geçti/kaldı ayrıntısı (İşlemler → Karar günlüğü).
- **Gerçekçi emir simülasyonu:** MEXC emir kuralları (asgari/azami tutar, fiyat/miktar adımı, fiyat bandı),
  maker/taker komisyon, alış-satış farkı, emir defterinde kayma, mum hacmiyle sınırlı ve kısmi gerçekleşme;
  gerçekleşmesi mümkün olmayan emir doldurulmaz.
- **Risk:** işlem başına %0,5, günlük %1,5, korelasyon, **toplam açık risk sınırı %1,5** (kural Z18; önceki
  örtük "3 × %0,5" sınırıyla aynı, davranışı değiştirmez), **ATR stop seçeneği (varsayılan kapalı)**.
- **Strateji profilleri:** Adaptif (varsayılan, önceki davranış), Trend (yalnız B), Seçici (A+B, kalite ≥ 60 ve
  net R:R ≥ 2). Mevcut kurallar ve varsayılan parametreler değiştirilmedi.
- **Backtest güvenceleri:** gelecek veri silinince geçmiş kararların değişmediği test edilir; profiller aynı
  koşullarda, dönemin iki yarısında ayrı ayrı karşılaştırılır.
- **Arayüz:** Panel'e risk durumu ve çalışma güvenilirliği kartları; karar günlüğü, işlem grafikleri, profil
  karşılaştırma; bekleyen emri iptal.

### Profil karşılaştırması (1 yıl, 15 coin, aynı koşullar)

| Profil | Tüm dönem | İlk yarı | İkinci yarı |
| --- | --- | --- | --- |
| Adaptif (varsayılan) | 194 işlem, **−21,0 %**, PF 0,61 | −8,9 % | −13,3 % |
| Trend (yalnız B) | 144 işlem, **−13,3 %**, PF 0,67 | −5,8 % | −8,0 % |
| Seçici | 87 işlem, **−10,7 %**, PF 0,60 | −5,3 % | −5,8 % |

Üç profil de iki yarıda da zararda; kârlı bir profil yok. Seçici daha az işlem açarak zararı azaltıyor ama
işlem başına beklenti (−0,27R) Adaptif'ten (−0,29R) anlamlı biçimde iyi değil. Çıktı:
[`docs/research/2026-11-09-profil-karsilastirma.txt`](docs/research/2026-11-09-profil-karsilastirma.txt).

## Modüller

| Modül | İçerik |
| --- | --- |
| `core/` | Saf Kotlin/JVM (Android bağımlılığı yok): MEXC veri katmanı, göstergeler, piyasa yapısı, tarama, stratejiler, kural motoru, risk, sanal hesap, canlı motor, backtest |
| `app/` | Android: 7/24 ön plan servisi + Türkçe kumanda paneli (Panel, Tarama, Coin detayı, İşlemler, Backtest, Ayarlar) |
| `cli/` | Komut satırı: `scan`, `backtest`, `paper` (aynı çekirdek; bilgisayarda/sunucuda 7/24 çalıştırılabilir) |

Çekirdek Android'den bağımsız olduğu için motor, ileride telefondan bağımsız bir sunucuda da aynı kodla
çalıştırılabilir. Gerçek parayla 7/24 işlemde telefonun tek yürütücü olması bağlantı/uyku riski taşır.

## Ekranlar (V2.2 sade arayüz)

Gerçek MEXC verisiyle (BTC/NEAR) Robolectric üzerinde çizilmiş görüntüler. Uygulama koyu temayla açılır.
Küçük telefon (320 × 640 dp) görüntüleri: `docs/screenshots/kucuk-ekran`. Değişikliklerin ayrıntısı:
[`docs/ARAYUZ.md`](docs/ARAYUZ.md).

![Önce / sonra](docs/screenshots/once-sonra.png)

| Panel | Tarama | Coin detayı |
| --- | --- | --- |
| ![Panel](docs/screenshots/1-panel.png) | ![Tarama](docs/screenshots/2-tarama.png) | ![Coin detayı](docs/screenshots/3-coin-detay.png) |
| **Açık işlemler** | **Geçmiş** | **İşlem ayrıntısı** |
| ![Açık işlemler](docs/screenshots/4-islemler.png) | ![Geçmiş](docs/screenshots/5-kapanmis-islemler.png) | ![İşlem ayrıntısı](docs/screenshots/8-islem-grafigi.png) |
| **Backtest** | **Karar günlüğü** | **Ayarlar** |
| ![Backtest](docs/screenshots/6-backtest.png) | ![Karar günlüğü](docs/screenshots/7-karar-gunlugu.png) | ![Ayarlar](docs/screenshots/12-ayarlar.png) |

## Akış

```
Her 15M mum kapanışı (+3 sn, sunucu saatine göre)
  → kapanmış mumlar REST ile alınır, süreklilik doğrulanır (bozuksa geçmiş yeniden yüklenir)
  → Tarama: izleme listesi + likiditeye göre ek coinler
      → elenir: işlem yapılamayan, hacmi düşük, spread'i geniş, emir defteri sığ
      → işaretlenir: aşırı 24s hareket, yüksek fitil oranı, ani hacim patlaması (manipülasyon riski)
      → BTC / genel piyasa filtresi (BTC 4H düşüş trendi ya da 4 saatte sert düşüş → alım yok)
      → ön puanla en iyi 2–4 aday
  → Aday başına: 4H rejimine uygun strateji(ler) → kurulum
  → 15 zorunlu kural (biri bile sağlanmazsa işlem YOK) + 11 kalite kuralı (puan)
  → Risk: %0,5 risk ile pozisyon büyüklüğü, borsa hassasiyeti/asgari tutar
  → Sanal giriş (anlık alış fiyatı sinyalden %0,3'ten fazla kaçtıysa iptal)
Açık pozisyon: stop/hedef WebSocket fiyatlarıyla anlık, yapısal bozulma 1H kapanışlarında izlenir.
```

Her karar (açılan, reddedilen, kurulum bulunamayan, kapanan) gerekçesiyle **karar günlüğüne** yazılır. Açılan her
işlem notun 6 sorusunu yanıtlar: *Neden bu coin? Neden bu giriş? Trend ve yapı uygun mu? Stop nerede ve neden?
Net R:R kaç? Ne zaman geçersiz olur?*

## Tasarım notuna göre durum

| Bölüm | Durum |
| --- | --- |
| 2. MEXC API | ✅ REST + protobuf WebSocket (öncelikli), 30 kanal/bağlantı paylaştırma, PING, 60 sn veri yoksa ve 23 saatte bir yeniden bağlanma, üstel geri çekilme; WebSocket kopuksa REST yedeği ve dakikalık REST doğrulaması. Kapanmamış mum sinyal sayılmaz. |
| 3. Coin tarama | ✅ BTC, ETH, SOL, NEAR, ZEC, INJ, AVAX + likiditeye göre 15 ek coin (ayarlanabilir 0–30) |
| 4. Teknik analiz | ✅ 4H: EMA, SuperTrend, ADX, yapı (HH/HL/LH/LL, BOS). 1H: S/R, FVG, order block, likidite (eşit tepe/dip), VWAP, R:R. 15M: mum kapanışı, hacim, RSI, MACD, EMA tetikleri. 5M: yalnız izleme (karar vermez). |
| 5. Stratejiler | ✅ A Breakout+Retest, B Trend Pullback; C Yatay piyasa **varsayılan kapalı** (ayrı backtest gerekli). Seçim 4H rejimine göre. |
| 6. Kural sistemi | ✅ Motor + JSON yapılandırma. ⏳ **60 kuralın metni henüz eklenmedi** (varsayımla doldurulmadı) |
| 7. Risk | ✅ Tablodaki tüm değerler; ayarlanabilir |
| 8. Emir yönetimi | ✅ Sanal; piyasa ya da limit (maker) giriş, limit hedef, başa baş, kısmi kâr, süre sınırı, coin dinlenmesi (deneysel). ⚠ Gerçek emir V2'de; aşağıdaki MEXC bulgusuna bakın |
| 9. Yapay zekâ | ⏳ V3 |
| 10. Kontrol paneli | ✅ Android (Türkçe). ⏳ Telegram bildirimi |
| 11. Test | ✅ Backtest (Aşama 1), canlı veriyle sanal işlem (Aşama 2), ileri yürüyen test, Monte Carlo, gölge işlem ile kural katkısı |

## MEXC ile ilgili doğrulanmış bulgular (Kasım 2026)

1. **Spot API'de stop emri türü yok.** Desteklenen türler yalnızca `LIMIT, MARKET, LIMIT_MAKER,
   IMMEDIATE_OR_CANCEL, FILL_OR_KILL` (resmi doküman, ENUM tanımları; canlı `exchangeInfo` de bunları gösteriyor).
   Notun 8. bölümündeki kritik şart bu yüzden **sağlanmıyor**: borsa tarafında koruyucu stop kurulamaz, koruma
   yalnızca yazılım stopuyla olur. V2'de gerçek paraya geçmeden önce bu riskin nasıl yönetileceğine karar verilmeli
   (ör. botun kesintisiz çalıştığı sunucu, bağlantı kopmasında pozisyonu kapatma politikası, küçük sermaye).
2. **Mum sınırı pratikte 500.** Doküman 1000 dese de `klines` istek başına en fazla 500 mum döndürüyor ve yalnız
   `startTime` verilirse onu yok sayıp en son mumları veriyor. Geçmiş yükleyici iki ucu da gönderip son mumdan
   devam eder.
3. **Likidite ince.** USDT paritelerinden yalnız 13'ü 2M, 29'u 1M USDT'nin üzerinde 24s hacme sahipti. NEAR ve
   AVAX'ın MEXC hacmi ~0,3M, ZEC ~1,3M idi; bu coinler tarama filtresinde sık sık elenir. Bu yüzden asgari
   hacim varsayılanı 1M (Ayarlar'dan değiştirilebilir).
4. Komisyon: `exchangeInfo` taker %0,05, maker %0. WebSocket akışı protobuf (`wss://wbs-api.mexc.com/ws`).

## Backtest sonuçları (dürüst özet)

Ayarlar varsayılan (risk %0,5, asgari net R:R 1,5, A+B etkin), 7 coinlik izleme listesi, komisyon + 0,05 % kayma
+ 0,05 % spread dahil:

| Dönem | Net getiri | BTC al-tut | Maks. düşüş | İşlem | Kazanma | Profit factor |
| --- | --- | --- | --- | --- | --- | --- |
| 90 gün (Ağu–Kas 2026) | **−2,7 %** | +18,4 % | 7,2 % | 44 | 27 % | 0,80 |
| 180 gün (May–Kas 2026) | **−9,7 %** | −6,2 % | 10,6 % | 75 | 21 % | 0,58 |

- Strateji A (Breakout + Retest) iki dönemde de belirgin zararda (kazanma ~%10).
- Strateji B (Trend Pullback) 90 günde PF 1,28, 180 günde PF 0,82.
- Strateji C tek başına 180 günde yalnız 4 işlem üretti; karar vermek için yetersiz.
- Kurulumları en çok engelleyen kural "Net R:R ≥ 1,5" (Z03); yani motor zayıf kurulumları zaten eliyor.

Sonuç: Mevcut parametrelerle **kanıtlanmış bir avantaj yok**; gerçek paraya geçilmemeli. Sonraki adım 60 kuralın
eklenmesi ve stratejilerin farklı dönemlerde (aşırı uyum yapmadan, ileri test ile) iyileştirilmesi.
Backtest'i Android'de *Backtest* sekmesinden ya da `cryptoscan backtest` ile yeniden çalıştırabilirsiniz.

## Bir yıllık araştırma (Kasım 2026)

Ayrıntılı çıktı: [`docs/research/2026-11-08-bir-yillik-arastirma.txt`](docs/research/2026-11-08-bir-yillik-arastirma.txt).
Tekrar üretmek için: `cryptoscan research --data <klasör>` (veri: MEXC 15M/1H/4H, coin başına bir JSON).

**Veri:** 15 coin (izleme listesi + MEXC'nin en likit 8 coini), 15 Kas 2025 → 8 Kas 2026. MEXC 15 dakikalık
mumları yalnız **13 Kasım 2025'ten** itibaren veriyor; bu yüzden en fazla ~1 yıl test edilebiliyor.

**Yöntem:** 8 seçenek sonuçlar görülmeden önce sabitlendi (`PRE_REGISTERED_VARIANTS`). Her biri yılın ilk ve ikinci
yarısında ayrı ayrı; ayrıca ileri yürüyen testte (3 ay seçim → sonraki 2 ay görülmemiş dönem) çalıştırıldı.
Monte Carlo ile 5.000 yeniden örnekleme yapıldı. Gölge işlemlerde bulunan her kurulum (alınsın ya da reddedilsin)
portföyden bağımsız simüle edilip her kuralın sonuca katkısı ölçüldü.

| Seçenek | İlk yarı | İkinci yarı |
| --- | --- | --- |
| V0 Mevcut (A+B, piyasa emri) | −8,7 % · PF 0,68 | −12,5 % · PF 0,58 |
| V1 Yalnız B | −5,6 % · PF 0,75 | −7,2 % · PF 0,65 |
| V2 Limit giriş/hedef (maker) | −20,7 % · PF 0,51 | −10,0 % · PF 0,74 |
| V4 V2 + %50 kısmi kâr 1R + başa baş | −13,6 % · PF 0,60 | −11,3 % · PF 0,65 |
| V7 Yalnız B + V4 | −9,8 % · PF 0,65 | −9,6 % · PF 0,61 |
| **İleri yürüyen test (görülmemiş dönemler)** | | **−21,3 %, 235 işlem, PF 0,63** |

**Sonuçlar:**

1. **Hiçbir seçenek kâra geçmedi**; Monte Carlo'ya göre zarar olasılığı ~%100. Emir/çıkış ayarları, sinyalin
   kendisi negatif beklentili olduğu için sonucu kurtaramıyor. Bu yüzden varsayılanlar değiştirilmedi; seçenekler
   Ayarlar'da "deneysel" olarak duruyor.
2. **Maker (limit) emir maliyeti gerçekten düşürüyor:** kurulum başına ortalama sonuç −0,21R'den −0,11R'ye çıktı.
   Kâr eden bir sinyal bulunduğunda kullanılmalı.
3. **Kural sistemi şu an yanlış işlemleri seçiyor:** kurallarca kabul edilen kurulumlar reddedilenlerden kötü.
   En büyük etken "net R:R ≥ 1,5" kuralı (geçenler −0,23R, kalanlar −0,09R): yakın yapısal hedefe göre yüksek
   R:R çıkan kurulumlar genellikle oynaklığa göre çok dar stoplu ve gürültüyle stop oluyor (hipotez).
4. **Sonucu iyileştiren kurallar:** en az 2 teyit (+0,10–0,12R), fiyat 1H VWAP üzerinde (+0,07R), BTC 4H
   yükselişte (+0,06–0,07R), hacim teyidi (+0,02–0,06R). Kalite puanı ise sonucu zayıf öngörüyor (yalnız en düşük
   dilim belirgin kötü).

**Sonraki adım:** Stratejilerin sinyal mantığı değişmeli (ör. oynaklığa göre asgari stop mesafesi, hedef seçimi).
Yeni fikirler bu yılın verisinde değil, **başka coinlerde** (aynı dönem) ya da ileriye dönük sanal işlemle
doğrulanmalı; aksi halde aşırı uyum olur. 60 kural eklendiğinde her birinin katkısı aynı araçla ölçülecek.

## 60 kuralı ekleme

Kurallar `core/src/main/resources/rules/default-rules.json` dosyasındadır. Yerleşik kalite kuralları kapatılabilir
ve ağırlıkları değiştirilebilir; **zorunlu kurallar kapatılamaz**. 60 kural kod yazmadan `custom` listesine eklenir:

```json
{
  "id": "R12",
  "title": "4H ADX trend gücünü teyit ediyor",
  "group": "MANDATORY",
  "condition": "h4.adx >= 25 and h4.plusDi > h4.minusDi",
  "source": "60 kural listesi, madde 12"
}
```

Koşul dili: `h4.`, `h1.`, `m15.`, `btc.h4.`, `btc.h1.` ön ekleriyle `close, emaFast, emaMid, emaSlow, emaTrend,
rsi, macd, macdSignal, macdHistogram, atr, adx, plusDi, minusDi, superTrend (1/-1), vwap, volume, volumeAverage,
regime, trend`; önceki mum için `h1.prev.rsi`. Ayrıca `setup.rr, setup.stopDistancePercent, setup.confirmations,
setup.confluences, coin.spread, coin.volume24h, coin.change24h, market.breadth`. İşleçler `> >= < <= == !=`,
koşullar `and`/`ve` ile birleşir. Hatalı yazılmış bir zorunlu kural işlemi **engeller** (güvenli taraf) ve
Ayarlar'da gösterilir. Bu dille ifade edilemeyen kurallar `BuiltInRules` içine kodla eklenir.

## Risk parametreleri

| Parametre | Değer | Kaynak |
| --- | --- | --- |
| İşlem başına risk | %0,5 | Tasarım notu |
| Günlük kayıp limiti | %1,5 (gün başı özsermayeye göre, gerçekleşmemiş dahil) | Tasarım notu |
| Eş zamanlı işlem | en fazla 3 | Tasarım notu |
| Asgari / tercih edilen net R:R | 1,5 / 2+ | Tasarım notu |
| Kaldıraç, short, zararda ek alım | yok / yok / yasak | Tasarım notu |
| Pozisyon üst sınırı | sermayenin %30'u | Test varsayımı (notta yok) |
| İlişkili coin toplam riski | %1 (1H getiri korelasyonu ≥ 0,7) | Test varsayımı (notta yok) |
| Kayma / yarım spread | %0,05 / canlı spread (yoksa %0,025) | Test varsayımı |

## Kullanım

**Android:** APK'yı kurun → *Panel* → *Botu başlat*. Bot ön plan servisi olarak çalışır, bildirim çubuğunda durum
gösterir, cihaz yeniden başlarsa otomatik devam eder. 7/24 için *Ayarlar → 7/24 çalışma → Muafiyet iste* ile
pil optimizasyonu muafiyeti verin. *Yeni işlemleri duraklat* açık pozisyonların takibini sürdürür; *Botu kapat*
takibi de durdurur.

> Not: Pil optimizasyonu muafiyetini doğrudan istemek Play Store politikasına uymaz; kişisel kullanım içindir.

**Komut satırı** (JDK 17+):

```bash
./gradlew :cli:installDist
cli/build/install/cryptoscan/bin/cryptoscan scan
cli/build/install/cryptoscan/bin/cryptoscan backtest --days 90 --symbols BTC,ETH,SOL --strategies A,B --out rapor.json
cli/build/install/cryptoscan/bin/cryptoscan paper --data ./cryptoscan-data   # 7/24 sanal işlem
cli/build/install/cryptoscan/bin/cryptoscan research --data ./veri --out arastirma.json   # deney paketi
cli/build/install/cryptoscan/bin/cryptoscan compare --data ./veri    # profilleri aynı koşullarda karşılaştır
cli/build/install/cryptoscan/bin/cryptoscan analyze --data ./veri    # A/B ayrı, rejim açık/kapalı, kayıp analizi
```

## Derleme ve testler

```bash
./gradlew :core:test                  # 93 çekirdek testi (+2 canlı)
./gradlew :core:test -Plive           # + gerçek MEXC'ye bağlanan duman testleri
./gradlew :app:testDebugUnitTest      # 10 ekran çizim testi (Robolectric) + 5 biçimlendirme testi
./gradlew :app:testDebugUnitTest -Pscreenshots   # ekran görüntülerini app/build/outputs/roborazzi'ye kaydeder
./gradlew :app:lintDebug :app:assembleDebug
```

Maven Central'a erişimi kısıtlı ortamlarda Robolectric'in Android çalışma ortamı için
`ROBOLECTRIC_REPO_URL=https://maven-central.storage-download.googleapis.com/maven2` ayarlanabilir.

Doğrulama: göstergeler (EMA, RSI, MACD, ATR, ADX/DI, SuperTrend) gerçek BTC verisinde **pandas-ta** ve **ta**
kütüphaneleriyle karşılaştırılır; analizin gelecek veriye bakmadığı, kesilmiş seriyle aynı sonucu verdiği test
edilir; protobuf çözücü gerçek MEXC mesajıyla test edilir; backtest gerçek NEAR/BTC verisiyle regresyon testine sahiptir.

Gereksinimler: JDK 17+ (21 önerilir), Android SDK 37. Sürümler: AGP 9.5, Gradle 9.9, Kotlin 2.4, Compose BOM 2026.09,
OkHttp 5.5, minSdk 26.

## Yol haritası

- **V2:** API anahtarı (çekim yetkisi kapalı, IP kısıtlı, Android Keystore ile şifreli), MEXC Spot Testnet yoksa
  çok küçük sermaye; stop emri olmadığı için yazılım stopu güvenlik politikası; Telegram bildirimleri; sunucu modu.
- **V3:** Portföy raporları, YZ destekli haber/kayıp analizi (emir yetkisi olmadan), haftalık rapor.
- **V4:** Kanıtlanmış stratejilerin rejime göre otomatik seçimi.

## Uyarı

Bu yazılım yatırım tavsiyesi değildir. Kripto para işlemleri yüksek risk içerir; hiçbir backtest gelecekteki
sonucu garanti etmez.
