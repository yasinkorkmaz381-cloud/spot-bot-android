# Kırılım–Retest (KR) stratejisi: ön kayıt

Tarih: 13 Kasım 2026. Bu belge, kod yazılmadan ve hiçbir backtest sonucu görülmeden önce yazıldı. Commit'i sonuç
dosyalarından önce gelir. Sonuç gördükten sonra değişen her şey ayrı bir "sapma" başlığında yazılacaktır.

Eski haftalık Momentum RS-1 bırakıldı. Yeni sistem RS-1'in karar kodunu kullanmaz; RS-1 kodu aktif sistemden
kaldırılır (git geçmişinde ve `v7` etiketinde durur), eski paper kayıtları arşive taşınır.

## 1. Tek düzen

4H yön → 1H önceden sabitlenmiş direnç → 15M kapanışla kırılım → retestte seviyenin korunması → alım → yakın hedef.

Bütün değerler **araştırma varsayımıdır**; geçmiş veriye göre seçilmemiş başlangıç değerleridir.

### 1.1 Göstergeler

| Ad | Tanım |
| --- | --- |
| EMA50(4H) | Kapanmış 4H mumların kapanışlarının 50 periyotluk EMA'sı |
| ATR15 | Kapanmış 15M mumlarda Wilder ATR(14) |
| ATR1H | Kapanmış 1H mumlarda Wilder ATR(14) |
| Hacim ortalaması | Önceki 20 adet 15M mumun hacim ortalaması (kırılım mumu hariç) |
| 1H ve 4H mumlar | 15M mumların UTC hizalı 4'lü / 16'lı gruplarından üretilir; grup eksikse o mum yoktur |

### 1.2 Kurallar

| # | Aşama | Kural (hepsi zorunlu; puanlama yok) |
| --- | --- | --- |
| Y1 | 4H yön | Son kapanmış 4H mumda `kapanış > EMA50` **ve** `EMA50 > 6 mum önceki EMA50` |
| S1 | 1H seviye | 1H fraktal tepe: tepe mumunun yükseği solundaki 3 ve sağındaki 3 mumun yükseğinden büyük. Sağdaki 3. mum kapanmadan seviye bilinmez |
| S2 | Seviye geçerliliği | Tepe en fazla 72 adet 1H mum önce oluştu ve oluştuğu 1H mum kapandıktan sonra **hiçbir 15M kapanış** seviyenin üstünde olmadı |
| S3 | Sabitleme | Bir 15M mumun kırılım sayılması için seviye o mum **açılmadan önce** S1–S2 ile bilinmelidir |
| K1 | Kırılım | 15M mum kapanışı seviyenin üstünde (fitil sayılmaz). Birden çok seviye aşıldıysa kapanışın altındaki en yüksek seviye alınır |
| K2 | Kırılım hacmi | Kırılım mumunun hacmi ≥ 1,5 × hacim ortalaması |
| K3 | Kırılım anında yön | Kırılım mumu kapandığında Y1 geçerli |
| K4 | Tek kullanım | Kırılan seviye listeden düşer; aynı seviye ikinci kez kırılım üretemez. Coin başına aynı anda tek bekleyen kurulum; yeni kırılım eskisinin yerini alır |
| R1 | Retest penceresi | Kırılım mumundan sonraki 8 adet 15M mum (2 saat). Kırılım ve retest aynı mum olamaz |
| R2 | Retest mumu | `düşük ≤ seviye + 0,25 × ATR15(kırılım anı)` **ve** `kapanış > seviye` **ve** `kapanış ≥ açılış` |
| R3 | İptal | Retestten önce herhangi bir 15M kapanış ≤ seviye → kurulum iptal. 8 mumda retest yok → süresi doldu. Bekleme sırasında bir 4H kapanışında Y1 bozulursa → iptal |
| G1 | Dolum | Retest mumu kapandıktan sonra: backtestte bir sonraki 15M mumun açılışından; paper'da o anki emir defterinden (alış tarafı VWAP) |
| G2 | Kovalamama | Dolum fiyatı > seviye + 0,75 × ATR15 ise sinyal iptal |
| G3 | Stop | `stop = (kırılımdan sonraki mumlardan retest mumuna kadar en düşük düşük) − 0,25 × ATR15` |
| G4 | Hedef | `hedef = min(dolumun üstündeki en yakın geçerli 1H seviyesi, dolum + 1,5 × ATR1H)`. Hedef uzağa taşınmaz |
| G5 | Net R:R | `(hedef×(1−çıkış maliyeti) − dolum×(1+giriş maliyeti)) / (dolum×(1+giriş maliyeti) − stop×(1−stop maliyeti)) ≥ 1,2`. Maliyet = komisyon + yarım spread + tahmini kayma, her bacakta ayrı |
| G6 | Maliyet yeterliliği | İki yön toplam maliyet ≤ brüt hedef hareketinin %40'ı |
| P1 | BTC şoku | BTC son 4 saatte (16 adet 15M) %2'den fazla düşmediyse giriş. Düşmüşse bekle |
| P2 | Göreceli güç | Coinin 24 saatlik getirisi − BTC'nin 24 saatlik getirisi > 0 |
| P3 | Likidite | Son 24 saatte MEXC'de ≥ 1.000.000 USDT işlem hacmi. Paper'da ek olarak spread ≤ %0,20 ve emir defterinden tahmini kayma ≤ %0,15 |
| P4 | Evren | Coin o günün CMC ilk 300 listesinde, MEXC'de doğrulanmış eşleşmesi var ve elenmemiş |
| P5 | Veri | Son 48 saatin 15M mumlarında boşluk yok; son mum beklenen kapanış zamanında |
| P6 | Olay riski | MEXC risk (ST) etiketi yok, alım-satım açık. Kullanıcının girdiği makro olay pencerelerinde (−30 dk, +60 dk) yeni giriş yok. Kaynak yoksa "kontrol edilmedi" gösterilir |

