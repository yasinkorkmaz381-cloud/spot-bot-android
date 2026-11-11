# CryptoScan Pro V5: tek strateji (Momentum RS-1), sonuç raporu (11 Kasım 2026)

**Kaynaklar:**

- Kurallar ve test protokolü: [RS1-ONKAYIT.md](RS1-ONKAYIT.md). Kod ve sonuçlardan önce commit edildi.
- Ham çıktılar:
  - [research/rs1-arastirma.txt](research/rs1-arastirma.txt)
  - [research/rs1-canli-tarama.txt](research/rs1-canli-tarama.txt)
- Yeniden üretmek için:

  ```
  cryptoscan download --out veri
  cryptoscan research --data veri
  ```

## Kısa sonuç

**Momentum RS-1 dokunulmamış bağımsız testte zarar ediyor.** Ön kayıtlı ölçüt sağlanmadı: BAŞARISIZ.

| Dönem (orta maliyet) | İşlem | Kazanma | PF | Ort. R (t) | Net | En büyük düşüş | BTC al-tut |
| --- | ---: | ---: | ---: | --- | ---: | ---: | ---: |
| Eğitim 2020-08 → 2022 | 42 | %31,0 | 1,93 | +0,372 (1,05) | +%7,85 | %3,6 | +%45,4 |
| Doğrulama 2023–2024 | 60 | %31,7 | 1,66 | +0,204 (1,11) | +%6,04 | %3,6 | +%463,7 |
| **Son test 2025-01 → 2026-11-09** | **45** | **%20,0** | **0,56** | **−0,145 (−1,15)** | **−%3,26** | %5,4 | −%19,8 |
| Tüm dönem 2020-08 → 2026-11 | 147 | %27,9 | 1,40 | +0,145 (1,10) | +%10,64 | %5,4 | +%562,3 |

USDT'de bekleme her dönemde %0'dır.

**Son testin ayrıntısı:**

- 1.000 USDT 967,39 USDT'ye düştü.
- İşlem başına beklenen değer −0,72 USDT.
- Toplam ücret 1,3 USDT, spread+kayma 3,2 USDT.
- Ortalama pozisyon süresi 7,6 gün.
- Piyasada kalma oranı %28.
- En kârlı parite (PENGU, +25,0 USDT) çıkarılınca ortalama R −0,269.
- Kötümser maliyette −%3,41, iyimser maliyette −%3,16. Sonucu belirleyen maliyet değil, işlem seçimi.

**Geçmiş dönemler de güçlü bir kanıt değil:**

- Eğitim ve doğrulamada pozitif ama t istatistikleri yaklaşık 1. Bu, rastlantıdan ayırt edilemez.
- Tüm dönemde kârın büyük kısmı BTC işlemlerinden geliyor (+94,1 USDT). BTC çıkarılınca tüm dönem ortalama R +0,025.
- Strateji 6 yılda +%10,6 kazandı; aynı dönemde BTC al-tut +%562.
- Düşüşü ise çok düşük (en fazla %5,4). Nedeni zamanın yalnız %32'sinde piyasada olması ve pozisyon başına küçük risk.

## Neden kaybediyor? (son test, gözlem; kurallar değiştirilmedi)

**Çıkış nedenleri:**

| Çıkış nedeni | Son test | Tüm dönem |
| --- | --- | --- |
| Haftalık uygunluk kaybı | 28 işlem, ort. +0,009R | 81 işlem, ort. +0,368R |
| BTC filtresi kapandı | 8 işlem, −0,130R | 32 işlem, +0,034R |
| Koruyucu stop | 4 işlem, −1,0R | 18 işlem, −1,0R |
| İz süren stop | 3 işlem, −0,296R | 14 işlem, +0,660R |
| İki gün EMA50 altında | 2 işlem, −0,422R | 2 işlem, −0,422R |

**Değerlendirme:**

