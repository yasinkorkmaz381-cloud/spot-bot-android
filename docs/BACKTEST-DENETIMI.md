# Profesyonel backtest ve strateji denetimi (V2.0 · 9 Kasım 2026)

Öncelik sırası: doğru veri → doğru backtest → risk → strateji → arayüz → APK. Bu belgede yalnız ölçülen ya da
testle doğrulanan sonuçlar var; kâr garantisi ya da kanıtlanmamış iyileşme iddiası yoktur.

## 1. Sistem denetimi (kısa)

| Konu | Durum |
| --- | --- |
| Veri | MEXC REST `klines` (15M/1H/4H; ayrıca belirsiz mumlar için 5M), sayfalama 500 mumluk sınırla boşluksuz. Canlıda protobuf WebSocket (bookTicker + 15M kline), REST ile doğrulama ve yedek. |
| Kapanmış mum | Karar yalnız 15M kapanışında, kapanmış mumlarla. Kesme testi (gelecek veriyi silmek kararları değiştirmiyor) geçiyor. Pivotlar onay mumu kapanmadan kullanılmıyor. |
| Isınma | Backtest başlangıcından önce 45 gün yüklenir. 45 ve 120 günlük ısınma aynı 90 günlük testte **birebir aynı 44 işlemi** verdi. |
| Göstergeler | EMA/RSI/ATR/ADX/MACD/SuperTrend/VWAP pandas-ta ve ta ile birebir (önceki doğrulama). |
| Strateji A | 4H yükseliş ya da yatay; 1H direnç hacimli kapanışla kırılır (≥ seviye + 0,1 ATR, hacim ≥ 1,3×), 0,3 ATR içinde retest, 15M tetik + ≥ 2 teyit. Stop: retest dibi/seviye toleransının 0,25 ATR altı. |
| Strateji B | 4H yükseliş; son 12 1H mumda ≥ 1 ATR düzeltme, EMA21–55/destek/FVG bölgesinde tutunma, 15M tetik + ≥ 2 teyit. Stop: düzeltme dibinin 0,25 ATR altı. |
| Zaman dilimi birleşimi | 4H rejim → strateji seçimi; 1H plan (seviye, stop, hedef, geçersizlik); 15M tetik. |
| Kâğıt işlem ile backtest | Aynı tarama, strateji, kural, boyutlandırma, çıkış yöneticisi ve sanal hesap kodu. Farklar: canlıda emir defteri yürütülür, backtest'te mum hacminin %10'u ve sabit spread; derinlik filtresi backtest'te yok; Z17 (veri tazeliği) yalnız canlıda başarısız olabilir. **Giriş fiyat kayması sınırı (%0,3) artık backtest'te de var.** |
| Ücret ve maliyet | Parite başına MEXC taker ücreti (çoğunlukla %0,05), kayma %0,05, yarım spread %0,025, her iki yönde. Net R:R hesabı bunları içerir. |
| Saklama | Portföy, ayarlar ve karar günlüğü JSON dosyalarında; backtest verisi önbellekte. |

## 2. Ekrandaki 13 işlemin denetimi

Ekran görüntüsü bu konuşmaya ulaşmadı; o 13 işlemin kendisi görülemedi. Bunun yerine uygulamanın varsayılan
ayarlarıyla (7 coin, 90 gün, 1000 USDT) aynı test yeniden çalıştırıldı ve **her işlem ayrı bir Python betiğiyle
MEXC ham mumlarından yeniden hesaplandı**: giriş zamanı ve fiyatı, ücretler, stop/hedef/geçersizlik sırası, çıkış
zamanı, fiyatı ve nedeni, net sonuç.

- Ekrandaki rakamların hesap şekli doğru: brüt kâr/zarar her işlemin **ücretler düşülmüş net** sonucundan
  toplanıyor (38,93 − 37,92 = 1,01; PF = 38,93 / 37,92 ≈ 1,03).
- Düzeltmeden önceki test: 38 işlem, 37'si bağımsız hesapla birebir tuttu.
- **1 işlem hatalıydı ve kritik bir hata ortaya çıkardı** (aşağıda 3.1).
- Düzeltmeden sonra 44 işlemin 43'ü birebir tuttu. Kalan 1 işlemin farkı hata değil: likidite sınırı yüzünden
  satışın kalanı bir sonraki mumda yapıldı.

