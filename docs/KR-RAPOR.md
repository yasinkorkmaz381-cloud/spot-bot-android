# Kırılım–Retest (KR): uygulama ve test raporu

13 Kasım 2026 · sürüm 4.0.0-paper · yalnız sanal işlem (paper trading).

## Sonuç

**Ön kayıtlı değerlendirmenin sonucu: Başarısız. Bu tek düzende kârlılık bulunamadı.**

Testin dayanağı şunlar:

- MEXC 15M verisi.
- O güne ait CMC ilk 300 listesi.
- Sonuçlardan önce yazılıp commit'lenen kurallar ([KR-ONKAYIT.md](KR-ONKAYIT.md), commit `a3e31fb`; kod commit'i `4c6419c`).

Bulgular:

- **Üç dönemin hepsinde zarar.** Araştırma, doğrulama ve son değerlendirme dönemlerinde işlem başı ortalama net R
  −0,44 ile −0,52 arasında. %90 güven aralıklarının üst sınırı da sıfırın altında.
- **Maliyetsiz de zarar.** Komisyon, spread ve kayma sıfır olsaydı da işlemler toplamda zararda kalıyordu. Düzenin
  kendisinde avantaj görülmedi; maliyet zararı yalnızca büyütüyor.
- **Yardımcı test de zarar.** Binance verisiyle 2023-11 → 2025-10 dönemi: 639 işlem, net −%34,6. Bu testin evreni
  geleceğe bakıyor; sonucu yukarı yönde yanlı olmalıydı, buna rağmen olumsuz.
- **Duyarlılık sonucu değiştirmiyor.** Sekiz tek değişkenli denemenin hepsinde PF 0,38–0,45. Başarısızlık bir
  parametre değerinden kaynaklanmıyor.

Ana yapılandırma sonuçlara göre değiştirilmedi. Deneme sayısı artırılmadı, en iyi dönem öne çıkarılmadı. Araştırma
döneminde bir kod hatası aranıp bir işlem ham MEXC mumlarıyla elle doğrulandı (aşağıda). Hata bulunmadı.

Uygulama bu stratejiyle paper olarak çalıştırılabilir. Ancak test sonuçlarına göre zarar etmesi beklenir; gerçek
parayla kullanım için hiçbir dayanak yoktur.

## Yapılanlar (uygulama planı ve durumu)

| Adım | Durum |
| --- | --- |
| Ön kayıt (kurallar, dönemler, maliyet, kabul ölçütleri) | Sonuçlardan önce commit'lendi |
| CMC ilk 300 → MEXC eşleşmesi, günlük yenileme, bayat liste kuralı | Çekirdekte (`Universe.kt`, `CmcClient.kt`) + testler |
| Tek karar motoru: kurulum izleyici, fiyatlama, miktar, çıkış | `core/kr`; backtest ve paper aynı sınıfları kullanıyor |
| Paper bot (canlı) | `KrPaper` + Android ön plan servisi |
| Sade arayüz (Ana ekran / Fırsatlar / İşlemler) | Ayrıntı, Ayarlar ve Arşiv ayrı ekranlarda |
| Eski RS-1 ve A/B/C | Aktif sistemden kaldırıldı. Kayıtları `archive-rs1/` ve `archive-v4/` altında; performansa karışmıyor |
| Backtest: araştırma, doğrulama, son değerlendirme, ×2 maliyet, duyarlılık, yardımcı test | Bu rapor |
| Testler, lint, APK | core 49 (1 canlı test atlandı), app 32, 0 hata, lint 0 |

## Tek stratejinin kural tablosu

Ayrıntılı ve resmî tanım: [KR-ONKAYIT.md](KR-ONKAYIT.md) §1–§3. Bütün kurallar zorunludur; puanlama yoktur.

