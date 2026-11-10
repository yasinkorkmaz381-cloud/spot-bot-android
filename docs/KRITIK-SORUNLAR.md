# Kritik sorunlar incelemesi: backtest zararı ve işlem açmayan bot (10 Kasım 2026)

Kısa sonuç:

- **Yazılım doğru çalışıyor.** Bot işlem açmıyor çünkü piyasa ve MEXC likiditesi şu an kurallara uymuyor.
- **Stratejilerin kanıtlanmış bir avantajı yok.** Denenen hiçbir iyileştirme bağımsız testte başarılı olmadı; bu
  yüzden hiçbiri etkinleştirilmedi.
- Varsayılan strateji ve risk ayarları değişmedi. Gerçek işlem modu yok.

Ham çıktı: [research/2026-11-10-strateji-calismasi.txt](research/2026-11-10-strateji-calismasi.txt).

## 1. 44 / 45 işlem tutarsızlığı

Backtest'te Strateji C de seçiliydi. 45. işlem BTCUSDT'de bir C işlemi (stop, −2,30 USDT). Aynı ayarla yeniden
çalıştırınca sonuç birebir çıktı: 970,82 USDT, 45 işlem; A −46,57, B +19,69, C −2,30.

- Hesap doğruydu; hata arayüzdeydi. Karşılaştırma kartı yalnız A ve B'yi gösteriyordu.
- Düzeltme: kart işlem yapan bütün stratejileri ve toplam satırını gösteriyor. Strateji toplamlarının genel sonuca
  eşit olduğu testle denetleniyor.

## 2. Backtest doğru mu?

Önceki denetimde her işlem ham MEXC mumlarından ayrı bir betikle yeniden hesaplanmıştı ve tutuyordu. Gelecek veriye
bakmama testi ve aynı mumda stop/hedef kuralı (5M doğrulama, yoksa stop önce) yerinde.

**Yeni bulgu: backtest maliyeti olduğundan düşük gösteriyordu.** Tüm coinlerde %0,05 alış-satış farkı varsayıyordu.
MEXC'de ölçülen gerçek fark (10.11.2026, 20 ölçümün medyanı):

| Coin | Spread | Coin | Spread | Coin | Spread |
| --- | --- | --- | --- | --- | --- |
| BTC | %0,004 | XRP | %0,037 | ADA | %0,173 |
| SOL | %0,012 | BCH | %0,044 | INJ | %0,201 |
| ETH | %0,014 | AVAX | %0,061 | ENA | %0,255 |
| BNB | %0,019 | LTC | %0,080 | DOGE | %0,282 |
| TRX | %0,031 | ZEC | %0,128 | NEAR | %0,376 |

Canlı tarama spread'i %0,20'yi aşan coini eler. Backtest ise NEAR, INJ, DOGE ve ENA'da işlem açıyordu. B'nin 90
günlük kârının büyük kısmı NEAR ve INJ işlemlerinden geliyordu; canlıda bu işlemler açılamaz.

Düzeltme: backtest'e "ölçülen spread" seçeneği eklendi ve uygulamada varsayılan yapıldı. Ölçülen fark geçmişe
uygulanır ve canlıdaki spread filtresi backtest'te de çalışır. Geçmiş emir defteri olmadığı için bu bir varsayımdır.

## 3. Bot neden işlem açmıyor?

Canlı motor bu ortamda gerçek MEXC verisiyle çalıştırıldı. 19:15 UTC taramasının sayaçları:

| Aşama | Coin |
| --- | --- |
| Taranan | 22 |
| Likidite / spread nedeniyle elenen | 13 (9 yüksek spread, 3 sığ emir defteri, 2 düşük hacim) |
| Riskli işaretlenen (fitil oranı yüksek) | 2 |
| Trend / güç zayıf, aday olmadı | 7 |
| Aday (strateji incelemesine giren) | 0 |
| Açılan işlem | 0 |

Ayrıca BTC 4 saatlikte düşüş trendinde, yani piyasa filtresi alımı kapatıyor. Filtre olmasa da sonuç değişmezdi:
aday sayısı zaten 0.

Kontrol listesi:

