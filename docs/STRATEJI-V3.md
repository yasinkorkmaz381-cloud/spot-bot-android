# Strateji araştırması V3: sonuçlar (10 Kasım 2026)

Yöntem, parametre seçenekleri ve başarı ölçütleri testlerden önce
[STRATEJI-V3-ONKAYIT.md](STRATEJI-V3-ONKAYIT.md) dosyasında commit edildi. Sonuç görüldükten sonra hiçbir ölçüt, dönem
ya da seçenek değiştirilmedi.

**Ham çıktılar:**

- [research/v3-sonuclar.txt](research/v3-sonuclar.txt) ve [research/v3-sonuclar.json](research/v3-sonuclar.json)
- [research/maliyet-ayristirma.txt](research/maliyet-ayristirma.txt)

**Yeniden üretmek için:**

```
cryptoscan v3 --data <4H veri klasörü> --out v3.json
cryptoscan costsplit --data <15M veri klasörü>
```

## Kısa sonuç

| Strateji | Ön kayıtlı karar | Neden |
| --- | --- | --- |
| **S1 Göreceli güç (BTC'ye göre), L=30** | **Umut verici** | Doğrulama +0,263R, PF 2,17 (156 işlem). Son test +0,214R, PF 1,75 (156). İleri yürüyen +0,153R (371). |
| S2 1D/4H momentum, N=60 | Başarısız | Doğrulamada PF 1,03, eşik 1,1. Kötümser maliyette doğrulama negatif. |
| S3 Daralma + teyitli kırılım, P=30 | Başarısız | Doğrulamada yalnız 24 işlem. Son test −0,235R, PF 0,55. İleri yürüyen −0,167R. |
| S4 Güçlü trendde geri çekilme, T=40 | Başarısız | Doğrulama −0,107R. Son test −0,157R. İleri yürüyen −0,090R. Her rejimde negatif. |

**S1 bile kanıtlanmış bir avantaj değildir.**

- **Düşük t istatistiği:** Son testte 0,82, ileri yürüyen testte 1,51. Kabaca 2'nin altı rastlantıdan ayırt edilemez.
- **İleri yürüyen sonuç birkaç pencereye bağlı:** 11 pencerenin 6'sı zararlı. Birleşik pozitif sonuç büyük ölçüde iki
  pencereden geliyor (+1,095R ve +1,032R).
- **Hayatta kalma yanlılığı:** Evrende borsadan kaldırılmış coinler (LUNA, FTT gibi) yok. Bu, "en güçlüleri al"
  mantığındaki bir stratejiyi özellikle iyimser gösterir.
- **Son testteki kâr tek pariteden:** ZEC'in katkısı +216,6 USDT. ZEC olmadan son test −57,8 USDT (bkz. "S1 kâr
  yoğunlaşması").
- **BTC'nin gerisinde:** Tüm dönemde (2020-08 → 2026-11) S1 +%51,6 kazandı; BTC al-tut +%562 kazandı. S1'in üstünlüğü
  yalnız düşen son test döneminde var: S1 +%15,9, BTC al-tut −%19,8.

Bu nedenle S1 canlıya değil, yalnız **ayrı bir sanal portföyde ileri teste** alındı. Gerçek emir gönderimi kapalı ve
kapalı kalıyor.

## 1. Mevcut sonucun doğrulanması: −%20,99 ile −%13,92 farkı

Önceki raporda A+B'nin bir yıllık sonucu iki farklı spread varsayımıyla hesaplanmıştı:

- Sabit spread: −%20,99.
- Ölçülen spread: −%13,92.

Fark maliyetten değil, **evren farkından** geliyor.

| Bileşen | İşlem | Net (USDT) |
| --- | ---: | ---: |
| 1) Ölçülen senaryoda elenen pariteler (DOGE %0,28, ENA %0,26, INJ %0,20, NEAR %0,38 spread) | 70 | −111,59 |
| 2) Ortak işlemler, sabit maliyetle | 104 | −67,33 |
| 2) Aynı işlemler, ölçülen maliyetle | 104 | −71,61 |
| 3) Yalnız sabit senaryoda açılan diğer işlemler (yol etkisi) | 20 | −30,94 |
| 4) Yalnız ölçülen senaryoda, boşalan yerlerde açılan işlemler | 37 | −67,60 |

Mutabakat birebir tutuyor:

- Sabit: 1 + 2 + 3 = −209,86 USDT (−%20,99).
- Ölçülen: 2 + 4 = −139,21 USDT (−%13,92).

**Sonuç:** "Daha iyi" görünen −%13,92 daha düşük maliyetten gelmiyor. Zarar eden dört parite evrenden çıktığı için
oluşuyor. Aynı işlemlerde ölçülen maliyet sonucu 4,28 USDT **kötüleştiriyor**.

