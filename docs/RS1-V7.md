# Momentum RS-1 V7: karşılaştırma ve gerçekleşme modeli eşitliği (12 Kasım 2026)

**Belgeler ve ham çıktılar:**

- Ön kayıt: [RS1-V7-ONKAYIT.md](RS1-V7-ONKAYIT.md). Commit `3561b48`, hiçbir V7 sonucuna bakılmadan önce.
- [research/rs1-v7-protokol.txt](research/rs1-v7-protokol.txt): bütün çalıştırmalar.
- [research/v7/](research/v7/): her yapılandırmanın işlem CSV'si.
- [research/rs1-v7-test-ciktilari.txt](research/rs1-v7-test-ciktilari.txt): test çıktıları.

## Sonuç

**Kazanan yok.** Ön kayıtlı seçim yöntemiyle hiçbir yapılandırma ötekilerden açık biçimde üstün çıkmadı. Bu durum:

- 9 pencerenin hiçbirinin eğitim verisinde,
- ne de tüm geçmişte

gerçekleşmedi. Bu yüzden **V7 kuralları V6 ile aynıdır** (`Rs1Versions.V7 = V6`, testli).

**Kazanç üstünlüğü gösterilemedi.** Dört yapılandırmanın da pencere dışı birleşik sonucu **"yetersiz kanıt"** sınıfında:
ortalama R için %90 güven aralığının alt sınırı her birinde negatif.

**Teslim edilen asıl iş, yazılım düzeltmeleri:**

- Android backtest artık araştırma motoruyla aynı veri, aynı mum ayrıntısı ve aynı gerçekleşme modeliyle çalışıyor.
- V5 ve V6 kural setleri sabitlendi; sonuçları her zaman yeniden üretilebilir.

## 1. V6'nın yeniden üretimi

**Bulunan hata:** V6 protokol komutu taban kural setini `Rs1Config()` varsayılanlarından alıyordu. V6 sonunda bu
varsayılanlar dondurulunca, yeniden çalıştırma B0'ı V5 yerine V6 kurallarından başlattı:

- Görülmüş dönem 17 işlem / 985,32 USDT çıktı. Doğrusu 45 işlem / 967,39 USDT.
- İşlem CSV'si tesadüfen aynı kaldı.

**Düzeltme:** `Rs1Versions.V5` ve `Rs1Versions.V6` açık, değişmez sabitler olarak eklendi; komutlar bunları kullanıyor.
Commit `8686220`.

**Doğrulama (iki kez):** Araştırma raporu, işlem CSV'si ve bağımsız test çıktısı **byte düzeyinde aynı**. İkinci
doğrulama, V7 motor değişikliklerinden (sermaye kullanımı ölçüsü, önbellek anahtarı) sonra yapıldı.

**Kullanıcı mesajındaki bulgular CSV'den yeniden hesaplandı:**

| Dönem | İşlem | Net | PF |
| --- | ---: | ---: | ---: |
| Araştırma | 16 | +256,92 USDT | 7,97 |
| Doğrulama | 26 | +145,87 USDT | 3,99 |
| Görülmüş dönem | 19 | −41,34 USDT | 0,30 |

- Görülmüş dönemin toplam maliyeti 1,68 USDT.
- Araştırma kazancının %81,2'si 2020'deki 2 işlemden geliyor.

## 2. Dört yapılandırma

Bütün diğer kurallar V6 ile aynı: evren, uygunluk, sıralama, risk %0,5, en fazla 2 pozisyon, coin başına %25, toplam
%50, 2,5 ATR koruyucu stop.

| Kod | Giriş | Çıkış |
| --- | --- | --- |
| W0 (= V6) | Haftalık (pazartesi) | Koruyucu stop + BTC rejimi |
| W1 | Haftalık | + iz süren stop (en yüksek kapanış − 3 ATR) |
| D0 | Her tamamlanmış günlük mumdan sonra | Koruyucu stop + BTC rejimi |
| D1 | Her gün | + iz süren stop |

### Birleşik pencere dışı sonuç

Dokuz değerlendirme penceresi (2022-08 → 2026-11), her pencere 1.000 USDT ile ayrı portföy, orta maliyet ve parite
ücreti.

