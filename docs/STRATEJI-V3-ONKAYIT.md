# Strateji araştırması V3: ön kayıt (11 Kasım 2026, sonuçlar görülmeden önce yazıldı)

Bu belge, testler çalıştırılmadan önce commit edilir. Stratejiler, parametre seçenekleri, dönemler, maliyet
senaryoları ve başarı ölçütleri burada sabitlenir. Sonuçlar görüldükten sonra değiştirilmez. Değişiklik gerekirse
ayrı ve gerekçeli bir commit olarak eklenir; ilk sonuçlar da raporda kalır.

## Veri

- Kaynak: MEXC spot, 4H mumlar, 2020-01-01 → 2026-11.
- 1D mumlar 4H'den UTC gün olarak üretilir; yalnız 6 mumu tamamlanmış günler kullanılır.
- Evren: 28 parite (BTC, ETH, SOL, XRP, ADA, DOGE, LINK, AVAX, DOT, LTC, BCH, TRX, BNB, ATOM, NEAR, UNI, FIL, ETC,
  XLM, AAVE, ARB, OP, APT, SUI, INJ, ZEC, ENA, HBAR).
- **Bilinen yanlılık:** Borsadan kaldırılmış pariteler (LUNA, FTT, MATIC gibi) indirilemez. Bu nedenle evren
  hayatta kalma yanlılığı taşır ve sonuçlar bir miktar iyimser olabilir.

**Geçmişte işlem yapılabilirlik.** Bir parite t anında ancak şu iki koşulla işlem görebilir:

- (a) En az 210 tamamlanmış günlük mumu var.
- (b) Son 30 günün medyan günlük USDT hacmi en az 1.000.000 (canlı taramayla aynı eşik).

Ayrıca bir emir, mumun USDT hacminin en fazla %10'u kadar dolabilir; fazlası alınmaz.

## Maliyet senaryoları (geçmiş spread verisi yok)

Her işlemde iki yön için taker ücreti %0,05 ve kayma %0,05 uygulanır. Yarım spread üç senaryoyla test edilir:

| Senaryo | Yarım spread | Yön başına toplam maliyet |
| --- | --- | --- |
| İyimser | %0,025 | %0,125 |
| **Orta (karar senaryosu)** | %0,075 | %0,175 |
| Kötümser | %0,15 | %0,25 |

Bir günün ölçülen spread'i geçmişin gerçeği olarak kullanılmaz.

## Ortak işlem kuralları

- Yalnız alım (spot), kaldıraç yok, açığa satış yok.
- Sinyal 4H kapanışında oluşur; giriş sonraki 4H mumunun açılışından yapılır.
- Stop mum içinde kontrol edilir. Açılış stopun altındaysa açılıştan çıkılır. Aynı mumda stop ve hedef varsa stop
  önce sayılır (ihtiyatlı).
- Strateji çıkış sinyalleri kapanışta oluşur; çıkış sonraki açılışta yapılır.
- Her strateji ayrı bir 1.000 USDT portföyde çalışır. Risk kuralları:
  - İşlem başına özsermayenin %0,5'i risk.
  - En fazla 3 açık pozisyon.
  - Pozisyon başına en fazla %30 özsermaye.
  - Günlük %1,5 kayıpta o gün yeni giriş yok.
  - Nakit yetmezse giriş yok.
- **BTC rejimi** (yalnız raporlama için): Günlük kapanış SMA200 üstünde ve SMA200 20 gündür yükseliyorsa *yükseliş*;
  altında ve düşüyorsa *düşüş*; diğer durumlar *yatay*.

## Stratejiler

Her stratejinin en fazla üç önceden belirlenmiş parametre seçeneği var.

### S1: BTC'ye göre göreceli güç ve trend takibi

- **Ne zaman:** Günde bir kez, günlük kapanışta.
- **Uygun parite:**
  - İşlem yapılabilir.
  - Kapanış EMA50(1D) üstünde.
  - EMA50(1D) 10 gün önceki değerinden yüksek.
  - Göreceli güç RS = (parite L günlük getirisi − BTC L günlük getirisi) > 0. BTC'nin kendisi de aday olabilir; onun
    RS'i 0 sayılır.
- **Giriş:** RS'e göre ilk 3 parite içindeyse ve boş yer varsa.
- **Stop:** Giriş − 3 × ATR14(1D). Günlük iz süren stop: girişten beri en yüksek kapanış − 3 × ATR14(1D); yalnız
  yukarı taşınır.
