# Kırılım–Retest 2. tur: sınırlı ve gerekçeli değişiklikler (ön kayıt)

Tarih: 14 Kasım 2026. Bu belge, değişikliklerin hiçbiri çalıştırılmadan önce yazıldı ve commit'lendi.

## 0. Dürüstlük notu: hangi veri artık bağımsız değil

İlk turda ([KR-ONKAYIT.md](KR-ONKAYIT.md), [KR-RAPOR.md](KR-RAPOR.md)) üç MEXC dönemi ve Binance yardımcı testi
**temel kurallarla birer kez görüldü**. Aşağıdaki zayıflık teşhisleri o sonuçlardan (üç dönemin hepsinden) çıkarıldı.
Bu yüzden:

- Hiçbir geçmiş dönem bu değişiklikler için **tamamen dokunulmamış değildir**.
- "Değerlendirme" dönemleri yalnız seçimin sonrasında **bir kez** çalıştırılır. Yine de "bağımsız" diye sunulmaz.
- Gerçekten bağımsız tek test, bundan sonraki canlı paper işlemlerdir.

## 1. Önce yeniden üretim

İlk turun sonuçları aynı veri ve kodla yeniden üretilir: `kr --phase final`. İşlem CSV'leri birebir aynı olmalıdır.
Aynı değilse araştırmaya geçilmez.

## 2. Dönemler

- **Araştırma:** MEXC 15M, 2025-11-28 → 2026-07-31 (ilk turun araştırma + doğrulama dönemleri).
  Varyantlar yalnız burada karşılaştırılır.
- **Değerlendirme 1:** MEXC 15M, 2026-08-01 → 2026-11-12. İlk turda temel kurallarla bir kez görüldü.
- **Değerlendirme 2:** Binance 15M, 2023-11-15 → 2025-10-31. Yardımcıdır; farklı borsa ve geleceğe bakan evren nedeniyle
  yanlıdır. İlk turda temel kurallarla bir kez görüldü.

Her dönem 1.000 USDT ile başlar. Normal ve ×2 maliyet çalıştırılır. Evren, maliyet modeli ve risk sınırları ilk
turla aynıdır.

## 3. Zayıflıklar ve tek değişkenli varyantlar

| Kod | Teşhis (ilk tur) | Değişiklik | Neden "büyük stop / küçük hedef" tuzağı değil |
| --- | --- | --- | --- |
| V1 | 290 işlemin 95'i giriş mumunun içinde stopa takıldı. Medyan stop %0,81; 15M gürültüsünün içinde | Stop tamponu 0,25 → 1,0 × ATR15 (stop retest düşüğünün altında daha derin) | Hedef formülü aynı kalır (yakın direnç / 1,5 ATR1H). Net R:R ≥ 1,2 ve maliyet ≤ %40 şartları aynen uygulanır. Ödülü yetmeyen işlem elenir; miktar risk %0,25'e göre küçülür |
| V2 | Zararlar BTC'nin düştüğü aylarda yoğun (Ocak, Eylül 2026) | BTC'nin 4H yönü de Y1 tanımıyla yükselişte olmalı | Stop/hedef değişmez |
| V3 | Retestlerin çoğu seviyenin hemen üstünde zayıf kapanışla onaylandı ve hemen bozuldu | Retest mumu: kapanış, mum aralığının üst yarısında ((kapanış − düşük) / (yüksek − düşük) ≥ 0,5) ve kapanış ≥ seviye + 0,1 × ATR15 | Stop/hedef değişmez |
| V4 | Maliyet, kazanan işlemlerin brüt kârının %89–95'i | İki yön maliyet / brüt hedef üst sınırı %40 → %25 (yalnız ödülü maliyete göre yeterli işlemler) | Hedef uzağa taşınmaz; yalnız eleme sıkılaşır |

Başka değişiklik, ek parametre taraması ya da ikinci tur yoktur. Toplam 5 yapılandırma: B0 (temel), V1–V4.
Birleşik yapılandırma, kuralla seçilirse en fazla 1 tane.

## 4. Seçim kuralı (yalnız araştırma dönemi, normal maliyet)

1. Bir varyant, şu üç şartın hepsini sağlıyorsa "iyileştirdi" sayılır:
   - ortalama net R'yi B0'a göre en az 0,05 artırıyor;
   - en az 60 işlem var;
   - ×2 maliyette ortalama net R'yi düşürmüyor (B0'ın ×2 sonucuna göre).
2. "İyileştiren" varyantların hepsi birlikte uygulanarak tek bir birleşik yapılandırma (VK) kurulur. Yalnız bir
   varyant iyileştirdiyse VK o varyanttır.
3. **Hiçbiri iyileştirmediyse:** B0 kalır ve "avantaj bulunamadı" yazılır.
4. **VK araştırma döneminde net zararda ise:** değerlendirme yine bir kez çalıştırılır ve rapor edilir. Ancak strateji
   "başarılı" sayılmaz ve uygulamaya geçirilmez.

## 5. Değerlendirme ve sınıf (VK, normal maliyet)

**"Avantaj gösterildi" için hepsi gerekir:**

- Değerlendirme 1'de en az 60 işlem.
- Net beklenti > 0 ve PF ≥ 1,2.
- En büyük düşüş ≤ %6.
- ×2 maliyette net > 0.
- Ortalama R'nin %90 bootstrap güven aralığı alt sınırı > 0.
- En iyi coin çıkarılınca net > 0.
- Değerlendirme 2'de (yardımcı) net > 0.

**"Başarısız":** Değerlendirme 1'de net beklenti ≤ 0.

**"Yetersiz kanıt":** Diğer bütün durumlar.

Hiçbir sonuç "başarılı strateji" diye sunulmaz. En iyi ihtimalle "bu verilerde avantaj gösterildi; canlı paper
doğrulaması gerekli" denir.

## 6. Raporlanacaklar (her yapılandırma × dönem × maliyet)

Net beklenti (USDT ve R), PF, en büyük düşüş, günlük işlem sayısı, ortalama elde tutma, toplam maliyet,
maliyet / brüt kâr, kazanma oranı ile ortalama kazanç ve kayıp (R).

## 7. Uygulamaya geçiş

Uygulama ancak VK "avantaj gösterildi" sınıfındaysa VK'ye geçer. Aksi halde uygulama B0 ile kalır ve rapor bunu söyler.
