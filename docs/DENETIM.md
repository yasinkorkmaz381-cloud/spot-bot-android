# Kod denetimi — CryptoScan Pro (V2 öncesi, 9 Kasım 2026)

Bu rapor V2 çalışmasına başlamadan **önceki** durumu (commit `cffd581`) anlatır. Her özellik, doğrulandığı yere
göre sınıflandırıldı. "Cihazda denenmedi" = kod derleniyor ve birim testleri geçiyor ama gerçek bir Android
telefonda çalıştırılmadı (bu ortamda emülatör yok).

## 1. Çalışıyor ve otomatik testlerle doğrulandı

| Özellik | Doğrulama |
| --- | --- |
| MEXC REST (exchangeInfo, klines, 24s ticker, book ticker, derinlik), 429/418 geri çekilme | MockWebServer testleri + canlı duman testi |
| Geçmiş mum indirme (500 mum sınırı, boşluksuz sayfalama) | Test + 1 yıllık gerçek indirme |
| MEXC protobuf WebSocket çözücü | Gerçek MEXC mesajıyla test |
| WebSocket istemcisi (abonelik, PING, yeniden bağlanma) | Canlı duman testi (JVM'de) |
| Göstergeler (EMA, RSI, MACD, ATR, ADX/DI, SuperTrend, VWAP) | pandas-ta ve ta ile birebir |
| Piyasa yapısı (swing, HH/HL, BOS, S/R, FVG, OB, likidite, rejim); gelecek veriye bakmama | Kesilmiş seriyle eşitlik testi |
| Tarama, piyasa filtresi, ön puan | Birim testleri + canlı CLI taraması |
| Strateji A/B/C, kural motoru (15 zorunlu + 11 kalite), koşul dili | Birim testleri |
| Risk: net R:R, %0,5 boyutlandırma, borsa hassasiyeti, asgari tutar, korelasyon, günlük limit | Birim testleri |
| Sanal hesap, işlem günlüğü, 6 soruluk gerekçe, kısmi kâr/başa baş/süre/dinlenme | Birim testleri |
| Canlı motor: başlangıç, ilk tarama, ağ yokken yeniden deneme, manuel kapatma | Sahte API ile uçtan uca test |
| Backtest, gölge işlem, Monte Carlo, ileri yürüyen test | Gerçek veriyle regresyon testi + 1 yıllık araştırma |
| 7 ekranın çizimi | Robolectric ekran testleri (yalnız çizim; dokunma etkileşimi test edilmedi) |

## 2. Kodu var, cihazda denenmedi

- Ön plan servisi (7/24), wake lock, START_STICKY ile yeniden başlama, açılışta otomatik başlatma
- Bildirimler ve bildirimdeki "yeni işlemleri durdur / botu kapat" düğmeleri
- Pil optimizasyonu muafiyeti isteği
- Uygulama içi backtest (telefonda veri indirip çalıştırma)
- Ayar değişikliklerinin canlı motora uygulanması, sanal hesabı sıfırlama
- Canlı limit (maker) giriş emirleri (deneysel; varsayılan kapalı)

## 3. Yalnız arayüzde görünen ya da kısmen çalışan

- **Ayarlar → Kurallar:** özel kural sayısını gösterir ama uygulamadan kural eklenemez (kurallar yalnız kod
  içindeki `rules/default-rules.json` ile eklenir).
- **Panel → Stratejiler kartı:** yalnız gösterim (açma/kapama Ayarlar'da).
- **Karar günlüğü:** son 300 kararı tutar ama coin başına **tüm kuralların** geçti/kaldı listesini ve
  **verinin zamanını** kaydetmez; yalnız reddedilen kurulumların başarısız zorunlu kuralları yazılır. Tarama
  adaylarına alınmayan coinler için "neden işlem açılmadı" kaydı yoktur.

## 4. Eksik ya da hatalı (V2'de düzeltilecek)

### Kritik altyapı
1. **Eski veriyle karar riski.** 15M mum güncellemesi bir coin için başarısız olursa hata kaydedilip geçiliyor,
   ardından o coin **önceki (eski) mumlarla** değerlendirilebiliyor. Fiyatın yaşı da girişte kontrol edilmiyor.
2. **Bekleyen limit emirler kalıcı değil** (uygulama yeniden başlarsa kaybolur) ve **nakit ayrılmıyor**; aynı
   nakit başka işlem için de kullanılabilir.
3. **Bakiye kontrolü yok:** `PaperAccount.open` nakit yetmese de pozisyon açabilir (normal akışta boyutlandırma
   bunu engelliyor, ama alt katmanda güvence yok).
4. **İş parçacığı yarışı:** "yeni işlemleri duraklat" arayüz iş parçacığından portföy durumunu güncelliyor; motorla
   aynı anda yazarsa bir güncelleme kaybolabilir. Sanal hesap sıfırlanınca bekleyen emirler temizlenmiyor.
5. **Çevrimdışı süre izlenmiyor:** uygulama/telefon kapalı kaldığında bu süre ve o sırada stopların aşılıp
   aşılmadığı kaydedilmiyor; çalışma süresi (uptime) ölçülmüyor.

### İşlem simülasyonu
6. Emirler **emir defteri derinliğine** göre doldurulmuyor (yalnız en iyi fiyat + sabit kayma). **Kısmi
   gerçekleşme** yok. Backtest'te mum hacmine göre likidite sınırı yok.
7. MEXC emir sınırlarından **en büyük tutar** (`maxQuoteAmount`), **piyasa emri asgari tutarı**
   (`quoteAmountPrecisionMarket`) ve **fiyat bandı** (`PERCENT_PRICE_BY_SIDE`) okunmuyor/uygulanmıyor.

### Risk
8. **Toplam açık risk sınırı** açık bir kural değil (yalnız "en fazla 3 işlem × %0,5" ile dolaylı).
9. **ATR tabanlı stop** seçeneği yok.

### Strateji ve test
10. Strateji profilleri (Adaptif / Trend / Seçici) yok; stratejiler **aynı koşullarda yan yana** karşılaştırılamıyor
    (CLI'daki araştırma komutu hariç). Uygulamada out-of-sample testi yok.
11. Backtest'in veri sızıntısı yapmadığı yalnız analiz katmanında test edildi; **tüm backtest için** "gelecek veri
    silinince geçmiş kararlar değişmiyor" testi yok.

### Bilinçli olarak yok
- **Gerçek emir gönderme yok.** Kodda imzalı uç nokta (`/api/v3/order`), API anahtarı ya da imza kodu bulunmuyor
  (kaynak taramasıyla doğrulandı). İstemci yalnız herkese açık GET uç noktalarını kullanıyor.
- Telegram bildirimi, YZ analizi, 60 kural: henüz yok.

## 5. Botun çalıştığı yer

- **Android uygulamasında bot telefonda çalışır** (ön plan servisi). Telefon kapanır, internet kesilir ya da Android
  uygulamayı durdurursa izleme durur; MEXC spot API'sinde borsa tarafı stop emri olmadığından bu süre boyunca
  pozisyonlar korumasızdır.
- Aynı çekirdek `cryptoscan paper` komutuyla **sunucuda/bilgisayarda** da çalışabilir (Android'e bağlı değil).
  Gerçek paraya geçilecekse sunucu çalıştırması önerilir.

---

# V2 sonrası durum (9 Kasım 2026)

| # | Bulgu | Durum | Nasıl doğrulandı |
| --- | --- | --- | --- |
| 1 | Eski veriyle karar riski | ✅ Düzeltildi: zorunlu kural **Z17** (15M/1H/4H son mumlar tarama kapanışıyla eşleşmeli, canlı fiyat ≤ 30 sn). Verisi eski coin "Veri güncel değil" olarak kaydedilir, işlem açılmaz | `OrdersTest` (tazelik), canlı motor testi |
| 2 | Bekleyen limit emirler kalıcı değil, nakit ayrılmıyor | ✅ Portföyde saklanıyor, nakit ayrılıyor, yeniden başlatmada sürüyor | `TradingEngineTest`: yeniden başlatma sonrası dolum |
| 3 | Bakiye kontrolü yok | ✅ Hesap ödeyemeyeceği dolumu reddeder | `OrdersTest` |
| 4 | İş parçacığı yarışı, sıfırlamada bekleyen emir | ✅ Duraklatma/sıfırlama motor iş parçacığında; sıfırlama emirleri temizler | Kod incelemesi (eşzamanlılık testi yok) |
| 5 | Çevrimdışı süre izlenmiyor | ✅ Dakikalık "canlıyım" kaydı; kesinti, nedeni (kullanıcı / beklenmedik) ve o sırada aşılan stop/hedef kaydedilir; 24 saatlik çalışma oranı | `TradingEngineTest`: kesinti kaydı |
| 6 | Derinliksiz dolum, kısmi gerçekleşme yok | ✅ Canlıda piyasa emri emir defterini %0,5 bant içinde tüketir; limit emir yalnız fiyat limitin ötesinde işlem görünce dolar; backtest'te mum hacminin %10'u sınırı; kısmi gerçekleşmede kalan miktar satılmaya devam eder | `OrdersTest`, `BacktestV2Test` |
| 7 | Emir sınırları eksik | ✅ Asgari/azami tutar, piyasa emri asgari tutarı, fiyat bandı, yön kısıtı, fiyat/miktar adımı | `OrdersTest` |
| 8 | Toplam risk sınırı örtük | ✅ Zorunlu kural **Z18**, varsayılan %1,5 (önceki örtük sınırla aynı) | `BacktestV2Test` |
| 9 | ATR stop yok | ✅ Seçenek eklendi (Asgari mesafe / ATR stop), **varsayılan kapalı** | `BacktestV2Test` |
| 10 | Profil karşılaştırması yok | ✅ Adaptif (varsayılan) / Trend / Seçici; aynı koşullarda karşılaştırma ve ilk/ikinci yarı; uygulamada ve `cryptoscan compare` | `BacktestV2Test`, 1 yıllık çalıştırma |
| 11 | Backtest sızıntı testi yok | ✅ "Kesim tarihinden sonraki veri silinince önceki kararlar değişmiyor" testi; gölge analizin kararları etkilemediği testi | `BacktestV2Test` |
| — | Coin bazında karar günlüğü yok | ✅ Her taramada her coin: sonuç, gerekçe, veri zamanları; adaylarda her strateji ve her kuralın geçti/kaldı sonucu; son 3 gün telefonda saklanır | `TradingEngineTest`, ekran testi, canlı CLI taraması |

## Hâlâ cihazda denenmemiş olanlar

Ön plan servisi, bildirimler, açılışta başlatma, pil muafiyeti, uygulama içi backtest, yeni karar günlüğü ve
grafik ekranlarının dokunma etkileşimleri. Ekranlar yalnız Robolectric'te gerçek veriyle çizildi.

## Hâlâ yok (bilinçli ya da sonraki aşama)

- Gerçek emir gönderme ve API anahtarı (V1/V2 yalnız sanal işlem; güvenlik testi bunu denetler)
- 60 kuralın metni, Telegram bildirimi, YZ analizi
