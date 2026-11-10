# V4: Geniş coin evreni ve iki aşamalı tarama (10 Kasım 2026)

**Yöntem:** [EVREN-V4-ONKAYIT.md](EVREN-V4-ONKAYIT.md) dosyasında testlerden önce commit edildi. Strateji olarak V3'teki
**S1 (BTC'ye göre göreceli güç, L=30)** kullanıldı ve kuralları değiştirilmedi:

- EMA50(1D).
- İlk 3'te giriş, ilk 6 dışında çıkış.
- 3 × ATR14(1D) stop.
- En fazla 3 pozisyon, işlem başına %0,5 risk.

**Ham çıktılar:**

- [research/v4-evren-karsilastirma.txt](research/v4-evren-karsilastirma.txt)
- [research/v4-evren-anlik.json](research/v4-evren-anlik.json): indirilen ve ayıklanan pariteler.

## Kısa sonuç

1. **Taramayı genişletmek kendiliğinden daha fazla kâr getirmiyor.** Bağımsız son testte (2025-01 → 2026-11, kademeli
   orta maliyet) sonuçlar şöyle:

   | Evren | Ort. R | Net |
   | --- | ---: | ---: |
   | Tüm uygun pariteler | +0,111 | +%11,4 |
   | Eski dar liste (28) | +0,211 | +%15,7 |
   | İlk 20 | −0,032 | — |
   | İlk 50 | −0,027 | — |
   | İlk 100 | +0,028 | — |

2. **Hiçbir evren ön kayıtlı karar kuralını geçmedi.** Hepsinde son testin kârı ZEC'e bağlı: ZEC çıkarılınca bütün
   evrenlerde son test ortalama R'si negatif.
3. **Geniş evrenin gerçek faydaları:**
   - Daha fazla çeşitlilik: eş zamanlı pozisyonların ortalama korelasyonu dar listede 0,48, geniş evrende 0,26.
   - Biraz daha düşük en büyük düşüş: tüm dönemde %15,1'den %13,7'ye.
   - Doğrulama döneminde daha iyi sonuç: +0,276R (t 2,52), dar listede +0,258R (t 1,88).

   Ancak bu farklar son testte kâra dönüşmedi.
4. **İki aşamalı tarama doğru çalışıyor.** Her gün yalnız ilk 20 adayı ayrıntılı inceleyen tarama, 6 yılda bütün
   evrenle **birebir aynı 613 işlemi** üretti.
5. **Karar:** Ön kayda göre S1 ileri testi **dar listeyle devam ediyor**. Geniş tarama uygulamada çalışıyor ve
   gösteriliyor. İsterseniz Ayarlar → Strateji ayarları → "S1 ileri test evreni" ile geniş evrene geçebilirsiniz; bu
   durumda ileri test sıfırdan başlar.

## 1. Dinamik coin keşfi (canlı)

**İlk anlık görüntü (10 Kasım 2026):**

| Aşama | Parite sayısı |
| --- | ---: |
| MEXC'deki USDT paritesi | 1.414 |
| Ürün türüne göre ayıklanan | 210 |
| İşlem kapalı, tek yönlü ya da MEXC risk (ST) etiketli | 108 |
| 24 saatlik hacmi 250 bin USDT'nin altında | 1.008 |
| Likidite kuralını geçen (≥ 210 gün geçmiş, 30 gün medyan hacim ≥ 1M USDT) | 31 |
| Trend koşulunu geçen | 2 |
| BTC'den güçlü (aday) | 2: XMR, INJ |

- **Ürün türüne göre ayıklanan 210 parite:** Tokenleştirilmiş hisse 164, stablecoin 18, hisse meme tokeni 18,
  emtia/metal 9, Pre-IPO 1.
- **Tarama süresi:** 19 saniye, 81 istek. Bunun 3'ü toplu istek: exchangeInfo, 24s ticker, book ticker.
- **Bugün likidite kuralını geçen yalnız 31 parite var.** MEXC'de 1.400'den fazla USDT paritesi bulunsa da günlük hacmi
  1M USDT'yi aşan parite sayısı 40'ın altında. Hacmi 1–20M arasında olan paritelerin medyan spread'i %0,22; 100 bin–1M
  arasındakilerde %0,49. "100–300+ coin taramak" teknik olarak yapılıyor, ama mevcut likidite kuralıyla işlem
  yapılabilir evren bugün bu kadar.

**Mimari:**

- **1. aşama:**
  - Önce 3 toplu istek atılır.
  - Ön elemeyi geçen her parite için günde bir kez 1D mumu çekilir. Mum önbellekte tutulur; aynı gün tekrar
    indirilmez (test edildi).
  - İstemci istekleri sıraya koyar ve 429/418 yanıtlarında `Retry-After` kadar bekler.
- **Yeni listeleme ve çıkarılma:** Bir önceki taramadaki parite listesiyle karşılaştırılır.
- **2. aşama:** En güçlü 20 aday ve açık pozisyonlar için şunlar hesaplanır:
  - Emir defterinde orta fiyatın %1 altına kadar derinlik.
  - ATR ve stop mesafesi.
  - 30 günlük volatilite.
  - EMA50 üstünde kalınan gün sayısı.
  - Yaklaşık pozisyon tutarı.
  - Üst sıradaki adaylarla 60 günlük korelasyon.
- **Kural ile uyarı ayrımı:** Uygunluk ve sıralama S1'in backtest kurallarıyla birebir aynı (test edildi). Spread,
  derinlik ve korelasyon geçmişte ölçülemediği için **uyarı** olarak gösteriliyor, işlem kuralı değil. Bu, canlı test
  ile backtest arasında kural farkı oluşmasın diye.

## 2. Tarihsel veri ve doğruluk

- **Veri kapsamı:** Bugün listede olan 1.204 paritenin (ürün ayıklaması sonrası) 1D geçmişi indirildi.
  - 148'inin geçmişi 210 günden kısa.
  - 585'i hiçbir zaman likidite eşiğine ulaşmamış.
  - Kalan **471 paritenin 4H geçmişi** indirildi. Diğerleri motorda zaten hiçbir zaman işlem göremez.
- **Listeleme tarihi:** Her paritenin ilk mumu esas alındı. İlk mumdan 210 gün geçmeden işlem yapılmaz.
- **Zamana bağlı işlem yapılabilir parite sayısı:**

  | Tarih | Tümü | Dar listeden |
  | --- | ---: | ---: |
  | 2021-01 | 16 | 9 |
  | 2022-01 | 33 | 12 |
  | 2023-01 | 16 | 14 |
  | 2024-01 | 41 | 20 |
  | 2025-01 | 121 | 28 |
  | 2026-01 | 68 | 27 |
  | 2026-11 | 31 | 17 |

  MEXC hacmi 2025'te çok yüksekmiş. Bu yüzden 20/50/100 sınırları en çok son test döneminde etkili:
  - İlk 20 sınırı 2.293 günün 1.810'unda etkili.
  - İlk 50 sınırı 618 günde etkili.
  - İlk 100 sınırı 350 günde etkili.

  Doğrulama döneminde (2023–2024) "ilk 50", "ilk 100" ve "tümü" neredeyse aynı evrendir.
- **Geleceğe bakma önlemi:** Bugünkü ST etiketi ve bugünkü "işlem kapalı" durumu geçmişte ayıklama için
  **kullanılmadı**. Evrenin büyüklük sırası her gün o güne kadarki 30 günlük hacimle belirlendi.
- **Hayatta kalma yanlılığı:** Borsadan kaldırılmış pariteler indirilemiyor. Küçük coinler daha sık listeden çıktığı için
  bu yanlılık geniş evrende dar listeden **daha büyük** olabilir. Sonuçlar bu yüzden iyimserdir.
- **Emir defteri ve spread geçmişi yok.** Spread, hacme bağlı kademeli senaryolarla modellendi:
  - Hacmi 20M USDT'nin altındaki paritelerde orta senaryo yarım spread %0,109, kötümser senaryo %0,284.
  - Kademeler 10 Kasım ölçümünden alındı.

## 3. Karşılaştırma (S1 kuralları aynı)

### Kademeli orta maliyet (karar senaryosu)

| Evren | Doğrulama 2023–24 | Son test 2025-01 → 2026-11 | Tüm dönem 2020-08 → 2026-11 |
| --- | --- | --- | --- |
| Dar liste (28) | 156 işlem · +0,258R · PF 2,15 · +%21,2 · düşüş %6,4 | 156 · +0,211R · PF 1,73 · +%15,7 · düşüş %15,1 | 500 · +0,176R (t 1,85) · +%50,5 · düşüş %15,1 |
| İlk 20 | 172 · +0,298R · PF 2,27 · +%27,8 · %5,0 | 178 · **−0,032R** · PF 0,86 · −%3,0 · %7,6 | 573 · +0,090R (t 2,01) · +%27,5 · %7,7 |
| İlk 50 | 180 · +0,257R · PF 2,04 · +%24,8 · %5,5 | 198 · **−0,027R** · PF 0,88 · −%2,9 · %8,6 | 599 · +0,082R · +%26,1 · %10,6 |
| İlk 100 | 180 · +0,281R · PF 2,21 · +%27,5 · %5,5 | 213 · +0,028R · PF 1,10 · +%2,7 · %9,9 | 615 · +0,105R (t 2,26) · +%36,0 · %9,9 |
| Tümü | 181 · +0,276R · PF 2,19 · +%27,1 · %5,5 | 211 · +0,111R · PF 1,36 · +%11,4 · %13,7 | 613 · +0,133R (t 2,27) · +%47,3 · %13,7 |

**Kıyaslama:**

| Dönem | BTC al-tut | USDT'de bekleme |
| --- | ---: | ---: |
| Doğrulama | +%463,7 | %0 |
| Son test | −%19,8 | %0 |
| Tüm dönem | +%562,3 | %0 |

### Maliyetler

Son test toplam ücret ile spread+kayma (USDT):

| Evren | Ücret | Spread + kayma |
| --- | ---: | ---: |
| Dar liste | 5,6 | 16,6 |
| İlk 20 | 4,7 | 13,6 |
| İlk 50 | 3,4 | 10,4 |
| İlk 100 | 3,5 | 11,0 |
| Tümü | 3,6 | 11,3 |

Geniş evrende yüzde olarak maliyet daha yüksek. Ancak risk bazlı boyutlandırma, oynak küçük coinlerde pozisyonu
küçülttüğü için USDT olarak toplam maliyet daha düşük. Kademeli kötümser senaryoda bile sıralama değişmiyor; ayrıntılar
ham çıktıda.

### Neden daha fazla coin daha fazla kâr getirmedi?

- **İşlem sayısı sınırlı:** En fazla 3 pozisyon kuralı yüzünden işlem sayısı evrenle birlikte büyümüyor. Tüm dönemde
  dar liste 500, tüm evren 613 işlem yaptı (+%23). Daha geniş evren daha fazla işlem değil, **hangi coinlerin**
  tutulduğunu değiştiriyor.
- **Piyasada kalma süresi artıyor:** Geniş evrende uygun bir coin hemen her zaman bulunuyor. Piyasada kalma oranı %83–88'den
  %95–100'e çıkıyor. Düşen piyasada (son test) bu, daha az nakitte beklemek demek.
- **Sonuç tek pariteye bağlı:** Son testte bütün evrenlerde en kârlı parite ZEC.
  - ZEC çıkarılınca son test ortalama R'si her evrende negatif:
    - Dar liste: −0,067.
    - İlk 20: −0,064.
    - İlk 50: −0,081.
    - İlk 100: −0,037.
    - Tümü: −0,027.
  - ZEC'in katkısı evrene göre büyük farklılık gösteriyor: Tümü'nde +150,6, ilk 20'de +25,5 USDT. Aynı kurallarla
    hangi coinlerin aynı anda ilk 3'te olduğu, ZEC yükselişinin yakalanıp yakalanmadığını belirliyor. Bu da sonucun
    kırılgan olduğunu gösteriyor.
- **Dar liste sonradan seçilmiş bir liste:** 28 parite bugün bilinen "başarılı" coinlerden oluşuyor. Dar listenin son
  testte daha iyi görünmesinin bir kısmı bu seçimden geliyor olabilir.

### Çeşitlilik ve korelasyon

| Evren (son test) | İşlem gören parite | Eş zamanlı pozisyon korelasyonu (ort.) | ≥ 0,8 olan çift |
| --- | ---: | ---: | ---: |
| Dar liste | 26 | 0,47 | %6 |
| Tümü | 116 | 0,18 | %1 |

**Sektör dağılımı** (tüm dönem, işlem sayısı; MEXC etiketleri, bir coin birden çok etikette olabilir):

| Evren | Main | Layer 1/2 | Innovation | Meme | DeFi | Privacy | YZ |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dar liste | 496 | 343 | 4 | 33 | 21 | 23 | — |
| Tümü | 374 | 198 | 225 | 84 | 39 | 38 | 35 |

Meme coinler ve yeni listelenen tokenler ayrı bir ayrıcalık almadı. Aynı likidite (en az 210 gün geçmiş, medyan hacim en
az 1M), trend ve risk kurallarına tabiler. Çeşitlilik için hiçbir coin zorla eklenmedi.

## 4. İki aşamalı tarama doğrulaması

- Tüm dönemde, her günün S1 sıralamasında ilk 20'ye en az bir kez giren paritelerden (471 paritenin 349'u) oluşan alt
  küme, bütün evrenle aynı motorla çalıştırıldı.