- **Haftalık çıkış kuralı trendi erken kesiyor.** "7 günlük getiri > 0" şartı her pazartesi yeniden aranıyor. Trend içindeki bir haftalık düzeltme bile pozisyonu kapatıyor; tutma süresi ortalama 7,6 gün. Önceki yıllarda bu çıkışlar kârlıyken (+0,368R) son testte sıfıra yakın.
- **Son test döneminde BTC filtresi günlerin yalnız %37'sinde açıktı.** Açıldığında da trendler kısa sürdü.
- **Likidite eşiği evreni çok daraltıyor:**
  - 30 gün medyan hacim ≥ 5M USDT şartını geçen parite sayısı çoğu zaman 4–25 arasında.
  - 2025'in başındaki zirvede 50.
  - Bugün 6.

Bunlar gelecekte test edilebilecek hipotezlerdir. Sonucu iyileştirmek için kurallar **değiştirilmedi**. Değişiklik yapılacaksa önce ayrı bir ön kayıt ve yeni bir dokunulmamış test dönemi gerekir.

## Kaldırılan eski modüller

**Çekirdek:**

- `engine/TradingEngine` (15M canlı motor, gölge portföyler).
- `strategy/*`: A Breakout + Retest, B Trend Pullback, C Range.
- `rules/*`: kural motoru.
- `scanner/Scanner`, `scanner/WideScanner`.
- `trading/*`: karar zinciri, sanal hesap, emir simülasyonu, karar günlüğü.
- `backtest/*`: eski 15M backtest, kayıp analizi, araştırma.
- `research/*`: V3 S1–S4, ileri test izleyicisi.
- `risk/*`, `analysis/*`.
- WebSocket istemcisi ve protobuf ayrıştırıcı. RS-1 yalnız REST kullanır.

**CLI:** Eski `backtest/compare/analyze/study/v3/v4/costsplit` komutları kaldırıldı.

**Uygulama:**

- Coin ayrıntı ekranı, işlem ayrıntısı, karar ve olay günlüğü.
- Strateji seçim menüsü.
- A/B karşılaştırma kartları.

**Kaldırmadan önce alınan yedekler:**

- Uzak depoda `e0f2032` commit'i (son V4 hali).
- Yerelde `yedek-v4-son` etiketi.
- Git paketi ve kaynak arşivi.
- Silinen her şey git geçmişinde duruyor.

**Otomatik doğrulama:** `SecurityTest.old strategies and the old trading engine cannot run` iki şeyi doğrular:

- Eski sınıflar (`TradingEngine`, `StrategyId`, A/B/C stratejileri, `DecisionPipeline`, `BacktestEngine`, `V3Engine`) derlenmiş kodda yok.
- Uygulama, CLI ve çekirdek kaynakları onlara başvurmuyor.

## Korunan veriler

**Cihazda:**

- İlk açılışta (ayar sürümü 3) eski motorun bütün dosyaları **silinmeden** `archive-v4/` klasörüne taşınır:
  - `portfolio.json`, `shadows.json`, `forward_s1.json`, `wide_scan.json`, `decisions/`.
  - `settings.json`'ın bir kopyası.
- Taşınanların listesi `archive-v4/BILGI.txt`'ye yazılır.
- Eski kapanmış işlemler İşlemler → **Arşiv** sekmesinde salt okunur görünür.
- "Bot çalışıyor" tercihi korunur.

**Sanal hesabı sıfırlama:** Mevcut kayıt silinmez, `archive-v4/paper-<zaman>.json` olarak saklanır.

**Depoda:** Eski araştırma ve backtest raporları `docs/` ve `docs/research/` altında duruyor.

**Testler:** `SettingsMigrationTest` şunları doğrular: dosyalar taşınır ve silinmez, ikinci açılış bir şey değiştirmez,
arşiv eski sınıflar olmadan okunur.

## Yeni strateji kuralları (özet; tam tanım ön kayıtta)

**BTC filtresi:** Günlük kapanış > EMA100 ve 30 günlük getiri > 0. Veri eksik ya da bayatsa yeni alım yok.

**Uygunluk şartları:**

