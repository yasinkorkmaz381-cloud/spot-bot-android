# Momentum RS-1: kurallar ve test protokolü (ön kayıt, 11 Kasım 2026)

Bu belge kod ve backtest sonuçlarından önce commit edilir.

- Strateji parametreleri kullanıcının V5 talimatındaki ilk sürüm araştırma parametreleridir.
- Talimatın açık bırakmadığı işletim tanımları (dolum anı, öncelik sırası, derinlik eşiği, günlük kayıp tanımı,
  rejimler) burada sabitlenir.
- Sonuç görüldükten sonra hiçbir değer değiştirilmez. Değişiklik gerekirse ayrı, gerekçeli bir commit olur ve ilk sonuç
  da raporda kalır.

## Veri ve zaman

- MEXC spot **1D mumları** (UTC) kullanılır. D gününün mumu D+1 00:00 UTC'de kapanır.
- Bütün göstergeler yalnız **tamamlanmış** günlük mumlardan hesaplanır:
  - EMA100(1D), EMA50(1D).
  - ATR14(1D), Wilder yöntemiyle.
  - 7 ve 30 günlük getiri: kapanıştan kapanışa.
  - 30 günlük medyan günlük USDT hacmi.
- **Güvenilir geçmiş:**
  - En az 150 tamamlanmış günlük mum (yeni listelenen coin bu süre dolmadan işlem görmez).
  - Son 60 günde eksik gün yok.
  - Son mum, değerlendirilen günün mumu olmalı (bayat veri yok).

## BTC piyasa filtresi

Yeni alıma ancak iki koşul birlikte sağlanırsa izin verilir:

- BTCUSDT son kapanış > EMA100(1D).
- BTC 30 günlük getiri > 0.

BTC verisi eksik ya da bayatsa filtre "kapalı" sayılır. Bu durumda yeni alım yapılmaz ve açık pozisyonlar çıkış
kurallarına göre yönetilir.

## Uygunluk (her coin, tamamlanmış gün t)

