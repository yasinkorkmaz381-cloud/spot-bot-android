# Momentum RS-1 V6: araştırma protokolü (ön kayıt, 12 Kasım 2026)

Bu belge, aşağıdaki varyantlar çalıştırılmadan önce commit edilir. Tarihler, deneme bütçesi, karar kuralları ve kabul
eşikleri sonradan değiştirilmez. Bütün denemeler ham çıktıda listelenir; gizli deneme yapılmaz.

## Neden yeni bir bağımsız dönem?

- 2020-08 → 2026-11 arasının tamamı önceki sürümlerde (V3, V4, V5) incelendi.
- RS-1'in 2025-01 → 2026-11 sonucu V5'te görüldü: −%3,26, ortalama −0,145R.
- Bu dönemde ayar yapıp sonra ona "bağımsız test" demek, kullanıcının yasakladığı şeydir. Bu yüzden:

| Rol | Dönem | Kullanım |
| --- | --- | --- |
| Araştırma | 2020-08-01 → 2023-01-01 | Seçim |
| Doğrulama | 2023-01-01 → 2025-01-01 | Seçim |
| Görülmüş dönem | 2025-01-01 → 2026-11-09 | **Seçimde kullanılmaz**, yalnız raporlanır ("bağımsız değil" etiketiyle) |
| **Bağımsız test** | **2018-01-01 → 2020-01-01** | RS-1 bu dönemde hiç çalıştırılmadı. Kurallar dondurulduktan sonra **bir kez** değerlendirilir. |
| İleri test | Bugünden sonra | Paper trading (aşağıda plan) |

**Ön kayıttan önce yapılan tek yoklama** (veri kapsamı; performansa bakılmadı):

- 2018–2019'da MEXC'de bugün listede olan pariteler arasından yalnız **49'unun** günlük verisi var.
- 250 gün geçmiş ve 30 gün medyan hacim ≥ 5M şartını geçen parite sayısı:
  - 2018 ortasında 0.
  - 2019 başında 4.
  - 2019 sonunda 12.
- Bağımsız test fiilen yaklaşık 2018-09 → 2019-12 dönemini ve çok dar bir evreni kapsar. Hayatta kalma yanlılığı bu
  dönemde daha büyüktür, çünkü 2018–2019'da listelenip sonra kaldırılan coinler yoktur.
- Sonucun **"yetersiz kanıt"** çıkması mümkündür ve kabul edilir.

## Veri

**Kaynaklar:**

| Dönem | 1D | 4H |
| --- | --- | --- |
| 2020-01 → 2026-11 | 1.203 parite | 471 parite (gün içi stop çözümü için) |
| 2018–2019 | 49 parite (veri olan) | Aynı 49 paritenin 4H verisi, test öncesi indirilir |

Bütün veriler MEXC'den; başka borsa verisi kullanılmaz.

**Kontroller:**

- Yinelenen ve sırasız mumlar ayıklanır.
- Kapanmamış mumlar alınmaz.
- Son 60 günde eksik gün olan parite işlem görmez.
- Kontrol sayıları raporlanır.

**Hacim:** MEXC kline'ının USDT cinsinden işlem hacmi alanı kullanılır (doğrulandı: alan 7).

**Ücret:** MEXC `exchangeInfo` `takerCommission` alanı, 10 Kasım 2026:

- BTC, ETH, SOL ve 179 parite: %0,05.
- 983 parite: %0,08.
- 248 parite: %0,2.

Bu ücretlerin geçmişte de geçerli olduğu bir **varsayımdır**; özellikle 2018–2019 için doğrulanamaz. Belirsizlik ×2
maliyet senaryosuyla sınanır.

## Kesin olarak düzeltilen gerçekçilik hataları (seçim değil)

1. **Parite bazında ücret.** V5'te her pariteye %0,05 uygulanıyordu.
2. **Gün içi stop.** 4H verisi olan günlerde stop olayı 4H mumlarıyla çözülür. Stop gün içinde boşlukla aşılırsa
   (mum açılışı ≤ stop) çıkış o açılış fiyatından yapılır. V5 günlük mumla stop fiyatından dolduruyordu.
3. **Gösterge ısınması.** EMA100 ilk 100 günün ortalamasıyla başlar. 150. günde bu başlangıç değerinin ağırlığı hâlâ
   yaklaşık %37'dir; 250. günde %5'in altına iner. Asgari geçmiş 150 günden **250 güne** çıkarılır.
4. **Test sonu değerlemesi.** Açık pozisyonlar zorla kapatılmaz. Gerçekleşmiş ve gerçekleşmemiş sonuç ayrı raporlanır;
   gerçekleşmemiş sonuçtan çıkış maliyeti düşülür.