| | W0 (V6) | W1 | D0 | D1 |
| --- | ---: | ---: | ---: | ---: |
| Kapanmış işlem | 41 | 52 | 77 | 94 |
| Toplam net (USDT) | +96,4 | +61,2 | +126,4 | +141,7 |
| ×2 maliyet net | +86,9 | +52,1 | +111,9 | +125,0 |
| PF | 1,75 | 1,34 | 1,92 | 2,06 |
| İşlem başına beklenti (USDT) | +1,83 | +0,77 | +1,40 | +1,30 |
| Ortalama R, %90 GA | [−0,25, +1,07] | [−0,30, +0,70] | [−0,07, +0,68] | [−0,02, +0,60] |
| En kötü pencere düşüşü | %4,25 | %2,97 | %3,45 | %3,97 |
| Pozitif pencere | 4/9 | 3/9 | 6/9 | 6/9 |
| W0'dan kötü olmayan pencere | — | 4/9 | 5/9 | 5/9 |
| En kârlı coin | SOL +77,6 | SOL +65,2 | SOL +61,1 | SOL +79,8 |
| En iyi coin olmadan beklenti | −0,07 | −0,52 | +0,62 | +0,48 |
| En iyi 3 işlem olmadan net | −31,7 | −60,0 | −4,9 | +6,3 |
| Piyasada kalma / sermaye kullanımı | %36 / %3 | %34 / %2 | %44 / %4 | %43 / %4 |
| Sınıf | Yetersiz kanıt | Yetersiz kanıt | Yetersiz kanıt | Yetersiz kanıt |

**Sermaye kullanımı çok düşük.** %0,5 risk ve 2,5 ATR stop, pozisyon başına küçük tutarlar üretiyor. Bu yüzden 1.000
USDT'nin ortalama yalnız %2–4'ü piyasada; getiri potansiyeli de, risk de sınırlı.

### Pencere bazında net (USDT)

| Pencere | Değerlendirme | W0 | W1 | D0 | D1 | Seçim (eğitimden) |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 1 | 2022-08 → 2023-01 | +9,2 | +9,2 | +5,6 | +5,6 | kazanan yok → W0 |
| 2 | 2023-02 → 2023-07 | −9,5 | −10,3 | +1,1 | −3,9 | kazanan yok → W0 |
| 3 | 2023-08 → 2024-01 | +85,7 | +80,3 | +96,0 | +98,8 | kazanan yok → W0 |
| 4 | 2024-02 → 2024-07 | +33,0 | −5,9 | +7,6 | +15,7 | kazanan yok → W0 |
| 5 | 2024-08 → 2025-01 | +23,2 | +21,5 | +32,0 | +17,9 | kazanan yok → W0 |
| 6 | 2025-02 → 2025-07 | −5,6 | −3,1 | −6,7 | +4,3 | kazanan yok → W0 |
| 7 | 2025-08 → 2026-01 | −18,9 | −10,5 | −19,4 | −5,5 | kazanan yok → W0 |
| 8 | 2026-02 → 2026-07 | −6,0 | −6,6 | −4,9 | −7,3 | kazanan yok → W0 |
| 9 | 2026-08 → 2026-11 (kısa) | −14,9 | −13,5 | +15,1 | +16,2 | kazanan yok → W0 |

**İşlem sayıları:**

| | W0 | W1 | D0 | D1 |
| --- | --- | --- | --- | --- |
| Eğitim pencereleri | 16 → 57 | 24 → 77 | 24 → 101 | 33 → 126 |
| Değerlendirme pencereleri (pencere başına) | 0–24 aralığında | | | |

Pencere 3 (2023-08 → 2024-01) dört yapılandırmanın da toplam kârının büyük kısmını tek başına veriyor.

### Tüm geçmiş 2020-08 → 2026-11 (tek portföy; bağımsız değil)

| | İşlem | Net | Düşüş | PF | Beklenti | Ort. R, %90 GA | ×2 maliyet | En iyi 3 olmadan |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| W0 | 61 | +%38,1 | %8,3 | 3,08 | +6,24 | +1,14 [+0,23, +2,31] | +%35,7 | +85,8 |
| W1 | 82 | +%14,6 | %6,0 | 1,74 | +1,79 | +0,35 [−0,03, +0,77] | +%13,2 | +2,3 |
| D0 | 103 | +%45,3 | %5,8 | 3,27 | +4,40 | +0,78 [+0,22, +1,49] | +%42,0 | +145,8 |
| D1 | 130 | +%29,3 | %4,6 | 2,52 | +2,25 | +0,41 [+0,15, +0,70] | +%26,5 | +135,2 |

