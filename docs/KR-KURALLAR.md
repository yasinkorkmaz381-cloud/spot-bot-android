# 60 kuraldan seçilenler ve Kırılım–Retest'e uyarlanışı

**Önemli not:** "Ekteki 60 kural" metni bu çalışmaya ulaşmadı. Ne mesajda ne de projede bulunuyor (`docs/KURALLAR.md`
9 Kasım 2026'da da aynı eksikliği kaydetmişti). Aşağıdaki tablo, istekte numarasıyla ve başlığıyla verilen kural
gruplarına dayanır. Kuralların tam metni gelirse tablo yeniden karşılaştırılmalıdır. Metni görülmeyen kurallar
uygulanmış gibi gösterilmez.

Kural kodları (Y1, S1, K1…) [KR-ONKAYIT.md](KR-ONKAYIT.md) ile aynıdır. Bütün kurallar zorunludur. Puanlama yoktur:
hiçbir kritik kural bir puanla ezilemez (kural 60).

| 60 kuraldaki no | Konu (istekteki başlık) | Uyarlama | Kod |
| --- | --- | --- | --- |
| 2 | 4H yön | Tek tanım: 4H kapanış > EMA50 ve EMA50, 6 mum öncekinden yüksek. Az sayıda, açık koşul | Y1 · `Trend4h` |
| 3 | 1H işlem bölgesi | 1H fraktal tepe (3+3 mum). Sağ taraf kapanmadan seviye bilinmez. En fazla 72 saat eski | S1–S3 · `SetupTracker.aggregateHour` |
| 4 | 15M giriş | Kırılım ve retest yalnız kapanmış 15M mumlarla | K1, R1–R3 |
| 6 | Fiyat yapısı | Fraktal tepe = yapısal direnç. Retest düşükleri = yapısal stop | S1, G3 |
| 7 | Destek/direnç | Kırılan direnç destek olarak test edilir (retest). Bir sonraki direnç hedeftir | R2, G4 |
| 8 | Kapanış teyidi | Fitil kırılım sayılmaz; seviye üstünde kapanış gerekir | K1 |
| 9 | Kırılım kalitesi | Seviye kırılımdan önce sabit. Kırılan seviye bir daha kullanılmaz | S3, K4 |
| 10 | Retest | Toleransla geri geliş (0,25 ATR), seviye üstünde yükseliş kapanışı, 8 mum süre. Seviye altında kapanışta iptal | R1–R3 |
| 11 | Kovalamama | Dolum seviye + 0,75 ATR'nin üstündeyse sinyal iptal | G2 |
| 12 | EMA | Yalnız 4H EMA50 (yön). Başka EMA teyidi aranmaz | Y1 |
| 18 | Hacim | Kırılım mumu hacmi ≥ 1,5 × önceki 20 mumun ortalaması; 24 saatlik MEXC hacmi ≥ 1 M USDT | K2, P3 |
| 19 | ATR | Retest toleransı, stop tamponu, kovalama sınırı, hedef (ATR 1H) ve tez iptali hep ATR ile ölçülür | §1.2 |
| 36 | Emir defteri / spread / kayma | Canlı: spread ≤ %0,20, planlanan tutar için emir defteri kayması ≤ %0,15; dolum VWAP'tan. Backtest: coin başına anlık spread + %0,05 kayma | P3 · `KrPaper.liveQuote` |
| 37 | BTC etkisi | BTC son 4 saatte %2'den fazla düştüyse yeni giriş yok (bekle) | P1 |
| 38 | BTC rejimi | 37 ile birleştirildi; ayrı bir BTC trend şartı eklenmedi (aynı bilgiyi iki kez saymamak için) | P1 |
| 39 | Göreceli güç | Coinin 24 saatlik getirisi BTC'ninkinden büyük olmalı (BTC'nin kendisi muaf) | P2 |
| 40 | Göreceli güç (süre) | 39 ile aynı ölçü; ikinci bir RS penceresi eklenmedi | P2 |
| 41 | Açık pozisyonu yeniden değerlendirme | Her 15M kapanışında tez iptali (kapanış < seviye − 0,25 ATR), 2 saatte ilerleme yok, 6 saat sınırı | §1.3 · `KrExits.onClose` |
| 45 | Gerekçeli giriş | Her işlem bir kurulum kimliğine bağlıdır (sembol + kırılım zamanı + seviye). Plan, işlem kaydında saklanır | `SetupPlan.id` |
| 48 | Yapısal stop | Retest yapısının en düşüğü − 0,25 ATR. Sabit yüzde yok, gevşetilmez, başabaşa taşınmaz | G3 |
| 49 | Stop takibi | Sert stop gün içinde sürekli denetlenir (paper: 1 dk mum + anlık alış fiyatı) | `KrPaper.protect` |
| 50 | Hedef | Tek tam çıkış: en yakın 1H direnç veya dolum + 1,5 × ATR(1H), hangisi yakınsa. Hedef uzağa taşınmaz | G4 |
| 51 | Maliyet sonrası R:R | Komisyon + spread + kayma, her bacakta. Net R:R ≥ 1,2 (test hipotezi) | G5 |
| 52 | Maliyet yeterliliği | İki yön maliyet ≤ brüt hedefin %40'ı | G6 |
| 53 | Miktar | Risk %0,25 / birim net stop kaybı; sonra %20 / %70 sınırları, bakiye, miktar adımı, asgari tutar | `KrSizing` |
| 56 | Portföy riski | Toplam açık stop riski ≤ %1, en fazla 4 pozisyon, coin başına tek pozisyon, günlük kayıp %2 | §2 |
| 57 | Veri kalitesi | Son 48 saatte 15M boşluğu yok, son mum beklenen kapanışta. CMC listesi 48 saatten eskiyse yeni giriş yok | P5, `LIST_STALE` |
| 58 | Çelişkide bekle | BTC verisi yoksa, 4H yön belirsizse ya da veri eksikse giriş yok | P1, K3, P5 |
| 59 | Kritik kurallar | Hepsi zorunlu; bir tanesi bile geçmezse giriş yok | `KrFilters`, `Rejection` |
| 60 | Puanla ezilmeme | Puanlama sistemi yok. Aynı kapanışta birden çok aday olursa yalnız sıralama yapılır (net R:R ↓, maliyet ↑, sembol) | `KrBacktest`, `KrPaper.scan` |

## Bilinçli olarak alınmayanlar

| Konu | Neden |
| --- | --- |
| 1D onayı | Yalnız bağlam. Ek zorunlu şart değil (isteğe uygun) |
| RSI, MACD, ADX, SuperTrend, VWAP'ın birlikte zorunlu tutulması | Hepsi aynı fiyat bilgisinden türer; tekrar teyit sayılmadı |
| OI, funding, long/short, futures basis | Spot bot için zorunlu girdi değil |
| OB / FVG / likidite süpürmesi | Ayrı giriş stratejisine dönüştürülmedi |
| Haberden yön tahmini | Yok. Yalnız süreli risk kuralı var: kullanıcının girdiği makro olay penceresi (−30/+60 dk) ve MEXC ST etiketi. Doğrulanmış haber/takvim kaynağı olmadığından arayüz "Kaynak yok — kontrol edilmedi" yazar |
| Plan A / Plan B | Tek alım düzeni var |
| TP1/TP2, kısmi satış, başabaşa taşıma | Bu sürümde yok (isteğe uygun) |