1. Kapanış > EMA100(1D).
2. 7 günlük getiri > 0.
3. 30 günlük getiri > 0.
4. 30 günlük getiri − BTC'nin 30 günlük getirisi ≥ 3 yüzde puan. BTC bu şarttan muaftır.
5. Spread ≤ %0,15.
6. Emir defteri derinliği: orta fiyatın ±%1 aralığında alış ve satış tarafı ayrı ayrı ≥ 3 × (özsermayenin %25'i). Bu,
   başlangıçta 750 USDT demektir.
7. 30 günlük medyan günlük hacim ≥ 5.000.000 USDT.
8. Son tamamlanmış günün aralığı (yüksek − düşük) ≤ 2,5 × ATR14.

**Geçmişte ölçülemeyen şartlar:** Koşul 5 ve 6 için geçmiş emir defteri ve spread verisi yoktur. Bu yüzden:

- **Backtest'te bu iki koşul uygulanamaz.** Raporda açıkça belirtilir.
- Spread etkisi maliyet senaryolarıyla modellenir. Orta senaryodaki yarım spread %0,075'tir; bu, koşul 5'in üst
  sınırıdır.
- **Paper trading'de** iki koşul haftalık değerlendirme anında canlı ölçülür ve kaydedilir.

**Sıralama:** Uygun coinler 30 günlük getiriye göre azalan sırada dizilir. En güçlü iki uygun coin adaydır.

## Zamanlama ve karar sırası (aynı veriyle aynı karar)

### Gün içi: koruyucu stop

Her gün boyunca sürekli kontrol edilir:

- Günün açılışı ≤ stop ise açılış fiyatından çıkılır (boşluk).
- Değilse, günün en düşüğü ≤ stop olduğunda stop fiyatından çıkılır.
- Giriş günü de dahildir.
- **Paper trading'de** günün o ana kadar kapanmış 1H mumları ve canlı fiyat kullanılır. Gün tamamlanınca aynı olay,
  aynı fiyatla 1D mumundan da çıkar.

### Her günlük kapanışta (t = 00:00 UTC)

Adımlar şu sırayla uygulanır:

1. **İz süren stop güncellemesi:** stop = max(stop, girişten beri en yüksek tamamlanmış kapanış − 3 × ATR14(t)). Stop
   yalnız yukarı taşınır.
2. **Çıkış sinyalleri** (öncelik sırasıyla; ilk tutan neden kaydedilir):
   - a) BTC filtresi kapalı → bütün pozisyonlar.
   - b) Coin son iki tamamlanmış günde EMA50 altında kapandı.
   - c) Yalnız pazartesi 00:00'da (haftalık değerlendirme): coin uygunluk şartlarından birini kaybetti. Backtest'te
     1–4, 7 ve 8; paper trading'de buna ek olarak 5 ve 6.

   Çıkışlar yeni günün (t'de başlayan günün) **açılış fiyatından** yapılır.
3. **Haftalık giriş** (yalnız pazartesi 00:00 kapanışında, değerlendirme 00:15 UTC):
   - Koşullar:
     - BTC filtresi açık.
     - Günlük kayıp sınırı tetiklenmemiş.
     - Boş yer var (en fazla 2 pozisyon; 2. adımdaki çıkışlardan sonra sayılır).
   - Aynı değerlendirmede çıkılan coine yeniden girilmez.
   - Giriş, pazartesi günlük mumunun açılış fiyatından yapılır. Bu fiyat 00:00 fiyatıdır ve 00:15'teki gerçek emrin
     yaklaşığıdır.

### Paper trading'de haftalık değerlendirme

- Canlı ölçüm (spread, derinlik) pazartesi 00:00–04:00 UTC arasında yapılmalıdır.
- Uygulama o aralıkta çalışmadıysa **o hafta yeni giriş yapılmaz**. Bu durum "yazılım/erişim nedeni" olarak
  gösterilir, kural kararı olarak gösterilmez.
- Çıkışlar veriden hesaplandığı için kaçmaz. Geç kaydedildiyse tespit zamanı ayrıca gösterilir.

## Risk ve boyutlandırma

| Parametre | Değer |
| --- | --- |
| Sermaye | 1.000 USDT |
| İşlem başına risk | özsermayenin %0,5'i |
| Maksimum açık pozisyon | 2 |
| Coin başına maksimum | özsermayenin %25'i |
| Toplam pozisyon (piyasa değeri) | ≤ %50 |
| Kaldıraç, açığa satış, zararda ek alım | yok |

- **Özsermaye:** Nakit + açık pozisyonların t kapanışından, satış maliyeti düşülmüş değeri.
- **Başlangıç stopu:** Giriş fiyatı − 2,5 × ATR14(t).
- **Miktar:** risk / (giriş dolum fiyatı − stop dolum fiyatı + iki yön ücret). Sonra sırayla:
  - %25 coin sınırı.
  - %50 toplam sınırı.
  - Nakit.
  - Günlük hacmin %10'u.
- **En küçük emir:** Tutar 5 USDT altındaysa işlem yapılmaz. Paper trading'de MEXC'nin miktar hassasiyeti ve asgari
  tutar kuralları da uygulanır.
- **Günlük kayıp sınırı:** t anındaki özsermaye, 24 saat önceki özsermayeden %1,5 veya daha fazla düşükse t'de yeni
  giriş yapılmaz.
- **Boşluk riski:** Boşluk ve kayma nedeniyle gerçekleşen zarar başlangıç riskini aşabilir. Bu, R değerlerinde olduğu
  gibi raporlanır.

## Maliyet senaryoları (geçmiş spread verisi yok)

Yön başına ücret %0,05 ve kayma %0,05 bütün senaryolarda aynıdır.

| Senaryo | Yarım spread | Yön başına toplam |
| --- | --- | --- |
| İyimser | %0,025 | %0,125 |
| **Orta (karar)** | %0,075 | %0,175 |
| Kötümser | %0,15 | %0,25 |

## Evren ve yanlılık

- **Evren:** 10 Kasım 2026'da MEXC'de bulunan 1.204 USDT paritesi. Stablecoin, tokenleştirilmiş hisse, emtia,
  Pre-IPO ve kaldıraçlı token ayıklanmıştır.
- **Listeleme:** İlk günlük mum esas alınır.
- **Borsadan kaldırılmış pariteler** indirilemez. Hayatta kalma yanlılığı var ve raporlanır.
- **Bugünkü bilgi geçmişte kullanılmaz:** Bugünkü ST etiketi ve işlem durumu geçmişte ayıklama için kullanılmaz.

## Dönemler ve ölçütler

Parametre seçimi yapılmaz. Eğitim dönemi yalnız raporlanır.

| Dönem | Aralık |
| --- | --- |
| Eğitim | 2020-08-01 → 2023-01-01 |
| Doğrulama | 2023-01-01 → 2025-01-01 |
| **Dokunulmamış son test** | 2025-01-01 → veri sonu |

**Rejimler** (giriş günündeki BTC durumu):

- **Yükseliş:** Kapanış > SMA200 ve SMA200 20 günde yükselmiş.
- **Düşüş:** Kapanış < SMA200 ve SMA200 düşüyor.
- **Yatay:** Diğer durumlar.
- **Yüksek volatilite:** BTC ATR14/kapanış oranı, son 365 günün %75'lik diliminin üstünde. Geleceğe bakmaz; ayrıca
  etiketlenir.

**Raporlanan ölçüler:**

- Başlangıç ve bitiş sermayesi, net kâr/zarar.
- İşlem sayısı, kazanma oranı, PF, ortalama R ve t istatistiği.
- İşlem başına net beklenen değer (USDT).
- En büyük düşüş (günlük değerleme).
- Toplam ücret ile spread+kayma.
- Ortalama pozisyon süresi.
- BTC al-tut ve USDT'de bekleme.
- En iyi parite çıkarıldığında sonuç.

**"Bağımsız testte başarılı" ölçütü** (son test, orta maliyet):

- Ortalama R > 0.
- PF ≥ 1,1.
- En az 30 işlem.
- En iyi parite çıkarıldığında ortalama R > 0.

30'dan az işlem varsa sonuç **"istatistik için yetersiz"** olarak raporlanır, başarılı sayılmaz.
