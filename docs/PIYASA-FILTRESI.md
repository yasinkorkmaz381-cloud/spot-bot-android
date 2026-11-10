# BTC / piyasa filtresi incelemesi (10 Kasım 2026)

Panelde görülen uyarı: **"Yeni işlem şu an engelli: BTC / piyasa filtresi alıma izin vermiyor."**

## Uyarının kaynağı

1. `Scanner.marketState` her 15 dakikalık taramada BTC'ye göre `allowsLongs` değerini hesaplar.
2. `TradingEngine.refreshRisk` bu sonucu `RiskStatus.of`'a verir.
3. `RiskStatus.of` alım yasaksa paneldeki metni üretir. Öncesinde başka bir engel yoksa bu metin görünür: duraklatma,
   günlük limit, işlem sınırı ya da toplam risk sınırı.
4. Aynı sonuç zorunlu kural **Z10** olarak her aday işlemde de denetlenir.

## Filtrenin şartları

| Kod | Şart | Tür | Hangi veri |
| --- | --- | --- | --- |
| B1 | BTC 4H, 1H ve 15M'de en az 60 kapanmış mum var | Veri | Mum sayısı |
| B2 | BTC'nin son 4H ve 1H mumu beklenen kapanış saatinde **(yeni)** | Veri | Son mum zamanı |
| B3 | BTC 4H rejimi düşüş trendi (TREND_DOWN) değil | Piyasa | 4H göstergeleri ve yapı |
| B4 | BTC son 4 saatte %3'ten fazla düşmedi | Piyasa | Son 5 kapanmış 1H mum |

Şartlardan biri geçmezse yeni alım yapılmaz.

**4H düşüş trendi (B3).** Aşağıdakilerin hepsi birlikte sağlanırsa rejim düşüş trendi sayılır:

- Kapanış EMA55'in altında.
- EMA21 EMA55'in altında.
- SuperTrend (10, 3) aşağı yönde.
- ADX(14) en az 20.
- 4H yapısı yükselişte (HH/HL) değil.

**1H'nin rolü.** 1H trendi filtrede kullanılmaz; 1H mumları yalnız son 4 saatteki sert düşüşü (B4) ölçmek için kullanılır.
Trend kararı 4H'ye göre verilir.

## Ölçülen durum (10.11.2026 00:30 UTC taraması, canlı MEXC)

**BTC 4H** (son kapanmış mum 10.11 00:00 UTC):

| Gösterge | Değer |
| --- | --- |
| Kapanış | 75.346,36 |
| EMA21 | 75.633,57 |
| EMA55 | 76.815,21 |
| SuperTrend | Aşağı |
| ADX | 40,9 |
| +DI / −DI | 11,6 / 28,1 |
| Yapı | LH/LL (DOWN) |
| Rejim | **TREND_DOWN** |

**BTC 1H** (son kapanmış mum 10.11 00:00 UTC):

- Kapanış 75.346,36, EMA21 74.968,29, EMA55 75.290,71.
- SuperTrend aşağı, ADX 16,8, rejim RANGE.
- Son 4 saat: **+%0,62**; −%3 eşiğinin uzağında.

Şartların sonucu:

| Şart | Sonuç |
| --- | --- |
| B1 BTC verisi yeterli | Geçti |
| B2 BTC verisi güncel | Geçti |
| **B3 BTC 4H düşüş trendinde değil** | **Geçmedi** |
| B4 Son 4 saatte sert düşüş yok | Geçti |

**Alımı engelleyen şart: B3, BTC'nin 4H düşüş trendi.** Bu bir veri sorunu değil, gerçek piyasa durumu.

Değerler motor kodundan bağımsız bir Python betiğiyle ham MEXC mumlarından yeniden hesaplandı. Motorla birebir aynı
çıktı:

- EMA SMA ile başlatıldı.
- ADX ve ATR Wilder yöntemiyle hesaplandı.
- SuperTrend TradingView mantığıyla hesaplandı.
- Swing gücü 3, 150 mumluk pencere kullanıldı.

## Bulunan sorunlar ve düzeltmeler

Filtrenin hesabı doğru çalışıyordu. Kurallar gevşetilmedi ve filtre kapatılmadı.

1. **Panel nedeni söylemiyordu.** Teknik metin yerine engelin türü ("Piyasa riski" kırmızı, "Veri sorunu" sarı) ve
   düz bir açıklama gösteriliyor. Örnek:

   > Piyasa riski: BTC 4 saatlik grafikte düşüş trendinde (fiyat ortalamaların altında, trend gücü yüksek). Bu durumda
   > yeni alım yapılmaz; açık pozisyonların takibi sürer.

2. **Veri eksikliği piyasa riskinden ayrılmıyordu.** "BTC verisi yetersiz" de aynı mesajı veriyordu. Artık her şart
   veri ya da piyasa olarak etiketli. Veri sorununda açıklama "bu bir piyasa sinyali değildir" diyor.

3. **BTC verisinin güncelliği denetlenmiyordu (gerçek eksik).**
   - Sorun: 15M mum güncellemesi ağ hatasıyla başarısız olursa hata yalnız kaydediliyordu. Filtre eski BTC mumlarıyla
     karar vermeye devam ediyordu ve bu, BTC düşerken bile alıma izin verebilirdi.
   - Düzeltme: B2 şartı eklendi. Son 4H ve 1H mumu beklenen saatte değilse alım yapılmaz ve bu "veri sorunu" olarak
     gösterilir. Bu kural yalnız sıkılaştırır.
   - Etki: Geçmiş testlerde sonuç değişmedi. 90 gün: 44 işlem, −%2,70. 1 yıl: 194 işlem, −%20,99. Önceki çalışmayla aynı.

4. **Ayrıntılar görünmüyordu.** Panel → "Risk ve sistem ayrıntıları" ve Tarama → "Piyasa filtresi ayrıntıları"
   şunları gösteriyor:
   - Her şartın sonucu.
   - BTC 4H ve 1H gösterge değerleri.
   - Son mum zamanları.

   Komut satırındaki `cryptoscan scan` aynı ayrıntıları yazdırıyor.

## Testler

`MarketFilterTest` (6 test) şunları doğruluyor:

- Düşen BTC'de engel "piyasa riski" ve B3 olarak raporlanıyor; ayrıntıda gösterge değerleri var.
- Yükselen ve güncel BTC'de dört şart da geçiyor.
- Veri yokken engel "veri sorunu" (B1).
- **Eski ama yükselişte görünen BTC verisiyle alım artık engelleniyor (B2).** Önceden bu durumda izin çıkıyordu.
- Son 4 saatte sert düşüş B4 ile engelleniyor.
- Panel metni açıklamayı ve türü gösteriyor.

Arayüz tarafında:

- Dokunma testi: panelde "Yeni işlem yok · Piyasa riski" ve açıklama görünüyor, eski teknik metin görünmüyor.
- Ekran görüntüleri: `1b-panel-piyasa-filtresi.png`, `2b-piyasa-filtresi.png`; küçük ekran sürümleri de var.

Toplam: çekirdek 116 test geçti (2 canlı test atlandı), uygulama 45 test geçti, lint temiz.