Ölçülen spread tek bir günün verisi; geçmişin tamamı için gerçek değer kabul edilmemeli. Bu yüzden:

- Uygulamadaki backtest artık varsayılan olarak "Orta" maliyet senaryosunu kullanıyor.
- "Bugünkü ölçüm" seçeneği yalnız bir senaryo olarak sunuluyor ve öyle etiketleniyor.

## 2. Veri ve yöntem (özet)

- **Veri:** MEXC 4H mumları, 28 parite, 2020-01 → 2026-11-10. Günlük mumlar yalnız tamamlanmış UTC günlerinden
  üretildi.
- **İşlem yapılabilir parite sayısı:** 2020-08: 12 · 2023-01: 14 · 2025-01: 28 · 2026-11: 17. Koşullar: en az 210 gün
  geçmiş ve 30 günlük medyan hacim en az 1M USDT. Emir, mum hacminin en fazla %10'u kadar dolabilir.
- **Dönemler:**
  - Eğitim: 2020-08 → 2022-12.
  - Doğrulama: 2023-01 → 2024-12.
  - Son test: 2025-01 → 2026-11, karar verilene kadar dokunulmadı.
- **İleri yürüyen test:** 12 ay eğitim → 6 ay test, 11 pencere.
- **Maliyet:** Yön başına ücret %0,05 ve kayma %0,05. Yarım spread üç senaryoyla:
  - İyimser %0,025.
  - **Orta %0,075 (karar senaryosu).**
  - Kötümser %0,15.
- **Risk kuralları:** Her strateji ayrı bir 1.000 USDT portföyde.
  - İşlem başına özsermayenin %0,5'i risk.
  - En fazla 3 pozisyon.
  - Pozisyon başına en fazla %30.
  - Günlük %1,5 kayıpta yeni giriş yok.
  - Mevcut uygulamanın risk ayarları değiştirilmedi.
- **Seçim:** Strateji başına önceden belirlenmiş üç seçenek. Yalnız eğitim sonucuna göre seçildi; toplam 12
  kombinasyon.

## 3. Sonuçlar (orta maliyet, seçilen seçenek)

| | S1 L=30 | S2 N=60 | S3 P=30 | S4 T=40 |
| --- | --- | --- | --- | --- |
| Eğitim | 191 işlem · +0,078R · PF 1,31 | 310 · +0,209R · 1,42 | 35 · +0,012R · 1,03 | 139 · +0,001R · 0,99 |
| Doğrulama | 156 · **+0,263R** · 2,17 · t 1,90 · +%21,6 · düşüş %6,4 | 310 · +0,024R · **1,03** · +%2,4 | **24** · +0,057R · 1,14 | 171 · −0,107R · 0,81 · −%8,8 |
| Son test | 156 · **+0,214R** · 1,75 · t 0,82 · +%15,9 · düşüş %15,1 | 243 · +0,090R · 1,17 · +%9,9 | 49 · −0,235R · 0,55 · −%5,7 | 130 · −0,157R · 0,73 · −%10,3 |
| İleri yürüyen (birleşik) | 371 · +0,153R · PF 1,70 · t 1,51 | 717 · +0,102R · 1,22 · t 1,50 | 81 · −0,167R · 0,66 | 337 · −0,090R · 0,83 |
| Tüm dönem | 500 · +%51,6 · düşüş %15,1 · piyasada %83 | 860 · +%52,5 · düşüş %21,7 | 108 · −%5,2 | 439 · −%19,0 · düşüş %26,6 |

**Kıyaslama** (aynı dönem, alış ve satış maliyeti dahil):

| Dönem | BTC al-tut | USDT'de bekleme |
| --- | ---: | ---: |
| Doğrulama | +%463,7 | %0 |
| Son test | −%19,8 | %0 |
| Tüm dönem | +%562,3 | %0 |

### Maliyet duyarlılığı (seçilen seçenek, son test)

| | İyimser | Orta | Kötümser |
| --- | --- | --- | --- |
| S1 | +0,221R | +0,214R | +0,202R |
| S2 | +0,115R | +0,090R | +0,056R (doğrulamada −0,007R) |
| S3 | −0,220R | −0,235R | −0,255R |
| S4 | −0,147R | −0,157R | −0,202R |

S1'in sonucu maliyete duyarlı değil, çünkü işlemleri seyrek ve uzun. S2'nin doğrulamadaki küçük kârı kötümser
maliyette kayboluyor.

### Komşu seçenekler (duyarlılık; seçim için kullanılmadı)

- **S1:** Doğrulamada ve son testte üç seçenek de (L = 30, 60, 90) pozitif; PF 1,75–2,68. Sonuç tek bir parametreye
  bağlı değil.
