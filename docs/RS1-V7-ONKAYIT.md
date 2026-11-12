# Momentum RS-1 V7: araştırma planı (ön kayıt, 12 Kasım 2026)

Bu belge, V7 yapılandırmalarının herhangi bir sonucuna bakılmadan önce commit edilir. Tarih pencereleri,
yapılandırmalar, seçim yöntemi ve deneme listesi sonradan değiştirilmez. Bütün çalıştırmalar ham çıktıda listelenir.

## Başlangıç noktası

- V6 kuralları (`Rs1Versions.V6`), raporu ve CSV'si değişmeden saklanır.
- Yeniden üretim: araştırma raporu, işlem CSV'si ve bağımsız test çıktısı byte düzeyinde aynı (commit `8686220`
  sonrasında).

## Karşılaştırılan dört yapılandırma

Bütün parametreler V6 ile aynıdır: evren, uygunluk şartları, sıralama, %0,5 risk, en fazla 2 pozisyon, coin başına %25,
toplam %50, 2,5 ATR koruyucu stop. Yalnız iki boyut değişir:

| Kod | Giriş | Çıkış |
| --- | --- | --- |
| **W0** (= V6, mevcut) | Haftalık (pazartesi) | Koruyucu stop + BTC rejimi |
| W1 | Haftalık | Koruyucu stop + BTC rejimi + iz süren stop |
| D0 | Her tamamlanmış günlük mumdan sonra | Koruyucu stop + BTC rejimi |
| D1 | Her gün | Koruyucu stop + BTC rejimi + iz süren stop |

- **İz süren stop:** Girişten beri en yüksek tamamlanmış günlük kapanış − 3 × ATR14. V5'teki değer; ayarlanmaz.
- **Eklenmeyenler:** EMA50, yeni gösterge, yeni skor ya da başka strateji.

## Veri

V6 ile aynı:

- MEXC 1D, 1.203 parite, 2020-01 → 2026-11-09.
- MEXC 4H, 471 parite, gün içi stop çözümü için.
- Parite bazında taker ücreti: 10 Kasım 2026 `exchangeInfo`.
- Orta maliyet senaryosu ve ×2 maliyet senaryosu.

**Bilinen sınırlamalar** (değişmedi):

- Bugünkü ücretler geçmişe uygulanıyor.
- Borsadan kaldırılmış coinler yok (hayatta kalma yanlılığı).
- Geçmiş spread ve emir defteri yok.

**2018–2019 verisi:** V6'nın bağımsız testinde bir kez kullanıldı. Bu çalışmada **seçimde kullanılmaz**. Dört
yapılandırma orada yalnız betimleyici olarak raporlanır.

**Bağımsızlık:** 2020–2026 daha önce incelendi. Bu çalışmanın bütün sonuçları **tarihsel sağlamlık incelemesidir;
bağımsız test değildir.** Gerçek ileri test, kurallar dondurulduktan sonra paper trading ile başlar.

## Kronolojik pencereler (genişleyen eğitim)

- **Eğitim:** Her değerlendirme penceresi için 2020-08-01'den pencere başına kadar (en az 24 ay).
- **Değerlendirme:** 6 ay.

| # | Eğitim | Değerlendirme |
| --- | --- | --- |
| 1 | 2020-08-01 → 2022-08-01 | 2022-08-01 → 2023-02-01 |
| 2 | 2020-08-01 → 2023-02-01 | 2023-02-01 → 2023-08-01 |
| 3 | 2020-08-01 → 2023-08-01 | 2023-08-01 → 2024-02-01 |
| 4 | 2020-08-01 → 2024-02-01 | 2024-02-01 → 2024-08-01 |
| 5 | 2020-08-01 → 2024-08-01 | 2024-08-01 → 2025-02-01 |
| 6 | 2020-08-01 → 2025-02-01 | 2025-02-01 → 2025-08-01 |
| 7 | 2020-08-01 → 2025-08-01 | 2025-08-01 → 2026-02-01 |
| 8 | 2020-08-01 → 2026-02-01 | 2026-02-01 → 2026-08-01 |
| 9 | 2020-08-01 → 2026-08-01 | 2026-08-01 → 2026-11-09 (kısa: yaklaşık 3 ay) |

**Pencere sınırları (önceden tanımlı):**

- Her eğitim ve değerlendirme çalıştırması 1.000 USDT ile **ayrı bir portföy** olarak başlar. Önceki pencereden pozisyon
  devredilmez.
- Pencere öncesi veri yalnız gösterge ısınması ve uygunluk (250 gün, 30 gün hacim) için kullanılır.
- Pencere sonunda açık kalan pozisyonlar kapatılmaz. Son kapanıştan, çıkış maliyeti düşülerek gerçekleşmemiş sonuç
  olarak değerlenir; işlem sayısına ve R istatistiklerine girmez, net getiriye girer.