1. Kapanış > EMA100.
2. 7 günlük getiri > 0.
3. 30 günlük getiri > 0.
4. BTC'ye göre en az +3 puan (BTC muaf).
5. Spread ≤ %0,15.
6. Derinlik: ±%1 aralığında her iki tarafta ≥ 3 × en büyük pozisyon.
7. 30 günlük medyan hacim ≥ 5M USDT.
8. Son günün aralığı ≤ 2,5 ATR.

Ayrıca en az 150 gün güvenilir geçmiş gerekir.

**Sıralama:** 30 günlük getiriye göre. En güçlü 2 uygun coin adaydır.

**Zamanlama:**

- Giriş yalnız pazartesi 00:15 UTC, tamamlanmış günlük mumlarla. Dolum pazartesi açılış fiyatından.
- Her gün:
  - İz süren stop güncellemesi.
  - BTC filtresi kapanırsa çıkış.
  - İki gün EMA50 altında kapanışta çıkış.
- Pazartesi: uygunluk kaybında çıkış.
- Gün içinde koruyucu stop sürekli izlenir.

**Risk:**

- Sermaye 1.000 USDT, işlem başına risk %0,5.
- En fazla 2 pozisyon.
- Coin başına en fazla %25, toplam en fazla %50.
- Günlük %1,5 kayıpta o gün yeni giriş yok.
- Başlangıç stopu 2,5 ATR. İz süren stop: en yüksek kapanış − 3 ATR, yalnız yukarı taşınır.
- Kaldıraç, açığa satış ve zararda ek alım yok.

## Tek karar motoru: backtest ve paper trading

**Akış:** MEXC verisi → veri doğrulama → BTC filtresi → coin seçimi → `Rs1Engine` → risk → paper emir. Bütün
parametreler tek kaynakta: `Rs1Config`.

**Backtest ve paper trading aynı `Rs1Engine` kodunu çalıştırır.** Paper trading her güncellemede motoru şu girdilerle
yeniden çalıştırır:

- Takip başladığından beri gelen gerçek MEXC 1D mumları.
- Haftalık canlı ölçümler (spread, derinlik).
- Devam eden günün 1H mumları. Bunlar yalnız gün içi stop ve dolum için kullanılır.

**Sonuçları:**

- Aynı veriyle her zaman aynı kararı verir.
- Uygulama yeniden başlasa da pozisyonlar ve geçmiş kaybolmaz.
- Uygulama çalışmazken oluşan çıkışlar da sonradan doğru fiyatla hesaplanır. Bu durumda geç kayıt ayrıca gösterilir.

**Otomatik testler** (`Rs1PaperTest`):

- Paper trading, aynı veride backtest motoruyla birebir aynı işlemleri, stopları ve haftalık kararları üretir.
- Gün içi stop, gün kapanmadan backtest'le aynı fiyatla kaydedilir.
- Kaçırılan haftalık değerlendirme o hafta girişi engeller ve "erişim nedeni" olarak raporlanır.
- Canlı spread ve derinlik başarısızlığı adayı engeller.
- Yeniden başlatmada veri kaybı olmaz.

## Taranabilir coin sayısı (11 Kasım 2026, canlı)

| Aşama | Parite sayısı |
| --- | ---: |
| MEXC USDT paritesi | 1.413 |
| Desteklenmeyen ürün | 210 |
| İşlem durdurulmuş, tek yönlü ya da risk etiketli | 112 |
| 24 saatlik hacmi 1M USDT'nin altında (ön eleme) | 1.067 |
| 1D verisi incelenen | 24 |
| **Likidite, veri ve spread şartlarını geçen** | **6** |
| Trend şartını geçen | 0 |

- **Bugünkü durum:** BTC filtresi kapalı (BTC EMA100 altında, 30 günlük getiri −%11,9). İşlem açılmaması kuralların
  kararıdır.
- **Tarama maliyeti:** 7 saniye, 33 istek. 3'ü toplu istek; 1D mumları günde bir kez indirilir.
- **Kapasite:** Mimari bütün pariteleri tarar. 5M USDT hacim şartı nedeniyle işlem yapılabilir evren bugün küçük.