Hiçbiri ötekilerin hepsini 8 ölçünün hepsinde geçmiyor:

- W0'nun beklentisi ve güven aralığı en yüksek.
- D0'ın net getirisi en yüksek.
- D1'in düşüşü en düşük.

Tüm geçmiş sonuçları, eğitim dönemleri dahil bütün veriyi kapsadığı için kanıt değildir.

### 2018–2019 (betimleyici)

Bu dönem V6'nın bağımsız testi için bir kez kullanılmıştı; burada seçimde kullanılmadı.

| | İşlem | Net | PF | Ort. R, %90 GA |
| --- | ---: | ---: | ---: | --- |
| W0 | 5 | +%3,1 | 4,14 | [−0,56, +3,17] |
| W1 | 11 | +%3,3 | 2,95 | [−0,13, +1,45] |
| D0 | 16 | +%1,1 | 1,38 | [−0,39, +0,79] |
| D1 | 23 | +%2,0 | 1,54 | [−0,21, +0,63] |

Hepsi 30 işlemin altında.

### Değerlendirme

- **Günlük giriş daha tutarlı ama kanıtlanmış değil.** Günlük giriş (D0/D1) pencere dışında W0'dan daha çok pozitif
  pencere (6/9'a karşı 4/9) ve daha düşük en iyi coin bağımlılığı gösteriyor. Ancak:
  - Güven aralıkları sıfırı kapsıyor.
  - Hepsinde en kârlı coin SOL.
  - Kârın büyük kısmı tek bir pencereden geliyor.
  - Ön kayıtlı kurala göre kazanan değil.
- **İz süren stop sonucu iyileştirmedi.** Haftalık girişte (W1) belirgin biçimde kötü; günlük girişte (D1) karışık.
- **Olası bir sonraki hipotez:** D0 ya da D1, yeni bir ön kayıt ve ileri test dönemiyle sınanabilir. Bu sonuçlarla
  kurallar değiştirilmedi.

## 3. Gerçekleşme modeli eşitliği (uygulandı)

| Konu | V6 | V7 |
| --- | --- | --- |
| Android backtest mum ayrıntısı | Yalnız 1D; stop günlük mumla (gün içi boşluk görülmez) | **Araştırma eşdeğeri mod** (varsayılan): likidite eşiğini geçen paritelerin 4H mumları indirilir, stop araştırma motoruyla aynı biçimde çözülür. **Yaklaşık mod** ayrı; sonucunda "eşdeğer değildir" uyarısı. |
| Veri hazırlığı ve çalıştırma | Android ve CLI ayrı kod | Tek yol: `Rs1Backtest.prepare` + `Rs1Backtest.run`. Test: aynı veriyle Android yolu = araştırma motoru, birebir. |
| Sinyal ve dolum | Günlük kapanışta sinyal, ertesi gün açılışında dolum (backtest); paper'da değerlendirme anındaki emir defteri | Aynı. Geçmiş kapanış fiyatından dolum yok. |
| Kapanışta yükseltilen stop | Ertesi günden itibaren geçerli | Aynı; yeni testle doğrulandı (aynı günün düşüğüne uygulanmaz) |
| Girişten önceki fiyat | Stopu tetiklemez (paper) | Aynı (testli) |
| Boşlukla aşılan stop | 4H açılışından (araştırma); günlük açılıştan (Android) | Her iki yolda 4H açılışından |
| Günlük mum hizası | CLI'da normalleştirme | `Rs1Backtest.run` içinde her yolda (UTC'ye hizasız mumlar sayılır, eksikler 4H'den kurulur) |

## 4. Portföy ve veri kontrolleri (korundu)

- **Risk sınırları:** İşlem başına %0,5, en fazla 2 pozisyon, coin başına %25, toplam %50, ortak portföy bakiyesi.
- **Veri ve uygunluk:** Tarihsel uygunluk (250 gün geçmiş, 30 gün medyan hacim), listeleme zamanı, eksik ve hizasız mum
  kontrolleri.
- **Veri kapsamı:**
  - 1.203 parite, 1.136.260 günlük mum: yinelenen 0, sırasız 0, hizasız 0.
  - 4H verisi 471 parite.
  - 2018–2019: 49 parite.
- **Korunan testler:** Kaçırılan haftalık pencere, süresi dolan sinyal, yeniden başlatma, bağlantı ve saat farkı.
- **Açık sınırlamalar:**
  - Bugünkü MEXC ücretleri geçmişe uygulanıyor.
  - Kaldırılmış coinler yok (hayatta kalma yanlılığı).
  - Geçmiş spread ve emir defteri yok.
  - 2020–2026 daha önce incelendi; buradaki bütün sonuçlar tarihsel sağlamlık incelemesidir.
- **Gerçek emir gönderimi yok.**

## 5. Deneme sayısı (ön kayıtla karşılaştırma)

Ön kayıtta 120 çalıştırma yazıyordu; gerçekleşen **160**. Fark yeni yapılandırma değildir:

- Ön kayıtlı seçim ölçütü "×2 maliyette net getiri" eğitim pencerelerinde de hesaplanmak zorundaydı. Bu, 4 × 9 = 36
  eğitim ×2 çalıştırması ekledi; deneme listesinde sayılmamıştı.
- 2018–2019 betimlemesi ×2 maliyetle de çalıştırıldı: 4 çalıştırma.

Başka yapılandırma ya da parametre denenmedi. Ana bağlamın sayacı 152 çalıştırma gösteriyor; buna 2018–2019'un 8
çalıştırması eklenince 160 ediyor.

## 6. V6 → V7 değişiklikleri ve gerekçeleri

| Değişiklik | Gerekçe |
| --- | --- |
| `Rs1Versions.V5/V6/V7` sabitleri | Sonuçlar varsayılanlara bağlı kalmasın; V6 yeniden üretimindeki hata |
| Android backtest 4H gün içi stop (araştırma eşdeğeri) ve ayrı yaklaşık mod | Android ile araştırma aynı gerçekleşme modelini kullansın |
| `Rs1Backtest` ortak yolu | Aynı girdiler → aynı sonuç (testli) |
| Sermaye kullanımı ölçüsü | Ön kayıtlı ölçü |
| Backtest ekranında kanıt sınıfı her zaman hesaplanır | Ekran işlem varken "kapanmış işlem yok" diyordu |
| Kurallar | **Değişmedi** (kazanan yok) |

## 7. Testler

Gerçek çıktı: [research/rs1-v7-test-ciktilari.txt](research/rs1-v7-test-ciktilari.txt).

**Çekirdek: 55 test.**

- 1'i atlandı: canlı MEXC testi, yalnız `-Plive` ile çalışır.
- Başarısız yok.
- Yeni testler: `Rs1BacktestTest` (3 test).
  - Android yolu = araştırma motoru (4H).
  - Yaklaşık mod yalnız günlük mum kullanır ve öyle etiketlenir.
  - Kapanışta yükseltilen stop aynı güne uygulanmaz.
- `SecurityTest`: uygulama kuralları = V7 = V6.

**Uygulama: 28 test.** Başarısız yok. Lint: 0 sorun.

**Derleme:** APK **3.2.0-paper** (versionCode 13).

## 8. İleri paper trading planı (dondurulmuş kurallar)

- **Kurallar:** `Rs1Versions.V7` (= V6), uygulamanın varsayılanı. Değiştirilmez.
- **Başlangıç ve dolum:** Takip başladıktan sonra gerçekleşen her işlem ileri test kaydıdır. Paper dolumu, değerlendirme
  anındaki gerçek emir defterinden yapılır.
- **Aylık kontrol:**
  - Kapanmış işlem sayısı.
  - PF ve beklenti.
  - Ortalama R ve güven aralığı.
  - En büyük düşüş.
  - Pozitif ay sayısı.
  - Takip edilmeyen aralıklar ve kaçırılan değerlendirmeler.
- **Erken durdurma:**
  - Düşüş %10'u aşarsa.
  - 50 işlemden sonra beklenti ≤ 0 ise.
- **Değerlendirme:** 100 kapanmış işlemde (V6/V7 kurallarıyla yılda yaklaşık 10–15 işlem: birkaç yıl). Ölçütler
  `Rs1Evidence` ile aynı.
- **Hipotez:** Günlük giriş (D0/D1) yalnız yeni bir ön kayıtla ve kendi ileri test dönemiyle sınanabilir. Bu çalışmanın
  sonuçları onu seçmeye yetmez.
- **Gerçek para:** "Kanıt var" sınıfı olmadan gündeme gelmez. Gerçek emir kodu yoktur.