## 3. Bulunan hatalar ve düzeltmeler

### 3.1 Kritik: satılamayan "toz" kalıntısı pozisyonu aylarca açık tuttu (canlı kâğıt işlemde de vardı)

- **Ne oldu:** ZECUSDT işleminde çıkış, ince mum hacmi yüzünden (o 15M mumda yalnız ~1.098 USDT işlem vardı)
  parçalı yapıldı. Çıkarma işleminden kalan kayan nokta artığı 0,000000999999 ZEC, borsa adımına yuvarlanınca
  sıfır oldu ve hiç satılamadı.
- **Sonuç:** Pozisyon 27 Ağustos'tan dönem sonuna (9 Kasım) kadar açık kaldı. Bu sürede bir coin yerini ve 3
  pozisyon sınırından birini boşuna işgal etti. Aynı sorun canlı kâğıt işlem motorunda da oluşabilirdi.
- **Düzeltme:** Miktar yuvarlaması kayan nokta gürültüsüne dayanıklı yapıldı. Satılamayan son kalan, aynı
  fiyattan kapanmış sayılıyor (`OrderRules.isDust`, `FillModel.dustExit/absorbDust`). Hem backtest hem canlı
  motor bunu kullanıyor; regresyon testi eklendi.
- **Etki (aynı 90 günlük dönem):**

  | | İşlem | Net | PF | Kazanma |
  | --- | --- | --- | --- | --- |
  | Önce | 38 | −%0,39 | 0,97 | %31,6 |
  | Sonra | 44 | −%2,70 | 0,80 | %27,3 |

  Düzeltilmiş sonuç daha kötü. Hata, sistemin ek işlem almasını engellediği için sonucu iyi gösteriyordu.

### 3.2 Diğer düzeltmeler

1. **Hedefin üzerinde açılan mum:** Önceden mumun dibi stopa değiyorsa stop sayılıyordu. Oysa hedef ilk işlemde
   görülmüştü. Artık kâr hedefi sayılıyor (kötümser yönde bir hataydı; test edilen verilerde sonuca etkisi
   görülmedi).
2. **Aynı mumda stop ve hedef:** Artık 5M mumlarla hangisinin önce geldiği doğrulanıyor. 5M verisi yoksa ya da
   aynı 5M mumda da ikisi birden varsa ihtiyatlı kural (stop önce) uygulanıyor ve işlem "belirsiz" olarak
   işaretleniyor. Uygulama ve komut satırı eksik 5M mumları indirip testi yeniden çalıştırıyor.
   - Ölçüm: 90 günde ve 1 yılda **0 durum**. Bu ayarlarda sonucu etkilemiyor.
   - 5M geçmişi MEXC'de yaklaşık Şubat 2026'dan, 1M geçmişi Ekim 2026'dan itibaren var.
3. **Spread ve kayma maliyeti:** Rapordaki değer yanlış formülle hesaplanıyordu (her iki yönü piyasa emri
   sayıyor, kısmi çıkışta kalan miktarı kullanıyordu). Artık her dolumda gerçek fark toplanıyor.
4. **Canlı motorla eşitlik:** Fiyatı sinyalden %0,3'ten fazla kayan girişler backtest'te de atlanıyor.
5. **Kısmi dolum sayacı:** 1H geçersizlik çıkışlarındaki kısmi dolumlar sayılmıyordu; artık sayılıyor.

## 4. Backtest'e eklenenler

- Raporda kazanan/kaybeden sayısı, brüt kâr ve zarar, ortalama R, toplam ücret, gerçek spread+kayma maliyeti,
  piyasa değerli en büyük düşüş, günlük ve aylık özsermaye değişimi.
- Her strateji için tam ölçüler: işlem, K/Z, kazanma oranı, ortalama kazanç ve kayıp, PF, beklenen değer,
  ortalama R, düşüş, net getiri, ücret.
- A+B, yalnız A ve yalnız B aynı koşullarda ayrı ayrı çalışıyor; her biri tüm dönem ve iki yarı olarak raporlanıyor.
- Her işlemin giriş anındaki özellikleri kaydediliyor. Sonradan işlem süresince en iyi ve en kötü hareket (R)
  ölçülüyor. Kayıp analizi ve önceden tanımlı filtre testleri bunlara dayanıyor.
- Komut satırı: `backtest --end` ve `--warmup` ile tekrarlanabilir dönem; yeni `analyze` komutu.