| Aşama | Kural |
| --- | --- |
| 4H yön | Son kapanmış 4H: kapanış > EMA50 ve EMA50 > 6 mum önceki EMA50 |
| 1H seviye | Fraktal tepe: 3 sol + 3 sağ mumdan yüksek. Sağdaki 3. mum kapanınca bilinir. En fazla 72 saat eski. Oluştuktan sonra hiçbir 15M kapanış üstünde değil |
| 15M kırılım | Seviye kırılım mumundan önce biliniyor. Kapanış seviyenin üstünde (fitil sayılmaz). Hacim ≥ 1,5 × önceki 20 mum. Kırılan seviye bir daha kullanılmaz |
| Retest | Kırılımdan sonraki 8 mum içinde: düşük ≤ seviye + 0,25 ATR15, kapanış > seviye, kapanış ≥ açılış. Kırılım ve retest aynı mum olamaz |
| İptal | Retestten önce kapanış ≤ seviye; 8 mumda retest yok; 4H yön bozuldu |
| Giriş | Retest kapanışından sonra uygulanabilir ilk fiyat. Seviye + 0,75 ATR15'in üstündeyse kovalanmaz |
| Stop | Kırılımdan sonraki mumların en düşüğü − 0,25 ATR15. Gevşetilmez, başabaşa taşınmaz |
| Hedef | min(en yakın kırılmamış 1H direnç, giriş + 1,5 × ATR1H). Tek tam çıkış |
| Maliyet sonrası | Net R:R ≥ 1,2. İki yön maliyet ≤ brüt hedefin %40'ı |
| Çıkış sırası | Stop → hedef (aynı mumda ikisi de görülürse stop) → yapı bozuldu (kapanış < seviye − 0,25 ATR) → 2 saatte hedefin yarısına gelmedi → 6 saat |
| Piyasa | BTC son 4 saatte %2'den fazla düşmedi. Coin 24 saatte BTC'den güçlü. 24 saat hacmi ≥ 1 M USDT. Canlıda ayrıca spread ≤ %0,20 ve emir defteri kayması ≤ %0,15 |
| Risk | İşlem başına %0,25; toplam açık risk ≤ %1; en fazla 4 pozisyon; coin başına ≤ %20, toplam ≤ %70 sermaye; günlük (TSİ) kayıp %2 → o gün yeni giriş yok |
| Sıralama | Aynı kapanıştaki adaylar: net R:R ↓, maliyet ↑, sembol |

Parametreler araştırma varsayımıdır; geçmiş veriye göre seçilmedi ve kanıtlanmış optimum değildir.

60 kural uyarlaması: [KR-KURALLAR.md](KR-KURALLAR.md). Kuralların metni bu çalışmaya ulaşmadı; tablo istekteki
numaralara ve başlıklara dayanıyor.

## Coin evreni

**Canlı eşleşme (13.11.2026 18:31 UTC CMC listesi):** İlk 300 → MEXC'de USDT paritesi 215 → taranan 211.

| Durum | Sayı | Not |
| --- | --- | --- |
| Elenen (stablecoin, tokenleştirilmiş hisse/emtia/ETF/tahvil, LP token) | 52 | |
| MEXC'de USDT spot paritesi yok | 33 | Örnek: LEO, GT, DASH, AKT, THETA, ICX… Tam liste: [kr-evren-canli.txt](research/kr/kr-evren-canli.txt) |
| Aynı sembol, farklı varlık | 1 | BEAM: MEXC fiyatı CMC'den %22 farklı |
| İşlem kapalı / ST etiketli | 3 | XMR (ST), PROM, RAIN |

Eşleşme kuralı:

- Önce sözleşme adresi karşılaştırılır.
- Adres yoksa MEXC fiyatı CMC fiyatının ±%10'u içinde olmalı.
- İlk 300'de aynı MEXC paritesine iki varlık eşleşirse fiyatı yakın olan alınır.
- 301 ve sonrası eklenmez.

Liste günde bir yenilenir. CMC zaman damgasından 48 saat sonra geçersiz sayılır: yeni giriş durur, açık
pozisyonların takibi sürer. Liste değişti diye açık pozisyon satılmaz.

**Backtest evreni:** Her gün o günün 00:00 UTC CMC listesi kullanıldı (gün başına ortalama ilk 300 → MEXC'de
~190 → taranan ~184). CMC'nin Basic planı son 12 ayın günlük geçmiş listelerini veriyor; MEXC 15M verisi de yaklaşık
aynı süreyi kapsıyor. Bu yüzden **ana testte bugünkü listeyle geçmişe bakma yanlılığı yok**. 12 Kasım listesi henüz
geçmiş uç noktasında olmadığı için o gün 11 Kasım listesi kullanıldı; bu, 48 saat kuralının içinde kalıyor.