- **Sonuç:** 613 işlem, **birebir aynı**. Net getiri her ikisinde +%47,34.
- Canlı ileri test bu yüzden yalnız 2. aşamaya girmiş paritelerin 4H verisini indirerek bütün evrenle aynı kararı
  verir. İzlenen parite listesi takip boyunca yalnız büyür.

## 5. Uygulamadaki değişiklikler (sürüm 2.5.0-paper)

- **Tarama ekranı, "Geniş evren taraması (S1)" kartı:**
  - Taranan toplam parite.
  - Ürün dışı ve işlem kapalı pariteler.
  - Likidite ve trend filtresini geçenler.
  - BTC'den güçlü olanlar ve işleme uygun adaylar.
  - En güçlü 20: RS, 7 ve 30 günlük momentum, hacim, stop mesafesi, spread, sektör etiketi, uyarılar.
  - Elenme nedenleri ve sayıları, güçlü ama elenenler.
  - Yeni listelenen ve listeden çıkan pariteler.
  - Yazılım/veri hatası, "kural kararı"ndan ayrı gösterilir.
- **Bot servisi:** Geniş taramayı 30 dakikada bir yeniler. 1D verisi günde bir kez indirilir.
- **Ayarlar:** "S1 ileri test evreni" seçeneği. Varsayılan dar liste. Değiştirince ileri test yeniden başlar.
- **İleri test kartı:** İzlenen evreni gösterir.
- **Değişmeyenler:**
  - S1 kuralları.
  - Risk limitleri.
  - B'nin aktif kâğıt profili.
  - Gerçek emir gönderimi yok; API anahtarı yok.
- **Yeni testler:**
  - `WideScannerTest`: Ürün ayıklama, kural sırası, S1 backtest sıralamasıyla eşitlik, önbellek, listeleme/çıkarılma
    tespiti, veri hatası.
  - `V3EngineTest`: İlk N evreni, kademeli maliyet.
  - `ForwardTrackerTest`: İzlenen liste yalnız büyür; evren değişince ileri test yeniden başlar.

## 6. Sınırlar

- Hayatta kalma yanlılığı (yukarıda). Geniş evren için daha ciddi.
- Geçmiş spread ve emir defteri yok. Kademeler tek günlük ölçümden alındı.
- Hacim ölçüsü MEXC'nin kendi hacmi. Wash trading içeren hacimler likiditeyi olduğundan iyi gösterebilir.
- Son testteki bütün sonuçlar tek bir paritenin (ZEC) hareketine bağlı. İstatistiksel güç düşük (t < 1).
- Hiçbir sonuç gelecekteki kâr garantisi değildir.