1D yalnız bağlamdır: ayrıntı ekranında gösterilir, giriş şartı değildir.

### 1.3 Çıkış ve öncelik

Her 15M mum için sırayla:

1. **Sert stop** (gün içi, sürekli): `düşük ≤ stop`. Açılış stopun altındaysa dolum açılıştan. Satış tarafında yarım
   spread ve kayma düşülür.
2. **Hedef** (gün içi): `yüksek ≥ hedef × (1 + yarım spread)`; dolum hedef fiyatından, komisyon taker sayılır.
   Aynı mumda stop ve hedef görülürse ve daha küçük mum yoksa **stop** sayılır ("sıra bilinmiyor" olarak işaretlenir).
3. **Tez iptali** (kapanışta): `kapanış < seviye − 0,25 × ATR15` → sonraki mumun açılışında çıkış.
4. **İlerleme yok** (kapanışta): girişten 8 mum (2 saat) sonra o ana kadarki en yüksek < `dolum + 0,5 × (hedef − dolum)` →
   sonraki açılışta çıkış.
5. **En uzun süre**: 24 mum (6 saat) → sonraki açılışta çıkış.

Stop hiçbir koşulda gevşetilmez, başabaşa taşınmaz. Kısmi satış yok.

## 2. Risk (başlangıç sınırları, optimum değil)

İşlem başına özsermayenin %0,25'i; toplam açık stop riski ≤ %1; en fazla 4 açık pozisyon; coin başına ≤ %20,
toplam ≤ %70 sermaye; aynı coinde tek pozisyon; günlük (TSİ günü) gerçekleşmiş + gerçekleşmemiş kayıp ≥ %2 → o gün yeni
giriş yok, korumalar sürer. Miktar = risk / (birim başına net stop kaybı), sonra sermaye sınırları, kullanılabilir
bakiye, miktar adımı ve asgari emir tutarına göre aşağı yuvarlanır. Kapasite yalnız başarılı girişte azalır.