## Sonuçlar (MEXC 15M, o günün CMC ilk 300'ü)

Her dönem 1.000 USDT ile ayrı başlar. Dönemler kronolojiktir. Yapılandırma ön kayıtta dondurulmuştu; doğrulama ve son
değerlendirme yalnız bir kez çalıştırıldı.

| Dönem | Maliyet | İşlem | Net USDT | Net % | PF | Ort. net R | Kazanan | En büyük düşüş | Maliyet / kazananların brüt kârı | Ort. elde tutma |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Araştırma 28.11.25–30.04.26 | Normal | 138 | −111,97 | −11,2 | 0,42 | −0,44 | %18 | %11,4 | %95 | 46 dk |
| | ×2 | 67 | −82,28 | −8,2 | 0,28 | −0,59 | %12 | %8,3 | %178 | 55 dk |
| Doğrulama 01.05–31.07.26 | Normal | 60 | −51,23 | −5,1 | 0,40 | −0,47 | %20 | %5,6 | %89 | 42 dk |
| | ×2 | 35 | −40,07 | −4,0 | 0,31 | −0,57 | %17 | %4,7 | %157 | 36 dk |
| Son değerlendirme 01.08–12.11.26 | Normal | 92 | −83,83 | −8,4 | 0,39 | −0,52 | %16 | %9,3 | %91 | 59 dk |
| | ×2 | 49 | −52,29 | −5,2 | 0,35 | −0,52 | %18 | %6,0 | %138 | 57 dk |

×2 maliyette işlem sayısı düşer, çünkü maliyet ve R:R süzgeçleri daha çok adayı eler.

Son değerlendirme döneminin sonunda 1 açık pozisyon (ETHFI) gerçekleşmemiş olarak kaldı.

### Ön kayıtlı sınıf (doğrulama + son değerlendirme, normal maliyet)

| Ölçüt | Sonuç |
| --- | --- |
| En az 100 işlem | ✓ (152) |
| Net beklenti > 0 | ✗ |
| PF ≥ 1,2 | ✗ (0,39) |
| En büyük düşüş ≤ %6 | ✗ (%9,3) |
| ×2 maliyette net > 0 | ✗ (−92,36) |
| Ortalama R %90 güven aralığı alt sınırı > 0 | ✗ (aralık −0,64 ile −0,36) |
| En iyi coin çıkarılınca net > 0 | ✗ |
| Doğrulama ve son değerlendirme ayrı ayrı net > 0 | ✗ / ✗ |

**Sınıf: Başarısız.**

### Neden kaybediyor (teşhis; kural değişikliği için kullanılmadı)

- **Çıkışlar stopta yoğunlaşıyor:** İşlemlerin %70'i stopla kapandı (araştırma 99/138, doğrulama 45/60, son değerlendirme
  64/92). 95 işlem giriş mumunun içinde stopa takıldı (42 + 26 + 27).
- **Stoplar sıkı:** Medyan stop mesafesi girişin %0,81'i, medyan hedef mesafesi %2,34. Planlanan medyan net R:R 2,1
  olmasına karşın kazanma oranı %16–20.
- **Avantaj maliyetten önce de yok.** Maliyetsiz sonuç (net + toplam maliyet):

  | Dönem | Maliyetsiz sonuç (USDT) |
  | --- | --- |
  | Araştırma | −32 |
  | Doğrulama | −19 |
  | Son değerlendirme | −33 |

  Maliyetler (medyan iki yön ~%0,32) kazananların brüt kârının %89–95'ini götürüyor.
- **Tek coin kaynaklı değil:** Kayıp tek bir coinden gelmiyor. En kötü coin −8 ile −12 USDT arasında; işlemler 32–70
  coine dağılmış.