- Değerlendirme penceresinin verisi seçim sırasında kullanılmaz: seçim yalnız eğitim çalıştırmasının ölçüleriyle
  yapılır.
- **Birleşik pencere dışı sonuç:** Pencere net getirilerinin toplamı. Her pencere 1.000 USDT ile başladığı için USDT
  toplamı da raporlanır. R istatistikleri bütün pencerelerin kapanmış işlemleri birleştirilerek hesaplanır.

## Ölçüler (her yapılandırma, her çalıştırma)

**Getiri ve risk:**

- Maliyet sonrası net getiri (%), gerçekleşmiş ve gerçekleşmemiş ayrı.
- En büyük düşüş (%; günlük değerleme).
- PF ve işlem başına beklenti (USDT).
- Ortalama R ve %90 bootstrap güven aralığı (10.000 yeniden örnekleme, tohum 42).

**Sermaye ve bağımlılık:**

- Piyasada kalma (%).
- Ortalama sermaye kullanımı: açık pozisyon piyasa değeri / özsermaye, günlük ortalama.
- En iyi coin çıkarıldığında beklenti ve en iyi 3 işlem çıkarıldığında net.

**Dayanıklılık:**

- İki kat maliyette net getiri.
- Pencereler arası tutarlılık: pozitif pencere sayısı ve W0'dan iyi olunan pencere sayısı.

## Seçim yöntemi (tek metriğe bağlı değil)

Bir pencerenin eğitim çalıştırmasında yapılandırma X şu iki şartı sağlıyorsa **"açık biçimde üstün"** sayılır:

1. En az 30 kapanmış işlem.
2. Diğer üç yapılandırmanın her birini şu 8 ölçünün **hepsinde** geçmemek üzere tutuyor ve en az birinde geçiyor
   (Pareto üstünlüğü):
   - Net getiri.
   - En büyük düşüş (düşük daha iyi).
   - PF.
   - Beklenti.
   - Güven aralığı alt sınırı.
   - ×2 maliyette net getiri.
   - En iyi coin olmadan beklenti.
   - En iyi 3 işlem olmadan net.

Böyle bir yapılandırma yoksa sonuç **"kazanan yok"** olur ve o pencerede mevcut V6 (W0) kullanılır.

## Nihai karar (V7'nin dondurulması)

V7 kuralları ancak bir yapılandırma X (W0 dışında) **hepsini** sağlarsa X olur:

- **(a)** Bütün geçmişte (2020-08-01 → 2026-11-09) yukarıdaki üstünlük ölçütünü sağlıyor.
- **(b)** Pencere dışı birleşik sonuçta, W0'a göre:
  - Toplam net getirisi düşük değil.
  - En kötü pencere düşüşü daha büyük değil.
  - Birleşik PF'si düşük değil.
- **(c)** Pencerelerin en az %60'ında (9 pencerenin en az 6'sında) net getirisi W0'ınkinden düşük değil.

Aksi halde **"kazanan yok"**: V7 kuralları V6 kurallarıyla aynı kalır ve yalnız uygulama düzeltmeleri teslim edilir.

**Kanıt sınıfı:** Pencere dışı birleşik işlemler için `Rs1Evidence` ölçütleriyle raporlanır. Bu sınıf "tarihsel
sağlamlık" olarak etiketlenir, bağımsız test sayılmaz.

## Deneme listesi (tam)

Her yapılandırma (4) için:

- 9 eğitim çalıştırması.
- 9 değerlendirme çalıştırması.
- 9 değerlendirme çalıştırması ×2 maliyetle.
- 1 tüm geçmiş çalıştırması ve 1 tüm geçmiş ×2 maliyet çalıştırması.
- 1 betimleyici 2018–2019 çalıştırması.

**Toplam: 4 × 30 = 120 çalıştırma.** Başka yapılandırma ve parametre denenmez.

## Gerçekleşme modeli eşitliği (uygulama düzeltmesi)

**Android backtest,** araştırma motoruyla aynı modeli kullanacak:

- 1D sinyal; sonraki günün açılışından dolum.
- 4H gün içi stop çözümü. Likidite eşiğini geçen paritelerin 4H verisi indirilir.
- Parite bazında ücret ve aynı maliyet senaryoları.

**Hızlı mod:** Gün içi veri indirilmeden yalnız günlük mumla çalışan mod ayrı bir **"yaklaşık"** mod olarak sunulur;
araştırma sonucuyla eşdeğer gösterilmez.

**Paper trading:** Değerlendirme anındaki gerçek emir defteri esas alınır.
