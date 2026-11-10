# Kural envanteri ve sınıflandırma (9 Kasım 2026)

Bu belge **yalnız kaynak kodda bulunan** kuralları sınıflandırır. Tasarım notunda "60 kural" geçiyor ama 60
kuralın metni projeye hiç verilmedi; eksik kurallar varsayımla doldurulmadı. Kodda 19 zorunlu (kritik) kural,
11 kalite (puanlama) kuralı ve yalnız Seçici profilde devreye giren 2 profil kapısı vardır: toplam 32.

## Zorunlu ile puanlama arasındaki ilişki

- Zorunlu kurallardan **biri bile** geçmezse işlem açılmaz. `RuleReport.allowed = zorunlu hata yok VE puan ≥ asgari puan`.
- Kalite puanı (0–100) yalnız sıralama ve bilgi içindir; varsayılan asgari puan 0'dır. Seçici profil P01 ile asgari
  puanı 60 yapar; bu da zorunlu bir kapıdır, puan zorunlu kuralı geçersiz kılamaz.
- Bunu doğrulayan test: `RuleEngineTest."a perfect quality score never overrides a failed mandatory rule"`. Ağırlığı
  1000 olan, her zaman geçen bir kalite kuralıyla puan 95'in üstüne çıkıyor ve Z12 yine işlemi engelliyor.

## Kural tablosu