- **S2:** Doğrulamada N=40 daha iyi görünüyor (+0,272R). Bunu sonradan seçmek, ön kayıttaki "eğitimde en iyisi"
  kuralını sonuca bakarak değiştirmek olurdu. Yapılmadı. S2 başarısız olarak kaldı.
- **S3 ve S4:** Seçeneklerin hiçbiri son testte pozitif değil.

### BTC rejimine göre (tüm dönem, giriş anındaki rejim)

| | Yükseliş | Yatay | Düşüş |
| --- | --- | --- | --- |
| S1 | 302 · +0,201R · +365 USDT | 59 · +0,454R · +152 | 134 · +0,006R · −8 |
| S2 | 584 · +0,144R · +487 | 95 · −0,072R · −59 | 174 · +0,067R · +68 |
| S3 | 62 · −0,143R | 9 · −0,061R ⚠ <30 | 37 · −0,029R |
| S4 | 314 · −0,065R · −108 | 53 · −0,034R | 71 · −0,252R · −79 |

S1 düşüş rejiminde sıfıra yakın. Kârını yükseliş ve yatay dönemlerde yapıyor.

### S1 ileri yürüyen pencereleri

| Test penceresi | Seçilen | İşlem | Ort. R |
| --- | --- | ---: | ---: |
| 2021-08 → 2022-01 | L=60 | 38 | −0,171 |
| 2022-01 → 2022-07 | L=30 | 27 | −0,144 |
| 2022-07 → 2023-01 | L=90 | 38 | +0,175 |
| 2023-01 → 2023-07 | L=90 | 26 | −0,100 |
| 2023-07 → 2024-01 | L=90 | 23 | **+1,095** |
| 2024-01 → 2024-07 | L=90 | 39 | −0,069 |
| 2024-07 → 2025-01 | L=60 | 30 | +0,443 |
| 2025-01 → 2025-07 | L=30 | 43 | −0,041 |
| 2025-07 → 2026-01 | L=90 | 28 | **+1,032** |
| 2026-01 → 2026-07 | L=30 | 41 | −0,167 |
| 2026-07 → 2026-11 | L=60 | 38 | +0,187 |

Pencerelerin 6'sı zararlı. Birleşik sonuç iki güçlü pencereye dayanıyor. S1 küçük kayıplarla bekleyip seyrek, büyük
trendlerden kazanan bir yapı; bu yüzden sonuç dalgalı ve kısa vadede zayıf görünebilir.

### S1 kâr yoğunlaşması

Komut: `cryptoscan v3concentration --data <4H veri klasörü>`. Aynı motor, aynı seçenek (L=30), orta maliyet.

| Dönem | Net (USDT) | En iyi 5 işlem olmadan | En iyi 10 işlem olmadan | En iyi parite | En iyi parite olmadan |
| --- | ---: | ---: | ---: | --- | ---: |
| Doğrulama (156 işlem) | +216,2 | −9,3 | −71,4 | SOL +74,3 | +141,9 |
| **Son test (156 işlem)** | **+158,8** | **−106,1** | −142,9 | **ZEC +216,6** | **−57,8** |

**Son testteki kârın tamamı tek bir pariteden, ZEC'ten geliyor.** ZEC çıkarılırsa S1 son testte zararda.

Trend takip stratejilerinde kârın birkaç büyük işlemden gelmesi olağandır. Kayıplar küçük tutulur, kazançlar seyrek ve
büyüktür. Ancak son testteki "olumlu" sonuç, ZEC'in tek bir büyük yükselişini yakalamaya dayanıyor. Bu, sonucun
tekrarlanabilir bir avantaj olduğuna dair kanıtı ciddi biçimde zayıflatıyor. Ön kayıttaki ölçüt karar kuralı olduğu
için S1 "umut verici" etiketini koruyor; ancak bu etiket "kanıtlandı" anlamına gelmiyor.

## 4. Yetersiz veri ve bilinen sınırlar

- **S3:** Eğitim ve doğrulamada 30'dan az işlem var. Sonuç istatistik için yetersiz; zaten başarısız.
- **Hayatta kalma yanlılığı:** Borsadan kaldırılmış pariteler indirilemedi (MATIC isteği HTTP 400 dönüyor; LUNA ve FTT
  yok). S1 için gerçek sonuç muhtemelen daha kötü.