- **Elle doğrulama:** ARUSDT, 28.11.2025.

  | Adım | Zaman (UTC) | Fiyat / veri |
  | --- | --- | --- |
  | Seviye | Saat 08 tepesi; sağ taraf 12:00'de kesinleşti | 4,360 |
  | Kırılım | 16:30 kapanışı | 4,364 (hacim ~3,7×) |
  | Retest | 16:45 | Düşük 4,357, kapanış 4,369, yükseliş mumu |
  | Giriş | 17:00 açılışı | 4,3734 (spread + kayma dahil) |
  | Stop | | 4,3485 |
  | Sonuç | 17:00 mumu | Düşük 4,293 → stop vuruldu |

  Motor kurala uygun çalışmış; kayıp piyasa davranışından geliyor.

### İşlem sıklığı (normal maliyet)

| Dönem | Gün | İşlemli gün | Günde | Haftada | Aktif gün başına | Bir günde en çok | Ort. taranan coin |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Araştırma | 154 | 72 | 0,90 | 6,3 | 1,9 | 8 | 184 |
| Doğrulama | 92 | 35 | 0,65 | 4,6 | 1,7 | 4 | 184 |
| Son değerlendirme | 104 | 49 | 0,88 | 6,2 | 1,9 | 6 | 186 |

Günlerin yarısından fazlasında işlem yok. Günlük kota veya garanti işlem sayısı yoktur. Risk sınırları işlem sayısını
kısıtlamadı: "4 pozisyon dolu" ve "toplam risk dolu" nedeniyle elenen aday sıfır.

### Kaçan fırsatlar (son değerlendirme, evrendeki coinler)

| Aşama | Sayı |
| --- | --- |
| Kırılım reddi: 4H yön yükselişte değil | 13.837 |
| Kırılım reddi: hacim düşük | 5.502 |
| Kırılım kabul | 4.736 |
| Kurulum bitti: retestten önce seviye altında kapanış | 2.213 |
| Kurulum bitti: retest 8 mumda gelmedi | 664 |
| Kurulum bitti: daha yeni kırılım | 198 |
| Retest onaylandı | 1.662 |
| Süzgeç: 24 saat hacmi < 1 M USDT | 1.045 |
| Süzgeç: BTC'ye göre zayıf | 119 |
| Giriş reddi: fiyat giriş bölgesinden uzaklaştı | 362 |
| Giriş reddi: net R:R < 1,2 | 43 |
| **Giriş** | **93** |

Diğer dönemlerin hunisi: [kr-sonuc.txt](research/kr/kr-sonuc.txt).

### Sınırlı duyarlılık (yalnız araştırma dönemi, tek seferde tek değişiklik)

| Değişiklik | İşlem | Net USDT | PF | Ort. R | En büyük düşüş |
| --- | --- | --- | --- | --- | --- |
| Ana yapılandırma | 138 | −111,97 | 0,42 | −0,44 | %11,4 |
| Net R:R ≥ 1,0 | 147 | −119,15 | 0,41 | −0,45 | %12,1 |
| Net R:R ≥ 1,5 | 121 | −97,36 | 0,42 | −0,47 | %10,2 |
| Retest 4 mum | 123 | −93,28 | 0,44 | −0,43 | %9,7 |
| Retest 12 mum | 147 | −112,96 | 0,43 | −0,43 | %11,5 |
| Hedef 1,0 × ATR1H | 96 | −74,17 | 0,45 | −0,47 | %8,5 |
| Hedef 2,0 × ATR1H | 145 | −123,34 | 0,40 | −0,46 | %12,5 |
| Kırılım hacmi 1,2× | 160 | −135,82 | 0,38 | −0,48 | %13,7 |
| Kırılım hacmi 2,0× | 100 | −88,47 | 0,38 | −0,49 | %9,0 |

Hiçbir varyant sıfıra yaklaşmıyor. Ana yapılandırma bu tabloya göre değiştirilmedi.

### Yardımcı test: Binance 15M, 2023-11-15 → 2025-10-31 (ana sonuç değildir)

MEXC 15M verisi yalnız ~360 gün geriye gidiyor. İki yıllık ek test için Binance'in resmî açık veri arşivi kullanıldı.

**Yanlılık kaynakları:**

- Farklı borsa.
- Evren, CMC'nin erişilebilen en eski listesi (14.11.2025). Bu liste o tarihte ilk 300'de olan, MEXC'de eşleşen ve
  Binance'te verisi bulunan 70 coinden oluşuyor ve bütün dönem boyunca sabit tutuldu.