## 5. A ve B ayrı ayrı (1 yıl, 14 coin + BTC, aynı veri/maliyet/sermaye)

Dönem: 2025-11-15 → 2026-11-08, bölme 2026-05-13. Ham çıktı:
[research/2026-11-09-kayip-ve-rejim-analizi.txt](research/2026-11-09-kayip-ve-rejim-analizi.txt).

| | İşlem | K/Z | Kazanma | PF | Ort. R | Beklenen | Net | Maks. düşüş | Ücret |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A + B | 194 | 44/150 | %22,7 | 0,61 | −0,29R | −1,08 USDT | −%20,99 | %22,11 | 43,43 |
| Yalnız A | 75 | 15/60 | %20,0 | 0,51 | −0,36R | −1,48 USDT | −%11,08 | %11,10 | 18,61 |
| Yalnız B | 144 | 36/108 | %25,0 | 0,67 | −0,24R | −0,93 USDT | −%13,32 | %16,97 | 32,29 |

- **İki strateji de iki yarıda da negatif.**
  - A: ilk yarı −0,33R, ikinci yarı −0,37R.
  - B: ilk yarı −0,19R, ikinci yarı −0,30R.
- 90 günlük uygulama testinde B pozitif çıktı (26 işlem, PF 1,28, +0,10R). Ancak 1 yılda negatif; kısa dönem
  sonucu tesadüf olabilir.
- 90 günlük testte ücret (10,69 USDT) ile spread+kayma (16,04 USDT) toplamı, net zarara (−26,98 USDT) neredeyse
  eşit. Maliyetler çıkınca sonuç başa baş.

## 6. Zararlı işlemlerin analizi (değişiklikten önce)

Varsayılan çalıştırmada 1 yılda 150 zararlı işlem var:

- 57'si lehte 0,3R bile gitmeden kapandı (anında başarısız). B'de bu oran 94 zararlının 42'si.
- 40'ı önce en az 1R lehte gitti, sonra zararla kapandı.

İki yarıda da aynı yönde görülen farklar (ortalama R):

| Özellik | Daha kötü dilim | Daha iyi dilim |
| --- | --- | --- |
| 1H ADX | < 20: −0,55R | ≥ 25: −0,22R |
| 1H RSI negatif uyumsuzluk | var: −0,52R | yok: −0,24R |
| Stop mesafesi | < 0,75 ATR: −0,57R | ≥ 1,5 ATR: +0,15R (ikinci yarıda −0,24R) |
| 4H/1H uyumu | uyumsuz: −0,45R | ikisi de yükseliş: −0,22R |
| 1H MACD histogramı | düşüyor: −0,36R | yükseliyor: −0,09R |
| Saat | 00–06 ve 12–18 UTC daha kötü | Bu bilgiye göre filtre **eklenmedi**: saat filtresi aşırı uyuma çok açıktır |

Önceden tanımlı 7 filtre (F1–F7) sonradan alt küme olarak ölçüldü. "Aday" ölçütü: iki yarıda da ortalama R
artıyor ve her yarıda en az 15 işlem kalıyor. Bu ölçütü F2 (ADX ≥ 20), F4 (stop ≥ 1 ATR), F6 (uyumsuzluk yok)
ve F7 (rejim yükseliş) geçti. **Hiçbiri kalan işlemleri pozitife çevirmedi**; en iyisi F4 ile −0,11R.

En güçlü aday F4, sonradan alt küme yerine **gerçek yeniden testle** de denendi. Mevcut ATR stop tabanı
seçeneği aynı eşikle (1,0 × ATR) kullanıldı:

| | İşlem | PF | Ort. R | Net | İlk yarı | İkinci yarı |
| --- | --- | --- | --- | --- | --- | --- |
| Temel | 194 | 0,61 | −0,29R | −%20,99 | −0,25R | −0,33R |
| ATR stop tabanı 1,0 | 151 | 0,73 | −0,15R | −%11,57 | −0,18R | −0,12R |

İki yarıda da iyileşme var ama sonuç hâlâ zarar. Varsayılan değiştirilmedi; seçenek Ayarlar → Stop yöntemi
altında duruyor.

## 7. Piyasa rejim motoru (5 sınıf)

