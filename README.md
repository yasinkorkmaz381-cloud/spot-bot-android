# SpotBot (Android)

Kripto **spot piyasası** için al-sat botu: Android uygulaması.
Kotlin + Jetpack Compose (Material 3) ile yazılmıştır.

> **Durum:** İlk iskelet. Uygulama şimdilik yalnızca **simülasyon (sanal işlem)** modunda çalışır:
> fiyatlar uygulama içinde üretilir, hiçbir borsaya bağlanılmaz ve gerçek para kullanılmaz.

## Ekranlar

| Ekran | İçerik |
| --- | --- |
| **Panel** | Bot durumu ve başlat/durdur, aktif strateji özeti, portföy (toplam değer, K/Z, nakit, açık pozisyon), son işlemler |
| **Piyasalar** | Takip edilen USDT paritelerinin canlı (simüle) fiyatları ve değişim yüzdesi, arama |
| **Strateji** | Parite, emir tutarı, kâr al % ve zarar durdur % ayarları, doğrulama |
| **Ayarlar** | İşlem modu, borsa bağlantı durumu, simülasyon hesabı bilgisi ve sıfırlama, sürüm |

## Strateji: Kâr al / Zarar durdur

1. Açık pozisyon yoksa bot, belirlenen tutarda (örn. 50 USDT) **alım** yapar.
2. Fiyat efektif giriş fiyatının **kâr al %** kadar üzerine çıkınca satar.
3. Fiyat **zarar durdur %** kadar altına düşünce satar.
4. Ardından yeni bir giriş yapar.

Her alım ve satımda %0,1 işlem ücreti (Binance spot standardı) hesaba katılır.
Strateji mantığı (`TakeProfitStopLossStrategy`) ve hesap güncellemesi (`PaperTrader`) saf
Kotlin fonksiyonlarıdır. Android'den ve borsadan bağımsız olarak test edilirler.

## Mimari

```
app/src/main/java/com/spotbot/android/
├── domain/            # Saf Kotlin: modeller ve işlem mantığı (Android bağımlılığı yok)
│   ├── model/         # TradingPair, MarketTicker, StrategyConfig, Trade, PaperAccount, BotState
│   └── trading/       # TakeProfitStopLossStrategy, PaperTrader
├── data/
│   ├── market/        # MarketRepository + SimulatedMarketRepository
│   └── bot/           # BotRepository + PaperTradingBotRepository
├── di/                # AppContainer (elle kurulan bağımlılıklar)
└── ui/                # Compose ekranları + ViewModel'ler (dashboard, markets, strategy, settings)
```

- **UI → ViewModel → Repository → Domain** tek yönlü veri akışı; durum `StateFlow` ile taşınır.
- Repository'ler arayüz arkasındadır. Gerçek Binance entegrasyonu `MarketRepository` ve
  `BotRepository` için yeni bir uygulama yazılarak, ekranlara dokunmadan eklenebilir.
- Para ve miktar hesapları `BigDecimal` ile yapılır (kayan nokta hatası olmaz).

## Derleme ve çalıştırma

Gereksinimler: JDK 17+ (21 önerilir), Android SDK (API 37).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # birim testleri
./gradlew lintDebug              # Android lint
./gradlew installDebug           # bağlı cihaza/emülatöre kur
```

Android Studio ile proje klasörünü açmanız da yeterli.
GitHub Actions her push'ta testleri, lint'i ve debug APK derlemesini çalıştırır; APK, iş
çıktılarına (artifact) yüklenir.

Sürümler: AGP 9.5, Gradle 9.9, Kotlin 2.4, Compose BOM 2026.09, minSdk 26, compileSdk/targetSdk 37.

## Yol haritası (sonraki adımlar)

- [ ] **Binance piyasa verisi:** REST (`/api/v3/ticker/24hr`) ve WebSocket ile gerçek fiyatlar
- [ ] **Kalıcılık:** strateji, işlem geçmişi ve hesap durumu için Room / DataStore
- [ ] **Güvenli API anahtarı saklama:** Android Keystore ile şifreleme. Anahtarlar yalnızca
      "spot trade" yetkili, **para çekme yetkisi kapalı** ve IP kısıtlı olmalı
- [ ] **Testnet ile gerçek emir:** önce Binance Spot Testnet üzerinde emir gönderme
- [ ] **Kesintisiz çalışma:** Android arka planda uygulamayı durdurabilir. 7/24 bot için
      foreground service ya da (tercihen) botun sunucuda çalıştığı, uygulamanın kumanda
      paneli olduğu bir mimari
- [ ] Ek stratejiler (DCA, grid, RSI/EMA sinyalleri), fiyat grafikleri, bildirimler

## Uyarı

Bu yazılım yatırım tavsiyesi değildir. Kripto para işlemleri yüksek risk içerir; gerçek
parayla kullanmadan önce stratejileri simülasyonda ve testnet'te kapsamlı biçimde deneyin.