## Testler

**Çekirdek:** 45 test (1'i yalnız `-Plive` ile çalışan canlı MEXC testi). Başarısız yok.

| Test sınıfı | Test | Kapsadığı |
| --- | ---: | --- |
| `Rs1EngineTest` | 12 | Yalnız pazartesi girişi; 2 pozisyon, %25 ve %50 sınırı; BTC filtresi; stop ve boşluk; iz süren stop; EMA50 çıkışı; haftalık çıkış; günlük kayıp; ileriye bakmama; 150 gün kuralı; likidite, RS ve aralık şartları |
| `Rs1PaperTest` | 6 | Paper ve backtest eşitliği, gün içi stop, kaçırılan hafta, yeniden başlatma, canlı şartlar, ölçüm penceresi |
| `Rs1ScannerTest` | 7 | Ürün ayıklama, motorla aynı aday, derinlik ve spread, yeni listeleme ve önbellek, bot döngüsü |
| `SecurityTest` | 4 | İmzalı uç nokta ve API anahtarı yok; yalnız GET; parametreler ön kayıtla aynı; eski stratejiler yok |

**Uygulama:** 28 test. Başarısız yok.

- Ayar göçü ve arşiv: 3 test.
- Etkileşim: 4 test (sekmeler, backtest seçimi, sıfırlama onayı, strateji seçimi yok).
- Biçimlendirme: 5 test.
- Ekran görüntüleri: 8 ekran × 2 ekran boyutu.

**Diğer:** Lint sorunsuz. APK **3.0.0-paper** (versionCode 11).

## Mevcut teknik riskler

- **Hayatta kalma yanlılığı:** Borsadan kaldırılmış coinler indirilemiyor. Gerçek sonuç muhtemelen daha kötü.
- **Geçmiş spread ve derinlik yok:**
  - Koşul 5 ve 6 backtest'te uygulanamıyor; maliyet senaryolarıyla modelleniyor.
  - Canlıda bu iki koşul ek eleme yapacağı için paper trading backtest'ten daha az işlem açabilir.
- **Dolum yaklaşımı:** Giriş pazartesi 00:00 açılış fiyatı + maliyetle hesaplanıyor; gerçek emir 00:15'te verilirdi.
  Gün içi stop 1D/1H verisiyle stop fiyatından dolduruluyor; gerçek boşluklarda kayıp daha büyük olabilir.
- **Android arka plan kısıtları:**
  - Haftalık giriş için uygulamanın pazartesi 00:15–04:00 UTC arasında çalışması gerekir. Çalışmazsa o hafta giriş
    yapılmaz; bu, ekranda erişim nedeni olarak gösterilir.
  - Çıkışlar kaçmaz ama geç kaydedilebilir.
  - Kesintisiz çalışma garanti edilemez. Panel'de uyarı ve döngü aralığı gösterilir.
- **Cihazda backtest süresi:** İlk çalıştırmada 1.200'den fazla paritenin günlük geçmişi indirilir; birkaç dakika
  sürer, sonra önbellekten okunur.
- **İstatistiksel güç düşük:** 6 yılda 147 işlem var; t istatistikleri yaklaşık 1. Tek bir paritenin (BTC, PENGU, RACA)
  sonuca etkisi büyük.
- **Gerçek işlem kapalı:** Gerçek emir gönderen kod yok; `SecurityTest` bunu her derlemede kontrol eder.

## Sonuç

Momentum RS-1 uygulamada tek strateji olarak çalışıyor. Backtest ve paper trading aynı motoru kullanıyor; geniş MEXC
evreni taranıyor.

Strateji, ilk sürüm parametreleriyle **bağımsız son testte zarar ediyor.** Kârlı görünmesi için hiçbir sonuç ya da kural
değiştirilmedi. Paper trading ileriye dönük veri toplamak için çalışabilir. Gerçek parayla kullanım için bir dayanak
yoktur.