- **Çıkış:** İlk 6 dışına düşerse ya da kapanış EMA50(1D) altına inerse.
- **Seçenekler:** L ∈ {30, 60, 90} gün.

### S2: 1D ve 4H momentum

- **Günlük koşul:** ROC(N) > 0 ve ROC(N/2) > 0 (iki ufuk aynı yönde).
- **4H tetik:** MACD(12,26,9) histogramı ≤ 0'dan > 0'a geçer.
- **Stop:** Giriş − 2,5 × ATR14(4H). İz süren stop: en yüksek 4H kapanış − 3 × ATR14(4H).
- **Çıkış:** Günlük kapanışta ROC(N) ≤ 0 olursa.
- **Seçenekler:** N ∈ {20, 40, 60} gün.

### S3: Volatilite daralması ve teyitli kırılım (4H)

- **Daralma:** Kırılımdan önceki mumda Bollinger(20, 2) genişliği / orta bant, son 180 mumun en düşük %P'lik
  diliminde.
- **Kırılım mumu:** Kapanış önceki 20 mumun en yükseğinin (seviye) üstünde ve hacim ≥ 1,5 × SMA20(hacim).
- **Teyit:** Sonraki mum da seviyenin üstünde kapanır. Giriş, teyit mumundan sonraki açılışta.
- **Stop:** Kırılımdan önceki 20 mumun en düşüğü. Giriş − stop > 3 × ATR14(4H) ise işlem yapılmaz.
- **Hedef:** Seviye + aralık yüksekliği (ölçülen hareket).
- **Süre sınırı:** 60 mum (10 gün).
- **Seçenekler:** P ∈ {10, 20, 30}.

### S4: Güçlü trendde geri çekilme ve dönüş

- **Günlük koşul:** ADX14(1D) ≥ 25, +DI > −DI, kapanış > EMA20(1D) > EMA50(1D).
- **4H geri çekilme:** Son 6 mumdan birinde RSI14(4H) ≤ T.
- **4H dönüş:** Mum kapanışı önceki mumun en yükseğinin üstünde ve mum yükseliş mumu.
- **Stop:** Son 12 mumun en düşüğü − 0,25 × ATR14(4H).
- **Hedef:** 2R.
- **Süre sınırı:** 30 mum.
- **Çıkış:** Günlük kapanış EMA50(1D) altına inerse.
- **Seçenekler:** T ∈ {30, 35, 40}.

## Dönemler

| Dönem | Aralık | İçerik |
| --- | --- | --- |
| Eğitim | 2020-08-01 → 2022-12-31 | 2021 boğa, 2022 ayı |
| Doğrulama | 2023-01-01 → 2024-12-31 | Toparlanma, boğa |
| Son test | 2025-01-01 → 2026-11-08 | Karar verilene kadar dokunulmaz |

**İleri yürüyen test:** 12 ay eğitim → 6 ay test, kayan pencerelerle. Her katmanda üç seçenekten eğitimde en iyisi
seçilir.

## Seçim ve başarı ölçütleri (orta maliyet senaryosu)

1. **Eğitim:** Ortalama R'si en yüksek seçenek seçilir. Seçeneğin en az 30 işlemi olmalı; hiçbiri 30'a ulaşmazsa en
   çok işlem yapan seçilir.
2. **Doğrulamayı geçme:** Ortalama R > 0, PF ≥ 1,1 ve en az 30 işlem.
3. **Son test:** Seçilen seçenekle tek bir kez çalıştırılır. Bütün stratejilerin sonucu şeffaflık için raporlanır.
4. **"Umut verici":** Doğrulamayı ve son testi aynı ölçütle geçer, ileri yürüyen birleşik sonuç ortalama R > 0
   verir.
5. **Uyarı işareti:** 30'dan az işlemli her sonuç "istatistik için yetersiz" diye işaretlenir.

Kötümser maliyet senaryosu ve komşu parametre seçenekleri duyarlılık olarak raporlanır. Karar ölçütü olarak
kullanılmaz.

**Canlı kâğıt işlem:** Yalnız "umut verici" stratejiler ayrı sanal portföyde takip edilmeye alınır. Hiçbiri değilse
yeni strateji canlıya alınmaz.