5. **Paper trading dolumu** (backtest'i etkilemez):
   - Giriş, değerlendirme anındaki emir defterinin pozisyon büyüklüğüne göre ortalama alış fiyatından (VWAP) yapılır.
   - Girişten önceki fiyatlar stopu tetiklemez.
   - MEXC miktar adımı ve asgari emir tutarı uygulanır.

## Taban çizgileri

- **B0, V5'in birebir yeniden üretimi:** Düz %0,05 ücret, günlük stop, 150 gün geçmiş. V5 raporundaki sayılarla aynı
  çıkmalıdır; çıkmazsa hata olarak raporlanır.
- **B1, düzeltilmiş taban:** Yukarıdaki 1–4 uygulanmış, kurallar V5 ile aynı. Bütün karşılaştırmalar B1'e göredir.

## Deneme bütçesi

En fazla 24 çalıştırma. Hepsi araştırma ve doğrulama dönemlerinde yapılır; görülmüş dönem yalnız raporlanır.

### Adım 1: giriş sıklığı (2 çalıştırma)

| Varyant | Tanım |
| --- | --- |
| A | Haftalık değerlendirme (B1) |
| B | Her tamamlanmış günlük mumdan sonra değerlendirme |

Diğer bütün kurallar sabittir; uygunluk kaybı çıkışı yine haftalıktır.

**Karar:** B, **hem araştırmada hem doğrulamada** şu iki şartı sağlarsa seçilir: ortalama R'si A'nınkinden düşük değil
ve PF'si A'nınkinden düşük değil. Aksi halde A seçilir. İşlem sayısı karar ölçütü değildir.

### Adım 2: çıkış bileşenleri (4 + 1 çalıştırma)

Koruyucu stop her zaman vardır. Adım 1 sonucuna göre, her seferinde bir bileşen kaldırılır:

- İz süren stop.
- İki gün EMA50 altında kapanış çıkışı.
- Haftalık uygunluk kaybı çıkışı.
- BTC rejimi bozulunca çıkış.

**Kaldırma kuralı:** Bileşen kaldırıldığında **hem araştırmada hem doğrulamada** ortalama R düşmüyor ve PF düşmüyorsa
bileşen kaldırılır (daha basit model en az aynı iyilikte).

**Birleşik kontrol (1 çalıştırma):** Kaldırılacak bileşenlerin hepsi birlikte kaldırılır. Sonuç, adım 1
yapılandırmasından iki dönemin herhangi birinde ortalama R ya da PF olarak kötüyse hiçbir bileşen kaldırılmaz.

**Ek betimleme (seçim değil):** Uygunluk kaybıyla çıkılan coinlerin çıkıştan sonraki 30 günlük getirisi raporlanır.
Bu, "gereksiz satış" olup olmadığını gösterir.

### Adım 3: giriş filtreleri (6 + 1 çalıştırma)

Adım 2 sonucuna göre, her seferinde bir filtre kaldırılır:

- BTC giriş filtresi.
- EMA100 trend şartı.
- 7 günlük getiri > 0.
- 30 günlük getiri > 0.
- BTC'ye göre ≥ 3 puan.
- Son gün aralığı ≤ 2,5 ATR.

Hacim, spread ve derinlik şartları güvenlik şartıdır ve sınanmaz.

**Kaldırma kuralı:** Hem araştırmada hem doğrulamada ortalama R düşmüyor, PF düşmüyor ve en büyük düşüş 2 puandan fazla
artmıyorsa filtre kaldırılır. Birleşik kontrol adım 2'deki gibidir (1 çalıştırma).

### Adım 4: duyarlılık (10 çalıştırma, yalnız raporlanır, seçim yapılmaz)

Nihai yapılandırmada, her seferinde tek parametre komşu değerlere alınır:

| Parametre | Komşu değerler |
| --- | --- |
| EMA100 | 80 / 120 |
| BTC'ye göre puan | 2 / 4 |
| Başlangıç stopu | 2 / 3 ATR |
| İz süren stop | 2,5 / 3,5 ATR |
| Hacim eşiği | 3M / 8M |

Sonuç komşu değerlerde işaret değiştiriyorsa ya da PF yarıdan fazla düşüyorsa "kırılgan" olarak raporlanır.

### Dondurma

Adım 3'ten sonra yapılandırma `Rs1Config` varsayılanlarına yazılır ve commit edilir. Bağımsız test bundan sonra **bir
kez** çalıştırılır.

## Kabul eşikleri (bağımsız test; orta maliyet ve parite bazında ücret)

Bütün şartlar sağlanırsa sınıflandırma **"Kanıt var"** olur:

1. En az 30 kapanmış işlem.
2. Maliyet sonrası işlem başına beklenen değer > 0.
3. Profit factor ≥ 1,20.
4. En büyük portföy düşüşü ≤ %10.
5. En iyi coin çıkarılınca beklenen değer > 0 **ve** en iyi 3 işlem çıkarılınca net sonuç > 0.
6. ×2 maliyet senaryosunda beklenen değer > 0.
7. Ortalama R için %90 bootstrap güven aralığının alt sınırı > 0 (10.000 yeniden örnekleme, sabit tohum 42).

Diğer sınıflar:

- **"Başarısız":** En az 30 işlem varken beklenen değer ≤ 0 ya da PF < 1,0.
- **"Yetersiz kanıt":** Diğer bütün durumlar; 30'dan az işlem dahil.

**Kıyaslama:** Aynı dönem ve sermayeyle USDT'de bekleme, BTC al-tut ve V5 RS-1 (B0). Getiri; düşüş ve piyasada
kalınan süreyle birlikte raporlanır.

## Paper trading ileri testi

Nihai yapılandırma paper trading'de değişmeden çalışır. Değerlendirme 100 kapanmış işlemde (en az 12 ay) aynı kabul
eşikleriyle yapılır. Erken durdurma şartları:

- Düşüş %10'u aşarsa.
- 50 işlemden sonra beklenen değer ≤ 0 ise.