Sınıflar BTC 4H üzerinden belirlenir: güçlü yükseliş, zayıf yükseliş, yatay/belirsiz, güçlü düşüş, aşırı
volatilite. Eşikler ölçümden önce sabitlendi (`RegimeClassifier`). Davranış:

- **Koruyucu:** Güçlü düşüşte ve aşırı volatilitede yeni alım yok.
- **Tam:** Güçlü yükselişte A+B, zayıf yükselişte yalnız B; diğer rejimlerde USDT'de beklenir.

Dönem içinde BTC'nin rejim dağılımı:

| Rejim | Payı |
| --- | --- |
| Yatay/belirsiz | %34,6 |
| Güçlü düşüş | %23,2 |
| Zayıf yükseliş | %22,0 |
| Güçlü yükseliş | %14,2 |
| Aşırı volatilite | %5,9 |

Filtre açık ve kapalı ölçüm:

| | İşlem | PF | Ort. R | Net | Maks. düşüş |
| --- | --- | --- | --- | --- | --- |
| Kapalı (varsayılan) | 194 | 0,61 | −0,29R | −%20,99 | %22,11 |
| Koruyucu | 185 | 0,58 | −0,31R | −%21,73 | %22,84 |
| Tam | 130 | 0,61 | −0,30R | −%14,26 | %18,06 |

"Tam" politikadaki daha küçük zarar, işlem başına kaliteden değil yalnız daha az işlemden geliyor (PF ve
ortalama R iyileşmedi). Bu yüzden rejim filtresi **varsayılan kapalı**. Rejim her kararda Z19 satırında bilgi
olarak görünür.

## 8. Kurallar

Sınıflandırma, kritik ve puan kuralları ile eksik veya belirsiz kurallar: [KURALLAR.md](KURALLAR.md).
Yeni kural uydurulmadı. Z19 yalnız istenen rejim motoru için eklendi ve varsayılan olarak kapalı.

## 9. Arayüz

Backtest ekranı şöyle düzenlendi:

1. **Özet:** başlangıç ve bitiş sermayesi, net kâr/zarar, işlem (K/Z), kazanma oranı, PF, maksimum düşüş,
   ortalama R, toplam ücret.
2. **A ve B tablosu.**
3. **Aylık değişim.**
4. **İşlem geçmişi:** strateji ve ay süzgeci; her satırda giriş ve çıkış tarih-saati ile fiyatı, net sonuç, R
   ve çıkış nedeni.

İşleme dokununca ayrıntı ekranı açılır:

- 15M gerçek mumlar, EMA21/55, giriş/stop/hedef/geçersizlik çizgileri.
- Girişte bilinen 1H destek ve dirençler, giriş ve çıkış anları.
- Giriş sonrası bölge gölgeli ve "karar anında bilinmiyordu" yazılı.
- Karar anındaki gerekçe ve göstergeler.

"A ve B ayrı" modu üç ayrı çalıştırmayı tüm dönem ve iki yarı olarak gösterir. Ayarlara rejim filtresi seçimi
eklendi (varsayılan kapalı).

## 10. Güvenlik

- Kâğıt işlem varsayılan ve tek mod. Uygulamada imzalı uç nokta, emir gönderme kodu ya da API anahtarı alanı yok;
  bu, `SecurityTest` ile denetleniyor. Bu nedenle saklanacak bir anahtar da yok.
- Gerçek işlem eklenecekse:
  - Anahtarlar Android Keystore ile şifrelenip yalnız cihazda tutulmalı.
  - Anahtarlar yalnız "spot işlem" yetkili, para çekme yetkisiz ve IP kısıtlı olmalı.
  - MEXC'de borsa tarafı stop emri olmadığı için yazılım stopunun davranışı küçük tutarlarla ayrıca
    doğrulanmalı.

## 11. Eksikler ve sınırlar

- 60 kuralın 28'inin metni yok.
- Strateji C hâlâ kapalı ve test edilmedi.
- Geçmiş emir defteri olmadığından backtest'te derinlik filtresi yok; spread sabit.
- Android ön plan servisi, bildirimler ve uygulama içi backtest gerçek telefonda denenmedi (bu ortamda emülatör
  yok). Ekranlar Robolectric ile çizildi; dokunma etkileşimleri otomatik test edilmedi.
- Sonuçlar 1 yıllık tek bir piyasa dönemine dayanıyor. BTC bu dönemde çoğunlukla düşüş ve yatay rejimdeydi.