| Kural | Ne denetler | Tür | Kategori | Ağırlık | Kaynak / not |
| --- | --- | --- | --- | --- | --- |
| Z01 | Sinyal kapanmış mumdan üretildi | Kritik | Veri doğrulama | — | Tasarım notu (kapanmış mum) |
| Z17 | Son 15M/1H/4H mumları tarama sınırıyla eşleşiyor, canlı fiyat ≤ 30 sn | Kritik | Veri doğrulama | — | V2 altyapı kuralı (yalnız canlı motorda veri sorunu çıkabilir) |
| Z04 | 4H ana trend düşüşte değil | Kritik | Trend | — | Tasarım notu (4H trend) |
| Z05 | Strateji 4H rejimine uygun (A: yükseliş/yatay, B: yükseliş) | Kritik | Piyasa rejimi | — | Tasarım notu (A/B tanımı) |
| Z10 | BTC filtresi: veri yeterli (B1) ve güncel (B2), BTC 4H düşüşte değil (B3), son 4 saatte %3'ten fazla düşmedi (B4) | Kritik | Piyasa rejimi | — | Tasarım notu "BTC filtresi"; %3 eşiği notta yok; B2 10.11.2026'da eklendi ([PIYASA-FILTRESI.md](PIYASA-FILTRESI.md)) |
| Z19 | BTC 4H 5 sınıflı rejim stratejiye izin veriyor | Kritik | Piyasa rejimi | — | Bu görevde istendi; **varsayılan kapalı** (her zaman geçer, rejimi yalnız bilgi olarak yazar) |
| Z06 | Strateji etkin (C test edilmeden açılmaz) | Kritik | Giriş teyidi | — | Tasarım notu |
| Z07 | En az 2 bağımsız teyit | Kritik | Giriş teyidi | — | Tasarım notu "tek göstergeyle işlem yok" |
| Z08 | Likidite ve işlem koşulları (hacim, spread, derinlik, işlem izni) | Kritik | Likidite | — | Tasarım notu; eşikler (1M USDT, %0,20, ±%1'de 10k) notta yok |
| Z09 | Manipülasyon işareti yok (aşırı 24s değişim, uzun fitiller, ani hacim patlaması) | Kritik | Likidite | — | Tasarım notu; ölçüt kodda tanımlandı |
| Z02 | Stop tanımlı ve girişin altında | Kritik | Risk | — | Tasarım notu |
| Z03 | Net R:R ≥ 1,5 (ücret, spread, kayma dahil) | Kritik | Risk | — | Tasarım notu |
| Z11 | Aynı coinde açık pozisyon yok (zararda ek alım yok) | Kritik | Risk | — | Tasarım notu |
| Z12 | En fazla 3 açık işlem | Kritik | Risk | — | Tasarım notu |
| Z13 | Günlük kayıp %1,5'i aşmadı | Kritik | Risk | — | Tasarım notu |
| Z14 | İlişkili coinlerde toplam risk ≤ %1 | Kritik | Risk | — | V1 risk eklemesi |
| Z15 | Pozisyon büyüklüğü borsa kurallarına uygun | Kritik | Risk | — | MEXC kuralları |
| Z18 | Toplam açık risk ≤ %1,5 | Kritik | Risk | — | V2: önceki örtük 3 × %0,5 sınırının açık hali |
| Z16 | Coin üst üste zarardan sonra dinlenmede değil (varsayılan kapalı) | Kritik | İptal / bekleme | — | V1 seçeneği |
| P01 | Seçici profil: kalite puanı ≥ 60 | Kritik (yalnız Seçici) | Risk | — | Kullanıcının seçtiği profil eşlemesi |
| P02 | Seçici profil: net R:R ≥ 2 | Kritik (yalnız Seçici) | Risk | — | Tasarım notu "tercihen 2" |
| K01 | Net R:R ≥ 2 | Puan | Risk | 3 | Tasarım notu "tercihen 2" |
| K02 | 4H ADX ≥ 25 | Puan | Trend | 2 | Eşik kodda seçildi |
| K06 | Fiyat 1H VWAP üzerinde | Puan | Trend | 1 | |
| K07 | Giriş bölgesinde ≥ 2 yapısal uyum | Puan | Yapı | 2 | |
| K11 | 1H yapı HH/HL | Puan | Yapı | 2 | |
| K09 | Spread < %0,05 | Puan | Likidite | 1 | |
| K04 | 15M RSI 45–70 | Puan | Momentum | 1 | |
| K05 | 15M MACD histogramı yükseliyor | Puan | Momentum | 1 | |
| K10 | BTC'ye göre 7 günlük göreceli güç pozitif | Puan | Momentum | 1 | |
| K03 | Tetik mumunda hacim ≥ 1,5× ortalama | Puan | Hacim | 2 | |
| K08 | BTC 4H yükselişte | Puan | Piyasa rejimi | 2 | |

Kodda kategori `BuiltInRules.categories` içinde tutulur; her kuralın bir kategorisi olduğu testle denetlenir.

## Eksik ya da belirsiz olanlar

1. **60 kuralın metni yok.** 32 kural var; kalan 28 kuralın ne olduğu bilinmiyor. Metin verildiğinde
   `rules/default-rules.json` içine özel kural (koşul dili) olarak eklenebilir; uygulama zorunlu kuralları
   kapattırmaz.
2. **Yapı, momentum ve hacim kategorilerinde kritik kural yok.** Bu kategoriler yalnız puanlıyor. Tasarım notunda
   bunların zorunlu olup olmadığı yazmıyor; bu yüzden değiştirilmedi.
3. **Eşikleri notta olmayan kurallar:** Z08 likidite eşikleri, Z09 manipülasyon ölçütü, Z10'daki %3 düşüş eşiği,
   K02'deki ADX 25, K03'teki 1,5× hacim, K04'teki RSI 45–70 aralığı ve tüm kalite ağırlıkları kodda seçildi.
   Hiçbiri sonuçlara göre ayarlanmadı; değiştirilmeleri için kaynak gerekir.
4. **Z05 ile Z04 kısmen örtüşüyor:** ikisi de 4H rejimine bakıyor (Z04 düşüşü engeller, Z05 strateji–rejim
   uyumunu ister). Davranış doğru; yalnız raporda aynı neden iki kez görünebilir.
5. **Z19 ile Z10 kısmen örtüşüyor:** Z10 BTC 4H düşüş trendini zaten engelliyor. Z19 kapalıyken bu örtüşme
   önemsiz; Z19 açılırsa "güçlü düşüş" sınıfında iki kural birden kaybeder.