Aynı 15M kapanışında birden çok giriş: net R:R büyükten küçüğe, eşitse toplam maliyet küçükten büyüğe, eşitse sembol
alfabetik.

## 3. Maliyet modeli

- Komisyon: MEXC `exchangeInfo` (resmî API) `takerCommission` değeri, coin başına, iki bacakta da taker. Maker dolumu
  varsayılmaz. Anlık görüntü zamanı rapora yazılır; geçmişteki ücretlerin farklı olabileceği belirtilir.
- Spread: coinin MEXC `bookTicker` anlık görüntüsündeki yarım spread (en az %0,01). Geçmiş spread verisi yok.
- Kayma: her bacakta %0,05.
- İki kat senaryo: üçü de ×2.

## 4. Veri ve dönemler

- MEXC 15M yalnız ~360 gün geriye gidiyor (en eski ~14 Kasım 2025). CMC Basic plan son 12 ayın günlük geçmiş ilk 300
  listesini veriyor. Ana test bu dönemde, **o günün** CMC listesiyle yapılır.
- İlk 14 gün ısınma (gösterge ve seviye geçmişi); işlem yok.
- **Araştırma**: 2025-11-28 → 2026-04-30. Kod hataları burada ayıklanır; sınırlı duyarlılık burada yapılır.
- **Doğrulama**: 2026-05-01 → 2026-07-31.
- **Son değerlendirme**: 2026-08-01 → 2026-11-12. Yapılandırma dondurulduktan sonra bir kez çalıştırılır.
- **Yardımcı test** (ana sonuç değildir): Binance 15M, 2023-11-01 → 2025-10-31, CMC'nin erişilebilen en eski listesi
  (2025-11-14) ile. Farklı borsa ve geriye dönük liste nedeniyle **yanlıdır**; yalnız farklı piyasa koşullarında
  davranışı görmek içindir. Maliyetler MEXC değerleriyle hesaplanır.
- Başlangıç bakiyesi 1.000 USDT; her dönem ayrı başlar. Dönem sonunda açık pozisyon gerçekleşmemiş olarak raporlanır.

## 5. Sınırlı duyarlılık (yalnız araştırma döneminde, 8 çalıştırma)

Tek seferde tek değişiklik: net R:R eşiği 1,0 / 1,5; retest penceresi 4 / 12 mum; hedef 1,0 / 2,0 × ATR1H;
kırılım hacmi 1,2 / 2,0 ×. **Ana yapılandırma bu sonuçlara göre değiştirilmeyecek**; yalnız sonucun parametreye ne
kadar duyarlı olduğu raporlanır.

## 6. Değerlendirme

Sunulacaklar: net getiri, PF, net beklenti (USDT ve R), en büyük düşüş, işlem sayısı, maliyetin brüt kâra oranı,
ortalama elde tutma, coin dağılımı, günlük/haftalık işlem sayısı, aktif gün başına işlem, taranan coin sayısı, elenme
hunisi (kaçan fırsat nedenleri), normal ve ×2 maliyet.

Sınıf (doğrulama + son değerlendirme birlikte, normal maliyet):

- **Kanıt var**: ≥ 100 işlem, net beklenti > 0, PF ≥ 1,2, en büyük düşüş ≤ %6, ×2 maliyette net > 0, işlem başı net R
  ortalamasının bootstrap %90 güven aralığı alt sınırı > 0 (10.000 örnek, tohum 42), en iyi coin çıkarılınca net > 0,
  doğrulama ve son değerlendirme ayrı ayrı net > 0.
- **Başarısız**: net beklenti ≤ 0.
- **Yetersiz kanıt**: ikisi de değil.

Çok işlem + negatif beklenti başarısızdır. Kârlılık bulunamazsa açıkça yazılır.