| Soru | Sonuç |
| --- | --- |
| Motor çalışıyor mu? | Evet; her 15 dakikalık kapanıştan 11 sn sonra karar verdi |
| Yeni işlemler duraklatılmış mı? | Hayır |
| BTC filtresi neyle engelliyor? | B3: BTC 4H düşüş trendi (gerçek piyasa durumu) |
| BTC verisi güncel mi? | Evet (B1 ve B2 geçiyor) |
| Mumlar ve göstergeler geçerli mi? | Evet; 400 mum yükleniyor, eski veri 0 coin |
| Sinyal kâğıt hesaba ulaşıyor mu? | Evet; tekrar-oynatma testiyle kanıtlandı (aşağıda) |
| Bakiye / pozisyon limiti engeli var mı? | Hayır |
| MEXC veri kaybı var mı? | Görülmedi: WebSocket bağlı, yeniden bağlanma 0, WS/REST fiyat sapması %0,04, saat farkı 173 ms |
| Arka planda duruyor mu? | Bu ortamda ölçülemez; telefonda Panel → çalışma oranı ve son kesinti gösteriyor |

**Tekrar-oynatma testi** (`SignalReplayTest`): backtest'in işlem açtığı gerçek bir geçmiş sinyal, canlı motora sahte
borsa üzerinden beslendi. Motor aynı sinyali buldu ve kâğıt işlemi açtı. Stop, hedef ve geçersizlik seviyeleri
backtest ile birebir aynı. Yani tarama → strateji → kurallar → boyutlandırma → sanal hesap zinciri çalışıyor.

Beklenen işlem sıklığı da düşük: bir yıllık testte, ölçülen spread'lerle 15 coinde 141 işlem (yaklaşık 2,6 günde
bir) açıldı. BTC düşüş trendindeyken günlerce işlem açılmaması normal.