- Bu liste geleceğe bakıyor, dolayısıyla sonuç yukarı yönde yanlı olmalıydı.
- Maliyetler MEXC'nin değerleri.

| Maliyet | İşlem | Net USDT | PF | Ort. R | Kazanan | En büyük düşüş |
| --- | --- | --- | --- | --- | --- | --- |
| Normal | 639 | −345,63 (−%34,6) | 0,56 | −0,31 | %22 | %34,6 |
| ×2 | 315 | −263,26 | 0,46 | −0,41 | %21 | %26,3 |

24 ayın 21'i negatif. Farklı piyasa koşullarında da (2024 yükselişi, 2025) sonuç aynı yönde.

## Önceki kodda bulunan hataların taşınmadığının doğrulanması

| İstekteki madde | Uygulama | Test |
| --- | --- | --- |
| Giriş sonrası kalan dakika aralığında stop kaçırılmasın | Paper: girişten sonra her döngüde 1 dk mumlar ve anlık alış fiyatı. Giriş dakikasının mumu, açılışı giriş fiyatı sayılarak denetlenir ve "sıra belirsiz" işaretlenir | `KrPaperTest.live monitoring…` |
| Stop zamanı gün başına yazılmasın | Çıkış, tespit edildiği mum aralığıyla (`exitWindowFrom/To`) kaydedilir. Paper'da 1 dk, backtestte 15M | `KrPaperTest` (aralık ≤ 60 sn) |
| Eksik veriyle "araştırma eşdeğeri" denmesin | Android'de backtest yok. Ana test yalnız MEXC 15M. Binance testi "yardımcı" ve "yanlı" diye etiketli | — |
| Başarısız aday kapasite tüketmesin | Kapasite yalnız başarılı girişte azalır | `KrBacktestTest.a rejected candidate…` |
| Yeniden başlatma çift işlem üretmesin | Kurulum kimliği işlendi olarak pozisyonla aynı atomik yazımda saklanır | `KrPaperTest.entry is taken live once…` |
| Haber, fiyat, CMC zamanları ayrı | `lastPriceDataAt`, `universe.list.cmcTimestamp/fetchedAt`, `termsAt`, `eventsUpdatedAt`. Haber kaynağı yok ve "kontrol edilmedi" diye gösteriliyor | Ayrıntılar ekranı |
| Arka plan kesintileri gizlenmesin | 3 dakikadan uzun izleme boşlukları kaydedilir. O aralıktaki çıkış "Canlı izlenmedi" diye etiketlenir. Kaçırılan retest kararı "geç" sayılıp işleme dönüştürülmez | `KrPaperTest.a monitoring gap…`, `…decision time missed…` |
| Paper sonuçları yeni ücret tablosuyla değişmesin | Giriş ve çıkış bacaklarının ücreti/spread'i pozisyonda saklanır | `KrPaperTest` (ücret değişse de çıkış ücreti %0,05) |
| Gelecek sızıntısı / kapanmamış mum / hiza | Seviye sağ taraf kapanmadan bilinmez. Yalnız kapanmış mumlar. 1H/4H UTC hizalı. Eksik grup mum üretmez. Son 48 saatte boşluk varsa giriş yok | `SetupTrackerTest` |
| Aynı mumda stop + hedef | Stop sayılır, işaretlenir. Ana testte 0 kez görüldü (yardımcıda 1) | `KrRulesTest` |

## Backtest ile paper arasındaki farklar

Aynı sınıflar kullanılıyor: `SetupTracker`, `KrFilters`, `KrPricing`, `KrSizing`, `KrExits`, `KrBook`.

| Konu | Backtest | Paper |
| --- | --- | --- |
| Dolum | Sonraki 15M açılışı × (1 + yarım spread + %0,05) | Karar anında emir defterinden alış VWAP'ı |
| Spread | Coin başına anlık görüntü (13.11.2026) | Canlı |
| Sert stop | 15M mum düşüğü (boşlukta açılış) | 1 dk mum + anlık alış fiyatı |
| Kapanış kuralları | Sonraki 15M açılışı | Kapanıştan sonraki ilk döngüde anlık alış |
| 4H yön | MEXC'nin 4H mumları (Haziran 2025'ten) | Son 300 adet 4H mum |
| 15M geçmişi | Bütün geçmiş | Son 500 mum (test: aynı plan) |
| ST etiketi / işlem durumu | Geçmişe uygulanmadı | Canlı denetleniyor |

