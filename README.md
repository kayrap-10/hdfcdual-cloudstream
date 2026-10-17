# HDFilmCehennemi Dual (Cloudstream)

HDFilmCehennemi sağlayıcısı + **bağımsız zamanlamalı çift altyazı** (Türkçe + İngilizce).

## Kurulum (tek komut)
```bash
./publish.sh            # veya ./publish.sh repo-adi
```
Gerekenler: `git` ve [GitHub CLI](https://cli.github.com). Script public repo açar, kodu yükler ve derlemeyi başlatır.

Derleme `https://github.com/KULLANICI/REPO/actions` sayfasında yeşil olunca Cloudstream'de:
1. Ayarlar > Eklentiler > **Depo ekle**
2. `https://raw.githubusercontent.com/KULLANICI/REPO/main/repo.json`
3. **HDFilmCehennemi Dual** eklentisini indir
4. Eklenti satırındaki **dişli** ile altyazı ayarlarını aç

## Altyazı modları
Sadece Türkçe, sadece İngilizce, **Dual**, kapalı. Dil başına ayrı ms gecikmesi vardır (diğer dili etkilemez).
Değişiklikten sonra bölümü yeniden aç. Dual altyazı tek birleştirilmiş iz olarak "Turkish + English (Dual)" adıyla görünür.

## Sorun giderme
- Derleme kırmızıysa Actions günlüğünü kopyalayıp gönder.
- Site değiştiyse `HDFilmCehennemi.kt` içindeki `TODO-VERIFY` satırlarını kontrol et.
- Domain değiştiyse `mainUrl` değerini güncelle ve `HDFilmCehennemi/build.gradle.kts` içindeki `version` sayısını artır.
- Chromecast'te dual çalışmaz (iki dil ayrı iz olarak verilir).