- **İşlem yapılabilir parite sayısı düşük:** 2023 öncesinde yalnız 12–14 parite var. Eğitim dönemi dar bir evrende.
- **Geçmiş spread verisi yok:** Spread yalnız senaryolarla modellendi.
- **4H çözünürlük:** Mum içi sıra bilinmiyor; stop her zaman hedeften önce sayıldı (ihtiyatlı).
- **Rejim sınıflaması:** İlk ~220 günde "veri yok". Bu işlemler ayrı gösterildi.
- **İstatistiksel güç:** S1'in son testi tek başına anlamlı değil (t 0,82). Ancak birkaç yüz yeni işlemle güvenilir
  bir karar verilebilir. Bu, yaklaşık 1–2 yıllık ileri test demek.

## 5. Uygulamadaki değişiklikler (sürüm 2.4.0-paper)

### Strateji A gölge teste alındı

- Ayar göçü (sürüm 2) A'yı aktif listeden çıkarıp gölge listeye ekler.
- B aktif kalır; aktif liste boş kalırsa B'ye düşülür.
- Risk ayarları, işlem geçmişi ve backtest kayıtları silinmez.
- Göç tekrar uygulanırsa sonuç değişmez. Kullanıcı A'yı sonradan elle açarsa kapatılmaz.
- Testler: `SettingsMigrationTest`.

### Gölge portföyler

- Her gölge strateji, aynı tarama, kural, risk, dolum ve çıkış koduyla kendi sanal hesabında (`shadows.json`) işlem
  açar.
- Aktif portföye dokunmaz. Testler: `SignalReplayTest`.

### S1 ileri testi

- `ForwardTracker`, araştırmadaki aynı `V3Engine` ve aynı S1 kodunu kullanır. Ayrı bir 1.000 USDT sanal portföydür.
- Yalnız takip başladıktan sonra kapanan 4H mumlarında işlem yapar. Önceki veri yalnız göstergeler ve işlem
  yapılabilirlik içindir.
- Bot servisi her 10 dakikada yeni 4H mumu kapanmış mı diye bakar. Ekranda **İşlemler → Gölge test** kartındaki
  "Güncelle" düğmesi de var.
- **Dolum modeli:** Sonraki 4H açılışı + orta maliyet senaryosu. Bu bir ileri testtir; canlı emir defteri dolumu
  değildir.
- **Testler:** `ForwardTrackerTest` iki şeyi doğrular:
  - Sonuç, aynı veride araştırma motoruyla birebir aynıdır.
  - Başlangıçtan önce işlem yoktur; başlangıç zamanı kalıcıdır; veri hatası ayrı raporlanır.
- **Gerçek MEXC verisiyle kontrol:** `cryptoscan forward --days 120` 28 paritenin tamamını hatasız yükledi (17'si
  işlem yapılabilir); ilk indirme yaklaşık 100 saniye sürdü. Bu 120 günlük deneme yalnız mekanizmanın çalıştığını
  gösterir. Son test dönemiyle örtüştüğü için yeni bir kanıt değildir.

### "İşlem açmadı" ile "işlem açamadı" ayrımı

`SystemStatus` iki durumu ayırır. Tarama, Panel ve ileri test kartında gösterilir. Testler: `ScanDiagnosticsTest`.

- **Yazılım / veri sorunu:**
  - Motor hatası.
  - Gecikmiş tarama.
  - 2 dakikadan eski fiyat.
  - Bayat mum verisi.
  - Emir simülasyonu hatası.
  - İleri testte veri indirilemedi.
- **Kural kararı:** "Yazılım sağlıklı … İşlem açılmaması kuralların kararıdır."

### Backtest maliyet senaryoları

- Seçenekler: İyimser, **Orta (varsayılan)**, Kötümser ve "Bugünkü ölçüm (senaryo)".
- Raporda kullanılan spread modeli yazılır.

### Değişmeyenler

- Gerçek emir gönderimi yok.
- API anahtarı yok.
- Risk limitleri aynı.
- B'nin kuralları aynı.

## 6. Sonraki adım için öneri

- S1'in ileri testini en az **100–150 kapanmış işlem** (yaklaşık 1 yıl) boyunca, kuralları değiştirmeden izleyin.
- **Değerlendirme:** Ön kayıttaki ölçüt aynen uygulanır (orta maliyet, ort. R > 0, PF ≥ 1,1).
  - Ayrıca BTC al-tut ile ve S1'in son testteki düşüşüyle (%15) karşılaştırın.
- **Durdurma ölçütü:** İleri testte düşüş %20'yi aşarsa ya da 100 işlemden sonra ort. R ≤ 0 olursa strateji
  bırakılmalı.
- B'nin aktif kâğıt sonucu ve A'nın gölge sonucu ayrı ayrı birikmeye devam ediyor. A ve B için geçmiş testlerdeki
  negatif sonuçlar ([KRITIK-SORUNLAR.md](KRITIK-SORUNLAR.md)) hâlâ geçerli.
- Hiçbir sonuç gelecekteki kâr garantisi değildir.
