# V4 geniş coin evreni: ön kayıt (10 Kasım 2026, sonuçlar görülmeden önce)

**Amaç:** S1'i (BTC'ye göre göreceli güç, L=30) daha geniş bir parite evreninde ölçmek. Stratejinin kuralları
değişmez:

- EMA50(1D).
- İlk 3'te giriş, ilk 6 dışında çıkış.
- 3 × ATR14(1D) stop ve iz süren stop.
- En fazla 3 pozisyon.
- %0,5 risk.

Bu belge, karşılaştırma testleri çalıştırılmadan önce commit edilir.

## Veri ve evren

- **Kaynak:** MEXC `exchangeInfo` içinde bugün görünen bütün USDT pariteleri.
- **Ürün türüne göre ayıklama:** Stablecoin, tokenleştirilmiş hisse, hisse meme tokeni, değerli metal, metal, Pre-IPO
  (MEXC `conceptPlates` etiketleri) ve kaldıraçlı token (adı rakam + L/S ile biten) çıkarılır. Ürün türü zamanla
  değişmediği için bu ayıklama geçmişe uygulanabilir.
- **Bugünkü bilgiyle geçmişte ayıklama yapılmaz:** MEXC risk etiketi (ST), "yalnız alım/satım" ve "spot işlem
  kapalı" durumları geçmişte bilinmiyordu. Bunlarla ayıklamak geleceğe bakmak olur. Bu durumlar yalnız canlı taramada
  uygulanır.
- **Önce 1D:** Bütün paritelerin 1D geçmişi indirilir.
- **Sonra 4H:** Yalnız geçmişte en az bir gün işlem yapılabilir olmuş paritelerin 4H verisi indirilir (en az 210 gün
  geçmiş ve 30 günlük medyan günlük hacim en az 1M USDT). Diğerleri motorda zaten hiçbir zaman işlem göremez; bu
  nedenle sonuç değişmez.
- **Listeleme tarihi:** İlk mumun tarihi alınır. Bir parite ilk mumundan 210 gün sonrasına kadar işlem göremez.
- **Bilinen yanlılık:** Borsadan kaldırılmış pariteler indirilemez. Hayatta kalma yanlılığı azaltılamaz; raporlanır.
- **Emir defteri:** Geçmiş emir defteri ve spread verisi yok. Derinlik yalnız canlı taramada ölçülür.

## Evren büyüklükleri (zamana bağlı, geleceğe bakmadan)

Her günlük kapanışta, o an işlem yapılabilir pariteler 30 günlük medyan günlük hacme göre sıralanır. BTC her zaman
evrendedir (göreceli güç ölçüsü için).

| Evren | Tanım |
| --- | --- |
| Eski dar liste | V3'teki 28 parite (sonradan seçilmiş sabit liste; iyimser yanlılık taşır) |
| İlk 20 | O günün en likit 20 paritesi |
| İlk 50 | O günün en likit 50 paritesi |
| İlk 100 | O günün en likit 100 paritesi |
| Tümü | İşlem yapılabilir bütün pariteler |

Belirli bir tarihte işlem yapılabilir parite sayısı N'den azsa, "ilk N" evreni o tarihte "tümü" ile aynıdır. Bu durum
raporda belirtilir.

## Maliyet senaryoları

İlk üç senaryo V3'teki düz senaryolardır: İyimser, Orta, Kötümser.

Geniş evrene giren paritelerin spread'i çok daha yüksektir. 10 Kasım 2026 anlık görüntüsünde günlük hacmi 1–20M USDT
olan 32 paritenin medyan tam spread'i %0,218'dir. Hacmi 20M üstü olanlarda bu oran %0,027'dir. Bu yüzden düz bir
spread varsayımı geniş evreni haksız biçimde avantajlı gösterir. Ek olarak iki **kademeli** senaryo kullanılır:

| Senaryo | 30 günlük medyan hacim ≥ 20M | 1M – 20M |
| --- | --- | --- |
| **Kademeli orta (karar senaryosu)** | yarım spread %0,075 | yarım spread %0,109 (o kademenin medyan tam spread'i %0,218'in yarısı) |
| Kademeli kötümser | %0,15 | %0,284 (o kademenin %75'lik dilimi %0,567'nin yarısı) |

- Hacim kademesi her işlemde o anki (geçmiş) 30 günlük medyan hacme göre belirlenir.
- Spread oranları tek günlük ölçümden gelir. Bunlar gerçek geçmiş değil, senaryodur.
- Ücret (%0,05) ve kayma (%0,05) bütün senaryolarda aynıdır.

## Dönemler ve ölçütler

- **Dönemler:** V3 ile aynı. Doğrulama 2023-01-01 → 2025-01-01. Son test 2025-01-01 → veri sonu. Tüm dönem
  2020-08-01 → veri sonu. Parametre seçimi yapılmaz, dolayısıyla eğitim dönemi yoktur.
- **Raporlanan ölçüler:** Her evren ve dönem için:
  - Net getiri, PF, ortalama R, t istatistiği, en büyük düşüş, işlem sayısı.
  - Ücret ve spread+kayma toplamı.
  - BTC al-tut ile karşılaştırma.
  - En iyi parite çıkarıldığında sonuç.
  - Sektör dağılımı (bugünkü `conceptPlates` etiketleriyle; yalnız betimleyici).
- **Korelasyon:** Eş zamanlı açık pozisyonların 60 günlük günlük getiri korelasyonu ortalaması raporlanır (betimleyici).

### Karar kuralı (canlı ileri test için)

Geniş evren ("Tümü") S1 ileri testinde ancak şu koşulla kullanılır:

- **Kademeli orta** maliyette **doğrulama ve son testin ikisinde de** ort. R > 0, PF ≥ 1,1 ve en az 30 işlem.
- Ek koşul: Son test sonucu en iyi parite çıkarıldığında da ort. R > 0 olmalı. V3'teki ZEC bağımlılığı nedeniyle
  eklenmiştir.

Koşul sağlanmazsa ileri test eski dar listeyle devam eder ve geniş tarama yalnız bilgi amaçlı gösterilir. En iyi
görünen evren büyüklüğü sonradan seçilmez.

## İki aşamalı tarama doğrulaması

**Canlı tarama:**

- **1. aşama:** Bütün parite 1D verisiyle süzülür: likidite, veri yeterliliği, 7 ve 30 günlük momentum, EMA50
  koşulları.
- **2. aşama:** Yalnız ilk 20 aday ayrıntılı incelenir: 4H veri, ATR, emir defteri derinliği, korelasyon.

**Doğrulama:** Geçmiş veride, her günün ilk 20 uygun adayından (ve açık pozisyonlardan) oluşan alt kümeyle çalışan
motor, bütün evrenle çalışan motorla birebir aynı işlemleri vermelidir. Vermezse iki aşamalı tasarım düzeltilir.