**Teşhis paneli:** Tarama → "Teşhis: neden işlem yok?" bu sayaçları, en sık nedenleri, son sinyali ve neden işleme
dönüşmediğini gösterir. Ana panelde son taramanın kısa nedeni yazar ("BTC trendi uygun değil", "15M giriş teyidi
bekleniyor", "MEXC verileri güncellenemedi" gibi). Hepsi karar günlüğünden hesaplanır.

## 4. Strateji A neden zarar ediyor?

Bir yılda 68 işlem, 56 zararlı (tek başına çalıştırıldığında 75 işlem, PF 0,51).

| Zararlı işlemlerde ortak neden | Zararlı | Kazananlarda |
| --- | --- | --- |
| Tetik mumunda hacim zayıf (< 1,5×) | 48 (%86) | 10 / 12 |
| BTC 4H yükseliş trendinde değildi | 30 (%54) | 4 / 12 |
| Stop dar (< 1 ATR) | 25 (%45) | 1 / 12 |
| 4H ve 1H trend uyumsuz | 19 (%34) | 5 / 12 |
| Kırılım tuttu ama hedefe varmadan döndü (≥ 1R lehte) | 18 (%32) | — |
| Sahte kırılım (lehte 0,3R bile gitmedi) | 15 (%27) | 0 / 12 |
| 4H yatayken kırılım | 10 (%18) | 5 / 12 |

Soruların yanıtları:

- Kırılım mumunun 1H kapanışı bekleniyor; 15M tetik de kapanmış mumla.
- Kırılımda 1H hacim şartı var (1,3×), ama 15M tetik mumunda hacim şartı yok.
- BTC yalnız düşüş trendinde engelliyor; BTC yatayken A işlem açabiliyor.
- 4H ve 1H uyumu zorunlu değil.

En belirgin ayrım dar stop: kazananların 12'de 1'inde, zararlıların %45'inde var. Ancak bunları filtrelemek sonucu
kâra çevirmedi (aşağıda).

## 5. İyileştirme denemeleri ve bağımsız test

Yöntem:

- Sekiz filtre (F1–F8) ölçümden önce sabitlendi.
- Her filtre A ve B için ayrı gerçek backtest olarak çalıştırıldı.
- Seçim yalnız eğitim döneminde (ilk 6 ay) yapıldı; sonuç görülmemiş test döneminde (son 6 ay) ölçüldü.

Eğitimde en iyi görünen filtreler ve testteki sonuçları (ortalama R):

| Strateji | Eğitimde seçilen | Eğitim | Test |
| --- | --- | --- | --- |
| A | F5: 4H ve 1H uyumlu | −0,20R (20 işlem) | −0,43R (35 işlem) |
| B | F4: stop ≥ 1 ATR | +0,02R (40 işlem) | −0,21R (31 işlem) |

A hiçbir filtreyle eğitimde bile pozitife geçmedi. B'nin tek pozitif görünen filtresi testte negatife döndü.

Üç yapılandırma, tüm dönem (15 coin, 1.000 USDT; BTC al-tut aynı dönemde −%20,57):

| Yapılandırma | Maliyet | İşlem | PF | Ort. R | Net | Maks. düşüş | Test dönemi |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A · Mevcut A + B | Standart | 194 | 0,61 | −0,29R | −%20,99 | %22,11 | −0,33R |
| B · Yalnız B | Standart | 144 | 0,67 | −0,24R | −%13,32 | %16,97 | −0,30R |
| C · Eğitimde seçilen (B + F4) | Standart | 71 | 0,83 | −0,08R | −%3,73 | %8,74 | −0,21R |
| A · Mevcut A + B | Ölçülen spread | 141 | 0,62 | −0,27R | −%13,92 | %14,07 | −0,24R |
| B · Yalnız B | Ölçülen spread | 98 | 0,56 | −0,32R | −%11,73 | %12,42 | −0,33R |
| C · Eğitimde seçilen (B + F4) | Ölçülen spread | 51 | 0,63 | −0,28R | −%6,07 | %7,12 | −0,52R |

C'nin daha küçük zararı daha az işlemden geliyor; test döneminde hâlâ negatif ve ölçülen spread'le daha da kötü.

**İleri yürüyen test** (3 ay eğitim → 2 ay test, 5 katman): eğitimde pozitif çıkan seçimler görülmemiş dönemde
49 işlem, PF 0,31, −0,57R verdi. Aynı dönemlerde mevcut A+B: 167 işlem, −0,32R. 49 işlem istatistik için az, ama yön
açık: eğitimde bulunan "iyileşme" tesadüf.

**Karar:** Hiçbir filtre etkinleştirilmedi. Filtre altyapısı kodda duruyor (`StrategyParams.entryFilters`,
varsayılan boş).

## 6. Strateji B gerçekten daha iyi mi?

A'dan daha az kötü, ama kârlı değil.

- 90 günlük +19,69 USDT sonucu 26 işleme dayanıyor ve canlıda elenecek coinleri içeriyor.
- Bir yılda B tek başına −%13,32; iki yarıda da negatif. Ölçülen spread'le PF 0,56.
- Kazanan ve kaybeden B işlemlerinin giriş anı ölçüleri birbirine çok yakın (1H ADX 36,2 ve 35,0; kalite puanı
  58,9 ve 59,2). Belirgin fark stop mesafesi (1,26 ve 0,99 ATR) ve 4H/1H uyumu (%84 ve %68); ikisi de testte
  avantaja dönüşmedi.

## 7. Düzeltilen yazılım hataları ve eklenenler

1. Backtest karşılaştırma kartı C stratejisini göstermiyordu.
2. Backtest spread'i gerçekçi değildi; ölçülen spread seçeneği ve canlıdaki spread filtresi eklendi.
3. Bot neden işlem açmadığını söylemiyordu; teşhis hunisi ve kısa neden eklendi.
4. Tekrar-oynatma altyapısı (`ReplayMarketDataApi`) ve testi eklendi.
5. Komut satırı: `paper` her taramada teşhis satırlarını yazdırıyor; `study` bu çalışmayı tekrar üretiyor.

Risk ayarları aynı: işlem başına %0,5, günlük %1,5, en fazla 3 pozisyon, toplam açık risk %1,5, ilişkili coin riski
%1, kaldıraç ve açığa satış yok. Net R:R hesabı ücret, spread ve kaymayı içeriyor.

## 8. Testler

| Test | Sonuç |
| --- | --- |
| Çekirdek | 123 geçti, 2 canlı test atlandı |
| Uygulama | 50 geçti |
| Lint | Sorun yok |
| APK | 2.3.0-paper derlendi |

Yeni testler: teşhis sayaçları ve kısa neden (3), strateji toplamları (1), ölçülen spread (1), ek filtreler (1),
tekrar-oynatma (1), arayüz (3).

Başarısız olan testler strateji tarafında: sekiz filtrenin hiçbiri bağımsız dönemde pozitif sonuç vermedi; ileri
yürüyen test de başarısız.

## 9. Gerçek işlem öncesi kalan riskler

- Stratejilerin maliyet sonrası beklenen değeri negatif. Bu çözülmeden gerçek para kullanılmamalı.
- Veri bir yıllık tek dönem; BTC bu dönemde −%20 ve çoğunlukla düşüş ya da yatay rejimdeydi.
- MEXC'de altcoin spread'leri geniş ve değişken; spread ölçümü tek güne ait.
- MEXC spot API'sinde borsa tarafı stop emri yok; telefon kapanırsa pozisyon korumasız kalır.
- Uygulama gerçek telefonda uzun süreli denenmedi (arka planda durma, pil kısıtları).
- 60 kuralın 28'inin metni hâlâ yok; Strateji C test edilmedi.