## Eksikler ve veri sınırlamaları

- **60 kuralın metni yok.** Uyarlama istekteki başlıklara dayanıyor.
- **MEXC 15M yalnız ~1 yıl.** İki yıl şartı ancak Binance verisiyle (farklı borsa, yanlı evren) yardımcı testte
  karşılanabildi.
- **Hayatta kalma yanlılığı:** MEXC'nin bugünkü `exchangeInfo`'su kullanıldı. MEXC'den kalkmış pariteler backtestte yok.
- **Ücret ve spread:** Ücretler 13.11.2026 `exchangeInfo` değerleri. Çoğu paritede taker %0,08; %0,05 ve %0,2 olanlar
  da var. MEXC'nin ücret sayfası bu ortamdan açılamadı; resmî API değeri kullanıldı. Maker dolumu varsayılmadı. Geçmiş
  spread ve emir defteri verisi yok; spread bugünkü anlık görüntüden alındı.
- **15M içi sıra:** Backtestte 15M içindeki fiyat sırası bilinmiyor. Aynı mumda stop ve hedef olursa stop sayılıyor
  (ana testte bu durum hiç olmadı).
- **Makro takvim ve haber:** Doğrulanmış kaynak yok. Yalnız kullanıcının elle girdiği olay pencereleri ve MEXC ST
  etiketi var. Arayüz bunu "Kaynak yok — kontrol edilmedi" diye gösteriyor.
- **CMC anahtarı:** Uygulama kullanıcının girdiği CMC anahtarıyla çalışır (Android Keystore ile şifreli). Anahtar
  yoksa evren alınamaz ve yeni giriş yapılmaz.
- **Android arka plan:** Pil kısıtları servisi durdurabilir. Bu kesintiler kaydedilir ama önlenemez.
- **Telefonda kurulum test edilmedi.** APK derlendi ve Robolectric testleri geçti.
- **Canlı paper döngüsü** bu ortamda yalnız sahte borsayla test edildi. Canlı MEXC + CMC ile evren eşleşmesi çalıştırıldı
  (`cryptoscan universe`); tam canlı tarama döngüsü uzun süre çalıştırılmadı.

## Dosyalar

| Dosya | İçerik |
| --- | --- |
| [KR-ONKAYIT.md](KR-ONKAYIT.md) | Ön kayıt (sonuçlardan önce) |
| [KR-KURALLAR.md](KR-KURALLAR.md) | 60 kural uyarlama tablosu |
| [research/kr/kr-sonuc.txt](research/kr/kr-sonuc.txt) | Üç dönem, ×2 maliyet, huni, sınıf |
| [research/kr/kr-arastirma.txt](research/kr/kr-arastirma.txt) | Araştırma dönemi ve duyarlılık |
| [research/kr/kr-yardimci.txt](research/kr/kr-yardimci.txt) | Binance yardımcı testi |
| `research/kr/kr-*-islemler.csv`, `*-islemler-x2.csv` | Bütün işlemler (her dönem, normal ve ×2) |
| `research/kr/kr-*-gunluk.csv` | Günlük işlem sayısı ve net |
| [research/kr/kr-evren.txt](research/kr/kr-evren.txt), [kr-evren-canli.txt](research/kr/kr-evren-canli.txt) | Evren eşleşmesi, MEXC'de olmayanlar |
| [research/kr/kr-test-ciktilari.txt](research/kr/kr-test-ciktilari.txt) | Test çıktıları |
| `research/kr/indirme/` | Veri indirme betikleri (CMC, MEXC 15M/4H, Binance) |
| `screenshots/kr/` | Arayüz ekran görüntüleri (normal ve küçük ekran) |

**Yeniden üretim:**

```
cli/build/install/cryptoscan/bin/cryptoscan kr --phase final --data <veri> --out <klasör>
```

Aynı veri ve sürümle sonuç birebir aynı çıkar. Araştırma dönemi iki ayrı çalıştırmada aynı verdi: 138 işlem,
−111,97 USDT.
